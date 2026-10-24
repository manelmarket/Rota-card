package com.rotacard.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Grava os prints: das corridas aceitas (galeria + historico) e das ofertas nao lidas (privado). */
object ShotSaver {
    private const val FAIL_DIR = "fails"
    private const val FAIL_MAX_AGE_MS = 7L * 24 * 3600 * 1000
    private const val FAIL_MAX_FILES = 60

    /**
     * Salva o print na galeria (Pictures/RotaCard), uma copia menor no app e registra no historico.
     * Roda numa thread propria. done recebe a mensagem para o Toast.
     */
    fun saveAccepted(
        ctx: Context, bmp: Bitmap?, o: Offer?, label: String, guess: Boolean,
        done: (String) -> Unit, wantedShot: Boolean = true
    ) {
        val app = ctx.applicationContext
        val id = System.currentTimeMillis()
        Thread {
            var gallery: String? = null
            var thumb: String? = null
            var err: String? = null
            if (bmp != null) {
                try {
                    val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(id))
                    val info = if (o != null) String.format(Locale.US, "_R%.2f_%.2fkm", o.price, o.perKm) else ""
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "Corrida_${ts}_$label$info.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RotaCard")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        app.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        app.contentResolver.update(uri, values, null, null)
                        gallery = uri.toString()
                    }
                } catch (e: Exception) {
                    err = e.javaClass.simpleName
                }
                try {
                    val w = 540
                    val h = (bmp.height * w.toFloat() / bmp.width).toInt()
                    val small = Bitmap.createScaledBitmap(bmp, w, h, true)
                    val f = History.shotFile(app, "$id.jpg")
                    f.parentFile?.mkdirs()
                    f.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                    if (small !== bmp) small.recycle()
                    thumb = "$id.jpg"
                } catch (_: Exception) { }
            }
            if (label != "Teste") {
                try {
                    History.add(app, Ride.from(
                        o, id, label, thumb, gallery, guess,
                        if (o != null) CardPrefs.load(app).costKm * o.totalKm else 0.0
                    ))
                    Baseline.invalidate()
                } catch (t: Throwable) {
                    app.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
                        .putString("lasterr", "historico: " + t.javaClass.simpleName + " " + (t.message ?: "")).apply()
                }
            }
            done(when {
                !wantedShot -> "Corrida salva no histórico"
                bmp == null && o != null -> "Corrida salva, sem print (a oferta já tinha saído da tela)"
                bmp == null -> "Aceite detectado, mas não consegui tirar o print"
                gallery == null && thumb == null -> "Falha ao salvar o print" + (err?.let { ": $it" } ?: "")
                else -> "Corrida salva (galeria: RotaCard)"
            })
        }.start()
    }

    // ------------------------------------------------------------ ofertas nao lidas

    fun failDir(ctx: Context) = File(ctx.filesDir, FAIL_DIR)

    /**
     * Guarda o print de uma oferta que o app nao conseguiu ler. Fica na area privada do app
     * (nao vai para a galeria), porque a tela pode mostrar dados do passageiro.
     */
    fun saveFailure(ctx: Context, bmp: Bitmap?, tag: String) {
        if (bmp == null) return
        val app = ctx.applicationContext
        Thread {
            try {
                val dir = failDir(app)
                dir.mkdirs()
                val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                File(dir, "Falha_${ts}_$tag.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                cleanupFailures(app)
            } catch (_: Throwable) { }
        }.start()
    }

    /** Apaga prints de falha com mais de 7 dias e mantem no maximo 60. */
    fun cleanupFailures(ctx: Context) {
        val files = failDir(ctx).listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        val now = System.currentTimeMillis()
        var left = files.size
        for (f in files) {
            if (now - f.lastModified() > FAIL_MAX_AGE_MS || left > FAIL_MAX_FILES) {
                if (f.delete()) left--
            }
        }
    }

    fun failureCount(ctx: Context): Int = failDir(ctx).listFiles()?.count { it.isFile } ?: 0

    /** Junta os prints de falha num zip (em cache/reports) para o motorista enviar. Null se nao houver. */
    fun zipFailures(ctx: Context): File? {
        val files = failDir(ctx).listFiles()?.filter { it.isFile } ?: return null
        if (files.isEmpty()) return null
        val dir = File(ctx.cacheDir, "reports")
        dir.mkdirs()
        val out = File(dir, "rotacard-falhas.zip")
        ZipOutputStream(FileOutputStream(out)).use { z ->
            for (f in files) {
                z.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        return out
    }

    fun clearFailures(ctx: Context) {
        failDir(ctx).listFiles()?.forEach { it.delete() }
    }
}
