package com.rotacard.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Servico de acessibilidade: le a oferta na tela dos apps de corrida, mostra o cartao e
 * registra as corridas aceitas. As regras de decisao ficam em [AcceptRules], o cartao em
 * [CardOverlay], os prints em [ShotStore]/[ShotSaver], a voz em [Voice] e a notificacao
 * em [StatusNotifier].
 */
class CardService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CardService? = null

        /** Intervalo da varredura quando um app de corrida esta aberto e quando nao esta. */
        private const val LOOP_ACTIVE_MS = 800L
        private const val LOOP_IDLE_MS = 1500L
        /** Prints mais velhos que isso nao servem mais para o aceite. */
        private const val SHOT_MAX_AGE_MS = 120_000L
    }

    private lateinit var wm: WindowManager
    private lateinit var overlay: CardOverlay
    private lateinit var voice: Voice
    private val handler = Handler(Looper.getMainLooper())
    private var scanPending = false
    private var hidePending = false
    private var running = false
    private var targetsOnScreen = false

    // teste do cartao
    private var testUntil = 0L
    private var testIdx = 0

    // contagem e vibracao: so conta a oferta depois de ve-la 2 vezes seguidas com os mesmos numeros
    private var lastKey = ""
    private var lastKeyAt = 0L
    private var candKey = ""
    private var candCount = 0

    // prints: so ficam guardados os que com certeza mostram a oferta (ver ShotStore)
    private val shots = ShotStore<Bitmap>(3)
    private var shotBusy = false
    private var lastShotReqAt = 0L
    private var shotKey = ""
    private var shotKeyCount = 0
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
    private var easyActiveUntil = 0L
    private var easyBase: IntArray? = null
    private var swipeDone = false
    private var lastRefuseAt = 0L
    private var fastRunning = false
    private val easyLog = mutableListOf<String>()
    private var curFirstAt = 0L
    private var goneHandled = false
    private var lastLeftLog = 0L

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
    private val hideRunnable = Runnable { hidePending = false; overlay.removeCard() }
    private val endTest = Runnable {
        if (SystemClock.uptimeMillis() >= testUntil - 50) overlay.removeCard()
    }
    private val loopRunnable = object : Runnable {
        override fun run() {
            safeScan()
            if (running) handler.postDelayed(this, if (targetsOnScreen) LOOP_ACTIVE_MS else LOOP_IDLE_MS)
        }
    }

    private fun prefs(): SharedPreferences = getSharedPreferences("cfg", MODE_PRIVATE)
    private fun debugOn(p: SharedPreferences = prefs()) = p.getBoolean("debug", false)

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlay = CardOverlay(this, wm)
        voice = Voice(this)
        running = true
        instance = this
        refreshNotification()
        Thread { try { ShotSaver.cleanupFailures(this) } catch (_: Throwable) { } }.start()
        handler.postDelayed(loopRunnable, LOOP_ACTIVE_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !License.canRun(this)) return
        val type = event.eventType
        val pkg = event.packageName?.toString()
        val target = Apps.isTarget(pkg)
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED && target) {
            try { handleClick(event) } catch (_: Exception) { }
        }
        // mudancas de conteudo de outros apps nao interessam; trocas de janela sim (para esconder o cartao)
        val structural = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (!target && !structural) return
        if (scanPending) return
        scanPending = true
        handler.postDelayed(scanRunnable, 200)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        stopAll()
        StatusNotifier.cancel(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopAll()
        if (::voice.isInitialized) voice.shutdown()
        super.onDestroy()
    }

    private fun stopAll() {
        running = false
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
        if (::overlay.isInitialized) overlay.removeAll()
        shots.clear()
    }

    /** Chamado pelo app: mostra um cartao de exemplo de verdade por 6 s (a cada toque troca a cor). */
    fun showTest() {
        if (!::overlay.isInitialized) return
        val s = CardPrefs.load(this)
        val level = testIdx % 3
        testIdx++
        testUntil = SystemClock.uptimeMillis() + 6000
        val o = CardPrefs.sample(level, s).copy(app = "Teste")
        overlay.showCard(o, s, if (prefs().getBoolean("compareAvg", true)) o.perKm / 1.1 else null)
        if (level == 0 && s.vibrate) Haptics.pulse(this)
        handler.removeCallbacks(endTest)
        handler.postDelayed(endTest, 6000)
    }

    /** Nunca deixa um erro interno derrubar o servico: guarda o erro para ver no Diagnostico. */
    private fun safeScan() {
        try {
            scan()
        } catch (t: Throwable) {
            try {
                val loc = t.stackTrace.firstOrNull()?.toString() ?: ""
                prefs().edit()
                    .putString("lasterr", t.javaClass.simpleName + ": " + (t.message ?: "") + "\n" + loc)
                    .apply()
            } catch (_: Throwable) { }
        }
    }

    /** Le o texto das janelas do app de corrida e devolve a oferta, com o app vindo do pacote. */
    private fun parseWindow(root: AccessibilityNodeInfo, label: String, sb: StringBuilder): Pair<Offer?, Int> {
        val n = collect(root, sb)
        val o = Parser.parse(sb.toString())?.let { if (it.app != label) it.copy(app = label) else it }
        return o to n
    }

    private fun scan() {
        if (!::overlay.isInitialized) return
        if (!License.canRun(this)) { overlay.removeAll(); return }
        License.checkAsync(this, false) { }
        Health.lastScan = SystemClock.elapsedRealtime()
        if (SystemClock.elapsedRealtime() - StatusNotifier.lastAt > 30000) refreshNotification()
        val nowMs = SystemClock.uptimeMillis()
        shots.prune(nowMs, SHOT_MAX_AGE_MS)
        if (nowMs < testUntil) return
        val prefs = prefs()
        if (!prefs.getBoolean("enabled", true)) {
            // so a leitura de ofertas esta pausada: o cartao do semaforo continua
            overlay.removeCard(); overlay.removeDbg()
            ocrOffer = null
            return
        }
        val debug = debugOn(prefs)
        val dm = resources.displayMetrics
        val r = Rect()
        var wins = 0
        var targets = 0
        var texts = 0
        var err = ""
        var offer: Offer? = null
        var parsedText = ""
        var failText = ""
        var big99 = false
        var cardLike = false
        var cardLabel: String? = null
        val pkgs = LinkedHashSet<String>()
        try {
            for (w in windows) {
                wins++
                val root = w.root ?: continue
                val pk = root.packageName?.toString() ?: "?"
                if (pk == packageName) continue
                pkgs.add(pk)
                // so le os apps de corrida: evita falso positivo (ex.: "5 min (2 km)" numa conversa) e gasta menos bateria
                val label = Apps.labelFor(pk) ?: continue
                targets++
                val one = StringBuilder()
                val (o, n) = parseWindow(root, label, one)
                texts += n
                val t = one.toString()
                if (offer == null) {
                    if (o != null) {
                        offer = o; parsedText = t
                        if (label == Apps.EASYMOB && o.app == Apps.EASYMOB) onEasyOffer(o, t, prefs)
                    } else if (Apps.hasTextOffer(label) && t.contains("R$") && t.contains("km")) failText = t
                }
                if (Apps.hasTextOffer(label) && n < 6) {
                    w.getBoundsInScreen(r)
                    if (r.top >= dm.heightPixels * 0.30 && r.height() >= dm.heightPixels * 0.25 &&
                        r.height() <= dm.heightPixels * 0.65) { cardLike = true; cardLabel = label }
                }
                if (label == Apps.NOVENTA_E_NOVE) {
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
                    val label = Apps.labelFor(rt.packageName?.toString())
                    pkgs.add("ativo:" + rt.packageName)
                    if (label != null) {
                        targets++
                        val one = StringBuilder()
                        val (o, n) = parseWindow(rt, label, one)
                        texts += n
                        if (o != null) { offer = o; parsedText = one.toString() }
                    }
                }
            } catch (e: Exception) {
                err += " " + e.javaClass.simpleName
            }
        }
        targetsOnScreen = targets > 0

        // 99: o app nao expoe o texto da oferta, entao le por imagem (OCR)
        val needOcr = offer == null && (big99 || cardLike) && prefs.getBoolean("ocr99", true)
        val shown = offer ?: (if (nowMs - ocrAt < 3000) ocrOffer else null)
        val saveOn = prefs.getBoolean("saveAccepted", true)
        // Uber/Easymob: tinha texto de oferta mas nao deu para ler -> guarda um print para poder corrigir
        if (offer == null && failText.isNotEmpty() && failureAllowed(prefs)) {
            takeShot { b -> ShotSaver.saveFailure(this, b, "Arvore") }
        }
        val tree = offer
        if (needOcr) captureOcr(debug, saveOn, if (big99) Apps.NOVENTA_E_NOVE else cardLabel)
        else if (saveOn && tree != null) captureOffer(tree)

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
                val refused = AcceptRules.refused(lastRefuseAt, curFirstAt, nowMs)
                val d = AcceptRules.decideGone(prefs.getBoolean("guessAccept", true), refused, duration, o.app, easyCount)
                if (d.accept) onAccepted(o, o.app, true)
                easyLogAdd(o.app + ": oferta sumiu apos " + (duration / 1000) + " s -> " + d.reason)
                dumpPost(o.app, d.reason)
            }
        }

        if (debug) {
            val e = prefs.edit()
            if (parsedText.isNotEmpty()) e.putString("last", parsedText.take(2500))
            if (offer == null && failText.isNotEmpty()) e.putString("fail", failText.take(2500))
            e.apply()
            val src = if (offer != null) "LEU OFERTA" else if (shown != null) "LEU OFERTA (OCR)" else "sem oferta"
            overlay.showDbg(
                "janelas=$wins apps=$targets textos=$texts $src $err 99grande=$big99 cartao=$cardLike ocr=$ocrState prints=${shots.size()}\n" +
                        pkgs.joinToString(" | ")
            )
        } else {
            overlay.removeDbg()
        }

        if (shown != null) {
            handler.removeCallbacks(hideRunnable); hidePending = false
            onOffer(shown)
            val s = CardPrefs.load(this, shown.app)
            val base = if (prefs.getBoolean("compareAvg", true)) Baseline.get(this, shown.app) else null
            overlay.showCard(shown, s, base)
        } else if (overlay.showing && !hidePending) {
            hidePending = true
            handler.postDelayed(hideRunnable, 2500)
        }
    }

    /** Conta a oferta no resumo do dia e vibra se for verde. Cada oferta e contada uma vez so. */
    private fun onOffer(o: Offer) {
        val key = AcceptRules.offerKey(o)
        val now = SystemClock.uptimeMillis()
        if (key == lastKey && now - lastKeyAt < 45000) { lastKeyAt = now; return }
        if (key == candKey) candCount++ else { candKey = key; candCount = 1 }
        if (candCount < 2) return
        lastKey = key; lastKeyAt = now; candKey = ""; candCount = 0
        val s = CardPrefs.load(this, o.app)
        Stats.record(this, o, s)
        Health.inc(this, "read_" + o.app)
        if (s.voice) voice.speakVerdict(o, s)
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

    /** A oferta com essa chave ainda esta no texto da tela agora? */
    private fun treeHasOffer(label: String, key: String): Boolean {
        try {
            for (w in windows) {
                val root = w.root ?: continue
                if (Apps.labelFor(root.packageName?.toString()) != label) continue
                val o = parseWindow(root, label, StringBuilder()).first ?: continue
                if (AcceptRules.offerKey(o) == key) return true
            }
        } catch (_: Throwable) { }
        return false
    }

    /**
     * Uber/Easymob: tira o print assim que a oferta aparece (sem esperar o intervalo normal) e
     * outro um pouco depois (caso o primeiro pegue a animacao de entrada). O print so e guardado se a
     * oferta continuava na tela quando a foto chegou; assim nunca fica guardada uma tela sem a oferta.
     */
    private fun captureOffer(o: Offer) {
        if (Build.VERSION.SDK_INT < 30 || shotBusy) return
        val key = AcceptRules.offerKey(o)
        val now = SystemClock.uptimeMillis()
        if (key != shotKey) { shotKey = key; shotKeyCount = 0 }
        if (shotKeyCount >= 2) return
        val minGap = if (shotKeyCount == 0) 350L else 1200L // o Android recusa prints com menos de ~333 ms
        if (now - lastShotReqAt < minGap) return
        lastShotReqAt = now
        shotKeyCount++
        shotBusy = true
        takeShot { bmp ->
            shotBusy = false
            if (bmp == null) { shotKeyCount--; return@takeShot }
            val latency = SystemClock.uptimeMillis() - now
            if (latency <= 250 || treeHasOffer(o.app, key)) shots.put(key, o.app, bmp, now)
            else shotKeyCount--
        }
    }

    /**
     * 99: tira o print e le a oferta por OCR. O print so e guardado se o OCR leu uma oferta nele —
     * antes ele era guardado antes do resultado do OCR e prints sem a oferta substituiam o bom.
     */
    private fun captureOcr(debug: Boolean, saveOn: Boolean, label: String?) {
        if (Build.VERSION.SDK_INT < 30) { ocrState = "android<11"; return }
        if (shotBusy) return
        val now = SystemClock.uptimeMillis()
        if (now - lastShotReqAt < 1000) return
        lastShotReqAt = now
        shotBusy = true
        takeShot { bmp ->
            if (bmp == null) {
                shotBusy = false
                ocrState = "shot-erro$shotErr"
                return@takeShot
            }
            try {
                val top = (bmp.height * 0.30).toInt()
                val crop = Bitmap.createBitmap(bmp, 0, top, bmp.width, bmp.height - top)
                recognizer.process(InputImage.fromBitmap(crop, 0))
                    .addOnSuccessListener { vt ->
                        shotBusy = false
                        onOcrText(vt.text, debug, label)
                        val got = ocrOffer
                        if (got != null) {
                            Health.inc(this, "ocr_ok")
                            if (saveOn) shots.put(AcceptRules.offerKey(got), got.app, bmp, now)
                        } else if (looksLikeOffer(vt.text)) {
                            Health.inc(this, "ocr_fail")
                            if (failureAllowed(prefs())) ShotSaver.saveFailure(this, bmp, "OCR")
                        }
                    }
                    .addOnFailureListener { shotBusy = false; ocrState = "falhou" }
            } catch (e: Throwable) {
                shotBusy = false
                ocrState = "erro-" + e.javaClass.simpleName
            }
        }
    }

    /** label = app dono da janela lida (o texto do OCR nem sempre diz qual app e). */
    private fun onOcrText(text: String, debug: Boolean, label: String?) {
        val p = prefs()
        if (debug && text.contains("R$")) p.edit().putString("ocr", text.take(2500)).apply()
        val parsed = Parser.parse(text, ocr = true)?.let { if (label != null && it.app != label) it.copy(app = label) else it }
        ocrOffer = parsed
        if (parsed == null && debug && (text.contains("km") || text.contains("min"))) {
            p.edit().putString("ocrfail", text.take(2500)).apply()
        }
        ocrAt = SystemClock.uptimeMillis()
        ocrState = if (parsed != null) "ok" else "sem-oferta"
    }

    /** Toque na tela dos apps de corrida: "Aceitar"/"Selecionar" registra a corrida; "Recusar" marca recusa. */
    private fun handleClick(e: AccessibilityEvent) {
        val prefs = prefs()
        if (!prefs.getBoolean("enabled", true)) return
        val label = Apps.labelFor(e.packageName?.toString()) ?: return
        val now = SystemClock.uptimeMillis()
        val sb = StringBuilder()
        e.text?.forEach { sb.append(it).append(' ') }
        e.contentDescription?.let { sb.append(it).append(' ') }
        val r = Rect()
        val src = e.source
        var box: Box? = null
        if (src != null) {
            src.getBoundsInScreen(r)
            box = Box(r.left, r.top, r.width(), r.height())
            collect(src, sb)
        }
        val recent = lastShown?.takeIf { now - lastShownAt < 4000 }
        val dm = resources.displayMetrics
        val res = AcceptRules.classifyClick(label, sb.toString(), box, dm.widthPixels, dm.heightPixels, recent?.app)
        if (res.refuse) lastRefuseAt = now
        if (debugOn(prefs)) {
            val cls = e.className?.toString()?.substringAfterLast('.') ?: "?"
            val txt = sb.toString().replace('\n', ' ').take(50)
            val tag = if (res.accept) (if (res.heuristic) "ACEITE (heuristica)" else "ACEITE") else if (res.refuse) "recusa" else "-"
            clickLog.add(0, "$label | $cls | $txt | ${r.width()}x${r.height()} top=${r.top} | $tag")
            while (clickLog.size > 14) clickLog.removeAt(clickLog.size - 1)
            prefs.edit().putString("clicks", clickLog.joinToString("\n")).apply()
        }
        if (res.accept) onAccepted(recent, label)
    }

    private fun onAccepted(o: Offer?, label: String, guess: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        val key = if (o != null) AcceptRules.offerKey(o) else "?$label"
        if (key == lastAcceptKey && now - lastAcceptAt < 10000) return
        lastAcceptKey = key
        lastAcceptAt = now
        if (o != null) {
            Stats.recordAccepted(this, o)
            // a oferta ja foi registrada pelo toque: nao registrar de novo como "provavel" quando ela sumir
            if (lastShown?.let { AcceptRules.offerKey(it) } == key) goneHandled = true
        }
        val saveOn = prefs().getBoolean("saveAccepted", true)
        val done: (String) -> Unit = { msg -> handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
        if (!saveOn) {
            ShotSaver.saveAccepted(this, null, o, label, guess, done, wantedShot = false)
            return
        }
        // 1) o print guardado enquanto a oferta estava na tela
        val shot = if (o != null) shots.find(key, o.app, curFirstAt - 500, now, SHOT_MAX_AGE_MS) else null
        when {
            shot != null -> ShotSaver.saveAccepted(this, shot, o, label, guess, done)
            // 2) toque agora com a oferta ainda na tela: tira o print ja
            o != null && !guess && now - lastShownAt < 1200 ->
                takeShot { b -> ShotSaver.saveAccepted(this, b, o, label, guess, done) }
            // 3) sem dados da oferta: o print e o unico registro, entao tira mesmo assim
            o == null -> takeShot { b ->
                if (b != null) ShotSaver.saveAccepted(this, b, null, label, guess, done)
                else handler.postDelayed({ takeShot { b2 -> ShotSaver.saveAccepted(this, b2, null, label, guess, done) } }, 450)
            }
            // 4) a oferta ja saiu da tela e nao ha print bom: salva a corrida sem print (um print agora mostraria outra tela)
            else -> ShotSaver.saveAccepted(this, null, o, label, guess, done)
        }
    }

    /** Guarda o que apareceu na tela ~2,5 s depois da oferta sumir (para aprender como cada app mostra o aceite). */
    private fun dumpPost(app: String, decision: String) {
        handler.postDelayed({
            try {
                val prefs = prefs()
                if (!debugOn(prefs)) return@postDelayed
                val part = Apps.packagePart(app)
                val sb = StringBuilder()
                for (w in windows) {
                    val r = w.root ?: continue
                    if (r.packageName?.toString()?.contains(part) == true) collect(r, sb)
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
        takeShot { b -> ShotSaver.saveAccepted(this, b, o, "Simulação", false, { msg -> handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }) }
    }

    /** Chamado pelo app: tira e salva um print agora, so para conferir que o salvamento funciona. */
    fun saveTest() {
        takeShot { b ->
            if (b != null) ShotSaver.saveAccepted(this, b, null, "Teste", false, { msg -> handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } })
            else Toast.makeText(this, "Não consegui capturar a tela (erro $shotErr)", Toast.LENGTH_LONG).show()
        }
    }

    private fun looksLikeOffer(text: String): Boolean =
        (text.contains("km") && text.contains("min")) || (text.contains("R$") && text.contains("km"))

    /** No maximo 1 print a cada 20 s e 30 por dia. */
    private fun failureAllowed(prefs: SharedPreferences): Boolean {
        if (!prefs.getBoolean("saveFails", true)) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastFailShotAt < 20000) return false
        val capKey = "failcap_" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val n = prefs.getInt(capKey, 0)
        if (n >= 30) return false
        val e = prefs.edit()
        // limpa os contadores dos dias anteriores
        for (k in prefs.all.keys) if (k.startsWith("failcap_") && k != capKey) e.remove(k)
        e.putInt(capKey, n + 1).apply()
        lastFailShotAt = now
        Health.inc(this, "unread")
        return true
    }

    /** Chamado pelo modo semaforos: mostra a previsao do sinal a frente por cima de qualquer app. */
    fun showLight(text: String, color: Int) {
        if (!::overlay.isInitialized || !running) return
        val atTop = CardPrefs.load(this).atBottom // o cartao das ofertas fica do outro lado
        overlay.showLight(text, color, atTop)
    }

    fun hideLight() {
        if (::overlay.isInitialized) overlay.removeLight()
    }

    /** Chamado pelo app: fala uma frase de exemplo. */
    fun speakTest() {
        if (::voice.isInitialized) voice.speak("Aceitar. 2,80 reais por quilômetro.")
    }

    /** Notificacao fixa com o status e o atalho Pausar/Retomar. */
    fun refreshNotification() = StatusNotifier.refresh(this)

    private fun easyLogAdd(line: String) {
        val prefs = prefs()
        if (!debugOn(prefs)) return
        easyLog.add(0, SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " " + line)
        while (easyLog.size > 12) easyLog.removeAt(easyLog.size - 1)
        prefs.edit().putString("easylog", easyLog.joinToString("\n")).apply()
    }

    /** Chamado quando uma oferta do Easymob e lida: guarda a contagem e liga a leitura rapida do arrasto. */
    private fun onEasyOffer(o: Offer, t: String, prefs: SharedPreferences) {
        val now = SystemClock.uptimeMillis()
        val k = AcceptRules.offerKey(o)
        if (k != easyKey) {
            easyKey = k
            easyBase = null
            swipeDone = false
        }
        easyCount = Parser.countdown(t)
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
            if (Apps.labelFor(r.packageName?.toString()) == Apps.EASYMOB) { root = r; break }
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
        val (swipe, iMax) = AcceptRules.detectSwipe(base, xs.toIntArray(), w)
        val now = SystemClock.uptimeMillis()
        if (swipe == Swipe.RIGHT && !swipeDone) {
            swipeDone = true
            easyLogAdd("ARRASTO PARA A DIREITA em " + (if (iMax >= 0) descr[iMax] else "?"))
            val off = lastShown?.takeIf { it.app == Apps.EASYMOB && now - lastShownAt < 5000 }
            onAccepted(off, Apps.EASYMOB)
        } else if (swipe == Swipe.LEFT && now - lastLeftLog > 3000) {
            lastLeftLog = now
            easyLogAdd("movimento para a esquerda")
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
}
