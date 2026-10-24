package com.rotacard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** O print salvo no aceite tem de ser o que mostrava a oferta, nunca uma captura posterior. */
class ShotStoreTest {

    @Test
    fun achaPelaChave() {
        val s = ShotStore<String>()
        s.put("Uber|10.00|5.0", "Uber", "print-oferta", at = 1_000)
        assertEquals("print-oferta", s.find("Uber|10.00|5.0", "Uber", since = 0, now = 5_000, maxAgeMs = 60_000))
    }

    @Test
    fun capturaDeOutraOfertaNaoSubstituiAPrimeira() {
        val s = ShotStore<String>()
        s.put("Uber|10.00|5.0", "Uber", "oferta-A", at = 1_000)
        s.put("Uber|22.00|9.0", "Uber", "oferta-B", at = 3_000)
        assertEquals("oferta-A", s.find("Uber|10.00|5.0", "Uber", since = 0, now = 4_000, maxAgeMs = 60_000))
    }

    @Test
    fun ocrComChaveDiferenteUsaOPrintDaMesmaPresenca() {
        val s = ShotStore<String>()
        s.put("99|12.80|5.4", "99", "print-ocr", at = 2_000)
        // a leitura seguinte do OCR deu 5.3 km: a chave muda, mas o print desta oferta continua valendo
        assertEquals("print-ocr", s.find("99|12.80|5.3", "99", since = 1_500, now = 6_000, maxAgeMs = 60_000))
        // um print de antes de a oferta aparecer nao vale
        assertNull(s.find("99|12.80|5.3", "99", since = 2_500, now = 6_000, maxAgeMs = 60_000))
    }

    @Test
    fun printVelhoNaoVale() {
        val s = ShotStore<String>()
        s.put("Uber|10.00|5.0", "Uber", "x", at = 1_000)
        assertNull(s.find("Uber|10.00|5.0", "Uber", since = 0, now = 200_000, maxAgeMs = 120_000))
        s.prune(now = 200_000, maxAgeMs = 120_000)
        assertEquals(0, s.size())
    }

    @Test
    fun mesmaOfertaFicaComOPrintMaisRecenteELimiteDeTamanho() {
        val s = ShotStore<String>(max = 2)
        s.put("k1", "Uber", "a", 1)
        s.put("k1", "Uber", "b", 2)
        assertEquals(1, s.size())
        assertEquals("b", s.find("k1", "Uber", 0, 3, 100))
        s.put("k2", "Uber", "c", 3)
        s.put("k3", "Uber", "d", 4)
        assertEquals(2, s.size())
        assertNull(s.find("k1", null, 0, 5, 100))
    }
}
