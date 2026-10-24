package com.antivol.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import java.io.ByteArrayOutputStream

/**
 * Écran en direct fluide (jusqu'à 10 images par seconde).
 * Android demande une autorisation d'enregistrement d'écran, à donner une fois (bouton dans l'appli).
 * Tant que personne ne regarde, l'affichage virtuel est mis en pause : presque aucune batterie utilisée.
 */
class ProjectionService : Service() {

    companion object {
        @Volatile var instance: ProjectionService? = null

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            ctx.startForegroundService(
                Intent(ctx, ProjectionService::class.java).putExtra("code", resultCode).putExtra("data", data)
            )
        }
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var thread: HandlerThread? = null
    @Volatile private var streaming = false
    private var lastSent = 0L
    private var w = 540
    private var h = 1170

    fun ready() = projection != null && display != null

    fun setStreaming(on: Boolean) {
        streaming = on
        try { display?.surface = if (on) reader?.surface else null } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("antivol_ecran", "Écran en direct", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "antivol_ecran")
            .setContentTitle("Antivol : écran en direct prêt")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(2, n)
        } catch (_: Exception) {
            stopSelf(); return START_NOT_STICKY
        }

        if (projection != null) return START_NOT_STICKY
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        try {
            val t = HandlerThread("antivol-proj").also { it.start() }
            thread = t
            val handler = Handler(t.looper)
            val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { cleanup() }
            }, handler)
            projection = mp

            val m = resources.displayMetrics
            w = 540
            h = (540f * m.heightPixels / m.widthPixels).toInt().let { it - it % 2 }
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            reader = r
            r.setOnImageAvailableListener({ ir ->
                val img = try { ir.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                try {
                    val now = System.currentTimeMillis()
                    if (!streaming || now - lastSent < 90) return@setOnImageAvailableListener
                    lastSent = now
                    val p = img.planes[0]
                    val rowPixels = p.rowStride / p.pixelStride
                    val bmp = Bitmap.createBitmap(rowPixels, h, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(p.buffer)
                    val out = ByteArrayOutputStream()
                    Bitmap.createBitmap(bmp, 0, 0, w, h).compress(Bitmap.CompressFormat.JPEG, 45, out)
                    bmp.recycle()
                    Lan.pushFrame(Crypto.encryptBytes(Prefs.secret(this), out.toByteArray()))
                } catch (_: Exception) {
                } finally {
                    img.close()
                }
            }, handler)
            display = mp.createVirtualDisplay(
                "antivol", w, h, m.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, handler
            )
            instance = this
            setStreaming(false)
        } catch (_: Exception) {
            cleanup()
        }
        return START_NOT_STICKY
    }

    private fun cleanup() {
        streaming = false
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        display = null; reader = null; projection = null
        thread?.quitSafely(); thread = null
        instance = null
        stopSelf()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
