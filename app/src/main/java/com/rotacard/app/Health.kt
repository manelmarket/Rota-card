package com.rotacard.app

import android.content.Context
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Contadores simples de "saude" do app, por dia. */
object Health {
    /** SystemClock.elapsedRealtime() do ultimo ciclo do servico de leitura. */
    @Volatile var lastScan = 0L

    private fun day(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    private fun p(ctx: Context) = ctx.getSharedPreferences("health", Context.MODE_PRIVATE)

    fun inc(ctx: Context, key: String) {
        val sp = p(ctx)
        val d = day()
        val same = sp.getString("day", "") == d
        val e = sp.edit()
        if (!same) { e.clear(); e.putString("day", d) }
        e.putInt(key, (if (same) sp.getInt(key, 0) else 0) + 1)
        e.apply()
    }

    fun get(ctx: Context, key: String): Int {
        val sp = p(ctx)
        return if (sp.getString("day", "") == day()) sp.getInt(key, 0) else 0
    }

    fun clear(ctx: Context) {
        p(ctx).edit().clear().apply()
    }

    /** Segundos desde o ultimo ciclo do servico; -1 se nunca rodou. */
    fun ageSeconds(): Long =
        if (lastScan == 0L) -1L else (SystemClock.elapsedRealtime() - lastScan) / 1000
}
