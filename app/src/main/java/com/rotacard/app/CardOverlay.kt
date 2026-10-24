package com.rotacard.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/** O cartao e a faixa de diagnostico desenhados por cima dos apps de corrida. */
class CardOverlay(private val ctx: Context, private val wm: WindowManager) {
    private var cardUi: CardUi? = null
    private var cardPos = ""
    private var dbg: TextView? = null

    val showing: Boolean get() = cardUi != null

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun overlayParams(yDp: Int, bottom: Boolean = false) = WindowManager.LayoutParams(
        (ctx.resources.displayMetrics.widthPixels * 0.92).toInt(),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = (if (bottom) Gravity.BOTTOM else Gravity.TOP) or Gravity.CENTER_HORIZONTAL
        y = dp(yDp)
    }

    private fun cardParams(s: CardSettings): WindowManager.LayoutParams {
        if (s.cardY >= 0f) {
            val lp = overlayParams(0, false)
            val h = ctx.resources.displayMetrics.heightPixels
            lp.y = (s.cardY * (h - dp(130))).toInt().coerceAtLeast(0)
            return lp
        }
        return overlayParams(if (s.atBottom) 48 else 36, s.atBottom)
    }

    /** Mostra (ou atualiza) o cartao. base = media de R$/km do motorista neste app, se houver. */
    fun showCard(o: Offer, s: CardSettings, base: Double?) {
        val posKey = s.atBottom.toString() + "|" + s.cardY
        if (cardUi != null && cardPos != posKey) removeCard()
        var ui = cardUi
        if (ui == null) {
            ui = CardUi(ctx)
            ui.bind(o, s, base)
            try { wm.addView(ui.root, cardParams(s)) } catch (_: Exception) { return }
            cardUi = ui
            cardPos = posKey
            ui.root.alpha = 0f
            ui.root.translationY = (if (s.atBottom) dp(14) else -dp(14)).toFloat()
            ui.root.animate().alpha(s.opacity).translationY(0f).setDuration(180).start()
        } else {
            ui.bind(o, s, base)
            ui.root.alpha = s.opacity
        }
    }

    fun removeCard() {
        cardUi?.let { try { wm.removeView(it.root) } catch (_: Exception) { } }
        cardUi = null
    }

    fun showDbg(msg: String) {
        if (dbg == null) {
            val t = TextView(ctx).apply {
                setTextColor(Color.WHITE); textSize = 10f
                setBackgroundColor(Color.parseColor("#CC000000"))
                setPadding(dp(6), dp(3), dp(6), dp(3))
            }
            try { wm.addView(t, overlayParams(120)) } catch (_: Exception) { return }
            dbg = t
        }
        dbg?.text = msg
    }

    fun removeDbg() {
        dbg?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        dbg = null
    }

    // ------------------------------------------------------------ cartao do semaforo

    private var light: TextView? = null
    private var lightTop = false

    /** Mostra a previsao do semaforo a frente (do lado oposto ao cartao das ofertas). */
    fun showLight(text: String, color: Int, atTop: Boolean) {
        if (light != null && lightTop != atTop) removeLight()
        if (light == null) {
            val t = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(dp(16), dp(10), dp(16), dp(10))
            }
            val lp = overlayParams(if (atTop) 36 else 48, !atTop)
            try { wm.addView(t, lp) } catch (_: Exception) { return }
            light = t
            lightTop = atTop
        }
        light?.let {
            it.text = text
            it.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Color.argb(80, 255, 255, 255))
            }
        }
    }

    fun removeLight() {
        light?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        light = null
    }

    fun removeAll() { removeCard(); removeDbg(); removeLight() }
}
