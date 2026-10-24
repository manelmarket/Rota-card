package com.rotacard.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Os semaforos cadastrados, em lights.json (gravacao atomica, como o historico). */
object LightStore {
    private val lock = Any()
    @Volatile private var cache: List<TrafficLight>? = null
    /** Muda a cada gravacao (as telas e o servico usam para saber se precisam recarregar). */
    @Volatile var version = 0
        private set

    private fun file(ctx: Context) = File(ctx.filesDir, "lights.json")

    fun toJson(l: TrafficLight): JSONObject = JSONObject().apply {
        put("id", l.id); put("name", l.name); put("lat", l.lat); put("lon", l.lon)
        put("b", if (l.bearing.isNaN()) JSONObject.NULL else l.bearing)
        put("n", l.posCount)
        val m = JSONArray()
        for (mk in l.marks) m.put(JSONArray().put(mk.phase.ordinal).put(mk.at))
        put("m", m)
    }

    fun fromJson(o: JSONObject): TrafficLight {
        val arr = o.optJSONArray("m") ?: JSONArray()
        val marks = ArrayList<Mark>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONArray(i) ?: continue
            val p = Phase.values().getOrNull(a.optInt(0, -1)) ?: continue
            marks.add(Mark(p, a.optLong(1)))
        }
        return TrafficLight(
            o.getLong("id"), o.optString("name", "Semáforo"), o.getDouble("lat"), o.getDouble("lon"),
            if (o.isNull("b")) Double.NaN else o.optDouble("b", Double.NaN), o.optInt("n", 1), marks
        )
    }

    private fun readAll(ctx: Context): List<TrafficLight> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val arr = try {
            JSONArray(f.readText())
        } catch (e: Exception) {
            try { f.copyTo(File(ctx.filesDir, "lights.corrupt-" + System.currentTimeMillis() + ".json"), true) } catch (_: Exception) { }
            return emptyList()
        }
        val out = ArrayList<TrafficLight>()
        for (i in 0 until arr.length()) {
            try { out.add(fromJson(arr.getJSONObject(i))) } catch (_: Exception) { }
        }
        return out
    }

    private fun writeAll(ctx: Context, list: List<TrafficLight>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        val f = file(ctx)
        val tmp = File(ctx.filesDir, "lights.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(f)) { f.writeText(arr.toString()); tmp.delete() }
        cache = list
        version++
    }

    fun load(ctx: Context): List<TrafficLight> = synchronized(lock) {
        cache ?: readAll(ctx).also { cache = it }
    }

    /** Registra a marcacao no sinal da esquina (ou cria um). Devolve o sinal e se ele e novo. */
    fun addMark(ctx: Context, lat: Double, lon: Double, heading: Double?, phase: Phase, at: Long): Pair<TrafficLight, Boolean> =
        synchronized(lock) {
            val cur = load(ctx)
            var id = System.currentTimeMillis()
            while (cur.any { it.id == id }) id++
            val (list, light) = Semaforos.withMark(cur, lat, lon, heading, phase, at, id, "Semáforo " + (cur.size + 1))
            writeAll(ctx, list)
            light to (light.id == id)
        }

    fun rename(ctx: Context, id: Long, name: String) = synchronized(lock) {
        writeAll(ctx, load(ctx).map { if (it.id == id) it.copy(name = name.take(60)) else it })
    }

    fun delete(ctx: Context, id: Long) = synchronized(lock) {
        writeAll(ctx, load(ctx).filter { it.id != id })
    }

    /** Apaga as marcacoes (para medir de novo, ex.: depois que a prefeitura mudou o tempo). */
    fun clearMarks(ctx: Context, id: Long) = synchronized(lock) {
        writeAll(ctx, load(ctx).map { if (it.id == id) it.copy(marks = emptyList()) else it })
    }

    fun get(ctx: Context, id: Long): TrafficLight? = load(ctx).firstOrNull { it.id == id }

    /** Texto do arquivo, para o backup. */
    fun exportJson(ctx: Context): String = synchronized(lock) {
        val arr = JSONArray()
        load(ctx).forEach { arr.put(toJson(it)) }
        arr.toString()
    }

    /** Junta semaforos de um backup: os novos entram, e os que ja existem ganham as marcacoes que faltam. */
    fun merge(ctx: Context, json: String): Int = synchronized(lock) {
        val cur = load(ctx).associateBy { it.id }.toMutableMap()
        var added = 0
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val l = try { fromJson(arr.getJSONObject(i)) } catch (_: Exception) { continue }
                val old = cur[l.id]
                if (old == null) {
                    cur[l.id] = l; added++
                } else {
                    cur[l.id] = old.copy(marks = (old.marks + l.marks).distinct().sortedBy { it.at }.takeLast(Semaforos.MAX_MARKS))
                }
            }
        } catch (_: Exception) { }
        writeAll(ctx, cur.values.sortedBy { it.id })
        added
    }
}
