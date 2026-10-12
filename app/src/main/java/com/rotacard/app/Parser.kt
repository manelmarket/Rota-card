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
    // Uber e 99: "4 min (1.1 km)", "11 minutos (4.5 km)", "4 min (487 m)"
    private val LEG = Regex("""(\d+)\s*min(?:utos)?\s*\(\s*(\d+(?:[.,]\d+)?)\s*(km|m)\s*\)""")
    // Easymob: "Embarque - (3,55 km)" e "Destino - (1,84 km)4 min"
    private val EMB = Regex("""Embarque[^()]*\(\s*(\d+(?:[.,]\d+)?)\s*km\s*\)""")
    private val DEST = Regex("""Destino[^()]*\(\s*(\d+(?:[.,]\d+)?)\s*km\s*\)""")
    private val RATING = Regex("""(?<![\d$,.])([1-5][,.]\d{1,2})(?![\d/])""")

    private fun num(s: String) = s.replace(',', '.').toDouble()

    private fun legKm(m: MatchResult): Double {
        val v = num(m.groupValues[2])
        return if (m.groupValues[3] == "m") v / 1000.0 else v
    }

    private fun build(app: String, text: String, legStart: Int, pickup: Double, trip: Double): Offer? {
        val priceMatch = PRICE.findAll(text).filter { it.range.first < legStart }.lastOrNull() ?: return null
        val price = priceMatch.groupValues[1].replace(".", "").replace(',', '.').toDouble()
        val between = text.substring(priceMatch.range.last + 1, legStart)
        val rating = RATING.find(between)?.groupValues?.get(1)?.let { num(it) }
        if (pickup + trip <= 0.0) return null
        return Offer(app, price, rating, pickup, trip)
    }

    fun parse(text: String): Offer? {
        val emb = EMB.find(text)
        val dest = DEST.find(text)
        if (emb != null && dest != null) {
            return build("Easymob", text, emb.range.first, num(emb.groupValues[1]), num(dest.groupValues[1]))
        }
        val legs = LEG.findAll(text).toList()
        if (legs.size >= 2) {
            val app = if (text.contains("Pgto") || text.contains("corridas")) "99" else "Uber"
            return build(app, text, legs[0].range.first, legKm(legs[0]), legKm(legs[1]))
        }
        return null
    }
}
