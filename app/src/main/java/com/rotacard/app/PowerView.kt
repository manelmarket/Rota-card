package com.rotacard.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/** Botao de ligar/desligar: quando ligado emite "ondas" de radar, como se estivesse ouvindo as ofertas. */
class PowerView(context: Context) : View(context) {

    var on: Boolean = true
        set(v) { field = v; invalidate() }

    private var phase = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val d = resources.displayMetrics.density

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(width, height) / 2f
        val r = maxR * 0.50f
        val cyan = Color.parseColor("#2DD4F0")

        if (on) {
            // ondas
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2f * d
            p.shader = null
            for (i in 0..2) {
                val t = (phase + i / 3f) % 1f
                p.color = cyan
                p.alpha = ((1f - t) * 120).toInt()
                c.drawCircle(cx, cy, r + (maxR - r) * t, p)
            }
            // brilho
            p.style = Paint.Style.FILL
            p.color = cyan
            p.alpha = 38
            c.drawCircle(cx, cy, r * 1.16f, p)
        }

        // disco
        val top = if (on) Color.parseColor("#3EE0B0") else Color.parseColor("#2A3556")
        val bottom = if (on) Color.parseColor("#0B8F8A") else Color.parseColor("#141C33")
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy - r * 0.45f, r * 1.5f, intArrayOf(top, bottom), null, Shader.TileMode.CLAMP)
        p.alpha = 255
        c.drawCircle(cx, cy, r, p)
        p.shader = null

        // borda
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2.5f * d
        p.color = if (on) cyan else Color.parseColor("#3A4668")
        p.alpha = if (on) 230 else 255
        c.drawCircle(cx, cy, r, p)

        // icone power
        val ir = r * 0.40f
        p.strokeWidth = r * 0.12f
        p.strokeCap = Paint.Cap.ROUND
        p.color = if (on) Color.WHITE else Color.parseColor("#7C89A8")
        p.alpha = 255
        rect.set(cx - ir, cy - ir, cx + ir, cy + ir)
        c.drawArc(rect, -60f, 300f, false, p)
        c.drawLine(cx, cy - ir * 1.12f, cx, cy - ir * 0.08f, p)
    }
}
