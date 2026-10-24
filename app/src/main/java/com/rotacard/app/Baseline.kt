package com.rotacard.app

import android.content.Context
import android.os.SystemClock

/**
 * A "media do motorista": R$/km medio das corridas aceitas nos ultimos 30 dias, por app.
 * O cartao usa para dizer se a oferta esta acima ou abaixo do normal dele.
 */
object Baseline {
    const val WINDOW_MS = 30L * 24 * 3600 * 1000
    /** Com menos corridas que isso a media ainda nao diz muito. */
    const val MIN_RIDES = 5
    private const val CACHE_MS = 5L * 60 * 1000

    @Volatile private var cache: Map<String, Double> = emptyMap()
    @Volatile private var cacheAt = 0L

    /** R$/km medio (total recebido / km total) das corridas do app na janela, ou null se ha poucas. */
    fun compute(rides: List<Ride>, app: String, now: Long): Double? {
        val sel = rides.filter { it.app == app && it.hasData && it.totalKm > 0.0 && now - it.id in 0..WINDOW_MS }
        if (sel.size < MIN_RIDES) return null
        val km = sel.sumOf { it.totalKm }
        return if (km > 0.0) sel.sumOf { it.price } / km else null
    }

    /** Diferenca percentual da oferta em relacao a media (ex.: +12 ou -8). */
    fun deltaPct(perKm: Double, base: Double): Int = Math.round((perKm / base - 1.0) * 100.0).toInt()

    fun get(ctx: Context, app: String): Double? {
        val t = SystemClock.elapsedRealtime()
        if (cacheAt == 0L || t - cacheAt > CACHE_MS) {
            val rides = try { History.load(ctx) } catch (_: Throwable) { emptyList() }
            val now = System.currentTimeMillis()
            val m = HashMap<String, Double>()
            for (a in Apps.ALL) compute(rides, a, now)?.let { m[a] = it }
            cache = m
            cacheAt = t
        }
        return cache[app]
    }

    fun invalidate() { cacheAt = 0L }
}
