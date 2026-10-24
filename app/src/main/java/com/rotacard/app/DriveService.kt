package com.rotacard.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.drawable.Icon
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import java.util.Locale

/** O que o modo semaforos sabe agora (para a tela e o cartao). */
data class DriveState(
    val hasFix: Boolean,
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val speedKmh: Double,
    val heading: Double?,
    val light: TrafficLight?,
    val distance: Double,
    val timing: Timing?,
    val prediction: Prediction?,
    val text: String,
    val pending: Int
)

/**
 * Modo semaforos: servico em primeiro plano com GPS. Acha o semaforo cadastrado a frente, mostra a
 * previsao por cima de qualquer app (pelo servico de acessibilidade, se ligado), fala ao chegar e
 * registra as marcacoes de verde/amarelo/vermelho (pela tela ou pelos botoes da notificacao).
 */
class DriveService : Service(), LocationListener {

    companion object {
        const val ACTION_START = "com.rotacard.app.DRIVE_START"
        const val ACTION_STOP = "com.rotacard.app.DRIVE_STOP"
        const val ACTION_MARK = "com.rotacard.app.DRIVE_MARK"
        const val EXTRA_PHASE = "phase"
        const val EXTRA_AT = "at"
        private const val NOTIF_ID = 2
        private const val CHANNEL = "rotacard_semaforos"

        val GREEN = Color.parseColor("#1B8E3E")
        val AMBER = Color.parseColor("#E0A100")
        val RED = Color.parseColor("#C62828")
        val GRAY = Color.parseColor("#455A64")

        @Volatile var instance: DriveService? = null
            private set
        @Volatile var state: DriveState? = null
            private set
        /** A tela de semaforos se inscreve aqui (chamado na thread principal, 1 vez por segundo). */
        var listener: ((DriveState) -> Unit)? = null

        fun hasPermission(ctx: Context) =
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Liga o modo. Devolve false se falta a permissao de localizacao. */
        fun start(ctx: Context): Boolean {
            if (!hasPermission(ctx)) return false
            ctx.startForegroundService(Intent(ctx, DriveService::class.java).setAction(ACTION_START))
            return true
        }

        fun stop(ctx: Context) {
            if (instance == null) return
            ctx.startService(Intent(ctx, DriveService::class.java).setAction(ACTION_STOP))
        }

        /** Marca a cor agora (o instante e o do toque, mesmo que o GPS demore). */
        fun mark(ctx: Context, phase: Phase, at: Long = System.currentTimeMillis()): Boolean {
            if (!hasPermission(ctx)) return false
            ctx.startForegroundService(
                Intent(ctx, DriveService::class.java).setAction(ACTION_MARK)
                    .putExtra(EXTRA_PHASE, phase.name).putExtra(EXTRA_AT, at)
            )
            return true
        }

        fun colorFor(p: Prediction?): Int {
            if (p == null || p.confidence == Confidence.LOW || p.confidence == Confidence.NONE) return GRAY
            return when (p.phase) { Phase.GREEN -> GREEN; Phase.YELLOW -> AMBER; Phase.RED -> RED; null -> GRAY }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var lm: LocationManager
    private var started = false
    private lateinit var voice: Voice

    private var fix: Location? = null
    private var fixAt = 0L
    private var prevFix: Location? = null
    private var heading: Double? = null
    private val pending = ArrayList<Pair<Phase, Long>>()
    private var pendingSince = 0L
    private var lastMarkedId = 0L
    private var lastMarkedAt = 0L

    private var announcedId = 0L
    private var announcedAt = 0L
    private var lastNotifText = ""
    private var lastNotifAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            try { update() } catch (_: Throwable) { }
            if (started) handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        voice = Voice(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            if (!hasPermission(this)) { stopSelf(); return START_NOT_STICKY }
            val n = buildNotification("Procurando o GPS…", "")
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIF_ID, n)
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 0f, this, Looper.getMainLooper())
            } catch (_: SecurityException) {
                stopSelf(); return START_NOT_STICKY
            } catch (_: Exception) { }
            started = true
            instance = this
            handler.post(tick)
        }
        if (intent?.action == ACTION_MARK) {
            val p = intent.getStringExtra(EXTRA_PHASE)?.let { runCatching { Phase.valueOf(it) }.getOrNull() }
            if (p != null) {
                pending.add(p to intent.getLongExtra(EXTRA_AT, System.currentTimeMillis()))
                if (pendingSince == 0L) pendingSince = SystemClock.elapsedRealtime()
                Haptics.tap(this)
                applyPending()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        started = false
        handler.removeCallbacksAndMessages(null)
        try { lm.removeUpdates(this) } catch (_: Exception) { }
        CardService.instance?.hideLight()
        voice.shutdown()
        if (instance === this) instance = null
        state = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ GPS

    override fun onLocationChanged(loc: Location) {
        // o GPS manda; a rede so serve enquanto o GPS nao chegou ou esta muito velho
        val now = SystemClock.elapsedRealtime()
        if (loc.provider == LocationManager.NETWORK_PROVIDER && fix?.provider == LocationManager.GPS_PROVIDER && now - fixAt < 10_000) return
        val prev = fix
        val speed = if (loc.hasSpeed()) loc.speed.toDouble()
        else if (prev != null && loc.time > prev.time) prev.distanceTo(loc) / ((loc.time - prev.time) / 1000.0) else 0.0
        if (loc.hasBearing() && speed > 2.0) {
            heading = loc.bearing.toDouble()
        } else {
            val ref = prevFix
            if (ref != null && speed > 1.5 && ref.distanceTo(loc) > 10f) {
                heading = Geo.bearing(ref.latitude, ref.longitude, loc.latitude, loc.longitude)
                prevFix = loc
            } else if (ref == null || ref.distanceTo(loc) > 30f) {
                prevFix = loc
            }
        }
        fix = loc
        fixAt = now
        applyPending()
        update()
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun goodFix(): Location? {
        val f = fix ?: return null
        if (SystemClock.elapsedRealtime() - fixAt > 15_000) return null
        if (f.hasAccuracy() && f.accuracy > 40f) return null
        return f
    }

    private fun applyPending() {
        if (pending.isEmpty()) return
        val f = goodFix()
        if (f == null) {
            if (SystemClock.elapsedRealtime() - pendingSince > 30_000) {
                pending.clear(); pendingSince = 0L
                Toast.makeText(this, "Sem GPS bom: marcação descartada", Toast.LENGTH_LONG).show()
            }
            return
        }
        val list = ArrayList(pending)
        pending.clear(); pendingSince = 0L
        for ((p, at) in list) {
            val (light, isNew) = LightStore.addMark(this, f.latitude, f.longitude, heading, p, at)
            lastMarkedId = light.id
            lastMarkedAt = SystemClock.elapsedRealtime()
            val t = Semaforos.analyze(light.marks, System.currentTimeMillis())
            val cor = when (p) { Phase.GREEN -> "Verde"; Phase.YELLOW -> "Amarelo"; Phase.RED -> "Vermelho" }
            val progress = when {
                t.cycleMs == null -> "falta fechar o 1º ciclo"
                t.cycleSamples < Semaforos.RECOMMENDED_CYCLES -> "ciclos medidos: ${t.cycleSamples} de ${Semaforos.RECOMMENDED_CYCLES}"
                else -> "ciclo de ${(t.cycleMs + 500) / 1000} s"
            }
            Toast.makeText(this, "$cor marcado • ${light.name}${if (isNew) " (novo)" else ""} • $progress", Toast.LENGTH_SHORT).show()
            if (isNew) nameFromStreet(light)
        }
        update()
    }

    /** Da ao sinal novo o nome da rua (pelo Geocoder do Android) e o sentido. */
    private fun nameFromStreet(l: TrafficLight) {
        if (!Geocoder.isPresent()) return
        val app = applicationContext
        Thread {
            try {
                @Suppress("DEPRECATION")
                val a = Geocoder(app, Locale.forLanguageTag("pt-BR")).getFromLocation(l.lat, l.lon, 1)?.firstOrNull()
                val street = a?.thoroughfare ?: return@Thread
                val dir = Semaforos.cardinal(l.bearing)?.let { " (sentido $it)" } ?: ""
                val cur = LightStore.get(app, l.id) ?: return@Thread
                if (cur.name.startsWith("Semáforo ")) LightStore.rename(app, l.id, street + dir)
            } catch (_: Throwable) { }
        }.start()
    }

    // ------------------------------------------------------------ previsao

    private fun update() {
        val f = fix
        val fresh = f != null && SystemClock.elapsedRealtime() - fixAt <= 20_000
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val nowWall = System.currentTimeMillis()
        val speed = if (f != null && f.hasSpeed()) f.speed * 3.6 else 0.0
        var light: TrafficLight? = null
        var dist = 0.0
        if (fresh && f != null) {
            val lights = LightStore.load(this)
            val hit = Semaforos.ahead(lights, f.latitude, f.longitude, heading)
            if (hit != null) { light = hit.first; dist = hit.second }
            // medindo um sinal (parado nele): continua mostrando o que acabou de ser marcado
            if (light == null && SystemClock.elapsedRealtime() - lastMarkedAt < 10 * 60_000) {
                LightStore.get(this, lastMarkedId)?.let { l ->
                    val d = Geo.distM(f.latitude, f.longitude, l.lat, l.lon)
                    if (d <= 60.0) { light = l; dist = d }
                }
            }
        }
        val l = light
        val timing = l?.let { Semaforos.analyze(it.marks, nowWall) }
        val pred = timing?.let { Semaforos.predict(it, nowWall) }
        val desc = if (l != null && timing != null) Semaforos.describe(pred, timing) else ""
        val text = when {
            !fresh -> "Procurando o GPS…"
            l == null -> "Nenhum semáforo cadastrado à frente"
            else -> l.name + " • " + desc
        }
        val st = DriveState(
            fresh, f?.latitude ?: 0.0, f?.longitude ?: 0.0, if (f != null && f.hasAccuracy()) f.accuracy else 0f,
            speed, heading, l, dist, timing, pred, text, pending.size
        )
        state = st
        listener?.invoke(st)

        // cartao por cima dos apps
        val confident = pred != null && (pred.confidence == Confidence.HIGH || pred.confidence == Confidence.MEDIUM)
        val svc = CardService.instance
        if (svc != null) {
            if (l != null && prefs.getBoolean("semOverlay", true) && (confident || dist <= 40.0)) {
                val dTxt = if (dist > 40.0) "  •  " + ((dist / 10).toInt() * 10) + " m" else ""
                svc.showLight("🚦 " + desc.replaceFirstChar { it.uppercase() } + dTxt, colorFor(pred))
            } else {
                svc.hideLight()
            }
        }

        // voz: uma vez por chegada, entre 60 e 180 m, andando
        val nowMs = SystemClock.elapsedRealtime()
        if (l != null && confident && prefs.getBoolean("semVoice", true) && dist in 60.0..180.0 && speed > 10.0 &&
            !(announcedId == l.id && nowMs - announcedAt < 90_000)) {
            announcedId = l.id
            announcedAt = nowMs
            voice.speak("Semáforo. " + desc.replace(Regex("""~?(\d+) s$""")) { m ->
                (if (m.value.startsWith("~")) "cerca de " else "") + m.groupValues[1] + " segundos"
            })
        }

        // notificacao (no maximo a cada 2 s)
        if (text != lastNotifText && nowMs - lastNotifAt >= 2000) {
            lastNotifText = text
            lastNotifAt = nowMs
            try {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIF_ID, buildNotification(if (l != null) l.name else "Modo semáforos ligado", if (l != null) desc else text))
            } catch (_: Throwable) { }
        }
    }

    // ------------------------------------------------------------ notificacao

    private fun buildNotification(title: String, text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Modo semáforos", NotificationManager.IMPORTANCE_LOW))
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        fun markAction(p: Phase, label: String, code: Int): Notification.Action {
            val i = Intent(this, DriveService::class.java).setAction(ACTION_MARK).putExtra(EXTRA_PHASE, p.name)
            // o instante vai sem EXTRA_AT: conta o momento em que o toque chega aqui
            return Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stat), label, PendingIntent.getService(this, code, i, flags)
            ).build()
        }
        val open = PendingIntent.getActivity(this, 20, Intent(this, SemaforoActivity::class.java), flags)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(markAction(Phase.GREEN, "Abriu verde", 21))
            .addAction(markAction(Phase.YELLOW, "Amarelo", 22))
            .addAction(markAction(Phase.RED, "Fechou", 23))
            .build()
    }
}
