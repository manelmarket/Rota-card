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
}
