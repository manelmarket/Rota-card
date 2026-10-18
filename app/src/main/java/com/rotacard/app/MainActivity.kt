package com.rotacard.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.animation.ObjectAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.os.Handler
import android.os.Looper
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {

    // ---- paleta: tinta azul-marinho, ciano de sinal, e as cores do semaforo do cartao ----
    private val BG = Color.parseColor("#0A101E")
    private val SURFACE = Color.parseColor("#111A2E")
    private val STROKE = Color.parseColor("#1D2A47")
    private val TEXT = Color.parseColor("#E8EEF9")
    private val MUTED = Color.parseColor("#8190AD")
    private val CYAN = Color.parseColor("#2DD4F0")
    private val GREEN = Color.parseColor("#22C55E")
    private val AMBER = Color.parseColor("#F59E0B")
    private val RED = Color.parseColor("#EF4444")

    private val REPO = "manelmarket/Rota-card"

    private lateinit var prefs: SharedPreferences
    private lateinit var power: PowerView
    private lateinit var scale: ScaleView
    private lateinit var stateText: TextView
    private lateinit var hintText: TextView
    private lateinit var a11yText: TextView
    private lateinit var a11yDot: View
    private lateinit var licText: TextView

    // faixas por app, decisao, meta e turno
    private var selApp = ""
    private val appChips = ArrayList<TextView>()
    private lateinit var goalBar: GoalBarView
    private lateinit var goalText: TextView
    private lateinit var shiftText: TextView
    private lateinit var shiftBtn: TextView
    private lateinit var goalF: EditText
    private lateinit var minHourF: EditText
    private lateinit var maxPickupF: EditText
    private lateinit var costF: EditText
    private lateinit var greenF: EditText
    private lateinit var yellowF: EditText
    private lateinit var ratingF: EditText
    private lateinit var updStatus: TextView
    private lateinit var updBtn: TextView
    private var pendingUrl: String? = null
    private val animators = mutableListOf<ObjectAnimator>()
    private val handler = Handler(Looper.getMainLooper())

    // resumo do dia
    private lateinit var statCount: TextView
    private lateinit var statSub: TextView
    private lateinit var statBest: TextView
    private lateinit var lastPrice: TextView
    private lateinit var lastInfo: TextView
    private lateinit var lastMeta: TextView
    private lateinit var mAvg: TextView
    private lateinit var mHour: TextView
    private lateinit var mBest: TextView
    private lateinit var splitBar: SplitBarView
    private val tick = object : Runnable {
        override fun run() {
            refreshStats()
            handler.postDelayed(this, 3000)
        }
    }

    // menu lateral
    private lateinit var drawer: ScrollView
    private lateinit var scrim: View
    private lateinit var previewUi: CardUi
    private lateinit var segTop: TextView
    private lateinit var segBottom: TextView
    private lateinit var seekSize: SeekBar
    private lateinit var seekOpacity: SeekBar
    private lateinit var tvSize: TextView
    private lateinit var tvOpacity: TextView
    private lateinit var vibSwitch: Switch
    private val chipViews = ArrayList<TextView>()
    private var previewLevel = 0
    private var drawerOpen = false
    private var drawerW = 0

    // ------------------------------------------------------------------ helpers de UI
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
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun pressable(v: View) {
        v.setOnTouchListener { view, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            false
        }
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            if (primary) {
                setTextColor(Color.parseColor("#04121A"))
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(CYAN, Color.parseColor("#3B82F6"))
                ).apply { cornerRadius = dp(28).toFloat() }
            } else {
                setTextColor(TEXT)
                background = bg(Color.parseColor("#17213A"), 28, STROKE)
            }
            isClickable = true
            setOnClickListener { onClick() }
            pressable(this)
        }

    private fun input(value: String, hint: String, decimal: Boolean = true) = EditText(this).apply {
        setText(value)
        this.hint = hint
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        textSize = 15f
        gravity = Gravity.CENTER
        isSingleLine = true
        inputType = if (decimal) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        else InputType.TYPE_CLASS_TEXT
        background = bg(Color.parseColor("#0C1322"), 12, STROKE)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun row(dot: Int?, label: String, field: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (dot != null) {
                addView(
                    View(context).apply {
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dot) }
                    },
                    LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) }
                )
            }
            addView(tv(label, 14f, TEXT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(field, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
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

    private fun card(title: String, build: LinearLayout.() -> Unit): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(SURFACE, 24, STROKE)
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        c.addView(tv(title, 16f, TEXT, true), lp(bottom = 12))
        c.build()
        return c
    }

    private fun pulse(v: View) {
        val a = ObjectAnimator.ofFloat(v, "alpha", 1f, 0.25f).apply {
            duration = 900
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
        }
        a.start()
        animators.add(a)
    }

    // ------------------------------------------------------------------ estado
    private fun a11yOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return s.contains(packageName, ignoreCase = true)
    }

    private fun refreshA11y() {
        val on = a11yOn()
        a11yText.text = if (on) "Acessibilidade ativa" else "Acessibilidade desligada. Toque para ativar"
        a11yText.setTextColor(if (on) GREEN else AMBER)
        a11yDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) GREEN else AMBER)
        }
    }

    private fun sfx() = if (selApp.isEmpty()) "" else "_$selApp"

    private fun loadFields() {
        val sf = sfx()
        greenF.setText(prefs.getFloat("green" + sf, prefs.getFloat("green", 2.0f)).toString())
        yellowF.setText(prefs.getFloat("yellow" + sf, prefs.getFloat("yellow", 1.5f)).toString())
        updateScale(false)
    }

    private fun styleAppChips() {
        appChips.forEachIndexed { i, c ->
            val sel = if (i == 0) selApp.isEmpty() else c.text.toString() == selApp
            c.setTextColor(if (sel) Color.parseColor("#04121A") else TEXT)
            c.background = if (sel)
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(CYAN, Color.parseColor("#3B82F6")))
                    .apply { cornerRadius = dp(12).toFloat() }
            else bg(Color.parseColor("#0C1322"), 12, STROKE)
        }
    }

    private fun shiftSummary(start: Long, prefix: String): String {
        val rides = History.load(this).filter { it.id >= start && it.hasData }
        val gross = rides.sumOf { it.price }
        val cost = rides.sumOf { it.cost }
        val ms = System.currentTimeMillis() - start
        val hours = ms / 3600000.0
        val m = ms / 60000
        val perH = if (hours > 0.05) gross / hours else 0.0
        return prefix + (m / 60) + "h" + (m % 60).toString().padStart(2, '0') + " • " + rides.size +
                " corridas • " + Fmt.money(gross) + " • R$ " + Fmt.n0(perH) + "/h real" +
                (if (cost > 0.0) " • líquido " + Fmt.money(gross - cost) else "")
    }

    private fun toggleShift() {
        val start = prefs.getLong("shiftStart", 0L)
        if (start == 0L) {
            prefs.edit().putLong("shiftStart", System.currentTimeMillis()).apply()
        } else {
            val summary = shiftSummary(start, "Último turno: ")
            prefs.edit().putLong("shiftStart", 0L).putString("lastShift", summary).apply()
        }
        refreshGoalShift()
    }

    private fun refreshGoalShift() {
        if (!::goalBar.isInitialized) return
        val goal = prefs.getFloat("goal", 0f)
        val got = Stats.load(this).accSum
        if (goal > 0f) {
            val frac = (got / goal).toFloat()
            goalBar.set(frac)
            goalText.text = Fmt.money(got) + " de " + Fmt.money(goal.toDouble()) +
                    "  (" + (frac * 100).roundToInt() + "%)" + (if (got >= goal) "  •  meta batida!" else "")
        } else {
            goalBar.set(0f)
            goalText.text = "Defina uma meta para acompanhar o dia. Aceitas hoje: " + Fmt.money(got)
        }
        val start = prefs.getLong("shiftStart", 0L)
        if (start > 0L) {
            shiftText.text = shiftSummary(start, "Turno em andamento: ")
            shiftBtn.text = "Encerrar turno"
        } else {
            val last = prefs.getString("lastShift", "") ?: ""
            shiftText.text = if (last.isEmpty()) "Inicie o turno para ver o ganho por hora real, contando o tempo parado." else last
            shiftBtn.text = "Iniciar turno"
        }
    }

    private fun refreshLicense() {
        if (!License.enabled(this)) { licText.visibility = View.GONE; return }
        val d = License.daysLeft(this)
        val date = java.text.SimpleDateFormat("dd/MM/yyyy", Locale.US)
            .format(java.util.Date(License.expiresAt(this)))
        licText.visibility = View.VISIBLE
        licText.text = when {
            d <= 0 -> "Acesso vencido"
            d == 1 -> "Acesso: vence amanhã ($date)"
            else -> "Acesso: $d dias restantes (até $date)"
        }
        licText.setTextColor(if (d <= 0) RED else if (d <= 3) AMBER else MUTED)
    }

    private fun refreshPower() {
        val on = prefs.getBoolean("enabled", true)
        power.on = on
        stateText.text = if (on) "Lendo ofertas" else "Pausado"
        stateText.setTextColor(if (on) TEXT else MUTED)
        hintText.text = if (on) "Toque no botão para pausar" else "Toque no botão para voltar a ler"
    }

    private fun updateScale(animate: Boolean) {
        val g = greenF.text.toString().replace(',', '.').toFloatOrNull()
        val y = yellowF.text.toString().replace(',', '.').toFloatOrNull()
        if (g != null && y != null && g > 0f && y > 0f) scale.setRange(y, g, animate)
    }

    override fun onResume() {
        super.onResume()
        if (License.enabled(this)) {
            if (!License.canRun(this)) {
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
                return
            }
            License.checkAsync(this, false) { ok ->
                if (!ok) runOnUiThread {
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                }
            }
            if (::licText.isInitialized) refreshLicense()
        }
        if (::a11yText.isInitialized) refreshA11y()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        animators.forEach { it.cancel() }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ resumo do dia
    private fun fmt2(v: Double) = String.format(Locale.US, "%.2f", v).replace('.', ',')
    private fun fmt1(v: Double) = String.format(Locale.US, "%.1f", v).replace('.', ',')

    private fun accLine(st: DayStats) =
        if (st.accepted > 0) "\nAceitas hoje: ${st.accepted} • R$ " + fmt2(st.accSum) else ""

    private fun pl(n: Int, one: String, many: String) = "$n " + (if (n == 1) one else many)

    private fun openHistory() {
        startActivity(Intent(this, HistoryActivity::class.java))
    }

    private fun refreshLast() {
        val r = History.last(this)
        if (r == null) {
            lastPrice.text = "--"
            lastMeta.text = "Nenhuma corrida aceita ainda"
            lastInfo.text = "Quando você aceitar uma, ela aparece aqui."
            return
        }
        val fmtDay = java.text.SimpleDateFormat("yyyyMMdd", Locale.US)
        val isToday = fmtDay.format(java.util.Date(r.id)) == fmtDay.format(java.util.Date())
        val whenTxt = if (isToday) java.text.SimpleDateFormat("'hoje' HH:mm", Locale.US).format(java.util.Date(r.id))
        else java.text.SimpleDateFormat("dd/MM HH:mm", Locale.US).format(java.util.Date(r.id))
        if (!r.hasData) {
            lastPrice.text = "--"
            lastMeta.text = "Oferta não lida (print salvo)"
            lastInfo.text = r.app + " • " + whenTxt
            return
        }
        lastPrice.text = Fmt.money(r.price)
        val h = r.perHour
        lastMeta.text = "R$ " + Fmt.d2(r.perKm ?: 0.0) + "/km" +
                (if (h != null) "  •  " + (if (r.minEstimated) "~" else "") + "R$ " + Fmt.n0(h) + "/h" else "")
        lastInfo.text = Fmt.d1(r.totalKm) + " km • " + r.app + " • " + whenTxt
    }

    private fun refreshStats() {
        if (!::statCount.isInitialized) return
        refreshLast()
        refreshGoalShift()
        val st = Stats.load(this)
        statCount.text = st.count.toString()
        if (st.count == 0) {
            statSub.text = "nenhuma oferta lida hoje"
            mAvg.text = "--"; mHour.text = "--"; mBest.text = "--"
            statBest.text = "As ofertas aparecem aqui conforme o app as lê." + accLine(st)
            splitBar.set(0, 0, 0)
        } else {
            statSub.text = if (st.count == 1) "oferta vista hoje" else "ofertas vistas hoje"
            mAvg.text = "R$ " + fmt2(st.avgKm)
            mHour.text = st.avgHour?.let { "R$ " + it.roundToInt() } ?: "--"
            mBest.text = "R$ " + fmt2(st.bestPerKm)
            statBest.text = pl(st.green, "verde", "verdes") + " • " + pl(st.yellow, "amarela", "amarelas") +
                    " • " + pl(st.red, "vermelha", "vermelhas") + "\n" +
                    "Melhor: R$ " + fmt2(st.bestPrice) + " em " + fmt1(st.bestDist) + " km (" + st.bestApp + ")" +
                    accLine(st)
            splitBar.set(st.red, st.yellow, st.green)
        }
    }

    // ------------------------------------------------------------------ menu lateral
    private fun openDrawer() {
        drawerOpen = true
        updatePreview()
        scrim.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(220).start()
        drawer.animate().translationX(0f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun closeDrawer() {
        drawerOpen = false
        scrim.animate().alpha(0f).setDuration(200).withEndAction { scrim.visibility = View.GONE }.start()
        drawer.animate().translationX(-drawerW.toFloat()).setDuration(220).start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawerOpen) closeDrawer() else super.onBackPressed()
    }

    private fun pct(v: Float) = "${(v * 100).roundToInt()}%"

    private fun refreshSeg() {
        val bottom = prefs.getBoolean("atBottom", false)
        fun style(t: TextView, selected: Boolean) {
            if (selected) {
                t.setTextColor(Color.parseColor("#04121A"))
                t.background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(CYAN, Color.parseColor("#3B82F6"))
                ).apply { cornerRadius = dp(12).toFloat() }
            } else {
                t.setTextColor(TEXT)
                t.background = null
            }
        }
        style(segTop, !bottom)
        style(segBottom, bottom)
    }

    private fun updatePreview() {
        if (!::previewUi.isInitialized) return
        val st = CardPrefs.load(this)
        previewUi.bind(CardPrefs.sample(previewLevel, st), st)
        previewUi.root.alpha = st.opacity
        val cols = intArrayOf(GREEN, AMBER, RED)
        chipViews.forEachIndexed { i, c ->
            val col = cols[i]
            val sel = i == previewLevel
            c.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (sel) Color.argb(50, Color.red(col), Color.green(col), Color.blue(col)) else Color.TRANSPARENT)
                setStroke(dp(1), if (sel) col else STROKE)
            }
        }
    }

    private fun styledSeek(max: Int, progress: Int, onChange: (Int) -> Unit): SeekBar =
        SeekBar(this).apply {
            this.max = max
            this.progress = progress
            progressTintList = ColorStateList.valueOf(CYAN)
            thumbTintList = ColorStateList.valueOf(CYAN)
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#2A3550"))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) onChange(p)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

    private fun labelValueRow(label: String, value: TextView): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(tv(label, 14f, TEXT, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(value)
        }

    private fun resetCardPrefs() {
        prefs.edit().remove("textScale").remove("opacity").remove("atBottom").remove("vibrate").apply()
        seekSize.progress = 4
        seekOpacity.progress = 12
        tvSize.text = pct(1.0f)
        tvOpacity.text = pct(1.0f)
        vibSwitch.isChecked = true
        refreshSeg()
        updatePreview()
    }

    // ------------------------------------------------------------------ atualizacao online
    @Suppress("DEPRECATION")
    private fun installedCode(): Int = try {
        val i = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) i.longVersionCode.toInt() else i.versionCode
    } catch (e: Exception) { 0 }

    private fun installedName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    private fun checkUpdate(repo: String, manual: Boolean) {
        if (manual) updStatus.text = "Verificando..."
        Thread {
            try {
                val conn = URL("https://api.github.com/repos/$repo/releases/latest")
                    .openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "RotaCard")
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                if (conn.responseCode != 200) {
                    val c = conn.responseCode
                    runOnUiThread {
                        if (manual) updStatus.text =
                            "Não achei versão publicada (HTTP $c). Confira o nome do repositório."
                    }
                } else {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val code = json.getString("tag_name").substringAfterLast('-').toIntOrNull() ?: 0
                    val assets = json.getJSONArray("assets")
                    var url: String? = null
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.getString("name").endsWith(".apk")) {
                            url = a.getString("browser_download_url"); break
                        }
                    }
                    runOnUiThread { showResult(code, url) }
                }
            } catch (e: Exception) {
                runOnUiThread { if (manual) updStatus.text = "Falha ao verificar. Confira a internet." }
            }
        }.start()
    }

    private fun showResult(code: Int, url: String?) {
        val mine = installedCode()
        if (url != null && code > mine) {
            pendingUrl = url
            updBtn.text = "Baixar e instalar (build $code)"
            updStatus.text = "Nova versão disponível: build $code. A sua é a $mine."
            updStatus.setTextColor(CYAN)
        } else {
            pendingUrl = null
            updBtn.text = "Verificar atualização"
            updStatus.text = "Você está na versão mais recente (build $mine)."
            updStatus.setTextColor(MUTED)
        }
    }

    private fun downloadAndInstall(url: String) {
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Permita instalar apps por aqui e volte", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }
        updStatus.text = "Baixando..."
        Thread {
            try {
                val dir = File(cacheDir, "updates"); dir.mkdirs()
                val f = File(dir, "RotaCard.apk")
                if (f.exists()) f.delete()
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "RotaCard")
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(32768)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n == -1) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct / 10 != lastPct / 10) {
                                    lastPct = pct
                                    runOnUiThread { updStatus.text = "Baixando... $pct%" }
                                }
                            }
                        }
                    }
                }
                runOnUiThread { installApk(f) }
            } catch (e: Exception) {
                runOnUiThread { updStatus.text = "Falha no download: ${e.javaClass.simpleName}" }
            }
        }.start()
    }

    private fun installApk(f: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            updStatus.text = "Baixado. Confirme a instalação na tela do Android."
            startActivity(i)
        } catch (e: Exception) {
            updStatus.text = "Não consegui abrir o instalador: ${e.javaClass.simpleName}"
        }
    }

    // ------------------------------------------------------------------ tela
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        if (!License.canRun(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
        }
        window.statusBarColor = BG
        window.navigationBarColor = BG

        greenF = input(prefs.getFloat("green", 2.0f).toString(), "Verde")
        yellowF = input(prefs.getFloat("yellow", 1.5f).toString(), "Amarelo")
        ratingF = input(prefs.getFloat("minRating", 4.8f).toString(), "Nota")
        scale = ScaleView(this)

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { updateScale(false) }
        }
        greenF.addTextChangedListener(watcher)
        yellowF.addTextChangedListener(watcher)

        // ---- cabecalho
        val title = TextView(this).apply {
            val who = if (License.enabled(this@MainActivity)) License.displayName(this@MainActivity) else "Manel"
            val full = if (who.isNotEmpty()) "Rota Boa, $who" else "Rota Boa"
            val sb = SpannableStringBuilder(full)
            if (who.isNotEmpty()) sb.setSpan(ForegroundColorSpan(CYAN), 10, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = sb
            textSize = 28f
            setTextColor(TEXT)
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            letterSpacing = 0.02f
        }
        val chip = tv(installedName(), 11f, MUTED).apply {
            background = bg(Color.parseColor("#141E36"), 14, STROKE)
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        val menuBtn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = bg(SURFACE, 14, STROKE)
            for (i in 0..2) {
                addView(
                    View(context).apply { setBackgroundColor(TEXT) },
                    LinearLayout.LayoutParams(dp(18), dp(2)).apply { topMargin = if (i == 0) 0 else dp(4) }
                )
            }
            isClickable = true
            setOnClickListener { openDrawer() }
            pressable(this)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(menuBtn, LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(12) })
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chip)
        }

        // ---- heroi: botao de ligar com ondas de radar
        power = PowerView(this)
        stateText = tv("", 22f, TEXT).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        hintText = tv("", 13f, MUTED).apply { gravity = Gravity.CENTER }
        power.setOnClickListener { v ->
            val on = !prefs.getBoolean("enabled", true)
            prefs.edit().putBoolean("enabled", on).apply()
            refreshPower()
            v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(3f)).setDuration(300).start()
            }.start()
        }
        a11yDot = View(this)
        a11yText = tv("", 13f, GREEN)
        val a11yRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg(SURFACE, 22, STROKE)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addView(a11yDot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(10) })
            addView(a11yText)
            isClickable = true
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            pressable(this)
        }
        pulse(a11yDot)
        licText = tv("", 12f, MUTED).apply {
            background = bg(SURFACE, 18, STROKE)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            visibility = View.GONE
        }
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(power, LinearLayout.LayoutParams(dp(230), dp(230)))
            addView(stateText, lp(top = 4))
            addView(hintText, lp(top = 2, bottom = 16))
            addView(a11yRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(licText, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }

        // ---- faixas de R$/km
        val appRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Geral", "Uber", "99", "Easymob").forEachIndexed { i, name ->
            val c = tv(name, 13f, TEXT, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(8), dp(6), dp(8))
                isClickable = true
                setOnClickListener {
                    selApp = if (i == 0) "" else name
                    styleAppChips()
                    loadFields()
                }
            }
            appChips.add(c)
            appRow.addView(
                c,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (i > 0) leftMargin = dp(6) }
            )
        }
        styleAppChips()
        val faixas = card("Quando o cartão fica verde ou vermelho") {
            addView(tv("Valores para:", 12f, MUTED), lp(bottom = 6))
            addView(appRow, lp(bottom = 12))
            addView(scale, lp(bottom = 12))
            addView(row(GREEN, "Verde a partir de R$/km", greenF), lp(bottom = 10))
            addView(row(AMBER, "Amarelo a partir de R$/km", yellowF), lp(bottom = 10))
            addView(row(null, "Nota mínima do passageiro", ratingF), lp(bottom = 14))
            addView(button("Salvar faixas", true) {
                try {
                    prefs.edit()
                        .putFloat("green" + sfx(), greenF.text.toString().replace(',', '.').toFloat())
                        .putFloat("yellow" + sfx(), yellowF.text.toString().replace(',', '.').toFloat())
                        .putFloat("minRating", ratingF.text.toString().replace(',', '.').toFloat())
                        .apply()
                    updateScale(true)
                    Toast.makeText(context, "Faixas salvas", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Valor inválido", Toast.LENGTH_SHORT).show()
                }
            }, lp())
        }

        // ---- leitura
        val leitura = card("Leitura das ofertas") {
            addView(switchRow("Guardar print das ofertas não lidas", prefs.getBoolean("saveFails", true)) {
                prefs.edit().putBoolean("saveFails", it).apply()
            }, lp())
            addView(switchRow("Notificação com atalho Pausar/Retomar", prefs.getBoolean("notif", true)) {
                prefs.edit().putBoolean("notif", it).apply()
                CardService.instance?.refreshNotification()
            }, lp(bottom = 8))
            addView(button("Saúde do app", false) {
                startActivity(Intent(this@MainActivity, HealthActivity::class.java))
            }, lp(bottom = 12))
            addView(switchRow("Ler a 99 por imagem (OCR)", prefs.getBoolean("ocr99", true)) {
                prefs.edit().putBoolean("ocr99", it).apply()
            }, lp())
            addView(switchRow("Faixa preta de diagnóstico", prefs.getBoolean("debug", true)) {
                prefs.edit().putBoolean("debug", it).apply()
            }, lp())
            addView(switchRow("Salvar print ao aceitar corrida", prefs.getBoolean("saveAccepted", true)) {
                prefs.edit().putBoolean("saveAccepted", it).apply()
            }, lp())
            addView(tv("Fica na galeria, no álbum RotaCard (pasta Pictures/RotaCard).", 12f, MUTED), lp(bottom = 8))
            addView(switchRow("Easymob: reconhecer arrasto para a direita", prefs.getBoolean("easySwipe", true)) {
                prefs.edit().putBoolean("easySwipe", it).apply()
            }, lp())
            addView(switchRow("Aceite provável: oferta que some antes do fim", prefs.getBoolean("guessAccept", true)) {
                prefs.edit().putBoolean("guessAccept", it).apply()
            }, lp())
            addView(tv("Quando o toque ou o arrasto não são percebidos, o app considera aceita a oferta que some antes de acabar o tempo, sem recusa. Essas corridas aparecem no histórico com a marca \"provável\". Vale para Uber, 99 e Easymob.", 12f, MUTED), lp(bottom = 8))
            addView(button("Simular aceite (testa histórico e galeria)", false) {
                val svc = CardService.instance
                if (svc != null) svc.simulateAccept()
                else Toast.makeText(this@MainActivity, "Ative o serviço de acessibilidade primeiro", Toast.LENGTH_LONG).show()
            }, lp(bottom = 8))
            addView(button("Testar salvar print", false) {
                val svc = CardService.instance
                if (svc != null) svc.saveTest()
                else Toast.makeText(this@MainActivity, "Ative o serviço de acessibilidade primeiro", Toast.LENGTH_LONG).show()
            }, lp(bottom = 8))
            addView(button("Abrir ajustes de acessibilidade", false) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }, lp())
        }

        // ---- atualizacao
        updStatus = tv("", 13f, MUTED)
        updBtn = button("Verificar atualização", true) {
            val url = pendingUrl
            if (url != null) downloadAndInstall(url) else checkUpdate(REPO, true)
        }
        val update = card("Atualização") {
            addView(updBtn, lp(bottom = 10))
            addView(updStatus, lp())
        }

        // ---- diagnostico (recolhido)
        val out = tv("", 11f, MUTED).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        fun viewBtn(label: String, key: String) = button(label, false) {
            out.text = prefs.getString(key, "(nada ainda)")
        }
        val diagBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(viewBtn("Último texto lido (Uber e Easymob)", "last"), lp(top = 12))
            addView(viewBtn("Último texto que falhou", "fail"), lp(top = 8))
            addView(viewBtn("Último texto do OCR (99)", "ocr"), lp(top = 8))
            addView(viewBtn("Últimos toques (Uber, 99, Easymob)", "clicks"), lp(top = 8))
            addView(viewBtn("Último OCR que falhou", "ocrfail"), lp(top = 8))
            addView(viewBtn("Último erro interno", "lasterr"), lp(top = 8))
            addView(viewBtn("Registro de aceites (provável e arrasto)", "easylog"), lp(top = 8))
            addView(viewBtn("Texto da tela depois da oferta", "post"), lp(top = 8))
            addView(out, lp(top = 12))
        }
        val arrow = tv("▾", 18f, MUTED)
        val diagHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(tv("Diagnóstico", 16f, TEXT, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(arrow)
            isClickable = true
            setOnClickListener {
                val open = diagBody.visibility == View.VISIBLE
                if (open) {
                    diagBody.visibility = View.GONE
                    arrow.animate().rotation(0f).setDuration(180).start()
                } else {
                    diagBody.alpha = 0f
                    diagBody.visibility = View.VISIBLE
                    diagBody.animate().alpha(1f).setDuration(220).start()
                    arrow.animate().rotation(180f).setDuration(180).start()
                }
            }
        }
        val diag = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(SURFACE, 24, STROKE)
            setPadding(dp(18), dp(16), dp(18), dp(18))
            addView(diagHead)
            addView(diagBody)
        }

        // ---- resumo do dia
        statCount = tv("0", 34f, TEXT).apply {
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }
        statSub = tv("ofertas vistas hoje", 13f, MUTED)
        mAvg = tv("--", 18f, TEXT, true)
        mHour = tv("--", 18f, TEXT, true)
        mBest = tv("--", 18f, TEXT, true)
        statBest = tv("", 12f, MUTED)
        splitBar = SplitBarView(this)
        fun metric(v: TextView, label: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(v)
            addView(tv(label, 11f, MUTED))
        }
        lastPrice = tv("--", 30f, TEXT).apply {
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }
        lastMeta = tv("", 14f, CYAN, true)
        lastInfo = tv("", 12f, MUTED)
        val resumo = card("Resumo de hoje") {
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = bg(Color.parseColor("#0C1424"), 16, STROKE)
                    setPadding(dp(14), dp(10), dp(14), dp(12))
                    addView(tv("Última corrida aceita", 12f, MUTED))
                    addView(lastPrice)
                    addView(lastMeta)
                    addView(lastInfo)
                }, lp(bottom = 14)
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.BOTTOM
                    addView(statCount)
                    addView(
                        statSub,
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            .apply { leftMargin = dp(10); bottomMargin = dp(6) }
                    )
                }, lp(bottom = 12)
            )
            addView(splitBar, lp(bottom = 4))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(metric(mAvg, "média R$/km"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(metric(mHour, "média R$/h"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(metric(mBest, "melhor R$/km"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }, lp(top = 12, bottom = 8)
            )
            addView(statBest, lp(bottom = 8))
            addView(button("Ver histórico completo  ›", true) { openHistory() }, lp(top = 4))
            addView(button("Zerar resumo", false) {
                Stats.clear(this@MainActivity)
                refreshStats()
            }, lp(top = 4))
        }

        resumo.isClickable = true
        resumo.setOnClickListener { openHistory() }

        // ---- menu lateral: personalizar cartao
        drawerW = minOf((resources.displayMetrics.widthPixels * 0.92f).toInt(), dp(440))
        previewUi = CardUi(this)
        val previewBox = FrameLayout(this).apply {
            background = bg(Color.parseColor("#0B1220"), 18, STROKE)
            setPadding(dp(10), dp(14), dp(10), dp(14))
            addView(
                previewUi.root,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Verde", "Amarelo", "Vermelho").forEachIndexed { i, name ->
            val col = intArrayOf(GREEN, AMBER, RED)[i]
            val c = tv(name, 13f, col, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(9), dp(8), dp(9))
                isClickable = true
                setOnClickListener { previewLevel = i; updatePreview() }
                pressable(this)
            }
            chipViews.add(c)
            chipRow.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { if (i > 0) leftMargin = dp(8) })
        }

        segTop = tv("Topo", 14f, TEXT, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(11), dp(10), dp(11))
            isClickable = true
            setOnClickListener { prefs.edit().putBoolean("atBottom", false).apply(); refreshSeg() }
        }
        segBottom = tv("Base", 14f, TEXT, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(11), dp(10), dp(11))
            isClickable = true
            setOnClickListener { prefs.edit().putBoolean("atBottom", true).apply(); refreshSeg() }
        }
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg(Color.parseColor("#0C1322"), 14, STROKE)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            addView(segTop, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(segBottom, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        val scaleNow = prefs.getFloat("textScale", 1.0f)
        val opNow = prefs.getFloat("opacity", 1.0f)
        tvSize = tv(pct(scaleNow), 13f, CYAN, true)
        tvOpacity = tv(pct(opNow), 13f, CYAN, true)
        seekSize = styledSeek(12, ((scaleNow - 0.8f) / 0.05f).roundToInt().coerceIn(0, 12)) { p ->
            val v = 0.8f + p * 0.05f
            prefs.edit().putFloat("textScale", v).apply()
            tvSize.text = pct(v)
            updatePreview()
        }
        seekOpacity = styledSeek(12, ((opNow - 0.4f) / 0.05f).roundToInt().coerceIn(0, 12)) { p ->
            val v = 0.4f + p * 0.05f
            prefs.edit().putFloat("opacity", v).apply()
            tvOpacity.text = pct(v)
            updatePreview()
        }
        vibSwitch = switchRow("Vibrar em ofertas verdes", prefs.getBoolean("vibrate", true)) {
            prefs.edit().putBoolean("vibrate", it).apply()
        }

        val drawerContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(40), dp(18), dp(28))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(tv("Personalizar cartão", 20f, TEXT, true),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(tv("✕", 20f, MUTED).apply {
                        setPadding(dp(12), dp(6), dp(4), dp(6))
                        isClickable = true
                        setOnClickListener { closeDrawer() }
                    })
                }, lp(bottom = 14)
            )
            addView(tv("Prévia ao vivo (aproximada)", 12f, MUTED), lp(bottom = 8))
            addView(previewBox, lp(bottom = 10))
            addView(chipRow, lp(bottom = 20))
            addView(tv("Posição na tela", 14f, TEXT, true), lp(bottom = 8))
            addView(seg, lp(bottom = 20))
            addView(labelValueRow("Tamanho da letra", tvSize), lp(bottom = 4))
            addView(seekSize, lp(bottom = 16))
            addView(labelValueRow("Transparência", tvOpacity), lp(bottom = 4))
            addView(seekOpacity, lp(bottom = 16))
            addView(vibSwitch, lp(bottom = 4))
            addView(button("Testar vibração", false) { Haptics.pulse(this@MainActivity) }, lp(bottom = 20))
            addView(button("Testar no celular", true) {
                val svc = CardService.instance
                if (svc != null) svc.showTest()
                else Toast.makeText(this@MainActivity, "Ative o serviço de acessibilidade primeiro", Toast.LENGTH_LONG).show()
            }, lp(bottom = 8))
            addView(tv("Mostra o cartão de verdade por 6 segundos. A cada toque troca a cor.", 12f, MUTED), lp(bottom = 16))
            addView(button("Restaurar padrão", false) { resetCardPrefs() }, lp())
            if (License.enabled(this@MainActivity)) {
                addView(tv("Conta", 14f, TEXT, true), lp(top = 24, bottom = 6))
                addView(tv("Usuário " + License.user(this@MainActivity), 13f, MUTED), lp(bottom = 8))
                addView(button("Sair da conta", false) {
                    License.logout(this@MainActivity)
                    startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                    finish()
                }, lp())
            }
        }
        scrim = View(this).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            alpha = 0f
            visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }
        drawer = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0E1626"))
            isClickable = true
            translationX = -drawerW.toFloat()
            addView(drawerContent)
        }

        // ---- meta e turno
        goalBar = GoalBarView(this)
        goalText = tv("", 13f, MUTED)
        shiftText = tv("", 13f, MUTED)
        shiftBtn = button("Iniciar turno", false) { toggleShift() }
        goalF = input(prefs.getFloat("goal", 0f).let { if (it > 0f) it.toString() else "" }, "R$")
        val metas = card("Meta e turno") {
            addView(goalBar, lp(bottom = 8))
            addView(goalText, lp(bottom = 12))
            addView(row(null, "Meta do dia (R$)", goalF), lp(bottom = 10))
            addView(button("Salvar meta", false) {
                val v = goalF.text.toString().replace(',', '.').toFloatOrNull() ?: 0f
                prefs.edit().putFloat("goal", if (v > 0f) v else 0f).apply()
                refreshGoalShift()
                Toast.makeText(context, "Meta salva", Toast.LENGTH_SHORT).show()
            }, lp(bottom = 16))
            addView(shiftText, lp(bottom = 10))
            addView(shiftBtn, lp())
        }

        // ---- decisao do cartao
        minHourF = input(prefs.getFloat("minHour", 40f).toString(), "R$/h")
        maxPickupF = input(prefs.getFloat("maxPickup", 3f).toString(), "km")
        costF = input(prefs.getFloat("costKm", 0f).toString(), "R$/km")
        val decisao = card("Decisão do cartão") {
            addView(switchRow("Mostrar o veredito (Aceitar, Pensar, Recusar)", prefs.getBoolean("showVerdict", true)) {
                prefs.edit().putBoolean("showVerdict", it).apply()
            }, lp())
            addView(switchRow("Falar o veredito em voz alta", prefs.getBoolean("voice", false)) {
                prefs.edit().putBoolean("voice", it).apply()
            }, lp(bottom = 8))
            addView(button("Testar voz", false) {
                val svc = CardService.instance
                if (svc != null) svc.speakTest()
                else Toast.makeText(this@MainActivity, "Ative o serviço de acessibilidade primeiro", Toast.LENGTH_LONG).show()
            }, lp(bottom = 14))
            addView(row(null, "R$/hora mínimo", minHourF), lp(bottom = 10))
            addView(row(null, "Busca máxima (km)", maxPickupF), lp(bottom = 10))
            addView(row(null, "Custo por km do carro (R$)", costF), lp(bottom = 6))
            addView(tv("Com o custo por km, o cartão mostra o lucro líquido de cada corrida. Deixe 0 para desligar.", 12f, MUTED), lp(bottom = 12))
            addView(button("Salvar decisão", true) {
                try {
                    prefs.edit()
                        .putFloat("minHour", minHourF.text.toString().replace(',', '.').toFloat())
                        .putFloat("maxPickup", maxPickupF.text.toString().replace(',', '.').toFloat())
                        .putFloat("costKm", costF.text.toString().replace(',', '.').toFloat())
                        .apply()
                    Toast.makeText(context, "Decisão salva", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Valor inválido", Toast.LENGTH_SHORT).show()
                }
            }, lp())
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(36), dp(18), dp(28))
            addView(header, lp(bottom = 6))
            addView(hero, lp(bottom = 22))
            addView(resumo, lp(bottom = 14))
            addView(metas, lp(bottom = 14))
            addView(faixas, lp(bottom = 14))
            addView(decisao, lp(bottom = 14))
            addView(leitura, lp(bottom = 14))
            addView(update, lp(bottom = 14))
            addView(diag, lp())
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(content)
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(BG)
            addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(drawer, FrameLayout.LayoutParams(drawerW, ViewGroup.LayoutParams.MATCH_PARENT))
        })
        refreshSeg()
        updatePreview()
        refreshStats()
        refreshLicense()

        refreshPower()
        refreshA11y()
        updateScale(false)

        // um unico momento de entrada: o botao surge e a regua se preenche
        power.alpha = 0f
        power.scaleX = 0.6f
        power.scaleY = 0.6f
        power.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setInterpolator(OvershootInterpolator(1.6f)).setDuration(650).start()
        scale.postDelayed({ updateScale(true) }, 350)

        checkUpdate(REPO, false)
    }
}
