package com.rotacard.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.tan

/** Conversao entre lat/lon e pixels do mapa (projecao Web Mercator, blocos de 256 px). */
object TileMath {
    const val TILE = 256.0
    fun size(z: Int) = TILE * (1 shl z)
    fun x(lon: Double, z: Int) = (lon + 180.0) / 360.0 * size(z)
    fun y(lat: Double, z: Int): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * size(z)
    }
    fun lon(x: Double, z: Int) = x / size(z) * 360.0 - 180.0
    fun lat(y: Double, z: Int): Double {
        val n = PI - 2.0 * PI * y / size(z)
        return Math.toDegrees(atan(0.5 * (exp(n) - exp(-n))))
    }
}

data class MapMarker(val id: Long, val lat: Double, val lon: Double, val bearing: Double, val color: Int)

/**
 * Mapa simples com os blocos do OpenStreetMap (baixados aos poucos e guardados no aparelho), os
 * semaforos cadastrados e a posicao do carro. Arrastar move o mapa; tocar num semaforo o seleciona.
 */
class TileMapView(context: Context) : View(context) {
    var zoom = 17
        set(v) { field = v.coerceIn(13, 19); invalidate() }
    // ate chegar a posicao do GPS (a tela usa a ultima posicao conhecida do celular)
    var centerLat = -15.7939
    var centerLon = -47.8828
    /** Segue o carro (desliga quando o motorista arrasta o mapa). */
    var follow = true
    var markers: List<MapMarker> = emptyList()
        set(v) { field = v; invalidate() }
    var selectedId = 0L
        set(v) { field = v; invalidate() }
    var onMarkerTap: ((Long) -> Unit)? = null

    private var meLat = Double.NaN
    private var meLon = Double.NaN
    private var meHeading: Double? = null

