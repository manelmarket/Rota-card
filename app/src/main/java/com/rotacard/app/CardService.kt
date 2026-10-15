package com.rotacard.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.content.ContentValues
import android.provider.MediaStore
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CardService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CardService? = null
    }

    private lateinit var wm: WindowManager
    private var cardUi: CardUi? = null
    private var cardAtBottom = false
    private var dbg: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var scanPending = false
    private var hidePending = false
    private var running = false

    // teste do cartao
    private var testUntil = 0L
    private var testIdx = 0

    // contagem e vibracao: so conta a oferta depois de ve-la 2 vezes seguidas com os mesmos numeros
    private var lastKey = ""
    private var lastKeyAt = 0L
    private var candKey = ""
    private var candCount = 0

    // captura de tela (usada pelo OCR da 99 e pelo print das corridas aceitas)
    private var shotBusy = false
    private var lastShotAt = 0L
    private var lastShot: Bitmap? = null
    private var lastShotKey: String? = null
    private var shotErr = 0

    // oferta que estava na tela e deteccao do "Aceitar"
    private var lastShown: Offer? = null
    private var lastShownAt = 0L
    private var lastAcceptKey = ""
    private var lastAcceptAt = 0L
    private val clickLog = mutableListOf<String>()

    // OCR (99)
    private var ocrOffer: Offer? = null
    private var ocrAt = 0L
    private var ocrState = "-"
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private val scanRunnable = Runnable { scanPending = false; safeScan() }
    private val hideRunnable = Runnable { hidePending = false; removeCard() }
    private val endTest = Runnable {
        if (SystemClock.uptimeMillis() >= testUntil - 50) removeCard()
    }
    private val loopRunnable = object : Runnable {
        override fun run() {
            safeScan()
            if (running) handler.postDelayed(this, 800)
        }
    }

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        running = true
        instance = this
        handler.postDelayed(loopRunnable, 800)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            try { handleClick(event) } catch (_: Exception) { }
        }
        if (scanPending) return
        scanPending = true
        handler.postDelayed(scanRunnable, 200)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        running = false
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
        removeCard(); removeDbg()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        running = false
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
        removeCard(); removeDbg()
        super.onDestroy()
    }

    /** Chamado pelo app: mostra um cartao de exemplo de verdade por 6 s (a cada toque troca a cor). */
    fun showTest() {
        if (!::wm.isInitialized) return
        val s = CardPrefs.load(this)
        val level = testIdx % 3
        testIdx++
        testUntil = SystemClock.uptimeMillis() + 6000
        showCard(CardPrefs.sample(level, s).copy(app = "Teste"))
        if (level == 0 && s.vibrate) Haptics.pulse(this)
        handler.removeCallbacks(endTest)
        handler.postDelayed(endTest, 6000)
    }

    /** Nunca deixa um erro interno derrubar o servico: guarda o erro para eu poder ver no Diagnostico. */
    private fun safeScan() {
        try {
            scan()
        } catch (t: Throwable) {
            try {
                val loc = t.stackTrace.firstOrNull()?.toString() ?: ""
                getSharedPreferences("cfg", MODE_PRIVATE).edit()
                    .putString("lasterr", t.javaClass.simpleName + ": " + (t.message ?: "") + "\n" + loc)
                    .apply()
            } catch (_: Throwable) { }
        }
    }

    private fun scan() {
        if (!::wm.isInitialized) return
        if (SystemClock.uptimeMillis() < testUntil) return
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
        var cardLike = false
        val pkgs = LinkedHashSet<String>()
        try {
            for (w in windows) {
                wins++
                val root = w.root ?: continue
                val pk = root.packageName?.toString() ?: "?"
                if (pk == packageName) continue
                pkgs.add(pk)
                val one = StringBuilder()
                val n = collect(root, one)
                texts += n
                val t = one.toString()
                if (offer == null) {
                    val o = Parser.parse(t)
                    if (o != null) { offer = o; parsedText = t }
                    else if ((pk.contains("ubercab") || pk.contains("easymob")) &&
                        t.contains("R$") && t.contains("km")) failText = t
                }
                if ((pk.contains("ubercab") || pk.contains("easymob")) && n < 6) {
                    w.getBoundsInScreen(r)
                    if (r.top >= dm.heightPixels * 0.30 && r.height() >= dm.heightPixels * 0.25 &&
                        r.height() <= dm.heightPixels * 0.65) cardLike = true
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
        val needOcr = offer == null && (big99 || cardLike) && prefs.getBoolean("ocr99", true)
        val shown = offer ?: (if (SystemClock.uptimeMillis() - ocrAt < 3000) ocrOffer else null)
        val saveOn = prefs.getBoolean("saveAccepted", true)
        if (needOcr || (saveOn && shown != null)) capture(needOcr, debug, shown?.let { offerKey(it) })
        if (shown != null) {
            lastShown = shown
            lastShownAt = SystemClock.uptimeMillis()
        } else if (SystemClock.uptimeMillis() - lastShownAt > 10000) {
            lastShot = null
        }

        if (debug) {
            if (parsedText.isNotEmpty()) prefs.edit().putString("last", parsedText.take(2500)).apply()
            if (offer == null && failText.isNotEmpty()) prefs.edit().putString("fail", failText.take(2500)).apply()
            val src = if (offer != null) "LEU OFERTA" else if (shown != null) "LEU OFERTA (OCR)" else "sem oferta"
            showDbg(
                "janelas=$wins textos=$texts $src $err 99grande=$big99 cartao=$cardLike ocr=$ocrState\n" +
                        pkgs.joinToString(" | ")
            )
        } else {
            removeDbg()
        }

        if (shown != null) {
            handler.removeCallbacks(hideRunnable); hidePending = false
            onOffer(shown)
            showCard(shown)
        } else if (cardUi != null && !hidePending) {
            hidePending = true
            handler.postDelayed(hideRunnable, 2500)
        }
    }

    /** Conta a oferta no resumo do dia e vibra se for verde. Cada oferta e contada uma vez so. */
    private fun onOffer(o: Offer) {
        val key = String.format(Locale.US, "%s|%.2f|%.1f", o.app, o.price, o.totalKm)
        val now = SystemClock.uptimeMillis()
        if (key == lastKey && now - lastKeyAt < 45000) { lastKeyAt = now; return }
        if (key == candKey) candCount++ else { candKey = key; candCount = 1 }
        if (candCount < 2) return
        lastKey = key; lastKeyAt = now; candKey = ""; candCount = 0
        val s = CardPrefs.load(this)
        Stats.record(this, o, s)
        if (s.vibrate && CardPrefs.level(o.perKm, s) == 2) Haptics.pulse(this)
    }

    /** Pede ao Android uma foto da tela. Sem popup nem som. */
    private fun takeShot(cb: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { cb(null); return }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    var out: Bitmap? = null
                    try {
                        val hw = result.hardwareBuffer
                        val hwBmp = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                        out = hwBmp?.copy(Bitmap.Config.ARGB_8888, false)
                        hwBmp?.recycle()
                        hw.close()
                    } catch (_: Throwable) { }
                    cb(out)
                }

                override fun onFailure(errorCode: Int) {
                    shotErr = errorCode
                    cb(null)
                }
            })
        } catch (e: Exception) {
            cb(null)
        }
    }

    /** Guarda a ultima foto da tela (para o print do "Aceitar") e, se preciso, le a oferta da 99 por OCR. */
    private fun capture(doOcr: Boolean, debug: Boolean, key: String?) {
        if (Build.VERSION.SDK_INT < 30) { if (doOcr) ocrState = "android<11"; return }
        if (shotBusy) return
        val now = SystemClock.uptimeMillis()
        if (now - lastShotAt < 1000) return
        lastShotAt = now
        shotBusy = true
        takeShot { bmp ->
            if (bmp == null) {
                shotBusy = false
                if (doOcr) ocrState = "shot-erro$shotErr"
                return@takeShot
            }
            lastShot = bmp
            lastShotKey = key
            if (!doOcr) { shotBusy = false; return@takeShot }
            try {
                val top = (bmp.height * 0.30).toInt()
                val crop = Bitmap.createBitmap(bmp, 0, top, bmp.width, bmp.height - top)
                recognizer.process(InputImage.fromBitmap(crop, 0))
                    .addOnSuccessListener { vt ->
                        shotBusy = false
                        onOcrText(vt.text, debug)
                        ocrOffer?.let { lastShotKey = offerKey(it) }
                    }
                    .addOnFailureListener { shotBusy = false; ocrState = "falhou" }
            } catch (e: Throwable) {
                shotBusy = false
                ocrState = "erro-" + e.javaClass.simpleName
            }
        }
    }

    private fun onOcrText(text: String, debug: Boolean) {
        if (debug && text.contains("R$")) {
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("ocr", text.take(2500)).apply()
        }
        val parsed = Parser.parse(text, ocr = true)
        ocrOffer = parsed
        if (parsed == null && debug && (text.contains("km") || text.contains("min"))) {
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("ocrfail", text.take(2500)).apply()
        }
        ocrAt = SystemClock.uptimeMillis()
        ocrState = if (ocrOffer != null) "ok" else "sem-oferta"
    }

    private fun offerKey(o: Offer) = String.format(Locale.US, "%s|%.2f|%.1f", o.app, o.price, o.totalKm)

    /** Toque na tela dos apps de corrida: se foi em "Aceitar"/"Selecionar", guarda o print da oferta. */
    private fun handleClick(e: AccessibilityEvent) {
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", true) || !prefs.getBoolean("saveAccepted", true)) return
        val pkg = e.packageName?.toString() ?: return
        val label = when {
            pkg.contains("ubercab") -> "Uber"
            pkg.contains("app99") -> "99"
            pkg.contains("easymob") -> "Easymob"
            else -> return
        }
        val now = SystemClock.uptimeMillis()
        val sb = StringBuilder()
        e.text?.forEach { sb.append(it).append(' ') }
        e.contentDescription?.let { sb.append(it).append(' ') }
        val r = Rect()
        val src = e.source
        if (src != null) {
            src.getBoundsInScreen(r)
            collect(src, sb)
        }
        val low = sb.toString().lowercase(Locale.ROOT)
        val reject = low.contains("recusar") || low.contains("rejeitar") ||
                low.contains("ignorar") || low.contains("cancelar")
        var accept = !reject && (low.contains("aceitar") || low.contains("selecionar"))
        val recent = lastShown?.takeIf { now - lastShownAt < 4000 }
        var heur = false
        // 99 nao expoe texto: toque grande na metade de baixo enquanto a oferta esta na tela
        if (!accept && label == "99" && recent != null && recent.app == "99" && src != null) {
            val dm = resources.displayMetrics
            if (r.top >= dm.heightPixels * 0.40 && r.height() >= dm.heightPixels * 0.10 &&
                r.width() >= dm.widthPixels * 0.5) {
                accept = true; heur = true
            }
        }
        if (prefs.getBoolean("debug", true)) {
            val cls = e.className?.toString()?.substringAfterLast('.') ?: "?"
            val txt = sb.toString().replace('\n', ' ').take(50)
            val res = if (accept) (if (heur) "ACEITE (heuristica)" else "ACEITE") else "-"
            clickLog.add(0, "$label | $cls | $txt | ${r.width()}x${r.height()} top=${r.top} | $res")
            while (clickLog.size > 8) clickLog.removeAt(clickLog.size - 1)
            prefs.edit().putString("clicks", clickLog.joinToString("\n")).apply()
        }
        if (accept) onAccepted(recent, label)
    }

    private fun onAccepted(o: Offer?, label: String) {
        val now = SystemClock.uptimeMillis()
        val key = if (o != null) offerKey(o) else "?$label"
        if (key == lastAcceptKey && now - lastAcceptAt < 10000) return
        lastAcceptKey = key
        lastAcceptAt = now
        if (o != null) Stats.recordAccepted(this, o)
        val shot = lastShot
        if (o != null && shot != null && lastShotKey == key && now - lastShotAt < 8000) {
            // print tirado ANTES do toque: mostra a oferta ainda na tela
            saveShot(shot, o, label)
        } else {
            takeShot { b ->
                if (b != null) saveShot(b, o, label)
                else handler.postDelayed({ takeShot { b2 -> saveShot(b2, o, label) } }, 450)
            }
        }
    }

    /** Salva o print na galeria (Pictures/RotaCard), guarda uma copia menor no app e registra no historico. */
    private fun saveShot(bmp: Bitmap?, o: Offer?, label: String) {
        val id = System.currentTimeMillis()
        Thread {
            var gallery: String? = null
            var thumb: String? = null
            var err: String? = null
            if (bmp != null) {
                try {
                    val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(id))
                    val info = if (o != null) String.format(Locale.US, "_R%.2f_%.2fkm", o.price, o.perKm) else ""
                    val name = "Corrida_${ts}_$label$info.jpg"
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RotaCard")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        contentResolver.update(uri, values, null, null)
                        gallery = uri.toString()
                    }
                } catch (e: Exception) {
                    err = e.javaClass.simpleName
                }
                try {
                    val w = 540
                    val h = (bmp.height * w.toFloat() / bmp.width).toInt()
                    val small = Bitmap.createScaledBitmap(bmp, w, h, true)
                    val f = History.shotFile(this, "$id.jpg")
                    f.parentFile?.mkdirs()
                    f.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                    thumb = "$id.jpg"
                } catch (_: Exception) { }
            }
            if (label != "Teste") History.add(this, Ride.from(o, id, label, thumb, gallery))
            val msg = when {
                bmp == null -> "Aceite detectado, mas não consegui tirar o print"
                gallery == null && thumb == null -> "Falha ao salvar o print" + (err?.let { ": $it" } ?: "")
                else -> "Corrida salva (galeria: RotaCard)"
            }
            handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        }.start()
    }

    /** Chamado pelo app: tira e salva um print agora, so para conferir que o salvamento funciona. */
    fun saveTest() {
        takeShot { b ->
            if (b != null) saveShot(b, null, "Teste")
            else Toast.makeText(this, "Não consegui capturar a tela (erro $shotErr)", Toast.LENGTH_LONG).show()
        }
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

    private fun overlayParams(yDp: Int, bottom: Boolean = false) = WindowManager.LayoutParams(
        (resources.displayMetrics.widthPixels * 0.92).toInt(),
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
        val s = CardPrefs.load(this)
        if (cardUi != null && cardAtBottom != s.atBottom) removeCard()
        var ui = cardUi
        if (ui == null) {
            ui = CardUi(this)
            ui.bind(o, s)
            try { wm.addView(ui.root, overlayParams(if (s.atBottom) 48 else 36, s.atBottom)) }
            catch (_: Exception) { return }
            cardUi = ui
            cardAtBottom = s.atBottom
            ui.root.alpha = 0f
            ui.root.translationY = (if (s.atBottom) dp(14) else -dp(14)).toFloat()
            ui.root.animate().alpha(s.opacity).translationY(0f).setDuration(180).start()
        } else {
            ui.bind(o, s)
            ui.root.alpha = s.opacity
        }
    }

    private fun removeCard() {
        cardUi?.let { try { wm.removeView(it.root) } catch (_: Exception) { } }
        cardUi = null
    }
}
