package com.rotacard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemaforoTest {
    private val s = 1000L
    private val min = 60 * s
    private val h = 60 * min
    /** 8h da manha de um dia qualquer (UTC, fuso 0 nos testes). */
    private val t0 = 100L * 24 * h + 8 * h

    private fun g(t: Long) = Mark(Phase.GREEN, t)
    private fun y(t: Long) = Mark(Phase.YELLOW, t)
    private fun r(t: Long) = Mark(Phase.RED, t)
    private fun analyze(m: List<Mark>, now: Long) = Semaforos.analyze(m, now, 0L)

    @Test
    fun umCicloCompletoComAmarelo() {
        val m = listOf(g(t0), y(t0 + 40 * s), r(t0 + 44 * s), g(t0 + 90 * s))
        val t = analyze(m, t0 + 100 * s)
        assertEquals(90 * s, t.cycleMs)
        assertEquals(40 * s, t.greenMs)
        assertEquals(4 * s, t.yellowMs)
        assertFalse(t.yellowEstimated)
        assertEquals(t0 + 90 * s, t.anchor)

        val start = t0 + 90 * s
        assertEquals(Phase.GREEN, Semaforos.predict(t, start + 10 * s)!!.phase)
        assertEquals(30 * s, Semaforos.predict(t, start + 10 * s)!!.remainingMs)
        assertEquals(Phase.YELLOW, Semaforos.predict(t, start + 42 * s)!!.phase)
        val red = Semaforos.predict(t, start + 60 * s)!!
        assertEquals(Phase.RED, red.phase)
        assertEquals(30 * s, red.remainingMs)
        // dois ciclos depois continua certo
        assertEquals(Phase.GREEN, Semaforos.predict(t, start + 180 * s + 5 * s)!!.phase)
    }

    @Test
    fun semAmareloUsaQuatroSegundos() {
        val t = analyze(listOf(g(t0), r(t0 + 45 * s), g(t0 + 90 * s)), t0 + 91 * s)
        assertEquals(41 * s, t.greenMs)
        assertTrue(t.yellowEstimated)
    }

    @Test
    fun soVerdeNaoDaCiclo() {
        val t = analyze(listOf(g(t0)), t0 + 5 * s)
        assertNull(t.cycleMs)
        assertNull(Semaforos.predict(t, t0 + 5 * s))
        assertTrue(Semaforos.describe(null, t).contains("falta medir"))
    }

    @Test
    fun marcacaoDepoisRefinaOCicloEContaAcerto() {
        // ciclo real de 90,4 s; a medicao direta deu 90 s (toque um pouco atrasado)
        val m = listOf(g(t0), r(t0 + 45 * s), g(t0 + 90 * s), g(t0 + 904 * s), g(t0 + 1808 * s))
        val t = analyze(m, t0 + 1810 * s)
        assertTrue(t.hits >= 2)
        assertEquals(90_400.0, t.cycleMs!!.toDouble(), 150.0)
        assertEquals((t0 + 1808 * s).toDouble(), t.anchor!!.toDouble(), 1000.0)
        assertEquals(Confidence.HIGH, Semaforos.predict(t, t0 + 1810 * s)!!.confidence)
    }

    @Test
    fun ciclosMuitoDiferentesESinalVariavel() {
        val m = listOf(g(t0), r(t0 + 30 * s), g(t0 + 60 * s), r(t0 + 100 * s), g(t0 + 150 * s))
        val t = analyze(m, t0 + 151 * s)
        assertTrue(t.variable)
        assertEquals(Confidence.NONE, Semaforos.predict(t, t0 + 151 * s)!!.confidence)
    }

    @Test
    fun ancoraVelhaPerdeConfianca() {
        val m = listOf(g(t0), r(t0 + 45 * s), g(t0 + 90 * s))
        val t = analyze(m, t0 + 2 * h)
        val p = Semaforos.predict(t, t0 + 2 * h)!!
        assertEquals(Confidence.LOW, p.confidence)
        assertTrue(Semaforos.describe(p, t).contains("marque quando abrir"))
        // logo depois de medir a previsao e boa; alguns ciclos depois o erro acumulado ja pesa
        val logo = analyze(m, t0 + 100 * s)
        assertEquals(Confidence.HIGH, Semaforos.predict(logo, t0 + 100 * s)!!.confidence)
        assertEquals(Confidence.MEDIUM, Semaforos.predict(logo, t0 + 90 * s + 450 * s)!!.confidence)
    }

    @Test
    fun usaOPlanoDoMesmoHorarioDoDia() {
        // manha: ciclo de 90 s. tarde: ciclo de 60 s.
        val manha = listOf(g(t0), r(t0 + 45 * s), g(t0 + 90 * s))
        val tarde0 = t0 + 10 * h
        val tarde = listOf(g(tarde0), r(tarde0 + 30 * s), g(tarde0 + 60 * s))
        assertEquals(60 * s, analyze(manha + tarde, tarde0 + 70 * s).cycleMs)
        // no dia seguinte de manha volta a usar 90 s
        assertEquals(90 * s, analyze(manha + tarde, t0 + 24 * h).cycleMs)
    }

    @Test
    fun cicloDeOutroHorarioViraConfiancaMedia() {
        val manha = listOf(g(t0), r(t0 + 45 * s), g(t0 + 90 * s))
        val noite = t0 + 12 * h
        val t = analyze(manha + g(noite), noite + 10 * s)
        assertEquals(90 * s, t.cycleMs)
        assertTrue(t.otherTime)
        assertEquals(noite, t.anchor)
        assertEquals(Confidence.MEDIUM, Semaforos.predict(t, noite + 10 * s)!!.confidence)
    }

    @Test
    fun vermelhoMarcadoViraAncora() {
        val m = listOf(g(t0), y(t0 + 40 * s), r(t0 + 44 * s), g(t0 + 90 * s), r(t0 + 1000 * s))
        val t = analyze(m, t0 + 1001 * s)
        // inicio do verde deduzido: vermelho - verde - amarelo
        assertEquals(t0 + 1000 * s - 44 * s, t.anchor)
        val p = Semaforos.predict(t, t0 + 1001 * s)!!
        assertEquals(Phase.RED, p.phase)
        assertEquals(45 * s, p.remainingMs)
    }

    /**
     * Um dia simulado: o sinal tem ciclo fixo, o motorista mede "medidos" ciclos no cadastro e depois
     * passa no sinal a cada 25 a 55 min, parado no vermelho, e marca quando abre. Os toques tem erro
     * de ~0,7 s. Devolve (previsoes confiaveis feitas, pior erro delas, ciclo final, acertos).
     */
    /**
     * Um dia simulado: o sinal tem ciclo fixo, o motorista mede "medidos" ciclos no cadastro e depois
     * passa no sinal a cada 25 a 55 min, parado no vermelho, e marca quando abre. Os toques tem erro
     * de ~0,7 s. Devolve [previsoes confiaveis (alta), pior erro entre as de confianca alta,
     * previsoes fora da margem que o proprio app estimou, ciclo final].
     */
    private fun simulate(seed: Long, medidos: Int): LongArray {
        val rnd = java.util.Random(seed)
        fun jitter() = (rnd.nextGaussian() * 700).toLong()
        val cycle = 97_300L
        val green = 45_000L
        val origin = t0 - 37_000L
        fun greenStartAfter(t: Long) = origin + ((t - origin) / cycle + 1) * cycle

        val g1 = greenStartAfter(t0)
        val marks = arrayListOf<Mark>()
        for (i in 0 until medidos) {
            marks.add(g(g1 + i * cycle + jitter()))
            marks.add(r(g1 + i * cycle + green + 4_000 + jitter()))
        }
        marks.add(g(g1 + medidos * cycle + jitter()))
        var t = g1 + medidos * cycle + 10_000
        var high = 0L
        var worstHigh = 0L
        var outOfMargin = 0L
        while (t < t0 + 4 * h) {
            t += (25 + rnd.nextInt(30)) * min
            val timing = analyze(marks, t)
            val p = Semaforos.predict(timing, t)!!
            val realNext = greenStartAfter(t)
            val e0 = kotlin.math.abs(t + p.nextGreenInMs - realNext) % cycle
            val err = minOf(e0, cycle - e0)
            if (p.confidence == Confidence.HIGH) { high++; worstHigh = maxOf(worstHigh, err) }
            if ((p.confidence == Confidence.HIGH || p.confidence == Confidence.MEDIUM) && err > maxOf(3 * p.sigmaMs, 2_000.0)) outOfMargin++
            marks.add(g(realNext + jitter()))
            t = realNext + 5_000
        }
        return longArrayOf(high, worstHigh, outOfMargin, analyze(marks, t).cycleMs!!)
    }

    @Test
    fun simulacaoComTresCiclosMedidosAcerta() {
        for (seed in 1L..30L) {
            val (high, worst, out, cyc) = simulate(seed, Semaforos.RECOMMENDED_CYCLES).toList()
            assertTrue("semente $seed: previsoes de confianca alta=$high", high >= 3)
            assertTrue("semente $seed: pior erro com confianca alta=$worst ms", worst <= 3_000)
            assertEquals("semente $seed: fora da margem", 0L, out)
            assertEquals("semente $seed", 97_300.0, cyc.toDouble(), 300.0)
        }
    }

    @Test
    fun simulacaoComUmCicloNuncaErraForaDaMargem() {
        for (seed in 1L..30L) {
            val (_, worst, out, _) = simulate(seed, 1).toList()
            assertEquals("semente $seed: fora da margem", 0L, out)
            assertTrue("semente $seed: pior erro com confianca alta=$worst ms", worst <= 3_000)
        }
    }

    // ------------------------------------------------------------ cadastro e GPS

    private val lat = -23.5500
    private val lon = -46.6300

    @Test
    fun marcacaoPertoNaMesmaDirecaoJuntaNoMesmoSinal() {
        val (l1, a) = Semaforos.withMark(emptyList(), lat, lon, 0.0, Phase.GREEN, t0, 1, "S1")
        val (l2, b) = Semaforos.withMark(l1, lat + 0.0002, lon, 10.0, Phase.RED, t0 + 45 * s, 2, "S2")
        assertEquals(1, l2.size)
        assertEquals(a.id, b.id)
        assertEquals(2, b.marks.size)
        assertEquals(2, b.posCount)
        assertEquals(lat + 0.0001, b.lat, 1e-9)
    }

    @Test
    fun direcaoOpostaECadastroNovo() {
        val (l1, _) = Semaforos.withMark(emptyList(), lat, lon, 0.0, Phase.GREEN, t0, 1, "S1")
        val (l2, b) = Semaforos.withMark(l1, lat, lon, 180.0, Phase.GREEN, t0 + 5 * s, 2, "S2")
        assertEquals(2, l2.size)
        assertEquals(2L, b.id)
    }

    @Test
    fun longeECadastroNovo() {
        val (l1, _) = Semaforos.withMark(emptyList(), lat, lon, 0.0, Phase.GREEN, t0, 1, "S1")
        val (l2, _) = Semaforos.withMark(l1, lat + 0.001, lon, 0.0, Phase.GREEN, t0, 2, "S2") // ~111 m
        assertEquals(2, l2.size)
    }

    @Test
    fun sinalAFrente() {
        val light = TrafficLight(1, "A", lat, lon, 0.0, 1, emptyList())
        // 100 m ao sul, indo para o norte: esta a frente
        val south = lat - 0.0009
        val hit = Semaforos.ahead(listOf(light), south, lon, 2.0)
        assertNotNull(hit)
        assertEquals(100.0, hit!!.second, 3.0)
        // indo para o sul (direcao contraria do sinal): nao
        assertNull(Semaforos.ahead(listOf(light), south, lon, 180.0))
        // ja passou (100 m ao norte, indo para o norte): nao
        assertNull(Semaforos.ahead(listOf(light), lat + 0.0009, lon, 0.0))
        // muito longe: nao
        assertNull(Semaforos.ahead(listOf(light), lat - 0.005, lon, 0.0))
        // parado em cima dele sem direcao: sim
        assertNotNull(Semaforos.ahead(listOf(light), lat + 0.0001, lon, null))
    }

    @Test
    fun geometria() {
        assertEquals(111.2, Geo.distM(0.0, 0.0, 0.001, 0.0), 0.5)
        assertEquals(0.0, Geo.bearing(0.0, 0.0, 1.0, 0.0), 0.01)
        assertEquals(90.0, Geo.bearing(0.0, 0.0, 0.0, 1.0), 0.01)
        assertEquals(20.0, Geo.angleDiff(350.0, 10.0), 1e-9)
        assertEquals(180.0, Geo.angleDiff(0.0, 180.0), 1e-9)
        assertEquals("norte", Semaforos.cardinal(355.0))
        assertEquals("leste", Semaforos.cardinal(80.0))
        assertEquals("sudoeste", Semaforos.cardinal(225.0))
        assertNull(Semaforos.cardinal(Double.NaN))
    }

    @Test
    fun horarioDoDiaDaVolta() {
        assertEquals(30 * min, Semaforos.timeOfDayDistance(23 * h + 45 * min, 24 * h + 15 * min, 0))
        // fuso -3 h: 02:00 UTC = 23:00 local; 05:00 UTC = 02:00 local -> 3 h
        assertEquals(3 * h, Semaforos.timeOfDayDistance(2 * h, 5 * h, -3 * h))
    }
}
