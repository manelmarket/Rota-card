package com.rotacard.app

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class LicResult(val ok: Boolean, val msg: String, val network: Boolean = true)

/**
 * Controle de acesso. Fala com o painel (Google Apps Script). Se a URL ainda nao foi configurada,
 * o controle fica desligado e o app funciona como antes.
 */
object License {
    private const val P = "lic"
    private const val DAY = 24L * 3600 * 1000
    private const val OFFLINE_GRACE = 72L * 3600 * 1000

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)

    @Volatile private var urlCache: String? = null
    fun url(ctx: Context): String {
        urlCache?.let { return it }
        val u = ctx.getString(R.string.license_url).trim()
        urlCache = u
        return u
    }

    fun enabled(ctx: Context): Boolean = url(ctx).startsWith("http")

    fun deviceId(ctx: Context): String {
        val p = prefs(ctx)
        val saved = p.getString("dev", null)
        if (!saved.isNullOrBlank()) return saved
        val raw = try {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) { null }
        val id = (raw ?: "").ifBlank { UUID.randomUUID().toString().replace("-", "") }
        p.edit().putString("dev", id).apply()
        return id
    }

    fun shortDevice(ctx: Context): String = deviceId(ctx).take(6).uppercase()

    fun user(ctx: Context): String = prefs(ctx).getString("user", "") ?: ""
    fun displayName(ctx: Context): String = prefs(ctx).getString("name", "") ?: ""
    fun lastMsg(ctx: Context): String = prefs(ctx).getString("lastMsg", "") ?: ""
    fun expiresAt(ctx: Context): Long = prefs(ctx).getLong("exp", 0L)

    private fun serverNow(ctx: Context): Long =
        System.currentTimeMillis() + prefs(ctx).getLong("offset", 0L)

    fun daysLeft(ctx: Context): Int {
        val ms = expiresAt(ctx) - serverNow(ctx)
        return if (ms <= 0) 0 else Math.ceil(ms.toDouble() / DAY).toInt()
    }

    // ---------------------------------------------------------------- decisao local (barata)
    @Volatile private var cacheAt = 0L
    @Volatile private var cacheVal = true
    @Volatile private var memSeen = 0L

    private fun compute(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (p.getString("token", null).isNullOrBlank()) return false
        val now = serverNow(ctx)
        if (now >= p.getLong("exp", 0L)) return false
        if (System.currentTimeMillis() - p.getLong("lastOk", 0L) > OFFLINE_GRACE) return false
        // relogio do aparelho voltado para tras: so volta a valer depois de uma checagem online
        val seen = maxOf(p.getLong("seen", 0L), memSeen)
        if (now < seen - 10 * 60 * 1000) return false
        if (now > memSeen) memSeen = now
        return true
    }

    fun canRun(ctx: Context): Boolean {
        if (!enabled(ctx)) return true
        val t = SystemClock.elapsedRealtime()
        if (t - cacheAt < 2000) return cacheVal
        val v = compute(ctx)
        cacheVal = v
        cacheAt = t
        return v
    }

    // ---------------------------------------------------------------- rede
    private fun post(ctx: Context, body: JSONObject): JSONObject {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        var conn = URL(url(ctx)).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 12000
        conn.readTimeout = 20000
        conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        conn.outputStream.use { it.write(bytes) }
        var code = conn.responseCode
        var hops = 0
        while ((code == 301 || code == 302 || code == 303 || code == 307 || code == 308) && hops < 4) {
            val loc = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            conn = URL(loc).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 12000
            conn.readTimeout = 20000
            code = conn.responseCode
            hops++
        }
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.readText() ?: ""
        conn.disconnect()
        return JSONObject(text)
    }

    private fun store(ctx: Context, r: JSONObject, user: String?, token: String?) {
        val now = r.optLong("now", System.currentTimeMillis())
        val e = prefs(ctx).edit()
        if (user != null) e.putString("user", user)
        if (token != null) e.putString("token", token)
        e.putLong("exp", r.optLong("exp"))
        e.putLong("offset", now - System.currentTimeMillis())
        e.putLong("lastOk", System.currentTimeMillis())
        e.putLong("seen", now)
        e.putString("name", r.optString("name", ""))
        e.remove("lastMsg")
        e.apply()
        cacheAt = 0L
    }

    fun login(ctx: Context, user: String, pass: String): LicResult {
        if (!enabled(ctx)) return LicResult(true, "")
        return try {
            val r = post(
                ctx,
                JSONObject().put("action", "login").put("u", user).put("p", pass).put("dev", deviceId(ctx))
            )
            if (r.optBoolean("ok")) {
                store(ctx, r, user, r.optString("token"))
                LicResult(true, "")
            } else {
                LicResult(false, r.optString("msg", "Não foi possível entrar."))
            }
        } catch (e: Exception) {
            LicResult(false, "Sem conexão com o servidor. Verifique a internet.", false)
        }
    }

    private val DEFINITIVE = setOf("blocked", "expired", "token", "device", "nouser")

    fun checkOnline(ctx: Context): LicResult {
        if (!enabled(ctx)) return LicResult(true, "")
        val p = prefs(ctx)
        val token = p.getString("token", null) ?: return LicResult(false, "Faça login.")
        return try {
            val r = post(
                ctx,
                JSONObject().put("action", "check").put("u", user(ctx)).put("dev", deviceId(ctx)).put("token", token)
            )
            if (r.optBoolean("ok")) {
                store(ctx, r, null, null)
                LicResult(true, "")
            } else {
                val code = r.optString("code")
                val msg = r.optString("msg", "Acesso encerrado.")
                if (code in DEFINITIVE) {
                    p.edit().remove("token").putString("lastMsg", msg).apply()
                    cacheAt = 0L
                    LicResult(false, msg)
                } else {
                    LicResult(canRun(ctx), msg, false)
                }
            }
        } catch (e: Exception) {
            LicResult(canRun(ctx), "offline", false)
        }
    }

    @Volatile private var lastTry = 0L
    @Volatile private var checking = false

    /** Confere com o servidor em segundo plano (no maximo a cada 10 minutos, a menos que force = true). */
    fun checkAsync(ctx: Context, force: Boolean, cb: (Boolean) -> Unit) {
        if (!enabled(ctx) || checking) return
        val now = SystemClock.elapsedRealtime()
        if (!force && lastTry != 0L && now - lastTry < 10 * 60 * 1000) return
        lastTry = now
        checking = true
        val app = ctx.applicationContext
        Thread {
            try {
                checkOnline(app)
                cacheAt = 0L
                cb(canRun(app))
            } catch (_: Throwable) {
            } finally {
                checking = false
            }
        }.start()
    }

    fun logout(ctx: Context) {
        prefs(ctx).edit().remove("token").remove("exp").remove("name").remove("lastMsg").apply()
        cacheAt = 0L
    }
}
