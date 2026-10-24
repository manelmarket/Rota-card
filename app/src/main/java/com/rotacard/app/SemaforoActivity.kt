package com.rotacard.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Modo semaforos: marcar verde/amarelo/vermelho, ver a previsao do sinal a frente, o mapa com os
 * semaforos cadastrados e abrir a navegacao no Waze ou no Google Maps com o modo ligado.
 */
class SemaforoActivity : Activity() {

    private val BG = Color.parseColor("#0A101E")
    private val SURFACE = Color.parseColor("#111A2E")
    private val STROKE = Color.parseColor("#1D2A47")
    private val TEXT = Color.parseColor("#E8EEF9")
    private val MUTED = Color.parseColor("#8190AD")
    private val CYAN = Color.parseColor("#2DD4F0")
    private val GREEN = Color.parseColor("#22C55E")
    private val AMBER = Color.parseColor("#F59E0B")
    private val RED = Color.parseColor("#EF4444")

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusTv: TextView
    private lateinit var toggleBtn: TextView
    private lateinit var predName: TextView
    private lateinit var predText: TextView
    private lateinit var predDist: TextView
    private lateinit var infoTv: TextView
    private lateinit var map: TileMapView
    private lateinit var listBox: LinearLayout
    private lateinit var destF: EditText
    private lateinit var a11yHint: TextView
    private var selectedId = 0L
    private var listVersion = -1
    private var pendingAfterPermission: (() -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            val st = DriveService.state
            if (DriveService.instance == null || st == null) renderStopped()
            refreshMarkers()
            refreshInfo()
            if (LightStore.version != listVersion) renderList()
            handler.postDelayed(this, 1000)
        }
    }

    // ------------------------------------------------------------ helpers de UI
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun bg(color: Int, r: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun tv(t: String, size: Float = 14f, color: Int = TEXT, medium: Boolean = false) =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    private fun lp(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun pressable(v: View) {
        v.setOnTouchListener { view, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
            }
            false
        }
    }

    private fun button(label: String, primary: Boolean, color: Int = TEXT, onClick: () -> Unit) =
        TextView(this).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(16), dp(13), dp(16), dp(13))
            if (primary) {
                setTextColor(Color.parseColor("#04121A"))
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(CYAN, Color.parseColor("#3B82F6")))
                    .apply { cornerRadius = dp(28).toFloat() }
            } else {
                setTextColor(color)
                background = bg(Color.parseColor("#17213A"), 28, STROKE)
            }
            isClickable = true
            setOnClickListener { onClick() }
            pressable(this)
        }

    private fun card(title: String?, build: LinearLayout.() -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = bg(SURFACE, 24, STROKE)
        setPadding(dp(18), dp(16), dp(18), dp(18))
        if (title != null) addView(tv(title, 16f, TEXT, true), lp(bottom = 10))
        build()
    }

    private fun switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        Switch(this).apply {
            text = label
            setTextColor(TEXT)
            textSize = 14f
            isChecked = checked
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(CYAN, Color.parseColor("#6B7794")))
            trackTintList = ColorStateList(states, intArrayOf(Color.parseColor("#552DD4F0"), Color.parseColor("#2A3550")))
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }

    private fun markButton(label: String, sub: String, color: Int, phase: Phase) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = bg(color, 22)
        setPadding(dp(6), dp(16), dp(6), dp(16))
        addView(tv(label, 17f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
        addView(tv(sub, 11f, Color.argb(220, 255, 255, 255)).apply { gravity = Gravity.CENTER })
        isClickable = true
        setOnClickListener {
            val at = System.currentTimeMillis()
            if (!DriveService.hasPermission(this@SemaforoActivity)) {
                askPermission { Toast.makeText(this@SemaforoActivity, "Pronto. Agora marque no instante em que a cor mudar.", Toast.LENGTH_LONG).show() }
            } else {
                DriveService.mark(this@SemaforoActivity, phase, at)
            }
        }
        pressable(this)
    }

    // ------------------------------------------------------------ tela
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)

        val back = TextView(this).apply {
            text = "‹"; textSize = 28f; setTextColor(TEXT); gravity = Gravity.CENTER
            background = bg(SURFACE, 14, STROKE)
            isClickable = true
            setOnClickListener { finish() }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back, LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(12) })
            addView(tv("Semáforos", 28f, TEXT).apply { typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val safety = tv("Marque só quando estiver parado e for seguro. A previsão é uma estimativa: obedeça sempre o semáforo.", 12f, AMBER)

        // ---- status e liga/desliga
        statusTv = tv("", 13f, MUTED)
        toggleBtn = button("Ligar modo semáforos", true) {
            if (DriveService.instance != null) {
                DriveService.stop(this)
                handler.postDelayed({ renderStopped() }, 300)
            } else {
                startDrive()
            }
        }
        a11yHint = tv("Para a previsão aparecer por cima do Waze, do Maps e dos apps de corrida, ligue a acessibilidade do Rota Card.", 12f, MUTED).apply {
            isClickable = true
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val statusCard = card(null) {
            addView(statusTv, lp(bottom = 10))
            addView(toggleBtn, lp())
            addView(a11yHint, lp(top = 8))
        }

        // ---- previsao do sinal a frente
        predName = tv("", 13f, MUTED, true)
        predText = tv("", 24f, TEXT).apply { typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD) }
        predDist = tv("", 12f, MUTED)
        val predCard = card("Semáforo à frente") {
            addView(predName)
            addView(predText, lp(top = 2))
            addView(predDist, lp(top = 2))
        }

        // ---- marcacao
        val markRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(markButton("VERDE", "abriu", Color.parseColor("#1B8E3E"), Phase.GREEN), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(markButton("AMARELO", "ficou", Color.parseColor("#C98F00"), Phase.YELLOW),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
            addView(markButton("VERMELHO", "fechou", Color.parseColor("#C62828"), Phase.RED),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        }
        val markCard = card("Marcar a cor") {
            addView(markRow, lp(bottom = 10))
            addView(tv("Toque no instante em que a cor muda. Para cadastrar um sinal, fique parado nele e marque ${Semaforos.RECOMMENDED_CYCLES} ciclos seguidos: verde, amarelo, vermelho, verde… (uns 5 minutos). Depois, sempre que passar e o sinal abrir, toque em VERDE: isso acerta o relógio do sinal e deixa a previsão mais precisa.", 12f, MUTED), lp())
            addView(tv("Também dá para marcar pelos botões da notificação do modo semáforos.", 12f, MUTED), lp(top = 6))
        }

        // ---- mapa
        map = TileMapView(this)
        map.onMarkerTap = { id -> selectedId = id; refreshInfo() }
        infoTv = tv("", 13f, TEXT)
        val zoomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("−", false) { map.zoom = map.zoom - 1 }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("+", false) { map.zoom = map.zoom + 1 },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
            addView(button("Centralizar", false) { map.recenter() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f).apply { leftMargin = dp(8) })
        }
        val mapFrame = FrameLayout(this).apply {
            background = bg(Color.parseColor("#0B1220"), 18, STROKE)
            clipToOutline = true
            addView(map, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val mapCard = card("Mapa") {
            addView(mapFrame, lp(bottom = 10))
            addView(zoomRow, lp(bottom = 10))
            addView(tv("Bolinhas: semáforos cadastrados, na cor prevista agora (cinza = sem previsão). O tracinho mostra de onde vem o carro. Toque numa bolinha para ver os tempos.", 12f, MUTED), lp(bottom = 8))
            addView(infoTv, lp())
        }

        // ---- navegacao
        destF = EditText(this).apply {
            hint = "Para onde? (endereço ou lugar)"
            setHintTextColor(MUTED)
            setTextColor(TEXT)
            textSize = 16f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
            imeOptions = EditorInfo.IME_ACTION_GO
            background = bg(Color.parseColor("#0C1322"), 14, STROKE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setText(prefs.getString("navDest", "") ?: "")
        }
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Waze", true) { navigate(false) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("Google Maps", true) { navigate(true) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        }
        val navCard = card("Navegar com os semáforos") {
            addView(destF, lp(bottom = 10))
            addView(navRow, lp(bottom = 8))
            addView(tv("A rota fica com o Waze ou o Google Maps. O Rota Card liga o modo semáforos e mostra a previsão do próximo sinal por cima do navegador, e fala ao chegar.", 12f, MUTED), lp())
        }

        // ---- ajustes
        val optCard = card("Ajustes") {
            addView(switchRow("Falar a previsão ao chegar no sinal", prefs.getBoolean("semVoice", true)) {
                prefs.edit().putBoolean("semVoice", it).apply()
            }, lp())
            addView(switchRow("Mostrar o cartão por cima dos outros apps", prefs.getBoolean("semOverlay", true)) {
                prefs.edit().putBoolean("semOverlay", it).apply()
            }, lp())
        }

        // ---- lista
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val listCard = card("Semáforos cadastrados") { addView(listBox, lp()) }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(36), dp(18), dp(28))
            addView(header, lp(bottom = 10))
            addView(safety, lp(bottom = 12))
            addView(statusCard, lp(bottom = 12))
            addView(predCard, lp(bottom = 12))
            addView(markCard, lp(bottom = 12))
            addView(mapCard, lp(bottom = 12))
            addView(navCard, lp(bottom = 12))
            addView(optCard, lp(bottom = 12))
            addView(listCard, lp())
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(content)
        })

        initialCenter()
        renderStopped()
        renderList()
    }

    override fun onResume() {
        super.onResume()
        DriveService.listener = { render(it) }
        DriveService.state?.let { render(it) }
        a11yHint.visibility = if (CardService.instance == null) View.VISIBLE else View.GONE
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        DriveService.listener = null
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        map.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------ permissao e servico

    private fun askPermission(then: () -> Unit) {
        pendingAfterPermission = then
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 31)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 31) return
        if (DriveService.hasPermission(this)) {
            initialCenter()
            pendingAfterPermission?.invoke()
        } else {
            Toast.makeText(this, "Sem a localização precisa o modo semáforos não funciona.", Toast.LENGTH_LONG).show()
        }
        pendingAfterPermission = null
    }

    private fun startDrive() {
        if (!DriveService.hasPermission(this)) { askPermission { startDrive() }; return }
        if (!DriveService.start(this)) return
        toggleBtn.text = "Desligar modo semáforos"
        statusTv.text = "Procurando o GPS…"
    }

    private fun initialCenter() {
        if (!DriveService.hasPermission(this)) {
            LightStore.load(this).lastOrNull()?.let { map.centerOn(it.lat, it.lon); map.follow = true }
            return
        }
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
            if (last != null) map.setMe(last.latitude, last.longitude, null)
            else LightStore.load(this).lastOrNull()?.let { map.centerOn(it.lat, it.lon); map.follow = true }
        } catch (_: SecurityException) { }
    }

    // ------------------------------------------------------------ navegacao

    private fun navigate(google: Boolean) {
        val q = destF.text.toString().trim()
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("navDest", q).apply()
        if (DriveService.instance == null) {
            if (DriveService.hasPermission(this)) startDrive() else { askPermission { startDrive(); navigate(google) }; return }
        }
        try {
            if (google) {
                val i = if (q.isEmpty()) Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))
                else Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(q)))
                i.setPackage("com.google.android.apps.maps")
                startActivity(i)
            } else {
                val url = if (q.isEmpty()) "https://waze.com/ul" else "https://waze.com/ul?q=" + Uri.encode(q) + "&navigate=yes"
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, if (google) "Google Maps não encontrado" else "Waze não encontrado", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------ atualizacao da tela

    private fun renderStopped() {
        if (DriveService.instance != null) return
        toggleBtn.text = "Ligar modo semáforos"
        statusTv.text = "Modo semáforos desligado."
        predName.text = ""
        predText.text = "—"
        predText.setTextColor(MUTED)
        predDist.text = "Ligue o modo para ver a previsão do sinal à frente."
    }

    private fun render(st: DriveState) {
        toggleBtn.text = "Desligar modo semáforos"
        statusTv.text = if (!st.hasFix) "Procurando o GPS… (fique em lugar aberto)" else {
            val dir = st.heading?.let { Semaforos.cardinal(it) }?.let { " • indo para o $it" } ?: ""
            "GPS ±${st.accuracy.toInt()} m • ${st.speedKmh.toInt()} km/h$dir" + (if (st.pending > 0) " • marcação aguardando GPS" else "")
        }
        if (st.hasFix) map.setMe(st.lat, st.lon, st.heading)
        val l = st.light
        if (l == null || st.timing == null) {
            predName.text = ""
            predText.text = if (st.hasFix) "Nenhum semáforo cadastrado à frente" else "—"
            predText.setTextColor(MUTED)
            predDist.text = ""
        } else {
            predName.text = l.name
            predText.text = Semaforos.describe(st.prediction, st.timing).replaceFirstChar { it.uppercase() }
            predText.setTextColor(
                when (DriveService.colorFor(st.prediction)) {
                    DriveService.GREEN -> GREEN; DriveService.AMBER -> AMBER; DriveService.RED -> RED; else -> MUTED
                }
            )
            predDist.text = (if (st.distance > 25) "a ${st.distance.toInt()} m" else "você está no sinal") +
                    (st.prediction?.let { p -> if (p.confidence == Confidence.HIGH || p.confidence == Confidence.MEDIUM) "  •  margem ±${((p.sigmaMs * 2 + 999) / 1000).toInt()} s" else "" } ?: "")
            if (selectedId == 0L) refreshInfo()
        }
    }

    private fun refreshMarkers() {
        val now = System.currentTimeMillis()
        map.markers = LightStore.load(this).map { l ->
            val p = Semaforos.predict(Semaforos.analyze(l.marks, now), now)
            val c = DriveService.colorFor(p)
            MapMarker(l.id, l.lat, l.lon, l.bearing, if (c == DriveService.GRAY) Color.parseColor("#78909C") else c)
        }
    }

    private fun lightInfo(l: TrafficLight): String {
        val now = System.currentTimeMillis()
        val t = Semaforos.analyze(l.marks, now)
        val p = Semaforos.predict(t, now)
        fun s(ms: Long) = "${(ms + 500) / 1000} s"
        val lines = ArrayList<String>()
        lines.add(l.name + (Semaforos.cardinal(l.bearing)?.let { "  •  para quem vai ao $it" } ?: ""))
        if (t.cycleMs == null) {
            lines.add("Ciclo ainda não medido: marque verde, vermelho e verde de novo.")
        } else {
            lines.add("Ciclo ${s(t.cycleMs)}" + (t.greenMs?.let { "  •  verde ${s(it)}" } ?: "") +
                    "  •  amarelo ${s(t.yellowMs)}" + (if (t.yellowEstimated) " (estimado)" else ""))
            val med = if (t.cycleSamples < Semaforos.RECOMMENDED_CYCLES)
                "ciclos medidos: ${t.cycleSamples} (o ideal é ${Semaforos.RECOMMENDED_CYCLES})" else "ciclos medidos: ${t.cycleSamples}"
            lines.add(med + "  •  conferências: ${t.hits} certas" + (if (t.misses > 0) ", ${t.misses} erradas" else ""))
            if (t.variable) lines.add("Os ciclos variam muito: parece semáforo inteligente ou com botão. Sem previsão.")
            if (t.otherTime) lines.add("Tempos medidos em outro horário do dia: o sinal pode estar em outro plano agora.")
        }
        lines.add("Agora: " + Semaforos.describe(p, t))
        lines.add("${l.marks.size} marcações")
        return lines.joinToString("\n")
    }

    private fun refreshInfo() {
        val id = if (selectedId != 0L) selectedId else DriveService.state?.light?.id ?: 0L
        val l = if (id != 0L) LightStore.get(this, id) else null
        infoTv.text = l?.let { lightInfo(it) } ?: "Toque num semáforo do mapa para ver os tempos."
    }

    private fun renderList() {
        listVersion = LightStore.version
        listBox.removeAllViews()
        val lights = LightStore.load(this).sortedByDescending { l -> l.marks.lastOrNull()?.at ?: 0L }
        if (lights.isEmpty()) {
            listBox.addView(tv("Nenhum ainda. Pare num semáforo, ligue o modo e marque as cores.", 13f, MUTED), lp())
            return
        }
        val now = System.currentTimeMillis()
        for (l in lights) {
            val t = Semaforos.analyze(l.marks, now)
            val sub = when {
                t.variable -> "variável"
                t.cycleMs == null -> "falta medir o ciclo"
                else -> "ciclo ${(t.cycleMs + 500) / 1000} s • ${t.cycleSamples} ciclos • ${t.hits} conferências"
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = bg(Color.parseColor("#0C1424"), 16, STROKE)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                addView(tv(l.name, 15f, TEXT, true))
                addView(tv(sub, 12f, MUTED))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    fun small(label: String, color: Int = TEXT, fn: () -> Unit) = button(label, false, color, fn).apply {
                        textSize = 12f; setPadding(dp(10), dp(8), dp(10), dp(8))
                    }
                    addView(small("Mapa") { selectedId = l.id; map.selectedId = l.id; map.centerOn(l.lat, l.lon); refreshInfo() })
                    addView(small("Renomear") { rename(l) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
                    addView(small("Medir de novo") { confirm("Apagar as marcações de ${l.name}?", "Use se a prefeitura mudou o tempo do sinal.") { LightStore.clearMarks(this@SemaforoActivity, l.id) } },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
                    addView(small("Apagar", RED) { confirm("Apagar ${l.name}?", "O semáforo e todas as marcações somem.") { LightStore.delete(this@SemaforoActivity, l.id); if (selectedId == l.id) selectedId = 0L } },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
                }, lp(top = 8))
            }
            listBox.addView(row, lp(bottom = 8))
        }
    }

    private fun rename(l: TrafficLight) {
        val input = EditText(this).apply { setText(l.name); setSelection(text.length) }
        AlertDialog.Builder(this)
            .setTitle("Nome do semáforo")
            .setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) LightStore.rename(this, l.id, n)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirm(title: String, msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("Sim") { _, _ -> ok() }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
