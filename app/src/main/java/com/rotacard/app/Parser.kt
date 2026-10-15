package com.rotacard.app

data class Offer(
    val app: String,
    val price: Double,
    val rating: Double?,
    val pickupKm: Double,
    val tripKm: Double,
    val pickupMin: Double,
    val tripMin: Double,
    val minEstimated: Boolean
) {
    val totalKm get() = pickupKm + tripKm
    val perKm get() = price / totalKm
    val totalMin get() = pickupMin + tripMin
    val perHour: Double? get() = if (totalMin > 0.0) price / totalMin * 60.0 else null
}

object Parser {
    // Preco "R$ 15,01" ou "R$14,60" (ignora "R$2,22/km")
    private val PRICE = Regex("""R\$\s*(\d{1,3}(?:\.\d{3})*,\d{2})(?!\d)(?!\s*/\s*km)""")
    // Preco lido por OCR: o "R" pode estar escondido (ex.: bolha da Uber por cima) ou o "$" lido como "S"
    private val PRICE_OCR = Regex("""(?:R\s*)?[\x24S]\s*(\d{1,3}(?:\.\d{3})*,\d{2})(?!\d)(?!\s*/\s*km)""")
    // Uber e 99: "4 min (1.1 km)", "11 minutos (4.5 km)", "1 minuto (0.2 km)", "4 min (487 m)"
    private val LEG = Regex("""(\d+)\s*min(?:utos?)?\s*[(\[{]\s*(\d+(?:[.,]\d+)?)\s*(km|m)\s*[)\]}]""")
    // Easymob: "Embarque - (3,55 km)" e "Destino - (1,84 km)4 min" (so o destino traz o tempo)
    private val EMB = Regex("""Embarque[^()]*\(\s*(\d+(?:[.,]\d+)?)\s*(km|m)\s*\)""")
    private val DEST = Regex("""Destino[^()]*\(\s*(\d+(?:[.,]\d+)?)\s*(km|m)\s*\)\s*(?:(\d+)\s*min)?""")
    private val RATING = Regex("""(?<![\d$,.])([1-5][,.]\d{1,2})(?![\d/])""")

    // Easymob nao informa o tempo ate o passageiro: estima pela distancia a esta velocidade media
    private const val PICKUP_KMH = 30.0

    private fun labelFor(text: String, ocr: Boolean): String {
        val is99 = text.contains("corridas") || text.contains("Pgto") ||
                text.contains("Dinheiro") || text.contains("CPF verif")
        val isUber = text.contains("UberX") || text.contains("minutos") ||
                text.contains("Selecionar") || text.contains("Verificado")
        return when {
            is99 && !isUber -> "99"
            isUber -> "Uber"
            ocr -> "99"
            else -> "Uber"
        }
    }

    private fun num(s: String) = s.replace(',', '.').toDouble()
    private fun km(v: String, unit: String) = num(v).let { if (unit == "m") it / 1000.0 else it }
    private fun legKm(m: MatchResult) = km(m.groupValues[2], m.groupValues[3])
    private fun legMin(m: MatchResult) = m.groupValues[1].toDouble()
    private fun price(m: MatchResult) = m.groupValues[1].replace(".", "").replace(',', '.').toDouble()

    private fun build(
        app: String, text: String, legStart: Int,
        pickup: Double, trip: Double,
        pickupMin: Double, tripMin: Double, estimated: Boolean,
        preferMax: Boolean
    ): Offer? {
        val prices = (if (preferMax) PRICE_OCR else PRICE).findAll(text).toList()
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
        return Offer(app, p, rating, pickup, trip, pickupMin, tripMin, estimated)
    }

    fun parse(text: String, ocr: Boolean = false): Offer? {
        val emb = EMB.find(text)
        val dest = DEST.find(text)
        if (emb != null && dest != null) {
            val pk = km(emb.groupValues[1], emb.groupValues[2])
            val tr = km(dest.groupValues[1], dest.groupValues[2])
            val tripMin = dest.groups[3]?.value?.toDoubleOrNull() ?: (tr / PICKUP_KMH * 60.0)
            val pickMin = pk / PICKUP_KMH * 60.0
            return build("Easymob", text, emb.range.first, pk, tr, pickMin, tripMin, true, false)
        }
        val legs = LEG.findAll(text).toList()
        if (legs.size >= 2) {
            val app = labelFor(text, ocr)
            return build(
                app, text, legs[0].range.first,
                legKm(legs[0]), legKm(legs[1]),
                legMin(legs[0]), legMin(legs[1]), false, ocr
            )
        }
        return null
    }
}
