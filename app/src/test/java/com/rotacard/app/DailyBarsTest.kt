package com.rotacard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class DailyBarsTest {
    private fun at(daysAgo: Int, hour: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -daysAgo)
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun ride(id: Long, price: Double, min: Double, hasData: Boolean = true) =
        Ride(id, "Uber", hasData, price, null, 1.0, 4.0, 0.0, min, false, null, null)

    @Test
    fun somaPorDiaComHojeNoFim() {
        val now = at(0, 20)
        val rides = listOf(
            ride(at(0, 9), 10.0, 30.0), ride(at(0, 10), 20.0, 30.0),
            ride(at(2, 15), 7.0, 0.0),
            ride(at(1, 8), 99.0, 10.0, hasData = false),
            ride(at(9, 8), 50.0, 10.0) // fora da janela de 7 dias
        )
        val bars = DailyBars.build(rides, 7, now)
        assertEquals(7, bars.size)
        assertEquals(30.0, bars[6].total, 0.001)
        assertEquals(30.0, bars[6].perHour!!, 0.001) // R$ 30 em 1 h
        assertEquals(0.0, bars[5].total, 0.001)
        assertEquals(7.0, bars[4].total, 0.001)
        assertNull(bars[4].perHour)
        assertEquals(37.0, bars.sumOf { it.total }, 0.001)
    }
}
