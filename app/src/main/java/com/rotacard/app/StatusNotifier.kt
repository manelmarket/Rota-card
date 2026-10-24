package com.rotacard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.SystemClock

/** Notificacao fixa com o status do servico e o atalho Pausar/Retomar. */
object StatusNotifier {
    private const val ID = 1
    private const val CHANNEL = "rotacard_status"
    @Volatile var lastAt = 0L
        private set

    fun refresh(ctx: Context) {
        try {
            val prefs = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!prefs.getBoolean("notif", true)) { nm.cancel(ID); return }
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Status do Rota Card", NotificationManager.IMPORTANCE_LOW))
            val on = prefs.getBoolean("enabled", true)
            val st = Stats.load(ctx)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val toggle = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, NotifyReceiver::class.java), flags)
            val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java), flags)
            val action = Notification.Action.Builder(
                Icon.createWithResource(ctx, R.drawable.ic_stat),
                if (on) "Pausar" else "Retomar",
                toggle
            ).build()
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(if (on) "Rota Card lendo ofertas" else "Rota Card pausado")
                .setContentText(st.count.toString() + " ofertas hoje • " + st.accepted + " aceitas")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(action)
                .build()
            nm.notify(ID, n)
            lastAt = SystemClock.elapsedRealtime()
        } catch (_: Throwable) { }
    }

    fun cancel(ctx: Context) {
        try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID) } catch (_: Throwable) { }
    }
}
