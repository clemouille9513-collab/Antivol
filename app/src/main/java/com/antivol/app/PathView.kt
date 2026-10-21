package com.antivol.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.cos
import kotlin.math.max

/** Dessine le trajet du téléphone (sans fond de carte). Le dernier point est en rouge. */
class PathView(ctx: Context) : View(ctx) {
    var points: List<Pair<Double, Double>> = emptyList()
        set(v) { field = v; invalidate() }

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF90CAF9.toInt(); strokeWidth = 5f; style = Paint.Style.STROKE
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF90CAF9.toInt() }
    private val last = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = 40f }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF101418.toInt())
        if (points.isEmpty()) {
            c.drawText("Aucune position reçue", 24f, height / 2f, text)
            return
        }
        val pad = 40f
        val minLat = points.minOf { it.first }
        val maxLat = points.maxOf { it.first }
        val minLon = points.minOf { it.second }
        val maxLon = points.maxOf { it.second }
        val k = cos(Math.toRadians((minLat + maxLat) / 2))
        val w = max((maxLon - minLon) * k, 1e-6)
        val h = max(maxLat - minLat, 1e-6)
        val scale = minOf((width - 2 * pad) / w, (height - 2 * pad) / h).toFloat()
        val offX = (width - (w * scale)) / 2f
        val offY = (height - (h * scale)) / 2f

        fun x(lon: Double) = offX + ((lon - minLon) * k * scale).toFloat()
        fun y(lat: Double) = height - (offY + ((lat - minLat) * scale).toFloat())

        for (i in 1 until points.size) {
            c.drawLine(
                x(points[i - 1].second), y(points[i - 1].first),
                x(points[i].second), y(points[i].first), line
            )
        }
        for (p in points) c.drawCircle(x(p.second), y(p.first), 8f, dot)
        val l = points.last()
        c.drawCircle(x(l.second), y(l.first), 16f, last)
    }
}
