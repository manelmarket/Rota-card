package com.rotacard.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DayStats(
    val count: Int,
    val avgKm: Double,
    val avgHour: Double?,
    val red: Int,
    val yellow: Int,
    val green: Int,
    val bestPerKm: Double,
    val bestPrice: Double,
    val bestDist: Double,
    val bestApp: String,
    val accepted: Int,
    val accSum: Double
)

/** Resumo do dia. Fica so no celular (SharedPreferences) e zera sozinho na virada do dia. */
object Stats {
    private const val P = "stats"
    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun load(ctx: Context): DayStats {
        val p = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
        if (p.getString("day", "") != today()) {
            return DayStats(0, 0.0, null, 0, 0, 0, 0.0, 0.0, 0.0, "", 0, 0.0)
        }
        val n = p.getInt("n", 0)
        val nh = p.getInt("nh", 0)
        return DayStats(
            n,
            if (n > 0) p.getFloat("sumKm", 0f).toDouble() / n else 0.0,
            if (nh > 0) p.getFloat("sumH", 0f).toDouble() / nh else null,
            p.getInt("r", 0), p.getInt("y", 0), p.getInt("g", 0),
            p.getFloat("bestKm", 0f).toDouble(),
            p.getFloat("bestPrice", 0f).toDouble(),
            p.getFloat("bestDist", 0f).toDouble(),
            p.getString("bestApp", "") ?: "",
            p.getInt("acc", 0),
            p.getFloat("accSum", 0f).toDouble()
        )
    }

    fun record(ctx: Context, o: Offer, s: CardSettings) {
        val p = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
        val same = p.getString("day", "") == today()
        val n = if (same) p.getInt("n", 0) else 0
        val nh = if (same) p.getInt("nh", 0) else 0
        val sumKm = if (same) p.getFloat("sumKm", 0f) else 0f
        val sumH = if (same) p.getFloat("sumH", 0f) else 0f
        var r = if (same) p.getInt("r", 0) else 0
        var y = if (same) p.getInt("y", 0) else 0
        var g = if (same) p.getInt("g", 0) else 0
        val best = if (same) p.getFloat("bestKm", 0f) else 0f

        when (CardPrefs.level(o.perKm, s)) { 2 -> g++; 1 -> y++; else -> r++ }

        val e = p.edit()
        if (!same) e.clear()
        e.putString("day", today())
        e.putInt("n", n + 1)
        e.putFloat("sumKm", sumKm + o.perKm.toFloat())
        val h = o.perHour
        if (h != null) {
            e.putInt("nh", nh + 1)
            e.putFloat("sumH", sumH + h.toFloat())
        } else {
            e.putInt("nh", nh)
            e.putFloat("sumH", sumH)
        }
        e.putInt("r", r).putInt("y", y).putInt("g", g)
        if (o.perKm.toFloat() > best) {
            e.putFloat("bestKm", o.perKm.toFloat())
            e.putFloat("bestPrice", o.price.toFloat())
            e.putFloat("bestDist", o.totalKm.toFloat())
            e.putString("bestApp", o.app)
        }
        e.apply()
    }

    fun recordAccepted(ctx: Context, o: Offer) {
        val p = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
        val same = p.getString("day", "") == today()
        val acc = if (same) p.getInt("acc", 0) else 0
        val sum = if (same) p.getFloat("accSum", 0f) else 0f
        val e = p.edit()
        if (!same) e.clear()
        e.putString("day", today())
        e.putInt("acc", acc + 1)
        e.putFloat("accSum", sum + o.price.toFloat())
        e.apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
