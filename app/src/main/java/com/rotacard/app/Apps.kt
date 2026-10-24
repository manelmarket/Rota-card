package com.rotacard.app

/**
 * Os apps de corrida que o Rota Card le. Tudo o que depende do nome do pacote fica aqui,
 * para que acrescentar um app novo seja mudar um lugar so.
 */
object Apps {
    const val UBER = "Uber"
    const val NOVENTA_E_NOVE = "99"
    const val EASYMOB = "Easymob"

    /** Nomes que aparecem nas telas do app (abas de faixas por app etc.). */
    val ALL = listOf(UBER, NOVENTA_E_NOVE, EASYMOB)

    private val MATCHERS = listOf(
        "ubercab" to UBER,
        "app99" to NOVENTA_E_NOVE,
        "easymob" to EASYMOB
    )

    /** Rotulo do app de corrida dono do pacote, ou null se nao for um app de corrida. */
    fun labelFor(pkg: String?): String? {
        if (pkg.isNullOrEmpty()) return null
        for ((part, label) in MATCHERS) if (pkg.contains(part)) return label
        return null
    }

    fun isTarget(pkg: String?): Boolean = labelFor(pkg) != null

    /** Trecho do nome do pacote usado para achar as janelas de um app pelo rotulo. */
    fun packagePart(label: String): String =
        MATCHERS.firstOrNull { it.second == label }?.first ?: label.lowercase()

    /** Apps cuja oferta vem no texto da tela (os outros precisam de OCR). */
    fun hasTextOffer(label: String?): Boolean = label == UBER || label == EASYMOB
}
