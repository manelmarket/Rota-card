package com.rotacard.app

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class HealthActivity : Activity() {

    private val BG = Color.parseColor("#0A101E")
    private val SURFACE = Color.parseColor("#111A2E")
    private val STROKE = Color.parseColor("#1D2A47")
    private val TEXT = Color.parseColor("#E8EEF9")
    private val MUTED = Color.parseColor("#8190AD")
    private val GREEN = Color.parseColor("#22C55E")
    private val AMBER = Color.parseColor("#F59E0B")
    private val RED = Color.parseColor("#EF4444")
    private val CYAN = Color.parseColor("#2DD4F0")

    private lateinit var box: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 2000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun bg(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
        setStroke(dp(1), STROKE)
    }

    private fun tv(t: String, size: Float, color: Int, medium: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun lp(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun btn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 15f
        setTextColor(TEXT)
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(18), dp(14), dp(18), dp(14))
        background = bg(Color.parseColor("#17213A"), 28)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun line(label: String, value: String, color: Int): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
            addView(tv(label, 14f, MUTED), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(tv(value, 14f, color, true).apply { gravity = Gravity.END })
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        val back = TextView(this).apply {
            text = "‹"
            textSize = 28f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            background = bg(SURFACE, 14)
            isClickable = true
            setOnClickListener { finish() }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back, LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(12) })
            addView(
                tv("Saúde do app", 28f, TEXT).apply {
                    typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(SURFACE, 24)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(btn("Ajustes de acessibilidade") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }, lp(top = 14))
            addView(btn("Ajustes de bateria") {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }, lp(top = 10))
            addView(btn("Zerar contadores de hoje") {
                Health.clear(this@HealthActivity)
                render()
            }, lp(top = 10))
            addView(btn("Enviar prints das ofertas não lidas") { shareFailures() }, lp(top = 10))
            addView(btn("Apagar prints das ofertas não lidas") {
                ShotSaver.clearFailures(this@HealthActivity)
                Toast.makeText(this@HealthActivity, "Prints apagados", Toast.LENGTH_SHORT).show()
                render()
            }, lp(top = 10))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(36), dp(18), dp(28))
            addView(header, lp(bottom = 16))
            addView(box, lp())
            addView(tv("Se alguma linha estiver vermelha ou amarela, o cartão pode falhar.", 12f, MUTED), lp(top = 10))
            addView(actions, lp())
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(content)
        })
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun render() {
        box.removeAllViews()

        val age = Health.ageSeconds()
        val alive = CardService.instance != null && age in 0..8
        val svc = when {
            alive -> "Funcionando"
            CardService.instance != null -> "Parado há $age s"
            else -> "Desligado"
        }
        box.addView(line("Serviço de leitura", svc, if (alive) GREEN else RED))

        val enabledList = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val a11y = enabledList.contains(packageName, ignoreCase = true)
        box.addView(line("Acessibilidade", if (a11y) "Ativa" else "Desligada", if (a11y) GREEN else RED))

        box.addView(line("Última leitura da tela", if (age < 0) "nunca" else "há $age s", if (alive) TEXT else AMBER))

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val free = pm.isIgnoringBatteryOptimizations(packageName)
        box.addView(line("Bateria", if (free) "Sem restrição" else "Com restrição (pode matar o app)", if (free) GREEN else AMBER))

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notif = nm.areNotificationsEnabled()
        box.addView(line("Notificações", if (notif) "Permitidas" else "Bloqueadas", if (notif) GREEN else AMBER))

        box.addView(line("Ofertas lidas hoje: Uber", Health.get(this, "read_Uber").toString(), TEXT))
        box.addView(line("Ofertas lidas hoje: 99", Health.get(this, "read_99").toString(), TEXT))
        box.addView(line("Ofertas lidas hoje: Easymob", Health.get(this, "read_Easymob").toString(), TEXT))
        box.addView(line("Leituras por imagem (OK)", Health.get(this, "ocr_ok").toString(), TEXT))
        val fails = Health.get(this, "ocr_fail")
        box.addView(line("Leituras por imagem (falharam)", fails.toString(), if (fails > 0) AMBER else TEXT))
        val unread = Health.get(this, "unread")
        box.addView(line("Ofertas não lidas (print guardado)", unread.toString(), if (unread > 0) AMBER else TEXT))
        box.addView(line("Prints de ofertas não lidas guardados", ShotSaver.failureCount(this).toString(), TEXT))
        box.addView(tv("Esses prints ficam só dentro do app (não vão para a galeria) e são apagados depois de 7 dias. Use \"Enviar prints\" para mandá-los a quem ajusta o leitor.", 11f, MUTED), lp(top = 4))
    }

    /** Junta os prints das ofertas nao lidas num zip e abre o "Compartilhar". */
    private fun shareFailures() {
        Thread {
            try {
                val f = ShotSaver.zipFailures(this)
                runOnUiThread {
                    if (f == null) {
                        Toast.makeText(this, "Nenhum print guardado", Toast.LENGTH_SHORT).show()
                    } else {
                        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                        val i = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(i, "Enviar prints"))
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread { Toast.makeText(this, "Falha: " + e.javaClass.simpleName, Toast.LENGTH_LONG).show() }
            }
        }.start()
    }
}
