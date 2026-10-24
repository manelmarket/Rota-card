package com.rotacard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Ofertas reais (dos prints) para garantir que o leitor continua funcionando depois de cada mudanca. */
class ParserTest {

    @Test
    fun uberNormal() {
        val o = Parser.parse("UberX\nR$ 8,08\n4,80 (390)\nVerificado\n5 min (2.1 km)\nRua A\n7 minutos (1.6 km)\nRua B\nSelecionar")
        assertNotNull(o)
        assertEquals("Uber", o!!.app)
        assertEquals(8.08, o.price, 0.001)
        assertEquals(3.7, o.totalKm, 0.001)
        assertEquals(12.0, o.totalMin, 0.001)
    }

    @Test
    fun uberUmMinuto() {
        val o = Parser.parse("UberX\nR$ 6,03\n5,00 (7)\n2 min (0.5 km)\nTv. A\n1 minuto (0.8 km)\nR. B\nSelecionar")
        assertNotNull(o)
        assertEquals(1.3, o!!.totalKm, 0.001)
    }

    @Test
    fun easymobEmMetros() {
        val txt = "R$ 8,50\n5,0\nEmbarque\n- (560m)\nR. Jose, 496\nDestino - (1,17 km)\n3 min\nTv. Vitoria\nEasy economico\nRecusar\nAceitar\n2\n#777697362"
        val o = Parser.parse(txt)
        assertNotNull(o)
        assertEquals("Easymob", o!!.app)
        assertEquals(8.5, o.price, 0.001)
        assertEquals(1.73, o.totalKm, 0.001)
        assertEquals(2, Parser.countdown(txt))
    }

    @Test
    fun noventaENoveOcrSemOR() {
        val o = Parser.parse("Dinheiro\n\$12,80\nR$2,37/km\n4,85 • 57 corridas\nCPF verif.\n3 min (581 m)\nLirios\n9 min (4,8 km)\nRua", true)
        assertNotNull(o)
        assertEquals("99", o!!.app)
        assertEquals(12.8, o.price, 0.001)
        assertEquals(5.381, o.totalKm, 0.001)
    }

    @Test
    fun noventaENovePlanoB() {
        val o = Parser.parse("Dinheiro\nR$14,30\nR$1,40/km\n4,84 • 51 corridas\n6 min 3,8 km\n9 min 6,4 km", true)
        assertNotNull(o)
        assertEquals(14.3, o!!.price, 0.001)
        assertEquals(10.2, o.totalKm, 0.05)
    }

    @Test
    fun textoSemOfertaNaoViraOferta() {
        assertNull(Parser.parse("Voce esta online\nFicar offline\nR$ 120,50 hoje"))
    }

    // ---------------------------------------------------------------- casos extras

    @Test
    fun uberNotaETempos() {
        val o = Parser.parse("UberX\nR$ 8,08\n4,80 (390)\nVerificado\n5 min (2.1 km)\nRua A\n7 minutos (1.6 km)\nRua B\nSelecionar")!!
        assertEquals(4.80, o.rating!!, 0.001)
        assertEquals(2.1, o.pickupKm, 0.001)
        assertEquals(5.0, o.pickupMin, 0.001)
        assertEquals(7.0, o.tripMin, 0.001)
        assertEquals(false, o.minEstimated)
        assertEquals(8.08 / 3.7, o.perKm, 0.001)
        assertEquals(8.08 / 12.0 * 60.0, o.perHour!!, 0.001)
    }

    @Test
    fun uberBuscaEmMetros() {
        val o = Parser.parse("UberX\nR$ 9,40\n4,95 (120)\n4 min (487 m)\nRua A\n12 minutos (5.2 km)\nRua B\nSelecionar")!!
        assertEquals(0.487, o.pickupKm, 0.0001)
        assertEquals(5.687, o.totalKm, 0.0001)
    }

    @Test
    fun precoComMilhar() {
        val o = Parser.parse("UberX\nR$ 1.234,56\n5 min (2.0 km)\nRua A\n90 minutos (110.0 km)\nRua B\nSelecionar")!!
        assertEquals(1234.56, o.price, 0.001)
    }

    @Test
    fun valorPorKmNaoEConfundidoComPreco() {
        // na 99 o "R$/km" vem logo depois do preco: o preco tem de ser o da corrida
        val o = Parser.parse("Pgto\nR$ 15,01\nR$2,22/km\n4,90 • 300 corridas\n3 min (1.2 km)\nRua A\n12 min (5.6 km)\nRua B", true)!!
        assertEquals(15.01, o.price, 0.001)
        assertEquals("99", o.app)
    }

    @Test
    fun ocrComDolarLidoComoS() {
        val o = Parser.parse("Dinheiro\nS12,80\n4,85 • 57 corridas\n3 min (0.6 km)\nRua A\n9 min (4.8 km)\nRua B", true)
        assertNotNull(o)
        assertEquals(12.8, o!!.price, 0.001)
    }

    @Test
    fun easymobSemTempoEstimaPelaDistancia() {
        val o = Parser.parse("R$ 20,00\n4,9\nEmbarque - (3,0 km)\nRua A\nDestino - (6 km)\nRua B\nAceitar")!!
        assertEquals("Easymob", o.app)
        assertEquals(9.0, o.totalKm, 0.001)
        // 3 km e 6 km a 30 km/h = 6 e 12 minutos, marcados como estimados
        assertEquals(6.0, o.pickupMin, 0.001)
        assertEquals(12.0, o.tripMin, 0.001)
        assertEquals(true, o.minEstimated)
    }

    @Test
    fun contagemDoEasymobAusente() {
        assertNull(Parser.countdown("R$ 8,50\nEmbarque\n- (560m)\nDestino - (1,17 km)\nAceitar"))
    }

    @Test
    fun umTrechoSoNaoEOferta() {
        assertNull(Parser.parse("Voce esta online\nR$ 30,00 hoje\n5 min (2.0 km) ate a area de alta demanda"))
    }

    @Test
    fun valorAbsurdoPorKmEDescartado() {
        // R$ 500 por 1 km: leitura errada (ex.: saldo do dia junto com um trecho curto)
        assertNull(Parser.parse("R$ 500,00\n1 min (0.5 km)\nRua A\n1 min (0.5 km)\nRua B"))
    }

    @Test
    fun precoDepoisDosTrechos() {
        val o = Parser.parse("UberX\n5 min (2.0 km)\nRua A\n10 minutos (4.0 km)\nRua B\nR$ 12,00\nSelecionar")!!
        assertEquals(12.0, o.price, 0.001)
        assertEquals("Uber", o.app)
    }
}
