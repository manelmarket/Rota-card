package com.rotacard.app

data class Offer(
    val app: String,
    val price: Double,
    val rating: Double?,
    val pickupKm: Double,
    val tripKm: Double
) {
    val totalKm get() = pickupKm + tripKm
    val perKm get() = price / totalKm
}

object Parser {
    // Preco "R$ 15,01" ou "R$14,60" (ignora "R$2,22/km")
    private val PRICE = Regex("""R\$\s*(\d{1,3}(?:\.\d{3})*,\d{2})(?!\d)(?!\s*/\s*km)""")
    // Uber e 99: "4 min (1.1 km)", "11 minutos (4.5 km)", "1 minuto (0.2 km)", "4 min (487 m)"
    private val LEG = Regex("""(\d+)\s*min(?:utos?)?\s*[(\[{]\s*(\d+(?:[.,]\d+)?)\s*(km|m)\s*[)\]}]""")
    // Easymob: "Embarque - (3,55 km)" e "Destino - (1,84 km)4 min"
    private val EMB = Regex("""Embarque[^()]*\(\s*(\d+(?:[.,]\d+)?)\s*(km|m)\s*\)""")
    private val DEST = Regex("""Destino[^()]*\(\s*(\d+(?:[.,]\d+)?)\s*(km|m)\s*\)""")
    private val RATING = Regex("""(?<![\d$,.])([1-5][,.]\d{1,2})(?![\d/])""")

    private fun num(s: String) = s.replace(',', '.').toDouble()
    private fun km(v: String, unit: String) = num(v).let { if (unit == "m") it / 1000.0 else it }
    private fun legKm(m: MatchResult) = km(m.groupValues[2], m.groupValues[3])
    private fun price(m: MatchResult) = m.groupValues[1].replace(".", "").replace(',', '.').toDouble()

    private fun build(
        app: String, text: String, legStart: Int,
        pickup: Double, trip: Double, preferMax: Boolean
    ): Offer? {
        val prices = PRICE.findAll(text).toList()
        val before = prices.filter { it.range.first < legStart }
        val pm = (if (preferMax) before.maxByOrNull { price(it) } else before.lastOrNull())
            ?: prices.firstOrNull { it.range.first > legStart }
            ?: return null
        val region = if (pm.range.first < legStart)
            text.substring(pm.range.last + 1, legStart) else text.substring(0, legStart)
        val rating = RATING.find(region)?.groupValues?.get(1)?.let { num(it) }
        val total = pickup + trip
        val p = price(pm)
        if (total <= 0.0 || p / total > 40.0) return null
        return Offer(app, p, rating, pickup, trip)
    }

    fun parse(text: String, ocr: Boolean = false): Offer? {
        val emb = EMB.find(text)
        val dest = DEST.find(text)
        if (emb != null && dest != null) {
            return build(
                "Easymob", text, emb.range.first,
                km(emb.groupValues[1], emb.groupValues[2]),
                km(dest.groupValues[1], dest.groupValues[2]), false
            )
        }
        val legs = LEG.findAll(text).toList()
        if (legs.size >= 2) {
            val app = if (ocr || text.contains("Pgto") || text.contains("corridas")) "99" else "Uber"
            return build(app, text, legs[0].range.first, legKm(legs[0]), legKm(legs[1]), ocr)
        }
        return null
    }
}
