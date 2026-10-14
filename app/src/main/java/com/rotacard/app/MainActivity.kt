package com.rotacard.app

import android.app.Activity
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
import android.widget.EditText
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

    private lateinit var prefs: SharedPreferences
    private lateinit var power: PowerView
    private lateinit var scale: ScaleView
    private lateinit var stateText: TextView
    private lateinit var hintText: TextView
    private lateinit var a11yText: TextView
    private lateinit var a11yDot: View
    private lateinit var greenF: EditText
    private lateinit var yellowF: EditText
    private lateinit var ratingF: EditText
    private lateinit var updStatus: TextView
    private lateinit var updBtn: TextView
    private var pendingUrl: String? = null
    private val animators = mutableListOf<ObjectAnimator>()

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
        if (::a11yText.isInitialized) refreshA11y()
    }

    override fun onDestroy() {
        animators.forEach { it.cancel() }
        super.onDestroy()
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
            val sb = SpannableStringBuilder("Rota Card")
            sb.setSpan(ForegroundColorSpan(CYAN), 5, 9, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
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
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(power, LinearLayout.LayoutParams(dp(230), dp(230)))
            addView(stateText, lp(top = 4))
            addView(hintText, lp(top = 2, bottom = 16))
            addView(a11yRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // ---- faixas de R$/km
        val faixas = card("Quando o cartão fica verde ou vermelho") {
            addView(scale, lp(bottom = 12))
            addView(row(GREEN, "Verde a partir de R$/km", greenF), lp(bottom = 10))
            addView(row(AMBER, "Amarelo a partir de R$/km", yellowF), lp(bottom = 10))
            addView(row(null, "Nota mínima do passageiro", ratingF), lp(bottom = 14))
            addView(button("Salvar faixas", true) {
                try {
                    prefs.edit()
                        .putFloat("green", greenF.text.toString().replace(',', '.').toFloat())
                        .putFloat("yellow", yellowF.text.toString().replace(',', '.').toFloat())
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
            addView(switchRow("Ler a 99 por imagem (OCR)", prefs.getBoolean("ocr99", true)) {
                prefs.edit().putBoolean("ocr99", it).apply()
            }, lp())
            addView(switchRow("Faixa preta de diagnóstico", prefs.getBoolean("debug", true)) {
                prefs.edit().putBoolean("debug", it).apply()
            }, lp(bottom = 8))
            addView(button("Abrir ajustes de acessibilidade", false) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }, lp())
        }

        // ---- atualizacao
        val repo = input(prefs.getString("repo", "manelmarket/Rota-card") ?: "", "usuario/repositorio", false)
        updStatus = tv("", 13f, MUTED)
        updBtn = button("Verificar atualização", true) {
            val r = repo.text.toString().trim()
            prefs.edit().putString("repo", r).apply()
            val url = pendingUrl
            if (url != null) downloadAndInstall(url) else checkUpdate(r, true)
        }
        val update = card("Atualização") {
            addView(tv("Repositório no GitHub", 12f, MUTED), lp(bottom = 6))
            addView(repo, lp(bottom = 12))
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

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(36), dp(18), dp(28))
            addView(header, lp(bottom = 6))
            addView(hero, lp(bottom = 22))
            addView(faixas, lp(bottom = 14))
            addView(leitura, lp(bottom = 14))
            addView(update, lp(bottom = 14))
            addView(diag, lp())
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(content)
        })

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

        checkUpdate(repo.text.toString().trim(), false)
    }
}
