package com.rotacard.app

import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Semaforos: o motorista marca, parado no sinal, quando ele abre (verde), fica amarelo e fecha
 * (vermelho). Com um ciclo completo medido (verde -> vermelho -> verde) o app sabe a duracao do
 * ciclo e de cada cor.
 *
 * Como a previsao funciona: cada marcacao de verde (e de vermelho) e um ponto "volta n do ciclo,
 * no instante t". Uma reta t = inicio + n * ciclo e ajustada nesses pontos (minimos quadrados).
 * Quanto mais longe no tempo estao as marcacoes, mais preciso fica o ciclo. Uma marcacao nova so
 * entra na reta se der para saber com seguranca de qual volta ela e; senao comeca um trecho novo
 * (o sinal pode ter mudado de plano). O erro esperado da previsao e calculado e vira a confianca.
 *
 * Tudo aqui e puro (sem Android) e testado.
 */

enum class Phase { GREEN, YELLOW, RED }

data class Mark(val phase: Phase, val at: Long)

data class TrafficLight(
    val id: Long,
    val name: String,
    val lat: Double,
    val lon: Double,
    /** Direcao de quem chega ao sinal, em graus (0 = norte, 90 = leste). NaN = ainda nao se sabe. */
    val bearing: Double,
    /** Quantas posicoes entraram na media de lat/lon. */
    val posCount: Int,
    val marks: List<Mark>
)

enum class Confidence { HIGH, MEDIUM, LOW, NONE }

/** O que se sabe dos tempos de um sinal. */
data class Timing(
    /** Duracao do ciclo completo; null = ainda nao mediu um ciclo. */
    val cycleMs: Long?,
    /** Duracao do verde (sem o amarelo); null = nao medido. */
    val greenMs: Long?,
    val yellowMs: Long,
    val yellowEstimated: Boolean,
    /** Instante de um inicio de verde (a referencia para a previsao). */
    val anchor: Long?,
    /** Os ciclos medidos variam muito: sinal "inteligente" ou por botao, sem previsao. */
    val variable: Boolean,
    /** Ciclos medidos diretamente (verde -> vermelho -> verde). */
    val cycleSamples: Int,
    /** Marcacoes que bateram com a previsao feita a partir das anteriores (2 ou mais voltas depois). */
    val hits: Int,
    /** Marcacoes que nao bateram (o sinal mudou de tempo ou o toque foi errado). */
    val misses: Int,
    /** Maior intervalo em que a previsao bateu. */
    val maxVerifiedAgeMs: Long,
    /** Os tempos vieram de marcacoes de outro horario do dia (o plano do sinal pode ser outro). */
    val otherTime: Boolean,
    /** Incerteza do ciclo (desvio padrao, ms). */
    val cycleSigmaMs: Double = 0.0,
    /** Pontos no trecho que deu a ancora. */
    val anchorPoints: Int = 1,
    /** Distancia (em voltas) entre a ancora e o centro do trecho. */
    val anchorSpread: Double = 0.0,
    /** A ancora foi deduzida do vermelho/amarelo (soma a incerteza das duracoes). */
    val anchorDerived: Boolean = false,
    /** Ultima marcacao do trecho que deu a ancora. */
    val anchorRunLastAt: Long? = null
)

data class Prediction(
    /** Cor agora; null quando so se sabe quando vem o proximo verde. */
    val phase: Phase?,
    /** Tempo ate a proxima troca de cor (ou ate o proximo verde se phase = null). */
    val remainingMs: Long,
    val nextGreenInMs: Long,
    val confidence: Confidence,
    /** Erro esperado da previsao (desvio padrao, ms). */
    val sigmaMs: Double = 0.0
)

