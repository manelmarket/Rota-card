package com.rotacard.app

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Relatorios das corridas aceitas: planilha (CSV) e PDF (com ou sem os prints). */
object Report {
    private val br = Locale.forLanguageTag("pt-BR")

    private fun dir(ctx: Context): File {
        val d = File(ctx.cacheDir, "reports")
        d.mkdirs()
        return d
    }

    fun csv(ctx: Context, rides: List<Ride>): File {
        val f = File(dir(ctx), "rotacard-corridas.csv")
        val dateF = SimpleDateFormat("dd/MM/yyyy", br)
        val timeF = SimpleDateFormat("HH:mm", br)
        val sb = StringBuilder()
        sb.append('\uFEFF')
        sb.append("Data;Hora;App;Valor (R$);Distancia total (km);Busca (km);Corrida (km);Tempo total (min);R$/km;R$/h;Nota;Liquido (R$);Provavel\n")
        for (r in rides.sortedBy { it.id }) {
            val d = Date(r.id)
            val cols = ArrayList<String>()
            cols.add(dateF.format(d))
            cols.add(timeF.format(d))
            cols.add(r.app)
            if (r.hasData) {
                cols.add(Fmt.d2(r.price))
                cols.add(Fmt.d1(r.totalKm))
                cols.add(Fmt.d1(r.pickupKm))
                cols.add(Fmt.d1(r.tripKm))
                cols.add(if (r.totalMin > 0.0) Fmt.n0(r.totalMin) else "")
                cols.add(r.perKm?.let { Fmt.d2(it) } ?: "")
                cols.add(r.perHour?.let { Fmt.n0(it) } ?: "")
                cols.add(r.rating?.let { Fmt.d2(it) } ?: "")
                cols.add(if (r.cost > 0.0) Fmt.d2(r.price - r.cost) else "")
            } else {
                for (i in 0 until 9) cols.add("")
            }
            cols.add(if (r.guess) "sim" else "")
            sb.append(cols.joinToString(";")).append('\n')
        }
        f.writeText(sb.toString(), Charsets.UTF_8)
        return f
    }

