package com.antivol.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Carte dessinée directement par l'appli (pas de page web) : fond de carte OpenStreetMap / CARTO,
 * point rouge de la position, cercle de précision et trajet. Glisse pour déplacer, pince pour zoomer.
 */
class TileMapView(ctx: Context) : View(ctx) {
    class Fix(val lat: Double, val lon: Double, val acc: Double)

    private var fixes: List<Fix> = emptyList()
    private var cLat = 46.6
    private var cLon = 2.4
    private var zoom = 5.0
    private var centered = false

    private val cache = LruCache<String, Bitmap>(140)
    private val pending = HashSet<String>()
    private val failedAt = HashMap<String, Long>()
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r).apply { isDaemon = true } }
    @Volatile private var tilesOk = 0
    @Volatile private var tilesFail = 0

    private val dp = ctx.resources.displayMetrics.density
    /** Les tuiles font 256 px : on les agrandit un peu sur les écrans denses pour que les noms restent lisibles. */
    private val k = (dp * 0.7).coerceAtLeast(1.0)

    private val bg = Paint().apply { color = 0xFFE5E3DF.toInt() }
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val trail = Paint().apply { color = 0xCC0A3CFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 4 * dp; isAntiAlias = true; strokeJoin = Paint.Join.ROUND }
    private val accFill = Paint().apply { color = 0x200A3CFF; style = Paint.Style.FILL; isAntiAlias = true }
    private val accLine = Paint().apply { color = 0xAA0A3CFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; isAntiAlias = true }
    private val dotFill = Paint().apply { color = 0xFFE11D2E.toInt(); style = Paint.Style.FILL; isAntiAlias = true }
    private val dotRing = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3 * dp; isAntiAlias = true }
    private val label = Paint().apply { color = 0xFF222222.toInt(); textSize = 12 * dp; isAntiAlias = true }
    private val labelBg = Paint().apply { color = 0xE6FFFFFF.toInt() }

    // ---------- Projection (Web Mercator) ----------

    private fun tx(lon: Double, z: Int) = (lon + 180.0) / 360.0 * 2.0.pow(z)
    private fun ty(lat: Double, z: Int): Double {
        val r = Math.toRadians(lat)
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * 2.0.pow(z)
    }
    private fun lonOf(x: Double, z: Int) = x / 2.0.pow(z) * 360.0 - 180.0
    private fun latOf(y: Double, z: Int): Double = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y / 2.0.pow(z)))))

    // ---------- Commandes ----------

    fun setFixes(list: List<Fix>, follow: Boolean) {
        fixes = list
        val l = list.lastOrNull()
        if (l != null) {
            if (!centered) { cLat = l.lat; cLon = l.lon; zoom = 17.0; centered = true }
            else if (follow) { cLat = l.lat; cLon = l.lon }
        }
        postInvalidate()
    }

    fun zoomBy(d: Double) { zoom = (zoom + d).coerceIn(3.0, 19.0); postInvalidate() }

    fun recenter() {
        val l = fixes.lastOrNull() ?: return
        cLat = l.lat; cLon = l.lon
        postInvalidate()
    }

    // ---------- Tuiles ----------

    private fun request(key: String, z: Int, x: Int, y: Int) {
        synchronized(pending) {
            if (key in pending) return
            val f = failedAt[key]
            if (f != null && System.currentTimeMillis() - f < 8_000) return
            pending.add(key)
        }
        pool.execute {
            var bmp: Bitmap? = null
            val urls = listOf(
                "https://${"abcd"[(x + y) % 4]}.basemaps.cartocdn.com/rastertiles/voyager/$z/$x/$y.png",
                "https://tile.openstreetmap.org/$z/$x/$y.png"
            )
            for (u in urls) {
                try {
                    val c = URL(u).openConnection() as HttpURLConnection
                    c.connectTimeout = 8000
                    c.readTimeout = 8000
                    c.setRequestProperty("User-Agent", "AntivolApp/1.0 (personal anti-theft app)")
                    if (c.responseCode == 200) bmp = c.inputStream.use { BitmapFactory.decodeStream(it) }
                    c.disconnect()
                } catch (_: Exception) {}
                if (bmp != null) break
            }
            synchronized(pending) {
                pending.remove(key)
                if (bmp != null) cache.put(key, bmp) else failedAt[key] = System.currentTimeMillis()
            }
            if (bmp != null) tilesOk++ else tilesFail++
            postInvalidate()
        }
    }

    // ---------- Dessin ----------

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, bg)
        val zi = floor(zoom).toInt().coerceIn(3, 19)
        val tile = (256.0 * 2.0.pow(zoom - zi) * k).toFloat()
        val cx = tx(cLon, zi); val cy = ty(cLat, zi)
        val n = 1 shl zi
        val x0 = floor(cx - (w / 2) / tile).toInt(); val x1 = floor(cx + (w / 2) / tile).toInt()
        val y0 = floor(cy - (h / 2) / tile).toInt(); val y1 = floor(cy + (h / 2) / tile).toInt()
        for (xx in x0..x1) for (yy in y0..y1) {
            if (yy < 0 || yy >= n) continue
            val xw = ((xx % n) + n) % n
            val left = w / 2 + ((xx - cx) * tile).toFloat()
            val top = h / 2 + ((yy - cy) * tile).toFloat()
            val key = "$zi/$xw/$yy"
            val b = cache.get(key)
            if (b != null) c.drawBitmap(b, null, RectF(left, top, left + tile, top + tile), tilePaint)
            else request(key, zi, xw, yy)
        }

        fun sx(lon: Double) = w / 2 + ((tx(lon, zi) - cx) * tile).toFloat()
        fun sy(lat: Double) = h / 2 + ((ty(lat, zi) - cy) * tile).toFloat()

        if (fixes.size > 1) {
            val p = Path()
            fixes.forEachIndexed { i, f -> if (i == 0) p.moveTo(sx(f.lon), sy(f.lat)) else p.lineTo(sx(f.lon), sy(f.lat)) }
            c.drawPath(p, trail)
        }
        val last = fixes.lastOrNull()
        if (last != null) {
            val px = sx(last.lon); val py = sy(last.lat)
            val mpp = 156543.03392 * cos(Math.toRadians(last.lat)) / (2.0.pow(zoom) * k)
            val r = (last.acc / mpp).toFloat().coerceAtLeast(8 * dp)
            c.drawCircle(px, py, r, accFill)
            c.drawCircle(px, py, r, accLine)
            c.drawCircle(px, py, 9 * dp, dotFill)
            c.drawCircle(px, py, 9 * dp, dotRing)
        }

        val text = if (last == null) "En attente d'une position…" else "%.5f, %.5f  ±%d m".format(last.lat, last.lon, last.acc.toInt())
        val tw = label.measureText(text)
        c.drawRect(6 * dp, h - 26 * dp, 6 * dp + tw + 12 * dp, h - 6 * dp, labelBg)
        c.drawText(text, 12 * dp, h - 12 * dp, label)
        val attr = "© OpenStreetMap © CARTO"
        val aw = label.measureText(attr) * 0.7f
        label.textSize = 9 * dp
        c.drawText(attr, w - aw - 6 * dp, h - 6 * dp, label)
        label.textSize = 12 * dp
        if (tilesOk == 0 && tilesFail > 3) {
            val m = "Fond de carte indisponible (internet ?). Le point reste valable."
            c.drawRect(6 * dp, 6 * dp, 6 * dp + label.measureText(m) + 12 * dp, 28 * dp, labelBg)
            c.drawText(m, 12 * dp, 22 * dp, label)
        }
    }

    // ---------- Doigts : glisser, pincer, double tape ----------

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val zi = floor(zoom).toInt().coerceIn(3, 19)
            val tile = 256.0 * 2.0.pow(zoom - zi) * k
            cLon = lonOf(tx(cLon, zi) + dx / tile, zi)
            cLat = latOf(ty(cLat, zi) + dy / tile, zi).coerceIn(-85.0, 85.0)
            postInvalidate()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { zoomBy(1.0); return true }
    })

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { zoomBy(log2(d.scaleFactor.toDouble())); return true }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(e)
        if (!scaler.isInProgress) gestures.onTouchEvent(e)
        return true
    }
}
