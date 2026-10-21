package com.rotacard.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Backup (zip) com o historico, os prints do historico e as configuracoes. */
object Backup {
    private val SKIP = setOf(
        "dev", "last", "fail", "ocr", "ocrfail", "clicks", "lasterr", "easylog", "post", "noticeSeen", "repo"
    )

    fun create(ctx: Context): File {
        val dir = File(ctx.cacheDir, "reports")
        dir.mkdirs()
        val name = "rotacard-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".zip"
        val out = File(dir, name)
        ZipOutputStream(FileOutputStream(out)).use { z ->
            val hf = File(ctx.filesDir, "history.json")
            if (hf.exists()) put(z, "history.json", hf.readBytes())
            put(z, "settings.json", settingsJson(ctx).toByteArray(Charsets.UTF_8))
            val sd = File(ctx.filesDir, "shots")
            sd.listFiles()?.forEach { f ->
                if (f.isFile) put(z, "shots/" + f.name, f.readBytes())
            }
        }
        return out
    }

    private fun put(z: ZipOutputStream, name: String, bytes: ByteArray) {
        z.putNextEntry(ZipEntry(name))
        z.write(bytes)
        z.closeEntry()
    }

    private fun settingsJson(ctx: Context): String {
        val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val arr = JSONArray()
        for ((k, v) in p.all) {
            if (k in SKIP || k.startsWith("failcap_")) continue
            val o = JSONObject().put("k", k)
            when (v) {
                is Float -> o.put("t", "f").put("v", v.toDouble())
                is Boolean -> o.put("t", "b").put("v", v)
                is Int -> o.put("t", "i").put("v", v)
                is Long -> o.put("t", "l").put("v", v)
                is String -> o.put("t", "s").put("v", v)
                else -> continue
            }
            arr.put(o)
        }
        return arr.toString()
    }

    private fun applySettings(ctx: Context, json: String) {
        val e = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val k = o.getString("k")
            if (k in SKIP) continue
            when (o.getString("t")) {
                "f" -> e.putFloat(k, o.getDouble("v").toFloat())
                "b" -> e.putBoolean(k, o.getBoolean("v"))
                "i" -> e.putInt(k, o.getInt("v"))
                "l" -> e.putLong(k, o.getLong("v"))
                "s" -> e.putString(k, o.getString("v"))
            }
        }
        e.apply()
    }

    /** Junta o historico do backup com o atual (sem duplicar) e aplica as configuracoes. */
    fun restore(ctx: Context, uri: Uri): String {
        var hist: ByteArray? = null
        var settings: ByteArray? = null
        val opened = ctx.contentResolver.openInputStream(uri) ?: return "Não consegui abrir o arquivo."
        opened.use { ins ->
            ZipInputStream(ins).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val name = e.name
                    val data = z.readBytes()
                    if (name == "history.json") {
                        hist = data
                    } else if (name == "settings.json") {
                        settings = data
                    } else if (name.startsWith("shots/") && !name.contains("..")) {
                        val f = File(File(ctx.filesDir, "shots"), name.removePrefix("shots/"))
                        f.parentFile?.mkdirs()
                        if (!f.exists()) f.writeBytes(data)
                    }
                    z.closeEntry()
                    e = z.nextEntry
                }
            }
        }
        val h = hist
        val s = settings
        if (h == null && s == null) return "Este arquivo não parece um backup do Rota Card."
        var added = 0
        if (h != null) added = History.merge(ctx, String(h, Charsets.UTF_8))
        if (s != null) applySettings(ctx, String(s, Charsets.UTF_8))
        return "Restaurado: $added corridas novas e as configurações. Feche e abra o app."
    }
}
