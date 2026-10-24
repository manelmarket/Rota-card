package com.rotacard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceptRulesTest {
    private val w = 1080
    private val h = 2400
    private val bigBottom = Box(60, 2000, 960, 260)
    private val smallTop = Box(950, 300, 80, 80)

    @Test
    fun textoAceitarEAceite() {
        val r = AcceptRules.classifyClick(Apps.UBER, "Aceitar", null, w, h, null)
        assertTrue(r.accept); assertFalse(r.heuristic); assertFalse(r.refuse)
    }

    @Test
    fun selecionarComMaiusculaEAceite() {
        assertTrue(AcceptRules.classifyClick(Apps.UBER, "SELECIONAR ", null, w, h, Apps.UBER).accept)
    }

    @Test
    fun recusarVenceAceitar() {
        val r = AcceptRules.classifyClick(Apps.EASYMOB, "Recusar Aceitar", bigBottom, w, h, Apps.EASYMOB)
        assertFalse(r.accept); assertTrue(r.refuse)
    }

    @Test
    fun botaoLargoEmbaixoEAceitePorHeuristica() {
        val r = AcceptRules.classifyClick(Apps.UBER, "", bigBottom, w, h, Apps.UBER)
        assertTrue(r.accept); assertTrue(r.heuristic)
    }

    @Test
    fun heuristicaSoValeComOfertaDoMesmoApp() {
        assertFalse(AcceptRules.classifyClick(Apps.UBER, "", bigBottom, w, h, Apps.EASYMOB).accept)
        assertFalse(AcceptRules.classifyClick(Apps.UBER, "", bigBottom, w, h, null).accept)
    }

    @Test
    fun toquePequenoNaUberERecusa() {
        val r = AcceptRules.classifyClick(Apps.UBER, "", smallTop, w, h, Apps.UBER)
        assertFalse(r.accept); assertTrue(r.refuse)
    }

    @Test
    fun noventaENoveToqueGrandeNaMetadeDeBaixo() {
        assertTrue(AcceptRules.classifyClick(Apps.NOVENTA_E_NOVE, "", Box(0, 1100, 1080, 300), w, h, Apps.NOVENTA_E_NOVE).accept)
        assertFalse(AcceptRules.classifyClick(Apps.NOVENTA_E_NOVE, "", Box(0, 1100, 300, 300), w, h, Apps.NOVENTA_E_NOVE).accept)
    }

    @Test
    fun ofertaQueSomeCedoSemRecusaEProvavel() {
        assertTrue(AcceptRules.decideGone(true, false, 5_000, Apps.UBER, null).accept)
    }

    @Test
    fun ofertaQueSomeComRecusaOuTardeNaoE() {
        assertFalse(AcceptRules.decideGone(true, true, 5_000, Apps.UBER, null).accept)
        assertFalse(AcceptRules.decideGone(true, false, 15_000, Apps.UBER, null).accept)
        assertFalse(AcceptRules.decideGone(false, false, 5_000, Apps.UBER, null).accept)
    }

    @Test
    fun easymobNoFimDaContagemNaoEAceite() {
        assertFalse(AcceptRules.decideGone(true, false, 5_000, Apps.EASYMOB, 1).accept)
        assertTrue(AcceptRules.decideGone(true, false, 5_000, Apps.EASYMOB, 8).accept)
    }

    @Test
    fun recusaSoValeParaAOfertaAtual() {
        assertTrue(AcceptRules.refused(lastRefuseAt = 10_000, firstSeenAt = 9_000, now = 12_000))
        assertFalse(AcceptRules.refused(lastRefuseAt = 8_000, firstSeenAt = 9_000, now = 12_000))
        assertFalse(AcceptRules.refused(lastRefuseAt = 0, firstSeenAt = 0, now = 12_000))
    }

    @Test
    fun arrastoParaADireita() {
        val base = intArrayOf(100, Int.MIN_VALUE, 500, 900)
        val now = intArrayOf(400, Int.MIN_VALUE, 510, 900)
        val (s, i) = AcceptRules.detectSwipe(base, now, w)
        assertEquals(Swipe.RIGHT, s); assertEquals(0, i)
    }

    @Test
    fun arrastoParaAEsquerdaEPequenosMovimentos() {
        assertEquals(Swipe.LEFT, AcceptRules.detectSwipe(intArrayOf(800, 500), intArrayOf(500, 500), w).first)
        assertEquals(Swipe.NONE, AcceptRules.detectSwipe(intArrayOf(100, 500), intArrayOf(150, 520), w).first)
        assertEquals(Swipe.NONE, AcceptRules.detectSwipe(intArrayOf(100), intArrayOf(100, 2), w).first)
    }
}
