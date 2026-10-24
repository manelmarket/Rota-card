package com.rotacard.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TileMathTest {
    @Test
    fun idaEVolta() {
        for ((lat, lon) in listOf(-23.55 to -46.63, -15.79 to -47.88, 0.0 to 0.0, 51.5 to -0.12)) {
            for (z in listOf(13, 17, 19)) {
                assertEquals(lat, TileMath.lat(TileMath.y(lat, z), z), 1e-9)
                assertEquals(lon, TileMath.lon(TileMath.x(lon, z), z), 1e-9)
            }
        }
    }

    @Test
    fun blocoConhecido() {
        // centro do mapa: no zoom 1 o ponto 0,0 fica no meio (256 px de cada lado)
        assertEquals(256.0, TileMath.x(0.0, 1), 1e-9)
        assertEquals(256.0, TileMath.y(0.0, 1), 1e-9)
        // bloco do OpenStreetMap que contem lat -23.55, lon -46.63 no zoom 17 (conferido a parte)
        assertEquals(48558, (TileMath.x(-46.63, 17) / 256).toInt())
        assertEquals(74362, (TileMath.y(-23.55, 17) / 256).toInt())
    }
}
