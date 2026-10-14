package com.rotacard.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import java.util.Locale

/** Regua de R$/km: mostra onde comeca o amarelo e o verde, igual ao cartao que aparece na corrida. */
class ScaleView(context: Context) : View(context) {

    private var yellow = 1.5f
    private var green = 2.0f
    private var sweep = 1f
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
        color = Color.parseColor("#8190AD")
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val clip = Path()

    fun setRange(y: Float, g: Float, animate: Boolean) {
        yellow = y; green = g
        if (animate) {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 800
                interpolator = DecelerateInterpolator()
                addUpdateListener { sweep = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            sweep = 1f; invalidate()
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (58 * d).toInt())
    }

    private fun fmt(v: Float) = "R$ " + String.format(Locale.US, "%.2f", v).replace('.', ',')

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val barH = 14f * d
        val top = 6f * d
        val max = maxOf(green * 1.6f, green + 0.5f)
        val gx = (green / max).coerceIn(0.1f, 0.95f) * w
        var yx = (yellow / max).coerceIn(0.03f, 0.9f) * w
        if (yx >= gx - 6 * d) yx = gx - 6 * d
        val visible = w * sweep

        rect.set(0f, top, w, top + barH)
        clip.reset()
        clip.addRoundRect(rect, barH / 2, barH / 2, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        p.style = Paint.Style.FILL
        val red = Color.parseColor("#EF4444")
        val amber = Color.parseColor("#F59E0B")
        val grn = Color.parseColor("#22C55E")
        val segs = arrayOf(floatArrayOf(0f, yx), floatArrayOf(yx, gx), floatArrayOf(gx, w))
        val cols = intArrayOf(red, amber, grn)
        for (i in 0..2) {
            val a = minOf(segs[i][0], visible)
            val b = minOf(segs[i][1], visible)
            if (b > a) { p.color = cols[i]; c.drawRect(a, top, b, top + barH, p) }
        }
        c.restore()

        // marcadores
        p.color = Color.parseColor("#8190AD")
        p.strokeWidth = 1.5f * d
        val ty = top + barH + 3f * d
        c.drawLine(yx, top + barH, yx, ty + 3f * d, p)
        c.drawLine(gx, top + barH, gx, ty + 3f * d, p)
        val by = ty + 20f * d
        c.drawText(fmt(yellow), yx.coerceIn(36f * d, w - 36f * d), by, t)
        c.drawText(fmt(green), gx.coerceIn(36f * d, w - 36f * d), by, t)
    }
}
