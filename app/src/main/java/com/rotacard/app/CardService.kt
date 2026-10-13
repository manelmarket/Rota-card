package com.rotacard.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale

class CardService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private var card: LinearLayout? = null
    private var big: TextView? = null
    private var small: TextView? = null
    private var dbg: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var scanPending = false
    private var hidePending = false
    private var running = false

    // OCR (99)
    private var ocrBusy = false
    private var lastOcrAt = 0L
    private var ocrOffer: Offer? = null
    private var ocrAt = 0L
    private var ocrState = "-"
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private val scanRunnable = Runnable { scanPending = false; scan() }
    private val hideRunnable = Runnable { hidePending = false; removeCard() }
    private val loopRunnable = object : Runnable {
        override fun run() {
            scan()
            if (running) handler.postDelayed(this, 800)
        }
    }

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        running = true
        handler.postDelayed(loopRunnable, 800)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (scanPending) return
        scanPending = true
        handler.postDelayed(scanRunnable, 200)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        running = false
        handler.removeCallbacksAndMessages(null)
        removeCard(); removeDbg()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        removeCard(); removeDbg()
        super.onDestroy()
    }

    private fun scan() {
        if (!::wm.isInitialized) return
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", true)) {
            removeCard(); removeDbg()
            ocrOffer = null
            return
        }
        val debug = prefs.getBoolean("debug", true)
        val dm = resources.displayMetrics
        val r = Rect()
        var wins = 0
        var texts = 0
        var err = ""
        var offer: Offer? = null
        var parsedText = ""
        var failText = ""
        var big99 = false
        val pkgs = LinkedHashSet<String>()
        try {
            for (w in windows) {
                wins++
                val root = w.root ?: continue
                val pk = root.packageName?.toString() ?: "?"
                if (pk == packageName) continue
                pkgs.add(pk)
                val one = StringBuilder()
                texts += collect(root, one)
                val t = one.toString()
                if (offer == null) {
                    val o = Parser.parse(t)
                    if (o != null) { offer = o; parsedText = t }
                    else if ((pk.contains("ubercab") || pk.contains("easymob")) &&
                        t.contains("R$") && t.contains("km")) failText = t
                }
                if (pk == "com.app99.driver") {
                    w.getBoundsInScreen(r)
                    if (r.height() > dm.heightPixels * 0.35) big99 = true
                }
            }
        } catch (e: Exception) {
            err = e.javaClass.simpleName
        }
        if (offer == null && wins == 0) {
            try {
                rootInActiveWindow?.let { rt ->
                    pkgs.add("ativo:" + rt.packageName)
                    val one = StringBuilder()
                    texts += collect(rt, one)
                    val o = Parser.parse(one.toString())
                    if (o != null) { offer = o; parsedText = one.toString() }
                }
            } catch (e: Exception) {
                err += " " + e.javaClass.simpleName
            }
        }

        // 99: o app nao expoe o texto da oferta, entao le por imagem (OCR)
        if (offer == null && big99 && prefs.getBoolean("ocr99", true)) runOcr(prefs.getBoolean("debug", true))
        val shown = offer ?: (if (SystemClock.uptimeMillis() - ocrAt < 3000) ocrOffer else null)

        if (debug) {
            if (parsedText.isNotEmpty()) prefs.edit().putString("last", parsedText.take(2500)).apply()
            if (offer == null && failText.isNotEmpty()) prefs.edit().putString("fail", failText.take(2500)).apply()
            val src = if (offer != null) "LEU OFERTA" else if (shown != null) "LEU OFERTA (OCR)" else "sem oferta"
            showDbg(
                "janelas=$wins textos=$texts $src $err 99grande=$big99 ocr=$ocrState\n" +
                        pkgs.joinToString(" | ")
            )
        } else {
            removeDbg()
        }

        if (shown != null) {
            handler.removeCallbacks(hideRunnable); hidePending = false
            showCard(shown)
        } else if (card != null && !hidePending) {
            hidePending = true
            handler.postDelayed(hideRunnable, 2500)
        }
    }

    private fun runOcr(debug: Boolean) {
        if (Build.VERSION.SDK_INT < 30) { ocrState = "android<11"; return }
        if (ocrBusy) return
        val now = SystemClock.uptimeMillis()
        if (now - lastOcrAt < 1500) return
        lastOcrAt = now
        ocrBusy = true
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        val hw = result.hardwareBuffer
                        val hwBmp = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                        val sw = hwBmp?.copy(Bitmap.Config.ARGB_8888, false)
                        hw.close()
                        if (sw == null) { ocrBusy = false; ocrState = "bmp-null"; return }
                        val top = (sw.height * 0.40).toInt()
                        val crop = Bitmap.createBitmap(sw, 0, top, sw.width, sw.height - top)
                        recognizer.process(InputImage.fromBitmap(crop, 0))
                            .addOnSuccessListener { vt ->
                                ocrBusy = false
                                onOcrText(vt.text, debug)
                            }
                            .addOnFailureListener { ocrBusy = false; ocrState = "falhou" }
                    } catch (e: Exception) {
                        ocrBusy = false; ocrState = "erro-" + e.javaClass.simpleName
                    }
                }

                override fun onFailure(errorCode: Int) {
                    ocrBusy = false; ocrState = "shot-erro$errorCode"
                }
            })
        } catch (e: Exception) {
            ocrBusy = false; ocrState = "erro-" + e.javaClass.simpleName
        }
    }

    private fun onOcrText(text: String, debug: Boolean) {
        if (debug && text.contains("R$")) {
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("ocr", text.take(2500)).apply()
        }
        ocrOffer = Parser.parse(text, ocr = true)
        ocrAt = SystemClock.uptimeMillis()
        ocrState = if (ocrOffer != null) "ok" else "sem-oferta"
    }

    private fun collect(n: AccessibilityNodeInfo, sb: StringBuilder): Int {
        var c = 0
        n.text?.let { sb.append(it).append('\n'); c++ }
        n.contentDescription?.let { sb.append(it).append('\n'); c++ }
        for (i in 0 until n.childCount) {
            val ch = n.getChild(i) ?: continue
            c += collect(ch, sb)
        }
        return c
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun overlayParams(yDp: Int) = WindowManager.LayoutParams(
        (resources.displayMetrics.widthPixels * 0.92).toInt(),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(yDp) }

    private fun showDbg(msg: String) {
        if (dbg == null) {
            val t = TextView(this).apply {
                setTextColor(Color.WHITE); textSize = 10f
                setBackgroundColor(Color.parseColor("#CC000000"))
                setPadding(dp(6), dp(3), dp(6), dp(3))
            }
            try { wm.addView(t, overlayParams(120)) } catch (_: Exception) { return }
            dbg = t
        }
        dbg?.text = msg
    }

    private fun removeDbg() {
        dbg?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        dbg = null
    }

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
            try { wm.addView(c, overlayParams(36)) } catch (_: Exception) { return }
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
