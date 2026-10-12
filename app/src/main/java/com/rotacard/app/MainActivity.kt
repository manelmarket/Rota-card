package com.rotacard.app

import android.app.Activity
import android.content.Intent
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

class MainActivity : Activity() {
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
        val lastView = TextView(this).apply { textSize = 11f; setTextIsSelectable(true) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            addView(TextView(context).apply { text = "Rota Card v1.1"; textSize = 24f })
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
            addView(debug)
            addView(Button(context).apply {
                text = "Ver ultimo texto lido da oferta"
                setOnClickListener {
                    lastView.text = p.getString("last", "(nada lido ainda)")
                }
            })
            addView(lastView)
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
