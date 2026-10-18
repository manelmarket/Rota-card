package com.rotacard.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Botao "Pausar/Retomar" da notificacao. */
class NotifyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val p = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        p.edit().putBoolean("enabled", !p.getBoolean("enabled", true)).apply()
        CardService.instance?.refreshNotification()
    }
}
