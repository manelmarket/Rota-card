package com.rotacard.app

import java.util.Locale

/** Retangulo simples (sem Android) para as regras poderem ser testadas na JVM. */
data class Box(val left: Int, val top: Int, val width: Int, val height: Int)

data class ClickResult(
    /** O toque foi em "Aceitar"/"Selecionar" (ou parece ter sido). */
    val accept: Boolean,
    /** Foi por heuristica (tamanho e posicao do botao), nao pelo texto. */
    val heuristic: Boolean,
    /** Conta como recusa da oferta que estava na tela. */
    val refuse: Boolean
)

data class GoneDecision(val accept: Boolean, val reason: String)

enum class Swipe { RIGHT, LEFT, NONE }

/**
 * Regras de decisao do servico de leitura, sem nada de Android: o que conta como aceite,
 * como recusa e quando uma oferta que sumiu vira "aceite provavel".
 */
object AcceptRules {
    private val REJECT_WORDS = listOf("recusar", "rejeitar", "ignorar", "cancelar")
    private val ACCEPT_WORDS = listOf("aceitar", "selecionar")

    /** Uma oferta que ficou mais tempo que isso na tela provavelmente expirou sozinha. */
    const val EARLY_MS = 12_000L
    /** Uma recusa vale para a oferta se aconteceu ate este tempo antes de ela sumir. */
    const val REFUSE_WINDOW_MS = 12_000L

    /**
     * Classifica um toque num app de corrida.
     * @param label app onde foi o toque (Uber, 99, Easymob)
     * @param text texto do botao e dos filhos (qualquer caixa)
     * @param box onde o toque caiu, ou null se o Android nao informou
     * @param recentApp app da oferta que estava na tela ha pouco (null se nenhuma)
     */
    fun classifyClick(label: String, text: String, box: Box?, screenW: Int, screenH: Int, recentApp: String?): ClickResult {
        val low = text.lowercase(Locale.ROOT)
        val reject = REJECT_WORDS.any { low.contains(it) }
        if (reject) return ClickResult(accept = false, heuristic = false, refuse = true)
        if (ACCEPT_WORDS.any { low.contains(it) }) return ClickResult(accept = true, heuristic = false, refuse = false)

        val sameApp = recentApp != null && recentApp == label
        if (box != null && sameApp) {
            if (label == Apps.NOVENTA_E_NOVE) {
                // a 99 nao expoe texto: toque grande na metade de baixo enquanto a oferta esta na tela
                if (box.top >= screenH * 0.40 && box.height >= screenH * 0.10 && box.width >= screenW * 0.5)
                    return ClickResult(accept = true, heuristic = true, refuse = false)
            } else {
                // Uber e Easymob: botao largo na parte de baixo (o "Aceitar") mesmo sem texto
                if (box.width >= screenW * 0.4 && box.top >= screenH * 0.6)
                    return ClickResult(accept = true, heuristic = true, refuse = false)
            }
        }
        // toque pequeno sem texto numa oferta da Uber (o "X") = recusa
        val refuse = label == Apps.UBER && sameApp
        return ClickResult(accept = false, heuristic = false, refuse = refuse)
    }

    /** A recusa registrada vale para a oferta que esteve na tela a partir de firstSeenAt? */
    fun refused(lastRefuseAt: Long, firstSeenAt: Long, now: Long): Boolean =
        lastRefuseAt > 0L && lastRefuseAt >= firstSeenAt && now - lastRefuseAt < REFUSE_WINDOW_MS

    /**
     * A oferta sumiu sem toque detectado. Decide se conta como aceite provavel.
     * @param durationMs quanto tempo a oferta ficou na tela
     * @param easyCount ultimo numero da contagem do Easymob (null se nao houver)
     */
    fun decideGone(guessOn: Boolean, refused: Boolean, durationMs: Long, app: String, easyCount: Int?): GoneDecision {
        val early = durationMs < EARLY_MS
        val easyLate = app == Apps.EASYMOB && easyCount != null && easyCount < 3
        return if (guessOn && !refused && early && !easyLate)
            GoneDecision(true, "ACEITE PROVAVEL")
        else
            GoneDecision(false, "sem aceite (ligado=$guessOn, recusa=$refused, cedo=$early, fimDaContagem=$easyLate)")
    }

    /**
     * Easymob: compara a posicao horizontal dos elementos da parte de baixo com a primeira leitura.
     * Um deslocamento grande para a direita (e nenhum grande para a esquerda) e o arrasto de aceitar.
     * Posicoes Int.MIN_VALUE sao ignoradas. Devolve o indice do elemento que mais andou.
     */
    fun detectSwipe(base: IntArray, now: IntArray, screenW: Int): Pair<Swipe, Int> {
        if (base.size != now.size) return Swipe.NONE to -1
        var maxDx = 0
        var minDx = 0
        var iMax = -1
        for (i in now.indices) {
            if (now[i] == Int.MIN_VALUE || base[i] == Int.MIN_VALUE) continue
            val dx = now[i] - base[i]
            if (dx > maxDx) { maxDx = dx; iMax = i }
            if (dx < minDx) minDx = dx
        }
        val limit = (screenW * 0.18f).toInt()
        val small = (screenW * 0.10f).toInt()
        return when {
            maxDx >= limit && minDx > -small -> Swipe.RIGHT to iMax
            minDx <= -limit && maxDx < small -> Swipe.LEFT to -1
            else -> Swipe.NONE to -1
        }
    }

    /** Chave que identifica uma oferta (app, valor e distancia). */
    fun offerKey(o: Offer): String = String.format(Locale.US, "%s|%.2f|%.1f", o.app, o.price, o.totalKm)
}
