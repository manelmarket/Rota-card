package com.rotacard.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Uma corrida aceita, com os dados da oferta e o print. */
data class Ride(
    val id: Long,
    val app: String,
    val hasData: Boolean,
    val price: Double,
    val rating: Double?,
    val pickupKm: Double,
    val tripKm: Double,
    val pickupMin: Double,
    val tripMin: Double,
    val minEstimated: Boolean,
    val shot: String?,
    val gallery: String?
) {
    val totalKm get() = pickupKm + tripKm
    val totalMin get() = pickupMin + tripMin
    val perKm: Double? get() = if (hasData && totalKm > 0.0) price / totalKm else null
    val perHour: Double? get() = if (hasData && totalMin > 0.0) price / totalMin * 60.0 else null

    companion object {
        fun from(o: Offer?, id: Long, label: String, shot: String?, gallery: String?): Ride =
            if (o != null)
                Ride(id, label, true, o.price, o.rating, o.pickupKm, o.tripKm, o.pickupMin, o.tripMin, o.minEstimated, shot, gallery)
            else
                Ride(id, label, false, 0.0, null, 0.0, 0.0, 0.0, 0.0, false, shot, gallery)
    }
}

object History {
    private val lock = Any()
    private const val MAX = 500

    private fun file(ctx: Context) = File(ctx.filesDir, "history.json")
    fun shotFile(ctx: Context, name: String) = File(File(ctx.filesDir, "shots"), name)

    private fun toJson(r: Ride) = JSONObject().apply {
        put("id", r.id); put("app", r.app); put("hasData", r.hasData)
        put("price", r.price); put("rating", r.rating ?: JSONObject.NULL)
        put("pk", r.pickupKm); put("tk", r.tripKm)
        put("pm", r.pickupMin); put("tm", r.tripMin); put("est", r.minEstimated)
        put("shot", r.shot ?: JSONObject.NULL); put("gallery", r.gallery ?: JSONObject.NULL)
    }

    private fun fromJson(o: JSONObject) = Ride(
        o.getLong("id"), o.optString("app", "?"), o.optBoolean("hasData", true),
        o.optDouble("price", 0.0),
        if (o.isNull("rating")) null else o.optDouble("rating"),
        o.optDouble("pk", 0.0), o.optDouble("tk", 0.0),
        o.optDouble("pm", 0.0), o.optDouble("tm", 0.0), o.optBoolean("est", false),
        if (o.isNull("shot")) null else o.optString("shot"),
        if (o.isNull("gallery")) null else o.optString("gallery")
    )

    private fun readAll(ctx: Context): MutableList<Ride> {
        val f = file(ctx)
        val out = mutableListOf<Ride>()
        if (!f.exists()) return out
        try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) out.add(fromJson(arr.getJSONObject(i)))
        } catch (_: Exception) { }
        return out
    }

    private fun writeAll(ctx: Context, list: List<Ride>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        file(ctx).writeText(arr.toString())
    }

    /** Mais recente primeiro. */
    fun load(ctx: Context): List<Ride> = synchronized(lock) { readAll(ctx).reversed() }

    fun last(ctx: Context): Ride? = synchronized(lock) { readAll(ctx).lastOrNull() }

    fun add(ctx: Context, r: Ride) = synchronized(lock) {
        val list = readAll(ctx)
        list.add(r)
        while (list.size > MAX) {
            val old = list.removeAt(0)
            old.shot?.let { shotFile(ctx, it).delete() }
        }
        writeAll(ctx, list)
    }

    fun delete(ctx: Context, id: Long) = synchronized(lock) {
        val list = readAll(ctx)
        list.filter { it.id == id }.forEach { r -> r.shot?.let { shotFile(ctx, it).delete() } }
        writeAll(ctx, list.filter { it.id != id })
    }
}

object Fmt {
    private val br = Locale.forLanguageTag("pt-BR")
    fun money(v: Double) = "R$ " + String.format(br, "%,.2f", v)
    fun d1(v: Double) = String.format(br, "%.1f", v)
    fun d2(v: Double) = String.format(br, "%.2f", v)
    fun n0(v: Double) = String.format(br, "%.0f", v)
}