object Semaforos {
    const val MIN_CYCLE_MS = 20_000L
    const val MAX_CYCLE_MS = 300_000L
    const val DEFAULT_YELLOW_MS = 4_000L
    /** Imprecisao tipica do toque do motorista (desvio padrao). */
    const val TAP_SIGMA_MS = 700.0
    /** Janela de horario (para cada lado) usada para escolher as marcacoes do mesmo "plano". */
    const val TIME_WINDOW_MS = 90L * 60 * 1000
    /** Depois disso sem nenhuma marcacao, o plano do sinal pode ter mudado. */
    const val MAX_ANCHOR_AGE_MS = 4L * 60 * 60 * 1000
    /** Marcas guardadas por sinal. */
    const val MAX_MARKS = 120
    /** Raio para considerar que a marcacao e de um sinal ja cadastrado. */
    const val MATCH_RADIUS_M = 50.0
    const val MATCH_ANGLE = 45.0
    /** Ciclos medidos recomendados no primeiro cadastro. */
    const val RECOMMENDED_CYCLES = 3

    private const val DAY = 24L * 60 * 60 * 1000

    private fun median(v: List<Long>): Long? {
        if (v.isEmpty()) return null
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** Distancia entre dois horarios do dia, em ms (0 a 12 h), no fuso informado. */
    fun timeOfDayDistance(a: Long, b: Long, tzOffsetMs: Long): Long {
        val ta = Math.floorMod(a + tzOffsetMs, DAY)
        val tb = Math.floorMod(b + tzOffsetMs, DAY)
        val d = abs(ta - tb)
        return minOf(d, DAY - d)
    }

    /**
     * Calcula os tempos do sinal. Usa de preferencia as marcacoes feitas perto deste horario do dia
     * (muitos sinais mudam o tempo no pico); se nao houver ciclo medido nesse horario, usa todas.
     */
    fun analyze(marks: List<Mark>, now: Long, tzOffsetMs: Long = TimeZone.getDefault().getOffset(now).toLong()): Timing {
        val all = marks.sortedBy { it.at }
        val near = all.filter { timeOfDayDistance(it.at, now, tzOffsetMs) <= TIME_WINDOW_MS }
        val t = analyzeSet(near, all)
        if (t.cycleMs != null) return t
        val t2 = analyzeSet(all, all)
        if (t2.cycleMs == null) return t2
        // tempos de outro horario; mas se o trecho mais recente tem varias marcacoes de agora que bateram, vale
        val confirmedNow = t2.anchorPoints >= 3 && t2.anchorRunLastAt != null && now - t2.anchorRunLastAt <= TIME_WINDOW_MS
        return t2.copy(otherTime = near.size < all.size && !confirmedNow)
    }

    /** Um trecho de marcacoes da mesma cor que cabem numa reta t = inicio + n * ciclo. */
    private class Run(val phase: Phase) {
        val n = ArrayList<Long>()
        val t = ArrayList<Long>()
        val size get() = n.size
        fun meanN() = n.average()
        fun meanT() = t.average()
        fun sxx(): Double { val m = meanN(); return n.sumOf { (it - m) * (it - m) } }
        fun sxy(): Double { val mn = meanN(); val mt = meanT(); return n.indices.sumOf { (n[it] - mn) * (t[it] - mt) } }
        fun start(c: Double) = meanT() - c * meanN()
        fun add(nn: Long, tt: Long) { n.add(nn); t.add(tt) }
    }

    private fun analyzeSet(set: List<Mark>, all: List<Mark>): Timing {
        val greens = set.filter { it.phase == Phase.GREEN }.map { it.at }
        val reds = set.filter { it.phase == Phase.RED }.map { it.at }

        // ciclos medidos diretamente: duas marcas da mesma cor com a outra cor no meio
        val samples = ArrayList<Long>()
        fun direct(times: List<Long>, other: (Mark) -> Boolean) {
            for (i in 0 until times.size - 1) {
                val d = times[i + 1] - times[i]
                if (d in MIN_CYCLE_MS..MAX_CYCLE_MS && set.any { other(it) && it.at > times[i] && it.at < times[i + 1] }) samples.add(d)
            }
        }
        direct(greens) { it.phase != Phase.GREEN }
        direct(reds) { it.phase == Phase.GREEN }

        // duracao das cores: a partir de cada verde, o amarelo e o vermelho seguintes (antes do proximo verde)
        val gS = ArrayList<Long>()
        val yS = ArrayList<Long>()
        val gyS = ArrayList<Long>()
        for ((i, g) in greens.withIndex()) {
            val limit = minOf(if (i + 1 < greens.size) greens[i + 1] else Long.MAX_VALUE, g + MAX_CYCLE_MS)
            val y = set.firstOrNull { it.phase == Phase.YELLOW && it.at > g && it.at < limit }?.at
            val r = set.firstOrNull { it.phase == Phase.RED && it.at > (y ?: g) && it.at < limit }?.at
            if (y != null) {
                if (y - g in 5_000L..180_000L) gS.add(y - g)
                if (r != null && r - y in 2_000L..8_000L) yS.add(r - y)
            } else if (r != null && r - g in 7_000L..185_000L) {
                gyS.add(r - g)
            }
        }
        val yellow = median(yS) ?: DEFAULT_YELLOW_MS
        val yellowEst = yS.isEmpty()
        var green = median(gS) ?: median(gyS)?.let { it - yellow }?.takeIf { it >= 3_000L }

        val c0 = median(samples)
        val variable = c0 != null && samples.size >= 2 && samples.any { abs(it - c0) > maxOf(3_000L, c0 / 10) }
        val lastAll = all.lastOrNull()
        if (c0 == null || variable) {
            val a = all.lastOrNull { it.phase == Phase.GREEN }?.at
            return Timing(c0, green, yellow, yellowEst, a, variable, samples.size, 0, 0, 0L, false)
        }

        // ajuste da reta, marca a marca, em ordem de tempo
        var c = c0.toDouble()
        var sigmaC = TAP_SIGMA_MS * 1.4
        val runs = ArrayList<Run>()
        val current = HashMap<Phase, Run>()
        var hits = 0
        var misses = 0
        var maxAge = 0L
        fun refit() {
            val sxx = runs.filter { it.size >= 2 }.sumOf { it.sxx() }
            if (sxx > 0.0) {
                c = runs.filter { it.size >= 2 }.sumOf { it.sxy() } / sxx
                sigmaC = TAP_SIGMA_MS / sqrt(sxx)
            }
        }
        for (m in set) {
            if (m.phase == Phase.YELLOW) continue
            val run = current[m.phase]
            if (run == null) {
                val r = Run(m.phase); r.add(0, m.at); runs.add(r); current[m.phase] = r
                continue
            }
            val start = run.start(c)
            val n = Math.round((m.at - start) / c)
            val lastN = run.n.last()
            if (n <= lastN) continue // toque repetido na mesma volta
            val sp = sqrt(TAP_SIGMA_MS * TAP_SIGMA_MS * (1.0 + 1.0 / run.size) +
                    ((n - run.meanN()) * sigmaC).let { it * it })
            if (3 * sp > c / 2) {
                // longe demais para saber de qual volta e: comeca um trecho novo
                val r = Run(m.phase); r.add(0, m.at); runs.add(r); current[m.phase] = r
                continue
            }
            val resid = m.at - (start + n * c)
            if (abs(resid) <= maxOf(3 * sp, 2_000.0)) {
                if (n - lastN >= 2) { hits++; maxAge = maxOf(maxAge, m.at - run.t.last()) }
                run.add(n, m.at)
                refit()
            } else {
                if (n - lastN >= 2) misses++
                val r = Run(m.phase); r.add(0, m.at); runs.add(r); current[m.phase] = r
            }
        }
        val cycle = c.roundToLong()
        if (green != null && green + yellow >= cycle) green = null

        // ancora: a partir do trecho mais recente (verde direto, ou vermelho/amarelo menos as duracoes)
        var anchor: Long? = null
        var points = 1
        var spread = 0.0
        var derived = false
        var runLast: Long? = null
        val latestRun = runs.maxByOrNull { it.t.last() }
        val lastRunT = latestRun?.t?.last() ?: Long.MIN_VALUE
        if (lastAll != null && lastAll.at > lastRunT) {
            // a ultima marcacao e de outro horario (fora do conjunto) ou um amarelo
            anchor = when (lastAll.phase) {
                Phase.GREEN -> lastAll.at
                Phase.YELLOW -> green?.let { lastAll.at - it }
                Phase.RED -> green?.let { lastAll.at - it - yellow }
            }
            derived = lastAll.phase != Phase.GREEN
        }
        if (anchor == null && latestRun != null) {
            val fit = latestRun.start(c) + latestRun.n.last() * c
            val greenFromRun: Double? = if (latestRun.phase == Phase.GREEN) fit else green?.let { fit - it - yellow }
            if (greenFromRun != null) {
                anchor = greenFromRun.roundToLong()
                points = latestRun.size
                spread = latestRun.n.last() - latestRun.meanN()
                derived = latestRun.phase != Phase.GREEN
                runLast = latestRun.t.last()
            } else {
                val gr = runs.filter { it.phase == Phase.GREEN }.maxByOrNull { it.t.last() }
                if (gr != null) {
                    anchor = (gr.start(c) + gr.n.last() * c).roundToLong()
                    points = gr.size
                    spread = gr.n.last() - gr.meanN()
                    runLast = gr.t.last()
                }
            }
        }

        return Timing(
            cycle, green, yellow, yellowEst, anchor, false, samples.size, hits, misses, maxAge, false,
            sigmaC, points, spread, derived, runLast
        )
    }

    /** Erro esperado (desvio padrao) da previsao no instante now. */
    fun sigmaAt(t: Timing, now: Long): Double {
        val c = t.cycleMs ?: return Double.MAX_VALUE
        val a = t.anchor ?: return Double.MAX_VALUE
        val k = (now - a).toDouble() / c + t.anchorSpread
        val base = TAP_SIGMA_MS * TAP_SIGMA_MS / maxOf(1, t.anchorPoints) + (k * t.cycleSigmaMs).let { it * it }
        return sqrt(base) + (if (t.anchorDerived) TAP_SIGMA_MS else 0.0)
    }

    fun predict(t: Timing, now: Long): Prediction? {
        val c = t.cycleMs ?: return null
        val a = t.anchor ?: return null
        if (t.variable) return Prediction(null, 0, 0, Confidence.NONE, Double.MAX_VALUE)
        val age = maxOf(0L, now - a)
        val off = Math.floorMod(now - a, c)
        val nextGreen = if (off == 0L) 0L else c - off
        val g = t.greenMs
        val (phase, rem) = when {
            g == null -> null to nextGreen
            off < g -> Phase.GREEN to (g - off)
            off < g + t.yellowMs -> Phase.YELLOW to (g + t.yellowMs - off)
            else -> Phase.RED to nextGreen
        }
        val sigma = sigmaAt(t, now)
        val conf = when {
            age > MAX_ANCHOR_AGE_MS || sigma > 6_000.0 -> Confidence.LOW
            t.otherTime || sigma > 1_800.0 -> Confidence.MEDIUM
            else -> Confidence.HIGH
        }
        return Prediction(phase, rem, nextGreen, conf, sigma)
    }

    /** Texto curto para o cartao e a notificacao. */
    fun describe(p: Prediction?, t: Timing): String {
        if (t.variable) return "sinal variável, sem previsão"
        if (t.cycleMs == null) return "falta medir um ciclo (verde → vermelho → verde)"
        if (p == null) return "sem previsão"
        if (p.confidence == Confidence.NONE) return "sinal variável, sem previsão"
        if (p.confidence == Confidence.LOW) return "sem previsão confiável: marque quando abrir"
        val til = if (p.confidence == Confidence.HIGH) "" else "~"
        val s = (p.remainingMs + 999) / 1000
        return when (p.phase) {
            Phase.GREEN -> "verde por mais $til$s s"
            Phase.YELLOW -> "amarelo"
            Phase.RED -> "vermelho, abre em $til$s s"
            null -> "próximo verde em $til${(p.nextGreenInMs + 999) / 1000} s"
        }
    }

    // ------------------------------------------------------------ cadastro

    /**
     * Junta a marcacao ao sinal da mesma esquina e direcao (ate 50 m e 45°) ou cria um sinal novo.
     * Devolve a lista nova e o sinal que recebeu a marca.
     */
    fun withMark(
        lights: List<TrafficLight>, lat: Double, lon: Double, heading: Double?,
        phase: Phase, at: Long, newId: Long, defaultName: String
    ): Pair<List<TrafficLight>, TrafficLight> {
        val hit = nearestForMark(lights, lat, lon, heading)
        val mark = Mark(phase, at)
        val updated: TrafficLight = if (hit != null) {
            val n = hit.posCount
            hit.copy(
                lat = (hit.lat * n + lat) / (n + 1),
                lon = (hit.lon * n + lon) / (n + 1),
                posCount = n + 1,
                bearing = if (hit.bearing.isNaN() && heading != null) heading else hit.bearing,
                marks = (hit.marks + mark).sortedBy { it.at }.takeLast(MAX_MARKS)
            )
        } else {
            TrafficLight(newId, defaultName, lat, lon, heading ?: Double.NaN, 1, listOf(mark))
        }
        val out = if (hit != null) lights.map { if (it.id == hit.id) updated else it } else lights + updated
        return out to updated
    }

    fun nearestForMark(lights: List<TrafficLight>, lat: Double, lon: Double, heading: Double?): TrafficLight? =
        lights.mapNotNull { l ->
            val d = Geo.distM(lat, lon, l.lat, l.lon)
            val ok = when {
                d > MATCH_RADIUS_M -> false
                heading == null || l.bearing.isNaN() -> d <= 25.0
                else -> Geo.angleDiff(heading, l.bearing) <= MATCH_ANGLE
            }
            if (ok) l to d else null
        }.minByOrNull { it.second }?.first

    /**
     * O sinal a frente: ate maxDist, na mesma direcao em que se esta indo e na frente do carro
     * (ou a menos de 30 m, parado nele). Devolve o sinal e a distancia.
     */
    fun ahead(lights: List<TrafficLight>, lat: Double, lon: Double, heading: Double?, maxDist: Double = 250.0): Pair<TrafficLight, Double>? =
        lights.mapNotNull { l ->
            val d = Geo.distM(lat, lon, l.lat, l.lon)
            if (d > maxDist) return@mapNotNull null
            if (heading == null) return@mapNotNull if (d <= 30.0) l to d else null
            if (!l.bearing.isNaN() && Geo.angleDiff(heading, l.bearing) > 40.0) return@mapNotNull null
            if (d > 30.0 && Geo.angleDiff(heading, Geo.bearing(lat, lon, l.lat, l.lon)) > 50.0) return@mapNotNull null
            l to d
        }.minByOrNull { it.second }

    private val CARDINAL = arrayOf("norte", "nordeste", "leste", "sudeste", "sul", "sudoeste", "oeste", "noroeste")

    /** "norte", "sudoeste"... (para quem vem nessa direcao). */
    fun cardinal(bearing: Double): String? {
        if (bearing.isNaN()) return null
        val i = Math.floorMod(Math.round(bearing / 45.0).toInt(), 8)
        return CARDINAL[i]
    }
}

object Geo {
    private const val R = 6_371_000.0

    fun distM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * R * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Direcao de 1 para 2, em graus de 0 a 360 (0 = norte). */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Diferenca entre duas direcoes, de 0 a 180 graus. */
    fun angleDiff(a: Double, b: Double): Double {
        val d = abs(((a - b) % 360.0 + 360.0) % 360.0)
        return if (d > 180.0) 360.0 - d else d
    }
}
