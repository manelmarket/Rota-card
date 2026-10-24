package com.rotacard.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

data class CardSettings(
    val green: Float,
    val yellow: Float,
    val minRating: Float,
    val textScale: Float,
    val opacity: Float,
    val atBottom: Boolean,
    val vibrate: Boolean,
    val minHour: Float,
    val maxPickup: Float,
    val costKm: Float,
    val showVerdict: Boolean,
    val voice: Boolean,
    val cardY: Float
)

data class Verdict(val code: Int, val text: String)

object CardPrefs {
    /** Se "app" for informado, usa as faixas proprias daquele app (ou as gerais, se nao houver). */
    fun load(ctx: Context, app: String? = null): CardSettings {
        val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val sf = if (app == null) "" else "_$app"
        val g = p.getFloat("green" + sf, p.getFloat("green", License.dflt(ctx, "green", 2.0f)))
        val y = p.getFloat("yellow" + sf, p.getFloat("yellow", License.dflt(ctx, "yellow", 1.5f)))
        return CardSettings(
            g, y, p.getFloat("minRating", License.dflt(ctx, "minRating", 4.8f)),
            p.getFloat("textScale", 1.0f), p.getFloat("opacity", 1.0f),
            p.getBoolean("atBottom", false), p.getBoolean("vibrate", true),
            p.getFloat("minHour", License.dflt(ctx, "minHour", 40f)),
            p.getFloat("maxPickup", License.dflt(ctx, "maxPickup", 3f)),
            p.getFloat("costKm", 0f),
            p.getBoolean("showVerdict", true), p.getBoolean("voice", false),
            p.getFloat("cardY", -1f)
        )
    }

    /** Lucro estimado depois do custo por km (null se o custo nao foi informado). */
    fun net(o: Offer, s: CardSettings): Double? =
        if (s.costKm > 0f) o.price - s.costKm * o.totalKm else null

    /** Aceitar, Pensar ou Recusar, juntando R$/km, R$/h, busca e nota. */
    fun verdict(o: Offer, s: CardSettings): Verdict {
        val reasons = ArrayList<String>()
        val h = o.perHour
        if (s.minHour > 0f && h != null && h < s.minHour) reasons.add("R$/h baixo")
        if (s.maxPickup > 0f && o.pickupKm > s.maxPickup) reasons.add("busca longa")
        val r = o.rating
        if (r != null && r < s.minRating) reasons.add("nota baixa")
        val level = level(o.perKm, s)
        val code = when {
            level == 0 -> 0
            level == 2 && reasons.isEmpty() -> 2
            reasons.size >= 2 -> 0
            else -> 1
        }
        val head = when (code) { 2 -> "✔ ACEITAR"; 1 -> "● PENSAR"; else -> "✖ RECUSAR" }
        val txt = if (reasons.isEmpty()) head else head + ": " + reasons.joinToString(", ")
        return Verdict(code, txt)
    }

    /** 2 = verde, 1 = amarelo, 0 = vermelho */
    fun level(perKm: Double, s: CardSettings): Int =
        if (perKm >= s.green) 2 else if (perKm >= s.yellow) 1 else 0

    /** Oferta de exemplo para a previa e o teste. level: 0 = verde, 1 = amarelo, 2 = vermelho */
    fun sample(level: Int, s: CardSettings): Offer {
        val perKm = when (level) {
            0 -> s.green * 1.25f
            1 -> (s.green + s.yellow) / 2f
            else -> s.yellow * 0.7f
        }
        val rating = if (level == 2) (s.minRating - 0.3f).toDouble() else 4.9
        return Offer("Uber", perKm.toDouble() * 5.6, rating, 1.1, 4.5, 4.0, 11.0, false)
    }
}

object Haptics {
    @Suppress("DEPRECATION")
    fun pulse(ctx: Context) {
        try {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 90, 70, 90), -1))
        } catch (_: Exception) {
        }
    }

    /** Vibracao curta de confirmacao (marcacao do semaforo). */
    @Suppress("DEPRECATION")
    fun tap(ctx: Context) {
        try {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }
}

/** O cartao em si. Usado pelo servico (por cima dos apps) e pela previa dentro do app. */
class CardUi(ctx: Context) {
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private val green = Color.parseColor("#1B8E3E")
    private val amber = Color.parseColor("#E0A100")
    private val red = Color.parseColor("#C62828")

    private val big = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        textSize = 26f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER
        maxLines = 1
    }
    private val small = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        maxLines = 1
    }
    private val verdictTv = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER
        maxLines = 1
    }
    val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(10))
        addView(big, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(small, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(verdictTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private var lastScale = -1f

    /** base = R$/km medio do motorista neste app (null = nao mostrar a comparacao). */
    fun bind(o: Offer, s: CardSettings, base: Double? = null) {
        val color = when (CardPrefs.level(o.perKm, s)) { 2 -> green; 1 -> amber; else -> red }
        val dark = Color.rgb(
            (Color.red(color) * 0.68f).toInt(),
            (Color.green(color) * 0.68f).toInt(),
            (Color.blue(color) * 0.68f).toInt()
        )
        root.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(color, dark)).apply {
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), Color.argb(80, 255, 255, 255))
        }

        if (s.textScale != lastScale) {
            lastScale = s.textScale
            big.setAutoSizeTextTypeUniformWithConfiguration(
                (14 * s.textScale).toInt().coerceAtLeast(10), (26 * s.textScale).toInt(), 1, TypedValue.COMPLEX_UNIT_SP
            )
            small.setAutoSizeTextTypeUniformWithConfiguration(
                (9 * s.textScale).toInt().coerceAtLeast(8), (14 * s.textScale).toInt(), 1, TypedValue.COMPLEX_UNIT_SP
            )
            verdictTv.setAutoSizeTextTypeUniformWithConfiguration(
                (9 * s.textScale).toInt().coerceAtLeast(8), (13 * s.textScale).toInt(), 1, TypedValue.COMPLEX_UNIT_SP
            )
        }

        val ratingTxt = o.rating?.let {
            String.format(Locale.US, "★ %.2f", it) + if (it < s.minRating) " ⚠" else ""
        } ?: "★ --"
        val til = if (o.minEstimated) "~" else ""

        val bigTxt = SpannableStringBuilder(
            String.format(Locale.US, "R$ %.2f/km", o.perKm).replace('.', ',')
        )
        o.perHour?.let { h ->
            val start = bigTxt.length
            bigTxt.append("  •  " + til + String.format(Locale.US, "R$ %.0f/h", h))
            bigTxt.setSpan(RelativeSizeSpan(0.78f), start, bigTxt.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        big.text = bigTxt
        small.text = String.format(
            Locale.US, "%s  •  %.1f km  •  %s%.0f min  •  R$ %.2f  •  %s",
            ratingTxt, o.totalKm, til, o.totalMin, o.price, o.app
        ).replace('.', ',')

        val parts = ArrayList<String>()
        if (s.showVerdict) {
            parts.add(CardPrefs.verdict(o, s).text)
            CardPrefs.net(o, s)?.let { parts.add("líquido " + Fmt.money(it)) }
        }
        if (base != null && base > 0.0) {
            val d = Baseline.deltaPct(o.perKm, base)
            parts.add(
                when {
                    d > 0 -> "▲ $d% acima da sua média"
                    d < 0 -> "▼ ${-d}% abaixo da sua média"
                    else -> "na sua média"
                }
            )
        }
        if (parts.isEmpty()) {
            verdictTv.visibility = View.GONE
        } else {
            verdictTv.visibility = View.VISIBLE
            verdictTv.text = parts.joinToString("  •  ")
        }
    }
}
