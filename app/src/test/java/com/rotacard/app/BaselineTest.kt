package com.rotacard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BaselineTest {
    private val day = 24L * 3600 * 1000
    private val now = 100L * day

    private fun ride(app: String, price: Double, km: Double, ageDays: Long, hasData: Boolean = true) =
        Ride(now - ageDays * day, app, hasData, price, null, 0.0, km, 0.0, 0.0, false, null, null)

    @Test
    fun poucasCorridasNaoDaoMedia() {
        val rides = List(4) { ride("Uber", 10.0, 5.0, 1) }
        assertNull(Baseline.compute(rides, "Uber", now))
    }

    @Test
    fun mediaPonderadaPelosKm() {
        val rides = List(4) { ride("Uber", 10.0, 5.0, 1) } + ride("Uber", 30.0, 5.0, 2)
        // (40 + 30) / 25 km = 2,80
        assertEquals(2.8, Baseline.compute(rides, "Uber", now)!!, 0.0001)
    }

    @Test
    fun ignoraOutroAppSemDadosEMaisDe30Dias() {
        val rides = List(5) { ride("Uber", 10.0, 5.0, 1) } +
                ride("99", 100.0, 1.0, 1) + ride("Uber", 100.0, 1.0, 1, hasData = false) + ride("Uber", 100.0, 1.0, 40)
        assertEquals(2.0, Baseline.compute(rides, "Uber", now)!!, 0.0001)
    }

    @Test
    fun diferencaPercentual() {
        assertEquals(12, Baseline.deltaPct(2.24, 2.0))
        assertEquals(-10, Baseline.deltaPct(1.8, 2.0))
    }
}
