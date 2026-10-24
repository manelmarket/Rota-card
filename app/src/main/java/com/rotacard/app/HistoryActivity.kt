package com.rotacard.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class HistoryActivity : Activity() {

    private val BG = Color.parseColor("#0A101E")
    private val SURFACE = Color.parseColor("#111A2E")
    private val STROKE = Color.parseColor("#1D2A47")
    private val TEXT = Color.parseColor("#E8EEF9")
    private val MUTED = Color.parseColor("#8190AD")
    private val CYAN = Color.parseColor("#2DD4F0")
    private val GREEN = Color.parseColor("#22C55E")
    private val AMBER = Color.parseColor("#F59E0B")
    private val RED = Color.parseColor("#EF4444")
    private val pt = Locale.forLanguageTag("pt-BR")

    private val pool = Executors.newFixedThreadPool(2)
    private var period = 0
    private val chips = ArrayList<TextView>()
    private lateinit var sumTotal: TextView
    private lateinit var sumCount: TextView
    private lateinit var mKm: TextView
    private lateinit var mHour: TextView
    private lateinit var mDist: TextView
    private lateinit var chartBox: LinearLayout
    private lateinit var chart: DailyBarsView
    private lateinit var chartTitle: TextView
    private lateinit var chartInfo: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var scrim: View
    private lateinit var panel: ScrollView
    private lateinit var panelBody: LinearLayout
    private var panelOpen = false
    private var screenH = 0

    // ------------------------------------------------------------ helpers
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
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
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
            setPadding(dp(18), dp(14), dp(18), dp(14))
            if (primary) {
                setTextColor(Color.parseColor("#04121A"))
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(CYAN, Color.parseColor("#3B82F6"))
                ).apply { cornerRadius = dp(28).toFloat() }
            } else {
                setTextColor(color)
                background = bg(Color.parseColor("#17213A"), 28, STROKE)
            }
            isClickable = true
            setOnClickListener { onClick() }
            pressable(this)
        }

    private fun levelColor(perKm: Double?, app: String? = null): Int {
        if (perKm == null) return MUTED
        return when (CardPrefs.level(perKm, CardPrefs.load(this, app))) { 2 -> GREEN; 1 -> AMBER; else -> RED }
    }

    private fun levelName(perKm: Double?, app: String? = null): String {
        if (perKm == null) return "--"
        return when (CardPrefs.level(perKm, CardPrefs.load(this, app))) { 2 -> "Verde"; 1 -> "Amarela"; else -> "Vermelha" }
    }

    private fun startOfToday(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    // ------------------------------------------------------------ tela
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        screenH = resources.displayMetrics.heightPixels

        val back = TextView(this).apply {
            text = "‹"
            textSize = 28f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            background = bg(SURFACE, 14, STROKE)
            isClickable = true
            setOnClickListener { finish() }
            pressable(this)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back, LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(12) })
            addView(
                tv("Histórico", 28f, TEXT).apply {
                    typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }

        // periodo
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Hoje", "7 dias", "Tudo").forEachIndexed { i, name ->
            val c = tv(name, 14f, TEXT, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(10))
                isClickable = true
                setOnClickListener { period = i; render() }
            }
            chips.add(c)
            chipRow.addView(
                c,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (i > 0) leftMargin = dp(8) }
            )
        }

        // resumo do periodo
        sumTotal = tv("R$ 0,00", 32f, TEXT).apply {
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }
        sumCount = tv("", 13f, MUTED)
        mKm = tv("--", 17f, TEXT, true)
        mHour = tv("--", 17f, TEXT, true)
        mDist = tv("--", 17f, TEXT, true)
        fun metric(v: TextView, label: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(v)
            addView(tv(label, 11f, MUTED))
        }
        val summary = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(SURFACE, 24, STROKE)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(tv("Total ganho no período", 12f, MUTED))
            addView(sumTotal)
            addView(sumCount, lp(bottom = 12))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(metric(mKm, "média R$/km"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(metric(mHour, "média R$/h"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(metric(mDist, "km rodados"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }, lp()
            )
        }

        // ganho por dia (7 dias ou as ultimas 2 semanas)
        chart = DailyBarsView(this)
        chartTitle = tv("", 12f, MUTED)
        chartInfo = tv("", 12f, MUTED)
        chartBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(SURFACE, 24, STROKE)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            addView(chartTitle, lp(bottom = 8))
            addView(chart, lp(bottom = 6))
            addView(chartInfo, lp())
        }

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val exportBtn = button("Exportar relatório", false) { askExport() }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(36), dp(18), dp(28))
            addView(header, lp(bottom = 16))
            addView(chipRow, lp(bottom = 14))
            addView(summary, lp(bottom = 10))
            addView(chartBox, lp(bottom = 10))
            addView(exportBtn, lp(bottom = 16))
            addView(listBox, lp())
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(content)
        }

        // painel de detalhes (sobe de baixo)
        panelBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        panel = ScrollView(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0E1626"))
                val r = dp(26).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setStroke(dp(1), STROKE)
            }
            isClickable = true
            translationY = screenH.toFloat()
            addView(panelBody)
        }
        scrim = View(this).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            alpha = 0f
            visibility = View.GONE
            setOnClickListener { closePanel() }
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(BG)
            addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(
                panel,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    .apply { topMargin = dp(60) }
            )
        })
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::listBox.isInitialized && !panelOpen) render()
    }

    override fun onDestroy() {
        pool.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (panelOpen) closePanel() else super.onBackPressed()
    }

    // ------------------------------------------------------------ lista
    private fun currentRides(): List<Ride> {
        val all = History.load(this)
        val cutoff = when (period) {
            0 -> startOfToday()
            1 -> startOfToday() - 6L * 24 * 3600 * 1000
            else -> 0L
        }
        return all.filter { it.id >= cutoff }
    }

    private fun askExport() {
        val rides = currentRides()
        if (rides.isEmpty()) {
            Toast.makeText(this, "Nenhuma corrida neste período", Toast.LENGTH_SHORT).show()
            return
        }
        val items = arrayOf("Planilha (CSV: abre no Excel e no Sheets)", "PDF resumido (tabela)", "PDF com os prints")
        AlertDialog.Builder(this)
            .setTitle("Exportar relatório")
            .setItems(items) { _, which -> doExport(which, rides) }
            .show()
    }

    private fun doExport(which: Int, rides: List<Ride>) {
        Toast.makeText(this, "Gerando...", Toast.LENGTH_SHORT).show()
        val label = arrayOf("Hoje", "Últimos 7 dias", "Tudo")[period]
        Thread {
            try {
                val f: File
                val mime: String
                if (which == 0) {
                    f = Report.csv(this, rides)
                    mime = "text/csv"
                } else {
                    f = Report.pdf(this, rides, which == 2, label)
                    mime = "application/pdf"
                }
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                runOnUiThread {
                    val i = Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(i, "Enviar relatório"))
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Falha ao gerar: " + e.javaClass.simpleName, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun styleChips() {
        chips.forEachIndexed { i, c ->
            val sel = i == period
            c.setTextColor(if (sel) Color.parseColor("#04121A") else TEXT)
            c.background = if (sel)
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(CYAN, Color.parseColor("#3B82F6")))
                    .apply { cornerRadius = dp(14).toFloat() }
            else bg(SURFACE, 14, STROKE)
        }
    }

    private fun dayLabel(ts: Long): String {
        val key = SimpleDateFormat("yyyyMMdd", Locale.US)
        val k = key.format(Date(ts))
        val today = key.format(Date())
        val yest = key.format(Date(System.currentTimeMillis() - 24L * 3600 * 1000))
        return when (k) {
            today -> "Hoje"
            yest -> "Ontem"
            else -> SimpleDateFormat("EEEE, dd/MM", pt).format(Date(ts)).replaceFirstChar { it.uppercase() }
        }
    }

    private fun render() {
        styleChips()
        val all = History.load(this)
        val cutoff = when (period) { 0 -> startOfToday(); 1 -> startOfToday() - 6L * 24 * 3600 * 1000; else -> 0L }
        val rides = all.filter { it.id >= cutoff }

        val withData = rides.filter { it.hasData }
        val totalPrice = withData.sumOf { it.price }
        val totalKm = withData.sumOf { it.totalKm }
        val timed = withData.filter { it.totalMin > 0.0 }
        val hours = timed.sumOf { it.totalMin } / 60.0
        sumTotal.text = Fmt.money(totalPrice)
        sumCount.text = when (rides.size) {
            0 -> "nenhuma corrida aceita"
            1 -> "1 corrida aceita"
            else -> "${rides.size} corridas aceitas"
        }
        val totalCost = withData.sumOf { it.cost }
        if (totalCost > 0.0) {
            sumCount.text = sumCount.text.toString() + "  •  líquido " + Fmt.money(totalPrice - totalCost)
        }
        mKm.text = if (totalKm > 0.0) "R$ " + Fmt.d2(totalPrice / totalKm) else "--"
        mHour.text = if (hours > 0.0) "R$ " + Fmt.n0(timed.sumOf { it.price } / hours) else "--"
        mDist.text = if (totalKm > 0.0) Fmt.d1(totalKm) + " km" else "--"
        renderChart(all)

        listBox.removeAllViews()
        if (rides.isEmpty()) {
            listBox.addView(
                tv("Nenhuma corrida neste período. As corridas aceitas aparecem aqui, cada uma com o print da oferta.", 14f, MUTED)
                    .apply { setPadding(dp(4), dp(12), dp(4), dp(12)) },
                lp()
            )
            return
        }
        val dayKey = SimpleDateFormat("yyyyMMdd", Locale.US)
        var lastDay = ""
        for (r in rides) {
            val k = dayKey.format(Date(r.id))
            if (k != lastDay) {
                lastDay = k
                listBox.addView(tv(dayLabel(r.id), 13f, MUTED, true), lp(top = 8, bottom = 8))
            }
            listBox.addView(rideRow(r), lp(bottom = 10))
        }
    }

    private fun renderChart(all: List<Ride>) {
        if (period == 0) { chartBox.visibility = View.GONE; return }
        val days = if (period == 1) 7 else 14
        val bars = DailyBars.build(all, days)
        if (bars.all { it.total <= 0.0 }) { chartBox.visibility = View.GONE; return }
        chartBox.visibility = View.VISIBLE
        chartTitle.text = if (days == 7) "Ganho por dia (7 dias)" else "Ganho por dia (últimas 2 semanas)"
        chart.set(bars)
        val worked = bars.filter { it.total > 0.0 }
        val best = worked.maxByOrNull { it.total }
        val bestHour = worked.filter { it.perHour != null }.maxByOrNull { it.perHour!! }
        val parts = ArrayList<String>()
        parts.add("média " + Fmt.money(worked.sumOf { it.total } / worked.size) + " por dia trabalhado")
        if (best != null) parts.add("melhor: " + best.label + " (" + Fmt.money(best.total) + ")")
        if (bestHour != null) parts.add("melhor R$/h: " + bestHour.label + " (R$ " + Fmt.n0(bestHour.perHour!!) + ")")
        chartInfo.text = parts.joinToString("  •  ")
    }

    private fun loadThumb(r: Ride, iv: ImageView, band: Boolean) {
        val name = r.shot ?: return
        pool.execute {
            try {
                val f = History.shotFile(this, name)
                if (!f.exists()) return@execute
                val opts = BitmapFactory.Options().apply { inSampleSize = if (band) 2 else 1 }
                val bmp = BitmapFactory.decodeFile(f.path, opts) ?: return@execute
                val out: Bitmap = if (band) {
                    val top = (bmp.height * 0.36f).toInt()
                    val h = (bmp.height * 0.52f).toInt().coerceAtMost(bmp.height - top)
                    Bitmap.createBitmap(bmp, 0, top, bmp.width, h)
                } else bmp
                runOnUiThread { iv.setImageBitmap(out) }
            } catch (_: Exception) { }
        }
    }

    private fun rideRow(r: Ride): View {
        val time = SimpleDateFormat("HH:mm", pt).format(Date(r.id))
        val perKm = r.perKm
        val col = levelColor(perKm, r.app)

        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = bg(Color.parseColor("#0C1322"), 14)
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        loadThumb(r, thumb, true)

        val priceTxt = if (r.hasData) Fmt.money(r.price) else "Oferta não lida"
        val til = if (r.minEstimated) "~" else ""
        val line2 = if (r.hasData && perKm != null) {
            val h = r.perHour
            "R$ " + Fmt.d2(perKm) + "/km" + (if (h != null) "  •  $til" + "R$ " + Fmt.n0(h) + "/h" else "")
        } else "print salvo, sem dados"
        val line3 = if (r.hasData) {
            Fmt.d1(r.totalKm) + " km  •  " + til + Fmt.n0(r.totalMin) + " min" +
                    (r.rating?.let { "  •  ★ " + Fmt.d2(it) } ?: "") + "  •  " + r.app + (if (r.guess) "  •  provável" else "")
        } else r.app

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        tv(priceTxt, 21f, TEXT).apply { typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD) },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    addView(tv(time, 13f, MUTED))
                }, lp()
            )
            addView(tv(line2, 14f, col, true), lp(top = 2))
            addView(tv(line3, 12f, MUTED), lp(top = 2))
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg(SURFACE, 20, STROKE)
            setPadding(dp(12), dp(12), dp(14), dp(12))
            addView(thumb, LinearLayout.LayoutParams(dp(84), dp(96)).apply { rightMargin = dp(12) })
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            isClickable = true
            setOnClickListener { openPanel(r) }
            pressable(this)
        }
    }

    // ------------------------------------------------------------ detalhes
    private fun detailRow(label: String, value: String, color: Int = TEXT): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
            addView(tv(label, 14f, MUTED), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(tv(value, 14f, color, true).apply { gravity = Gravity.END })
        }

    private fun divider(): View = View(this).apply { setBackgroundColor(STROKE) }

    private fun summaryText(r: Ride): String {
        val whenTxt = SimpleDateFormat("dd/MM/yyyy HH:mm", pt).format(Date(r.id))
        if (!r.hasData) return "Corrida ${r.app} aceita em $whenTxt"
        val h = r.perHour
        return "Corrida ${r.app} • $whenTxt\n" + Fmt.money(r.price) +
                " • R$ " + Fmt.d2(r.perKm ?: 0.0) + "/km" +
                (if (h != null) " • R$ " + Fmt.n0(h) + "/h" else "") +
                " • " + Fmt.d1(r.totalKm) + " km"
    }

    private fun bestUri(r: Ride): Uri? {
        r.gallery?.let { g ->
            try {
                val u = Uri.parse(g)
                contentResolver.openInputStream(u)?.close()
                return u
            } catch (_: Exception) { }
        }
        val f = r.shot?.let { History.shotFile(this, it) }
        if (f != null && f.exists()) return FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        return null
    }

    private fun share(r: Ride) {
        val uri = bestUri(r)
        if (uri == null) {
            Toast.makeText(this, "Esta corrida não tem print disponível", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, summaryText(r))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "Compartilhar corrida"))
    }

    private fun openOriginal(r: Ride) {
        val uri = bestUri(r)
        if (uri == null) {
            Toast.makeText(this, "Esta corrida não tem print disponível", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Nenhum app para abrir a imagem", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(r: Ride) {
        AlertDialog.Builder(this)
            .setMessage("Excluir esta corrida do histórico? O print na galeria continua lá.")
            .setPositiveButton("Excluir") { _, _ ->
                History.delete(this, r.id)
                closePanel()
                render()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openPanel(r: Ride) {
        panelBody.removeAllViews()
        val perKm = r.perKm
        val col = levelColor(perKm, r.app)
        val til = if (r.minEstimated) "~" else ""

        panelBody.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(tv("Detalhes da corrida", 20f, TEXT, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(tv("✕", 20f, MUTED).apply {
                    setPadding(dp(12), dp(6), dp(4), dp(6))
                    isClickable = true
                    setOnClickListener { closePanel() }
                })
            }, lp(bottom = 14)
        )

        val shotFile = r.shot?.let { History.shotFile(this, it) }
        if (shotFile != null && shotFile.exists()) {
            val iv = ImageView(this).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                maxHeight = dp(460)
                background = bg(Color.parseColor("#0C1322"), 16)
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
                isClickable = true
                setOnClickListener { openOriginal(r) }
            }
            try { iv.setImageBitmap(BitmapFactory.decodeFile(shotFile.path)) } catch (_: Exception) { }
            panelBody.addView(iv, lp(bottom = 4))
            panelBody.addView(tv("Toque no print para abrir o original na galeria", 11f, MUTED), lp(bottom = 12))
        } else {
            panelBody.addView(
                tv("Print não disponível para esta corrida.", 13f, MUTED),
                lp(bottom = 12)
            )
        }

        if (r.hasData) {
            val h = r.perHour
            panelBody.addView(detailRow("Valor da corrida", Fmt.money(r.price)))
            panelBody.addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            if (r.cost > 0.0) {
                panelBody.addView(detailRow("Líquido (após custo por km)", Fmt.money(r.price - r.cost), GREEN))
                panelBody.addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            }
            panelBody.addView(detailRow("Ganho por km", "R$ " + Fmt.d2(perKm ?: 0.0) + "/km", col))
            panelBody.addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            panelBody.addView(detailRow("Ganho por hora", if (h != null) til + "R$ " + Fmt.n0(h) + "/h" else "--"))
            panelBody.addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            panelBody.addView(detailRow("Classificação", levelName(perKm, r.app), col))
            panelBody.addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            panelBody.addView(detailRow("Distância total", Fmt.d1(r.totalKm) + " km"))
            panelBody.addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            panelBody.addView(detailRow("Busca até o passageiro", Fmt.d1(r.pickupKm) + " km  •  " + til + Fmt.n0(r.pickupMin) + " min"))
            panelBody.addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            panelBody.addView(detailRow("Corrida", Fmt.d1(r.tripKm) + " km  •  " + Fmt.n0(r.tripMin) + " min"))
            panelBody.addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            panelBody.addView(detailRow("Tempo total", til + Fmt.n0(r.totalMin) + " min"))
            panelBody.addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            panelBody.addView(detailRow("Nota do passageiro", r.rating?.let { "★ " + Fmt.d2(it) } ?: "--"))
            panelBody.addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        } else {
            panelBody.addView(
                tv("O app detectou que você aceitou, mas não conseguiu ler os números da oferta. O print ficou salvo.", 13f, MUTED),
                lp(bottom = 8)
            )
        }
        panelBody.addView(detailRow("App", r.app))
        panelBody.addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        panelBody.addView(detailRow("Aceita em", SimpleDateFormat("dd/MM/yyyy  HH:mm:ss", pt).format(Date(r.id))))
        if (r.guess) {
            panelBody.addView(
                tv("Aceite provável: o app deduziu porque a oferta do Easymob sumiu antes do fim da contagem.", 11f, MUTED),
                lp(top = 8)
            )
        }
        if (r.minEstimated) {
            panelBody.addView(
                tv("~ Tempo estimado: o Easymob não informa o tempo até o passageiro (calculado a 30 km/h).", 11f, MUTED),
                lp(top = 8)
            )
        }

        panelBody.addView(button("Compartilhar", true) { share(r) }, lp(top = 18))
        panelBody.addView(button("Abrir original na galeria", false) { openOriginal(r) }, lp(top = 10))
        panelBody.addView(button("Excluir do histórico", false, RED) { confirmDelete(r) }, lp(top = 10))

        panel.scrollTo(0, 0)
        panelOpen = true
        scrim.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(220).start()
        panel.animate().translationY(0f).setDuration(280).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun closePanel() {
        panelOpen = false
        scrim.animate().alpha(0f).setDuration(200).withEndAction { scrim.visibility = View.GONE }.start()
        panel.animate().translationY(screenH.toFloat()).setDuration(240).start()
    }
}
