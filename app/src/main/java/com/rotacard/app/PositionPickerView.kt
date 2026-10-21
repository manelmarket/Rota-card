package com.rotacard.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** Miniatura de um celular: arraste o cartao para escolher a altura dele na tela. */
class PositionPickerView(
    context: Context,
    private val prefs: SharedPreferences,
    private val onChange: () -> Unit
) : View(context) {

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val phone = RectF()
    private val card = RectF()
    private var frac = 0f
    private var cardH = 0f
    private var travel = 1f
    private var padTop = 0f

    init { reload() }

    fun reload() {
        val y = prefs.getFloat("cardY", -1f)
        frac = if (y >= 0f) y else if (prefs.getBoolean("atBottom", false)) 1f else 0f
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (220 * d).toInt())
    }

    private fun layoutPhone() {
        val ph = height - 8 * d
        val pw = ph * 0.46f
        val left = (width - pw) / 2f
        val top = 4 * d
        phone.set(left, top, left + pw, top + ph)
        cardH = ph * 0.12f
        padTop = top + 6 * d
        travel = ph - cardH - 12 * d
    }

    override fun onDraw(c: Canvas) {
        layoutPhone()
        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#0C1322")
        c.drawRoundRect(phone, 18 * d, 18 * d, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2 * d
        p.color = Color.parseColor("#2A3A5F")
        c.drawRoundRect(phone, 18 * d, 18 * d, p)

        val cw = phone.width() * 0.92f
        val cy = padTop + travel * frac
        card.set(phone.centerX() - cw / 2f, cy, phone.centerX() + cw / 2f, cy + cardH)
        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#1B8E3E")
        c.drawRoundRect(card, 8 * d, 8 * d, p)
        p.color = Color.argb(220, 255, 255, 255)
        c.drawRoundRect(
            RectF(card.left + 10 * d, card.centerY() - 6 * d, card.right - 10 * d, card.centerY() - 2 * d),
            2 * d, 2 * d, p
        )
        p.color = Color.argb(150, 255, 255, 255)
        c.drawRoundRect(
            RectF(card.left + 16 * d, card.centerY() + 3 * d, card.right - 16 * d, card.centerY() + 6 * d),
            2 * d, 2 * d, p
        )
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                layoutPhone()
                val f = ((e.y - padTop - cardH / 2f) / travel).coerceIn(0f, 1f)
                frac = f
                prefs.edit().putFloat("cardY", f).apply()
                invalidate()
                onChange()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
