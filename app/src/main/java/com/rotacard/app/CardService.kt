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

    // Easymob: arrasto para aceitar
    private var easyKey = ""
    private var easyCount: Int? = null
    private var easySeenAt = 0L
    private var easyActiveUntil = 0L
    private var easyBase: IntArray? = null
    private var swipeDone = false
    private var easyGoneDone = false
    private var lastRefuseAt = 0L
    private var fastRunning = false
    private val easyLog = mutableListOf<String>()
    private var curFirstAt = 0L
    private var goneHandled = false
    private var lastLeftLog = 0L

    // voz, notificacao e prints das ofertas nao lidas
    private var tts: android.speech.tts.TextToSpeech? = null
    private var ttsReady = false
    private var ttsPending: String? = null
    private var lastNotifAt = 0L
    private var lastFailShotAt = 0L
    private val fastRunnable = object : Runnable {
        override fun run() {
            try { fastTick() } catch (_: Throwable) { }
            if (running && SystemClock.uptimeMillis() < easyActiveUntil) handler.postDelayed(this, 150)
            else fastRunning = false
        }
    }

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
        refreshNotification()
        handler.postDelayed(loopRunnable, 800)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!License.canRun(this)) return
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
        try { (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(1) } catch (_: Throwable) { }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        running = false
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
        removeCard(); removeDbg()
        try { tts?.shutdown() } catch (_: Throwable) { }
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
        if (!License.canRun(this)) { removeCard(); removeDbg(); return }
        License.checkAsync(this, false) { }
        Health.lastScan = SystemClock.elapsedRealtime()
        if (SystemClock.elapsedRealtime() - lastNotifAt > 30000) refreshNotification()
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
                    if (o != null) {
                        offer = o; parsedText = t
                        if (pk.contains("easymob") && o.app == "Easymob") onEasyOffer(o, t, prefs)
                    }
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
        // Uber/Easymob: tinha texto de oferta mas nao deu para ler -> guarda um print para eu poder corrigir
        if (offer == null && failText.isNotEmpty() && failureAllowed()) {
            takeShot { b -> writeFailure(b, "Arvore") }
        }
        if (needOcr || (saveOn && shown != null)) capture(needOcr, debug, shown?.let { offerKey(it) })
        val nowMs = SystemClock.uptimeMillis()
        if (shown != null) {
            val prev = lastShown
            // comeca uma "presenca" nova quando e outro app ou passou mais de 3 s sem ver a oferta
            if (prev == null || prev.app != shown.app || nowMs - lastShownAt > 3000) {
                curFirstAt = nowMs
                goneHandled = false
            }
            lastShown = shown
            lastShownAt = nowMs
        } else {
            val o = lastShown
            val gap = nowMs - lastShownAt
            if (o != null && !goneHandled && gap in 2000..8000) {
                goneHandled = true
                val duration = lastShownAt - curFirstAt
                val refused = lastRefuseAt >= curFirstAt && nowMs - lastRefuseAt < 12000
                val early = duration < 12000
                val c = easyCount
                val easyLate = o.app == "Easymob" && c != null && c < 3
                val decision: String
                if (prefs.getBoolean("guessAccept", true) && !refused && early && !easyLate) {
                    decision = "ACEITE PROVAVEL"
                    onAccepted(o, o.app, true)
                } else {
                    decision = "sem aceite (recusa=$refused, cedo=$early, fimDaContagem=$easyLate)"
                }
                easyLogAdd(o.app + ": oferta sumiu apos " + (duration / 1000) + " s -> " + decision)
                dumpPost(o.app, decision)
            }
            if (gap > 10000) lastShot = null
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
        val s = CardPrefs.load(this, o.app)
        Stats.record(this, o, s)
        Health.inc(this, "read_" + o.app)
        if (s.voice) speakVerdict(o, s)
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
                        if (ocrOffer != null) {
                            Health.inc(this, "ocr_ok")
                        } else if (looksLikeOffer(vt.text)) {
                            Health.inc(this, "ocr_fail")
                            if (failureAllowed()) writeFailure(bmp, "OCR")
                        }
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
        if (reject) lastRefuseAt = now
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
        // Uber e Easymob: botao largo na parte de baixo (o "Aceitar") mesmo sem texto
        if (!accept && !reject && label != "99" && recent != null && src != null) {
            val dm2 = resources.displayMetrics
            if (r.width() >= dm2.widthPixels * 0.4 && r.top >= dm2.heightPixels * 0.6) {
                accept = true; heur = true
            }
        }
        // toque pequeno sem texto numa oferta da Uber (o "X") = recusa
        if (!accept && !reject && label == "Uber" && recent != null) lastRefuseAt = now
        if (prefs.getBoolean("debug", true)) {
            val cls = e.className?.toString()?.substringAfterLast('.') ?: "?"
            val txt = sb.toString().replace('\n', ' ').take(50)
            val res = if (accept) (if (heur) "ACEITE (heuristica)" else "ACEITE") else "-"
            clickLog.add(0, "$label | $cls | $txt | ${r.width()}x${r.height()} top=${r.top} | $res")
            while (clickLog.size > 14) clickLog.removeAt(clickLog.size - 1)
            prefs.edit().putString("clicks", clickLog.joinToString("\n")).apply()
        }
        if (accept) onAccepted(recent, label)
    }

    private fun onAccepted(o: Offer?, label: String, guess: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        val key = if (o != null) offerKey(o) else "?$label"
        if (key == lastAcceptKey && now - lastAcceptAt < 10000) return
        lastAcceptKey = key
        lastAcceptAt = now
        if (o != null) Stats.recordAccepted(this, o)
        val shot = lastShot
        if (o != null && shot != null && (lastShotKey == key || guess) && now - lastShotAt < (if (guess) 12000 else 8000)) {
            // print tirado ANTES do toque: mostra a oferta ainda na tela
            saveShot(shot, o, label, guess)
        } else {
            takeShot { b ->
                if (b != null) saveShot(b, o, label, guess)
                else handler.postDelayed({ takeShot { b2 -> saveShot(b2, o, label, guess) } }, 450)
            }
        }
    }

    /** Salva o print na galeria (Pictures/RotaCard), guarda uma copia menor no app e registra no historico. */
    private fun saveShot(bmp: Bitmap?, o: Offer?, label: String, guess: Boolean = false) {
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
            if (label != "Teste") {
                try {
                    History.add(this, Ride.from(
                        o, id, label, thumb, gallery, guess,
                        if (o != null) CardPrefs.load(this).costKm * o.totalKm else 0.0
                    ))
                } catch (t: Throwable) {
                    getSharedPreferences("cfg", MODE_PRIVATE).edit()
                        .putString("lasterr", "historico: " + t.javaClass.simpleName + " " + (t.message ?: "")).apply()
                }
            }
            val msg = when {
                bmp == null -> "Aceite detectado, mas não consegui tirar o print"
                gallery == null && thumb == null -> "Falha ao salvar o print" + (err?.let { ": $it" } ?: "")
                else -> "Corrida salva (galeria: RotaCard)"
            }
            handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        }.start()
    }

    /** Guarda o que apareceu na tela do app ~2,5 s depois da oferta sumir (para eu aprender como cada app mostra o aceite). */
    private fun dumpPost(app: String, decision: String) {
        handler.postDelayed({
            try {
                val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
                if (!prefs.getBoolean("debug", true)) return@postDelayed
                val key = when (app) { "Uber" -> "ubercab"; "99" -> "app99"; else -> "easymob" }
                val sb = StringBuilder()
                for (w in windows) {
                    val r = w.root ?: continue
                    if (r.packageName?.toString()?.contains(key) == true) collect(r, sb)
                }
                val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " " + app + " [" + decision + "]\n" +
                        sb.toString().replace('\n', ' ').take(600)
                val old = prefs.getString("post", "") ?: ""
                prefs.edit().putString("post", (line + "\n\n" + old).take(2600)).apply()
            } catch (_: Throwable) { }
        }, 2500)
    }

    /** Chamado pelo app: simula um aceite (print + historico) para conferir que tudo funciona. */
    fun simulateAccept() {
        val s = CardPrefs.load(this)
        val o = CardPrefs.sample(0, s).copy(app = "Simulação")
        takeShot { b -> saveShot(b, o, "Simulação") }
    }

    /** Chamado pelo app: tira e salva um print agora, so para conferir que o salvamento funciona. */
    fun saveTest() {
        takeShot { b ->
            if (b != null) saveShot(b, null, "Teste")
            else Toast.makeText(this, "Não consegui capturar a tela (erro $shotErr)", Toast.LENGTH_LONG).show()
        }
    }

    private fun looksLikeOffer(text: String): Boolean =
        (text.contains("km") && text.contains("min")) || (text.contains("R$") && text.contains("km"))

    /** No maximo 1 print a cada 20 s e 30 por dia, para nao encher a galeria. */
    private fun failureAllowed(): Boolean {
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        if (!prefs.getBoolean("saveFails", true)) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastFailShotAt < 20000) return false
        val capKey = "failcap_" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val n = prefs.getInt(capKey, 0)
        if (n >= 30) return false
        prefs.edit().putInt(capKey, n + 1).apply()
        lastFailShotAt = now
        Health.inc(this, "unread")
        return true
    }

    private fun writeFailure(bmp: Bitmap?, tag: String) {
        if (bmp == null) return
        Thread {
            try {
                val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "Falha_${ts}_$tag.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RotaCard/Falhas")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                }
            } catch (_: Throwable) { }
        }.start()
    }

    private fun speak(text: String) {
        try {
            val t = tts
            if (t == null) {
                ttsPending = text
                tts = android.speech.tts.TextToSpeech(this) { st ->
                    if (st == android.speech.tts.TextToSpeech.SUCCESS) {
                        tts?.language = Locale.forLanguageTag("pt-BR")
                        ttsReady = true
                        ttsPending?.let { p ->
                            tts?.speak(p, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "rc")
                        }
                        ttsPending = null
                    }
                }
            } else if (ttsReady) {
                t.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "rc")
            } else {
                ttsPending = text
            }
        } catch (_: Throwable) { }
    }

    private fun speakVerdict(o: Offer, s: CardSettings) {
        val v = CardPrefs.verdict(o, s)
        val word = when (v.code) { 2 -> "Aceitar"; 1 -> "Pensar"; else -> "Recusar" }
        speak(word + ". " + String.format(Locale.US, "%.2f", o.perKm).replace('.', ',') + " reais por quilômetro.")
    }

    /** Chamado pelo app: fala uma frase de exemplo. */
    fun speakTest() {
        speak("Aceitar. 2,80 reais por quilômetro.")
    }

    /** Notificacao fixa com o status e o atalho Pausar/Retomar. */
    fun refreshNotification() {
        try {
            val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (!prefs.getBoolean("notif", true)) { nm.cancel(1); return }
            nm.createNotificationChannel(
                android.app.NotificationChannel("rotacard_status", "Status do Rota Card", android.app.NotificationManager.IMPORTANCE_LOW)
            )
            val on = prefs.getBoolean("enabled", true)
            val st = Stats.load(this)
            val flags = android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            val toggle = android.app.PendingIntent.getBroadcast(this, 1, Intent(this, NotifyReceiver::class.java), flags)
            val open = android.app.PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), flags)
            val action = android.app.Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat),
                if (on) "Pausar" else "Retomar",
                toggle
            ).build()
            val n = android.app.Notification.Builder(this, "rotacard_status")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(if (on) "Rota Card lendo ofertas" else "Rota Card pausado")
                .setContentText(st.count.toString() + " ofertas hoje • " + st.accepted + " aceitas")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(action)
                .build()
            nm.notify(1, n)
            lastNotifAt = SystemClock.elapsedRealtime()
        } catch (_: Throwable) { }
    }

    private fun easyLogAdd(line: String) {
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        if (!prefs.getBoolean("debug", true)) return
        easyLog.add(0, SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " " + line)
        while (easyLog.size > 12) easyLog.removeAt(easyLog.size - 1)
        prefs.edit().putString("easylog", easyLog.joinToString("\n")).apply()
    }

    /** Chamado quando uma oferta do Easymob e lida: guarda a contagem e liga a leitura rapida do arrasto. */
    private fun onEasyOffer(o: Offer, t: String, prefs: android.content.SharedPreferences) {
        val now = SystemClock.uptimeMillis()
        val k = offerKey(o)
        if (k != easyKey) {
            easyKey = k
            easyBase = null
            swipeDone = false
            easyGoneDone = false
        }
        easyCount = Parser.countdown(t)
        easySeenAt = now
        easyActiveUntil = now + 3000
        if (!fastRunning && prefs.getBoolean("easySwipe", true)) {
            fastRunning = true
            handler.post(fastRunnable)
        }
    }

    /**
     * Enquanto a oferta do Easymob esta na tela, olha varias vezes por segundo onde ficam os elementos
     * da parte de baixo. Se algum se desloca bastante para a direita, e o arrasto de aceitar.
     */
    private fun fastTick() {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        var root: AccessibilityNodeInfo? = null
        for (win in windows) {
            val r = win.root ?: continue
            if (r.packageName?.toString()?.contains("easymob") == true) { root = r; break }
        }
        if (root == null) return
        val xs = ArrayList<Int>()
        val descr = ArrayList<String>()
        val rect = Rect()
        fun walk(n: AccessibilityNodeInfo) {
            n.getBoundsInScreen(rect)
            if (rect.centerY() >= h * 0.55 && rect.width() > 0 && rect.width() <= w * 0.45 && rect.height() > 0) {
                xs.add(rect.centerX())
                val cls = n.className?.toString()?.substringAfterLast('.') ?: "?"
                val tx = (n.text ?: n.contentDescription)?.toString()?.take(12) ?: ""
                descr.add("$cls[$tx]x=${rect.centerX()}")
            } else {
                xs.add(Int.MIN_VALUE)
                descr.add("")
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c)
            }
        }
        walk(root)
        if (xs.size < 8) return

        val base = easyBase
        if (base == null || base.size != xs.size) {
            easyBase = xs.toIntArray()
            easyLogAdd("ESTRUTURA: " + descr.filter { it.isNotEmpty() }.joinToString(" ").take(300))
            return
        }
        var maxDx = 0
        var minDx = 0
        var iMax = -1
        for (i in xs.indices) {
            if (xs[i] == Int.MIN_VALUE || base[i] == Int.MIN_VALUE) continue
            val dx = xs[i] - base[i]
            if (dx > maxDx) { maxDx = dx; iMax = i }
            if (dx < minDx) minDx = dx
        }
        val limit = (w * 0.18f).toInt()
        val now = SystemClock.uptimeMillis()
        if (!swipeDone && maxDx >= limit && minDx > -(w * 0.10f).toInt()) {
            swipeDone = true
            easyLogAdd("ARRASTO PARA A DIREITA: dx=$maxDx em " + (if (iMax >= 0) descr[iMax] else "?"))
            val off = lastShown?.takeIf { it.app == "Easymob" && now - lastShownAt < 5000 }
            onAccepted(off, "Easymob")
        } else if (minDx <= -limit && maxDx < (w * 0.10f).toInt()) {
            if (now - lastLeftLog > 3000) {
                lastLeftLog = now
                easyLogAdd("movimento para a esquerda: dx=$minDx")
            }
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
        val s = CardPrefs.load(this, o.app)
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
