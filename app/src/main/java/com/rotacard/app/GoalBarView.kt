package com.rotacard.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Barra de progresso da meta do dia. */
class GoalBarView(context: Context) : View(context) {
    private var frac = 0f
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()

    fun set(f: Float) {
        frac = f.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (12 * d).toInt())
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
        p.color = Color.parseColor("#1D2A47")
        c.drawRect(0f, 0f, w, h, p)
        if (frac > 0f) {
            p.color = if (frac >= 1f) Color.parseColor("#22C55E") else Color.parseColor("#2DD4F0")
            c.drawRect(0f, 0f, w * frac, h, p)
        }
        c.restore()
    }
}
