package com.rotacard.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class CardService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private var card: LinearLayout? = null
    private var big: TextView? = null
    private var small: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var scanPending = false
    private var hidePending = false

    private val scanRunnable = Runnable { scanPending = false; scan() }
    private val hideRunnable = Runnable { hidePending = false; removeCard() }

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (scanPending) return
        scanPending = true
        handler.postDelayed(scanRunnable, 200)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeCard()
        super.onDestroy()
    }

    private fun scan() {
        val sb = StringBuilder()
        try {
            for (w in windows) {
                val root = w.root ?: continue
                if (root.packageName?.toString() == packageName) continue
                collect(root, sb)
            }
        } catch (_: Exception) { }
        val offer = Parser.parse(sb.toString())
        if (offer != null) {
            handler.removeCallbacks(hideRunnable); hidePending = false
            showCard(offer)
        } else if (card != null && !hidePending) {
            hidePending = true
            handler.postDelayed(hideRunnable, 1500)
        }
    }

    private fun collect(n: AccessibilityNodeInfo, sb: StringBuilder) {
        n.text?.let { sb.append(it).append('\n') }
        n.contentDescription?.let { sb.append(it).append('\n') }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            collect(c, sb)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showCard(o: Offer) {
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val green = p.getFloat("green", 2.0f)
        val yellow = p.getFloat("yellow", 1.5f)
        val minRating = p.getFloat("minRating", 4.8f)

        val color = when {
            o.perKm >= green -> Color.parseColor("#1B8E3E")
            o.perKm >= yellow -> Color.parseColor("#E0A100")
            else -> Color.parseColor("#C62828")
        }
        val ratingTxt = o.rating?.let {
            String.format(Locale.US, "★ %.2f", it) + if (it < minRating) " ⚠" else ""
        } ?: "★ --"

        val bigTxt = String.format(Locale.US, "R$ %.2f/km", o.perKm).replace('.', ',')
        val smallTxt = String.format(
            Locale.US, "%s  •  %.1f km  •  R$ %.2f  •  %s",
            ratingTxt, o.totalKm, o.price, o.app
        ).replace('.', ',')

        if (card == null) {
            val b = TextView(this).apply {
                setTextColor(Color.WHITE); textSize = 26f
                setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            }
            val s = TextView(this).apply {
                setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
            }
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
                addView(b); addView(s)
            }
            val lp = WindowManager.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.92).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(36) }
            try { wm.addView(c, lp) } catch (_: Exception) { return }
            card = c; big = b; small = s
        }
        card?.background = GradientDrawable().apply {
            setColor(color); cornerRadius = dp(16).toFloat()
        }
        big?.text = bigTxt
        small?.text = smallTxt
    }

    private fun removeCard() {
        card?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        card = null; big = null; small = null
    }
}