    private val d = resources.displayMetrics.density
    /** Blocos maiores em telas densas, para as ruas ficarem legiveis. */
    private val scale = maxOf(1.0, d / 2.0)
    private val ts = TileMath.TILE * scale
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10 * d; color = Color.parseColor("#444444") }
    private val path = Path()
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val mem = object : LruCache<String, Bitmap>(48) {}
    private val loading = Collections.synchronizedSet(HashSet<String>())
    private val failed = Collections.synchronizedMap(HashMap<String, Long>())
    private val pool = Executors.newFixedThreadPool(2)
    private val diskDir = File(context.cacheDir, "tiles")

    fun setMe(lat: Double, lon: Double, heading: Double?) {
        meLat = lat; meLon = lon; meHeading = heading
        if (follow) { centerLat = lat; centerLon = lon }
        invalidate()
    }

    fun centerOn(lat: Double, lon: Double) {
        follow = false
        centerLat = lat; centerLon = lon
        invalidate()
    }

    fun recenter() {
        follow = true
        if (!meLat.isNaN()) { centerLat = meLat; centerLon = meLon }
        invalidate()
    }

    fun shutdown() = pool.shutdownNow()

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (300 * d).toInt())
    }

    // ------------------------------------------------------------ blocos

    private fun tile(z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        mem.get(key)?.let { return it }
        val f = File(diskDir, "$key.png")
        if (loading.contains(key)) return null
        val failedAt = failed[key]
        if (failedAt != null && System.currentTimeMillis() - failedAt < 60_000) return null
        loading.add(key)
        pool.execute {
            try {
                var bmp: Bitmap? = null
                if (f.exists() && System.currentTimeMillis() - f.lastModified() < 30L * 24 * 3600 * 1000) {
                    bmp = BitmapFactory.decodeFile(f.path)
                }
                if (bmp == null) {
                    val c = URL("https://tile.openstreetmap.org/$key.png").openConnection() as HttpURLConnection
                    // a politica do OpenStreetMap pede um User-Agent que identifique o app
                    c.setRequestProperty("User-Agent", "RotaCard/2.9 (Android; github.com/manelmarket/Rota-card)")
                    c.connectTimeout = 10_000
                    c.readTimeout = 15_000
                    try {
                        if (c.responseCode == 200) {
                            val bytes = c.inputStream.use { it.readBytes() }
                            f.parentFile?.mkdirs()
                            f.writeBytes(bytes)
                            bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }
                    } finally {
                        c.disconnect()
                    }
                }
                if (bmp != null) { mem.put(key, bmp); failed.remove(key) } else failed[key] = System.currentTimeMillis()
            } catch (_: Throwable) {
                failed[key] = System.currentTimeMillis()
            } finally {
                loading.remove(key)
                postInvalidate()
            }
        }
        return null
    }

    // ------------------------------------------------------------ desenho

    private fun screenX(lon: Double) = ((TileMath.x(lon, zoom) - TileMath.x(centerLon, zoom)) * scale + width / 2.0).toFloat()
    private fun screenY(lat: Double) = ((TileMath.y(lat, zoom) - TileMath.y(centerLat, zoom)) * scale + height / 2.0).toFloat()

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.parseColor("#E5E3DF"))
        val cx = TileMath.x(centerLon, zoom) * scale
        val cy = TileMath.y(centerLat, zoom) * scale
        val left = cx - width / 2.0
        val top = cy - height / 2.0
        val n = 1 shl zoom
        val tx0 = floor(left / ts).toInt()
        val ty0 = floor(top / ts).toInt()
        val tx1 = floor((left + width) / ts).toInt()
        val ty1 = floor((top + height) / ts).toInt()
        for (tx in tx0..tx1) for (ty in ty0..ty1) {
            if (ty < 0 || ty >= n) continue
            val wx = Math.floorMod(tx, n)
            val bmp = tile(zoom, wx, ty) ?: continue
            val sx = (tx * ts - left).toFloat()
            val sy = (ty * ts - top).toFloat()
            c.drawBitmap(bmp, null, android.graphics.RectF(sx, sy, sx + ts.toFloat(), sy + ts.toFloat()), tilePaint)
        }

        // semaforos: bolinha na cor da previsao e um tracinho na direcao de quem chega
        for (m in markers) {
            val x = screenX(m.lon)
            val y = screenY(m.lat)
            if (x < -30 || y < -30 || x > width + 30 || y > height + 30) continue
            val r = (if (m.id == selectedId) 11 else 8) * d
            if (!m.bearing.isNaN()) {
                val a = Math.toRadians(m.bearing + 180.0) // de onde o carro vem
                paint.color = Color.parseColor("#263238")
                paint.strokeWidth = 3 * d
                paint.style = Paint.Style.STROKE
                c.drawLine(x, y, x + (sin(a) * 22 * d).toFloat(), y - (cos(a) * 22 * d).toFloat(), paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = m.color
            c.drawCircle(x, y, r, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2 * d
            paint.color = if (m.id == selectedId) Color.parseColor("#2DD4F0") else Color.WHITE
            c.drawCircle(x, y, r, paint)
        }

        // o carro
        if (!meLat.isNaN()) {
            val x = screenX(meLon)
            val y = screenY(meLat)
            val h = meHeading
            paint.style = Paint.Style.FILL
            if (h != null) {
                val a = Math.toRadians(h)
                fun px(ang: Double, len: Float) = x + (sin(a + ang) * len).toFloat()
                fun py(ang: Double, len: Float) = y - (cos(a + ang) * len).toFloat()
                path.reset()
                path.moveTo(px(0.0, 14 * d), py(0.0, 14 * d))
                path.lineTo(px(2.5, 9 * d), py(2.5, 9 * d))
                path.lineTo(px(PI, 4 * d), py(PI, 4 * d))
                path.lineTo(px(-2.5, 9 * d), py(-2.5, 9 * d))
                path.close()
                paint.color = Color.parseColor("#0EA5C6")
                c.drawPath(path, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2 * d
                paint.color = Color.WHITE
                c.drawPath(path, paint)
            } else {
                paint.color = Color.parseColor("#0EA5C6")
                c.drawCircle(x, y, 8 * d, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2 * d
                paint.color = Color.WHITE
                c.drawCircle(x, y, 8 * d, paint)
            }
        }

        // atribuicao exigida pelo OpenStreetMap
        val attr = "© colaboradores do OpenStreetMap"
        val tw = text.measureText(attr)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(200, 255, 255, 255)
        c.drawRect(width - tw - 8 * d, height - 16 * d, width.toFloat(), height.toFloat(), paint)
        c.drawText(attr, width - tw - 4 * d, height - 4 * d, text)
    }

    // ------------------------------------------------------------ toque

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (Math.abs(e.x - downX) > 8 * d || Math.abs(e.y - downY) > 8 * d)) dragging = true
                if (dragging) {
                    follow = false
                    val nx = TileMath.x(centerLon, zoom) - (e.x - lastX) / scale
                    val ny = TileMath.y(centerLat, zoom) - (e.y - lastY) / scale
                    centerLon = TileMath.lon(nx, zoom)
                    centerLat = TileMath.lat(ny, zoom)
                    lastX = e.x; lastY = e.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!dragging) {
                    val hit = markers.minByOrNull {
                        val dx = screenX(it.lon) - e.x
                        val dy = screenY(it.lat) - e.y
                        dx * dx + dy * dy
                    }
                    if (hit != null) {
                        val dx = screenX(hit.lon) - e.x
                        val dy = screenY(hit.lat) - e.y
                        if (dx * dx + dy * dy <= (28 * d) * (28 * d)) { selectedId = hit.id; onMarkerTap?.invoke(hit.id) }
                    }
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
