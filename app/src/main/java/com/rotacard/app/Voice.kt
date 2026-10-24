package com.rotacard.app

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Fala o veredito em voz alta (texto para fala do Android, em portugues). */
class Voice(private val ctx: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun speak(text: String) {
        try {
            val t = tts
            if (t == null) {
                pending = text
                tts = TextToSpeech(ctx) { st ->
                    if (st == TextToSpeech.SUCCESS) {
                        tts?.language = Locale.forLanguageTag("pt-BR")
                        ready = true
                        pending?.let { p -> tts?.speak(p, TextToSpeech.QUEUE_FLUSH, null, "rc") }
                        pending = null
                    }
                }
            } else if (ready) {
                t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rc")
            } else {
                pending = text
            }
        } catch (_: Throwable) { }
    }

    fun speakVerdict(o: Offer, s: CardSettings) {
        val v = CardPrefs.verdict(o, s)
        val word = when (v.code) { 2 -> "Aceitar"; 1 -> "Pensar"; else -> "Recusar" }
        speak(word + ". " + String.format(Locale.US, "%.2f", o.perKm).replace('.', ',') + " reais por quilômetro.")
    }

    fun shutdown() {
        try { tts?.shutdown() } catch (_: Throwable) { }
        tts = null
        ready = false
    }
}
