package com.rotacard.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class LoginActivity : Activity() {

    private val BG = Color.parseColor("#0A101E")
    private val SURFACE = Color.parseColor("#111A2E")
    private val STROKE = Color.parseColor("#1D2A47")
    private val TEXT = Color.parseColor("#E8EEF9")
    private val MUTED = Color.parseColor("#8190AD")
    private val CYAN = Color.parseColor("#2DD4F0")
    private val AMBER = Color.parseColor("#F59E0B")
    private val RED = Color.parseColor("#EF4444")

    private lateinit var userF: EditText
    private lateinit var passF: EditText
    private lateinit var btn: TextView
    private lateinit var msg: TextView
    private var busy = false
    private var updBtnRef: TextView? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun bg(color: Int, r: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun field(hint: String, password: Boolean) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(MUTED)
        setTextColor(TEXT)
        textSize = 18f
        gravity = Gravity.CENTER
        isSingleLine = true
        inputType = if (password) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_NUMBER
        background = bg(Color.parseColor("#0C1322"), 14, STROKE)
        setPadding(dp(14), dp(14), dp(14), dp(14))
    }

    private fun lp(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        // sem URL do painel configurada: nao ha o que pedir
        if (!License.enabled(this) || License.canRun(this)) {
            goMain()
            return
        }

        val hero = PowerView(this).apply { on = true }
        val title = TextView(this).apply {
            val sb = SpannableStringBuilder("Rota Boa")
            sb.setSpan(ForegroundColorSpan(CYAN), 5, 8, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = sb
            textSize = 30f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }
        val sub = TextView(this).apply {
            text = "Acesso restrito. Entre com o usuário e a senha que o administrador enviou."
            textSize = 14f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
        }
        userF = field("Usuário", false).apply { setText(License.user(this@LoginActivity)) }
        passF = field("Senha", true)
        msg = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(AMBER)
            text = License.lastMsg(this@LoginActivity)
        }
        btn = TextView(this).apply {
            text = "Entrar"
            textSize = 16f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.parseColor("#04121A"))
            setPadding(dp(18), dp(15), dp(18), dp(15))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(CYAN, Color.parseColor("#3B82F6"))
            ).apply { cornerRadius = dp(28).toFloat() }
            isClickable = true
            setOnClickListener { doLogin() }
            setOnTouchListener { v, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                }
                false
            }
        }
        val foot = TextView(this).apply {
            text = "Código do aparelho: " + License.shortDevice(this@LoginActivity)
            textSize = 11f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
        }

        val updBtn = TextView(this).apply {
            text = "Baixar atualização"
            textSize = 15f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = bg(Color.parseColor("#17213A"), 28, STROKE)
            visibility = if (License.needsUpdate(this@LoginActivity)) View.VISIBLE else View.GONE
            isClickable = true
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/manelmarket/Rota-card/releases/latest")))
            }
        }
        updBtnRef = updBtn
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(26), dp(48), dp(26), dp(28))
            addView(hero, LinearLayout.LayoutParams(dp(190), dp(190)))
            addView(title, lp(top = 6))
            addView(sub, lp(top = 6, bottom = 24))
            addView(userF, lp(bottom = 12))
            addView(passF, lp(bottom = 12))
            addView(msg, lp(bottom = 12))
            addView(btn, lp())
            addView(updBtn, lp(top = 12))
            addView(foot, lp(top = 26))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(box)
        })
    }

    private fun goMain() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    private fun doLogin() {
        if (busy) return
        val u = userF.text.toString().filter { it.isDigit() }
        val p = passF.text.toString().filter { it.isDigit() }
        if (u.isEmpty() || p.isEmpty()) {
            msg.setTextColor(AMBER)
            msg.text = "Informe o usuário e a senha."
            return
        }
        busy = true
        btn.text = "Entrando..."
        btn.alpha = 0.6f
        msg.setTextColor(MUTED)
        msg.text = ""
        Thread {
            val r = License.login(this, u, p)
            runOnUiThread {
                busy = false
                btn.text = "Entrar"
                btn.alpha = 1f
                if (r.ok) {
                    goMain()
                } else {
                    msg.setTextColor(RED)
                    msg.text = r.msg
                    updBtnRef?.visibility = if (License.needsUpdate(this)) View.VISIBLE else View.GONE
                    passF.setText("")
                }
            }
        }.start()
    }
}