    fun pdf(ctx: Context, rides: List<Ride>, withShots: Boolean, periodLabel: String): File {
        val f = File(dir(ctx), if (withShots) "rotacard-relatorio-com-prints.pdf" else "rotacard-relatorio.pdf")
        val doc = PdfDocument()
        val w = 595
        val h = 842
        val m = 36f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
        var canvas = page.canvas
        var y = m

        fun newPage() {
            doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
            canvas = page.canvas
            y = m
        }

        fun text(s: String, x: Float, yy: Float, size: Float, bold: Boolean, color: Int) {
            paint.textSize = size
            paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.color = color
            canvas.drawText(s, x, yy, paint)
        }

        val data = rides.filter { it.hasData }
        val total = data.sumOf { it.price }
        val cost = data.sumOf { it.cost }
        val km = data.sumOf { it.totalKm }
        val timed = data.filter { it.totalMin > 0.0 }
        val hours = timed.sumOf { it.totalMin } / 60.0
        val nowTxt = SimpleDateFormat("dd/MM/yyyy HH:mm", br).format(Date())

        text("Rota Card - Relatório de corridas", m, y + 16f, 18f, true, Color.BLACK)
        y += 32f
        text("Período: " + periodLabel + "   •   gerado em " + nowTxt, m, y, 10f, false, Color.DKGRAY)
        y += 24f
        text("Corridas aceitas: " + rides.size, m, y, 12f, false, Color.BLACK); y += 16f
        text("Total ganho: " + Fmt.money(total), m, y, 12f, true, Color.BLACK); y += 16f
        if (cost > 0.0) {
            text("Líquido (após custo por km): " + Fmt.money(total - cost), m, y, 12f, false, Color.BLACK)
            y += 16f
        }
        val avgKm = if (km > 0.0) "R$ " + Fmt.d2(total / km) else "--"
        val avgH = if (hours > 0.0) "R$ " + Fmt.n0(timed.sumOf { it.price } / hours) else "--"
        text("Média: " + avgKm + "/km   •   " + avgH + "/h   •   " + Fmt.d1(km) + " km rodados", m, y, 12f, false, Color.BLACK)
        y += 26f

        val xs = floatArrayOf(36f, 126f, 186f, 246f, 296f, 356f, 416f, 476f)
        val heads = listOf("Data/hora", "App", "Valor", "Km", "R$/km", "R$/h", "Nota", "Líquido")

        fun header() {
            for (i in xs.indices) text(heads[i], xs[i], y, 10f, true, Color.BLACK)
            paint.color = Color.GRAY
            canvas.drawLine(m, y + 4f, w - m, y + 4f, paint)
            y += 18f
        }
        header()

        val dtF = SimpleDateFormat("dd/MM HH:mm", br)
        for (r in rides) {
            if (y > h - m - 16f) {
                newPage()
                header()
            }
            text(dtF.format(Date(r.id)), xs[0], y, 9f, false, Color.BLACK)
            text(r.app + (if (r.guess) "*" else ""), xs[1], y, 9f, false, Color.BLACK)
            if (r.hasData) {
                text(Fmt.d2(r.price), xs[2], y, 9f, false, Color.BLACK)
                text(Fmt.d1(r.totalKm), xs[3], y, 9f, false, Color.BLACK)
                text(r.perKm?.let { Fmt.d2(it) } ?: "-", xs[4], y, 9f, false, Color.BLACK)
                text(r.perHour?.let { Fmt.n0(it) } ?: "-", xs[5], y, 9f, false, Color.BLACK)
                text(r.rating?.let { Fmt.d2(it) } ?: "-", xs[6], y, 9f, false, Color.BLACK)
                text(if (r.cost > 0.0) Fmt.d2(r.price - r.cost) else "-", xs[7], y, 9f, false, Color.BLACK)
            } else {
                text("(oferta não lida)", xs[2], y, 9f, false, Color.GRAY)
            }
            y += 14f
        }
        if (rides.any { it.guess }) {
            y += 6f
            text("* aceite provável (detectado pelo sumiço da oferta)", m, y, 8f, false, Color.GRAY)
        }

        if (withShots) {
            val cols = 3
            val gap = 10f
            val cellW = (w - 2 * m - gap * (cols - 1)) / cols
            val cellH = (h - 2 * m - gap) / 2f
            val withShot = rides.filter { r ->
                r.shot != null && History.shotFile(ctx, r.shot).exists()
            }.take(60)
            newPage()
            text("Prints das corridas", m, y + 12f, 14f, true, Color.BLACK)
            if (withShot.isEmpty()) text("Nenhum print disponível neste período.", m, y + 34f, 10f, false, Color.DKGRAY)
            y = m + 24f
            val cellTop0 = y
            val cellH2 = (h - m - cellTop0 - gap) / 2f
            for ((i, r) in withShot.withIndex()) {
                val slot = i % 6
                if (i > 0 && slot == 0) {
                    newPage()
                }
                val top0 = if (i < 6) cellTop0 else m
                val ch = if (i < 6) cellH2 else cellH
                val cx = m + (slot % cols) * (cellW + gap)
                val cy = top0 + (slot / cols) * (ch + gap)
                try {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    val bmp = BitmapFactory.decodeFile(History.shotFile(ctx, r.shot!!).path, opts)
                    if (bmp != null) {
                        val area = ch - 30f
                        val sc = minOf(cellW / bmp.width, area / bmp.height)
                        val dw = bmp.width * sc
                        val dh = bmp.height * sc
                        canvas.drawBitmap(bmp, null, RectF(cx + (cellW - dw) / 2f, cy, cx + (cellW + dw) / 2f, cy + dh), paint)
                        bmp.recycle()
                    }
                } catch (_: Throwable) { }
                text(dtF.format(Date(r.id)) + " • " + r.app, cx, cy + ch - 18f, 8f, true, Color.BLACK)
                val line2 = if (r.hasData)
                    Fmt.money(r.price) + " • R$ " + Fmt.d2(r.perKm ?: 0.0) + "/km" +
                            (r.perHour?.let { " • R$ " + Fmt.n0(it) + "/h" } ?: "")
                else "oferta não lida"
                text(line2, cx, cy + ch - 7f, 8f, false, Color.DKGRAY)
            }
        }

        doc.finishPage(page)
        FileOutputStream(f).use { doc.writeTo(it) }
        doc.close()
        return f
    }
}
