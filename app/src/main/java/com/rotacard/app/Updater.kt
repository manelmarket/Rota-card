package com.rotacard.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Versao publicada no GitHub: numero do build e links do APK e do arquivo com o SHA-256. */
data class UpdateInfo(val code: Int, val apkUrl: String?, val shaUrl: String?)

/** Confere, baixa e abre o instalador das versoes publicadas nas releases do GitHub. */
object Updater {
    const val REPO = "manelmarket/Rota-card"

    private fun open(url: String, readTimeout: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", "RotaCard")
            connectTimeout = 15000
            this.readTimeout = readTimeout
        }

    /** Ultima release. Lanca IllegalStateException("HTTP n") se o GitHub nao responder 200. */
    fun latest(repo: String = REPO): UpdateInfo {
        val conn = open("https://api.github.com/repos/$repo/releases/latest", 15000)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP " + conn.responseCode)
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val code = json.getString("tag_name").substringAfterLast('-').toIntOrNull() ?: 0
            val assets = json.getJSONArray("assets")
            var apk: String? = null
            var sha: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.getString("name")
                when {
                    name.endsWith(".apk.sha256") -> sha = a.getString("browser_download_url")
                    name.endsWith(".apk") -> apk = a.getString("browser_download_url")
                }
            }
            return UpdateInfo(code, apk, sha)
        } finally {
            conn.disconnect()
        }
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(65536)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Primeiro "token" hexadecimal de 64 caracteres do arquivo .sha256 (formato do sha256sum). */
    fun parseSha(text: String): String? =
        Regex("""\b[0-9a-fA-F]{64}\b""").find(text)?.value?.lowercase()

    /**
     * Baixa o APK para o cache. Se a release traz o .sha256, confere o arquivo e lanca
     * SecurityException se nao bater (o arquivo e apagado).
     */
    fun download(ctx: Context, info: UpdateInfo, progress: (Int) -> Unit): File {
        val url = info.apkUrl ?: throw IllegalStateException("sem APK")
        val dir = File(ctx.cacheDir, "updates"); dir.mkdirs()
        val f = File(dir, "RotaCard.apk")
        if (f.exists()) f.delete()
        val conn = open(url, 30000)
        try {
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                f.outputStream().use { out ->
                    val buf = ByteArray(32768)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct / 10 != lastPct / 10) { lastPct = pct; progress(pct) }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        val shaUrl = info.shaUrl
        if (shaUrl != null) {
            val c = open(shaUrl, 15000)
            val expected = try { parseSha(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
            if (expected == null || expected != sha256(f)) {
                f.delete()
                throw SecurityException("O arquivo baixado não confere com a versão publicada")
            }
        }
        return f
    }

    fun installIntent(ctx: Context, f: File): Intent {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
