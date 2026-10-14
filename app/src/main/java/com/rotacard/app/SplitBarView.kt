package com.rotacard.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Barra com a proporcao de ofertas verdes, amarelas e vermelhas do dia. */
class SplitBarView(context: Context) : View(context) {
    private var r = 0
    private var y = 0
    private var g = 0
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()

    fun set(red: Int, yellow: Int, green: Int) {
        r = red; y = yellow; g = green
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (10 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        clip.reset()
        clip.addRoundRect(rect, h / 2, h / 2, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        p.style = Paint.Style.FILL
        val total = r + y + g
        if (total == 0) {
            p.color = Color.parseColor("#1D2A47")
            c.drawRect(0f, 0f, w, h, p)
        } else {
            val parts = intArrayOf(g, y, r)
            val cols = intArrayOf(Color.parseColor("#22C55E"), Color.parseColor("#F59E0B"), Color.parseColor("#EF4444"))
            var x = 0f
            for (i in 0..2) {
                val seg = w * parts[i] / total
                if (seg > 0f) {
                    p.color = cols[i]
                    c.drawRect(x, 0f, x + seg, h, p)
                    x += seg
                }
            }
        }
        c.restore()
    }
}
