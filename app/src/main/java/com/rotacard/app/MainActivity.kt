package com.rotacard.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private lateinit var updStatus: TextView
    private lateinit var updBtn: Button
    private var pendingUrl: String? = null

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
                            "Nao achei versao publicada (HTTP $c). Confira o nome do repositorio."
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
                runOnUiThread { if (manual) updStatus.text = "Falha ao verificar (sem internet?)" }
            }
        }.start()
    }

    private fun showResult(code: Int, url: String?) {
        val mine = installedCode()
        if (url != null && code > mine) {
            pendingUrl = url
            updBtn.text = "Baixar e instalar (build $code)"
            updStatus.text = "Nova versao disponivel: build $code (a sua e $mine)."
        } else {
            pendingUrl = null
            updBtn.text = "Verificar atualizacao"
            updStatus.text = "Voce esta na versao mais recente (build $mine)."
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
            updStatus.text = "Baixado. Confirme a instalacao na tela do Android."
            startActivity(i)
        } catch (e: Exception) {
            updStatus.text = "Nao consegui abrir o instalador: ${e.javaClass.simpleName}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()

        fun field(hint: String, value: Float) = EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(value.toString())
        }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, pad, 0, 0) }

        val green = field("Verde", p.getFloat("green", 2.0f))
        val yellow = field("Amarelo", p.getFloat("yellow", 1.5f))
        val rating = field("Nota minima", p.getFloat("minRating", 4.8f))
        val debug = CheckBox(this).apply {
            text = "Modo diagnostico (faixa preta no topo)"
            isChecked = p.getBoolean("debug", true)
            setOnCheckedChangeListener { _, v -> p.edit().putBoolean("debug", v).apply() }
        }
        val ocr = CheckBox(this).apply {
            text = "Ler a 99 por imagem (OCR)"
            isChecked = p.getBoolean("ocr99", true)
            setOnCheckedChangeListener { _, v -> p.edit().putBoolean("ocr99", v).apply() }
        }
        val out = TextView(this).apply { textSize = 11f; setTextIsSelectable(true) }

        val power = Button(this).apply { textSize = 18f }
        fun refreshPower() {
            val on = p.getBoolean("enabled", true)
            power.text = if (on) "LIGADO - toque para desligar" else "DESLIGADO - toque para ligar"
            power.setBackgroundColor(Color.parseColor(if (on) "#1B8E3E" else "#C62828"))
            power.setTextColor(Color.WHITE)
        }
        power.setOnClickListener {
            p.edit().putBoolean("enabled", !p.getBoolean("enabled", true)).apply()
            refreshPower()
        }
        refreshPower()

        fun viewBtn(title: String, key: String) = Button(this).apply {
            text = title
            setOnClickListener { out.text = p.getString(key, "(nada ainda)") }
        }

        // ----- Atualizacao online -----
        val repo = EditText(this).apply {
            hint = "usuario/repositorio"
            setText(p.getString("repo", "manelmarket/Rota-card"))
        }
        updStatus = TextView(this).apply { textSize = 13f }
        updBtn = Button(this).apply { text = "Verificar atualizacao" }
        updBtn.setOnClickListener {
            val r = repo.text.toString().trim()
            p.edit().putString("repo", r).apply()
            val url = pendingUrl
            if (url != null) downloadAndInstall(url) else checkUpdate(r, true)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            addView(TextView(context).apply { text = "Rota Card"; textSize = 24f })
            addView(TextView(context).apply { text = "Versao instalada: ${installedName()}"; textSize = 12f })
            addView(power)
            addView(label("Verde a partir de R$/km:")); addView(green)
            addView(label("Amarelo a partir de R$/km (abaixo disso fica vermelho):")); addView(yellow)
            addView(label("Nota minima do passageiro (abaixo aparece ⚠):")); addView(rating)
            addView(Button(context).apply {
                text = "Salvar"
                setOnClickListener {
                    try {
                        p.edit()
                            .putFloat("green", green.text.toString().replace(',', '.').toFloat())
                            .putFloat("yellow", yellow.text.toString().replace(',', '.').toFloat())
                            .putFloat("minRating", rating.text.toString().replace(',', '.').toFloat())
                            .apply()
                        Toast.makeText(context, "Salvo", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Valor invalido", Toast.LENGTH_SHORT).show()
                    }
                }
            })
            addView(Button(context).apply {
                text = "Ativar servico de acessibilidade"
                setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            })
            addView(ocr)
            addView(debug)
            addView(label("Atualizacao online (repositorio no GitHub):"))
            addView(repo)
            addView(updBtn)
            addView(updStatus)
            addView(label("Diagnostico:"))
            addView(viewBtn("Ver ultimo texto lido (Uber/Easymob)", "last"))
            addView(viewBtn("Ver ultimo texto que FALHOU", "fail"))
            addView(viewBtn("Ver ultimo texto OCR (99)", "ocr"))
            addView(out)
        }
        setContentView(ScrollView(this).apply { addView(root) })

        // checa em segundo plano ao abrir o app
        checkUpdate(repo.text.toString().trim(), false)
    }
}
