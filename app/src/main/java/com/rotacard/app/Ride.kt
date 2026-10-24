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
    val gallery: String?,
    val guess: Boolean = false,
    val cost: Double = 0.0
) {
    val totalKm get() = pickupKm + tripKm
    val totalMin get() = pickupMin + tripMin
    val perKm: Double? get() = if (hasData && totalKm > 0.0) price / totalKm else null
    val perHour: Double? get() = if (hasData && totalMin > 0.0) price / totalMin * 60.0 else null

    companion object {
        fun from(o: Offer?, id: Long, label: String, shot: String?, gallery: String?, guess: Boolean = false, cost: Double = 0.0): Ride =
            if (o != null)
                Ride(id, label, true, o.price, o.rating, o.pickupKm, o.tripKm, o.pickupMin, o.tripMin, o.minEstimated, shot, gallery, guess, cost)
            else
                Ride(id, label, false, 0.0, null, 0.0, 0.0, 0.0, 0.0, false, shot, gallery, guess, 0.0)
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
        put("guess", r.guess)
        put("cost", r.cost)
    }

    private fun fromJson(o: JSONObject) = Ride(
        o.getLong("id"), o.optString("app", "?"), o.optBoolean("hasData", true),
        o.optDouble("price", 0.0),
        if (o.isNull("rating")) null else o.optDouble("rating"),
        o.optDouble("pk", 0.0), o.optDouble("tk", 0.0),
        o.optDouble("pm", 0.0), o.optDouble("tm", 0.0), o.optBoolean("est", false),
        if (o.isNull("shot")) null else o.optString("shot"),
        if (o.isNull("gallery")) null else o.optString("gallery"),
        o.optBoolean("guess", false),
        o.optDouble("cost", 0.0)
    )

    private fun readAll(ctx: Context): MutableList<Ride> {
        val f = file(ctx)
        val out = mutableListOf<Ride>()
        if (!f.exists()) return out
        val text = f.readText()
        val arr = try {
            JSONArray(text)
        } catch (e: Exception) {
            // arquivo estragado: guarda uma copia e para, em vez de seguir com a lista vazia e
            // apagar todo o historico na proxima gravacao
            try { f.copyTo(File(ctx.filesDir, "history.corrupt-" + System.currentTimeMillis() + ".json"), true) } catch (_: Exception) { }
            throw IllegalStateException("historico ilegivel", e)
        }
        for (i in 0 until arr.length()) {
            try { out.add(fromJson(arr.getJSONObject(i))) } catch (_: Exception) { } // pula so o item ruim
        }
        return out
    }

    /** Grava num arquivo temporario e troca de uma vez: se o app morrer no meio, o historico antigo fica intacto. */
    private fun writeAll(ctx: Context, list: List<Ride>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        val f = file(ctx)
        val tmp = File(ctx.filesDir, "history.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(f)) {
            f.writeText(arr.toString())
            tmp.delete()
        }
    }

    /** Mais recente primeiro. Se o arquivo estiver estragado, devolve vazio (a copia fica guardada). */
    fun load(ctx: Context): List<Ride> = synchronized(lock) {
        try { readAll(ctx).reversed() } catch (_: IllegalStateException) { emptyList() }
    }

    fun last(ctx: Context): Ride? = synchronized(lock) {
        try { readAll(ctx).lastOrNull() } catch (_: IllegalStateException) { null }
    }

    /** Le para alterar: se o arquivo estiver estragado, recomeca do zero (a copia ja foi guardada por readAll). */
    private fun readForWrite(ctx: Context): MutableList<Ride> =
        try { readAll(ctx) } catch (_: IllegalStateException) { file(ctx).delete(); mutableListOf() }

    fun add(ctx: Context, r: Ride) = synchronized(lock) {
        val list = readForWrite(ctx)
        list.add(r)
        while (list.size > MAX) {
            val old = list.removeAt(0)
            old.shot?.let { shotFile(ctx, it).delete() }
        }
        writeAll(ctx, list)
    }

    /** Junta ofertas de um backup sem duplicar (compara pelo id). Devolve quantas entraram. */
    fun merge(ctx: Context, json: String): Int = synchronized(lock) {
        val cur = readForWrite(ctx)
        val ids = HashSet<Long>()
        cur.forEach { ids.add(it.id) }
        var added = 0
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val r = fromJson(arr.getJSONObject(i))
                if (!ids.contains(r.id)) {
                    cur.add(r)
                    ids.add(r.id)
                    added++
                }
            }
        } catch (_: Exception) { }
        cur.sortBy { it.id }
        while (cur.size > MAX) {
            val old = cur.removeAt(0)
            old.shot?.let { shotFile(ctx, it).delete() }
        }
        writeAll(ctx, cur)
        added
    }

    fun delete(ctx: Context, id: Long) = synchronized(lock) {
        val list = readForWrite(ctx)
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
