package com.rotacard.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import java.util.Calendar
import java.util.Locale

/** Um dia do grafico: rotulo curto (ex.: "seg"), total ganho e R$/h medio (null se sem tempo). */
data class DayBar(val label: String, val total: Double, val perHour: Double?)

object DailyBars {
    private val DOW = arrayOf("dom", "seg", "ter", "qua", "qui", "sex", "sáb")

    /** Total por dia dos ultimos "days" dias (o ultimo e hoje), a partir das corridas aceitas. */
    fun build(rides: List<Ride>, days: Int, now: Long = System.currentTimeMillis()): List<DayBar> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, -(days - 1))
        val out = ArrayList<DayBar>()
        for (i in 0 until days) {
            val start = cal.timeInMillis
            val label = DOW[cal.get(Calendar.DAY_OF_WEEK) - 1]
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val end = cal.timeInMillis
            val sel = rides.filter { it.hasData && it.id >= start && it.id < end }
            val timed = sel.filter { it.totalMin > 0.0 }
            val hours = timed.sumOf { it.totalMin } / 60.0
            out.add(DayBar(label, sel.sumOf { it.price }, if (hours > 0.0) timed.sumOf { it.price } / hours else null))
        }
        return out
    }
}

/** Barras com o total ganho por dia. O dia de maior ganho mostra o valor em cima. */
class DailyBarsView(context: Context) : View(context) {
    private var bars: List<DayBar> = emptyList()
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    fun set(b: List<DayBar>) {
        bars = b
        contentDescription = b.joinToString(", ") { it.label + " " + Fmt.money(it.total) }
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (120 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        if (bars.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val top = 16 * d
        val bottom = h - 18 * d
        val max = bars.maxOf { it.total }
        val slot = w / bars.size
        val bw = (slot * 0.62f).coerceAtMost(28 * d)
        val iMax = bars.indexOfFirst { it.total == max }
        for ((i, b) in bars.withIndex()) {
            val cx = slot * i + slot / 2
            // trilho
            p.color = Color.parseColor("#1D2A47")
            rect.set(cx - bw / 2, top, cx + bw / 2, bottom)
            c.drawRoundRect(rect, bw / 4, bw / 4, p)
            if (max > 0.0 && b.total > 0.0) {
                val y = bottom - ((bottom - top) * (b.total / max)).toFloat()
                p.color = if (i == bars.size - 1) Color.parseColor("#2DD4F0") else Color.parseColor("#3B82F6")
                rect.set(cx - bw / 2, y, cx + bw / 2, bottom)
                c.drawRoundRect(rect, bw / 4, bw / 4, p)
                if (i == iMax) {
                    txt.color = Color.parseColor("#E8EEF9")
                    txt.textSize = 10 * d
                    c.drawText("R$ " + String.format(Locale.US, "%.0f", b.total), cx, (y - 4 * d).coerceAtLeast(10 * d), txt)
                }
            }
            txt.color = Color.parseColor("#8190AD")
            txt.textSize = 10 * d
            c.drawText(if (bars.size > 10) b.label.take(1) else b.label, cx, h - 4 * d, txt)
        }
    }
}
