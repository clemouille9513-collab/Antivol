package com.antivol.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.util.Size
import android.view.View
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Flux vidéo de la caméra avant ou arrière (jusqu'à 30 images par seconde), envoyé en MJPEG
 * à la page HTML de contrôle par le petit serveur du S22 (voir Lan). La caméra ne s'allume que
 * tant que quelqu'un regarde, et s'éteint 3 secondes après la dernière personne.
 */
object CamStream {
    @Volatile var lastError = ""
    @Volatile var running = false
        private set

    private val lock = Object()
    private var users = 0
    private var curFront = true
    private var curHd = false
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var marker: View? = null
    private var jpeg: ByteArray? = null
    private var seq = 0L
    private var frames = 0
    private var winStart = 0L
    private val main = Handler(Looper.getMainLooper())

    private fun camId(cm: CameraManager, front: Boolean): String? {
        val wanted = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        return try {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wanted }
        } catch (_: Exception) { null }
    }

    private fun pickSize(cm: CameraManager, id: String, hd: Boolean): Size {
        val aspect = if (hd) 16f / 9f else 4f / 3f
        val area = if (hd) 1280 * 720 else 640 * 480
        val sizes = try {
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)?.toList()
        } catch (_: Exception) { null }
        if (sizes.isNullOrEmpty()) return Size(640, 480)
        return sizes.filter { abs(it.width.toFloat() / it.height - aspect) < 0.05f }
            .minByOrNull { abs(it.width * it.height - area) }
            ?: sizes.minByOrNull { abs(it.width * it.height - area) }!!
    }

    /** Rotation à appliquer à l'image pour la redresser, et sa taille : (rotation, largeur, hauteur). */
    fun info(ctx: Context, front: Boolean, hd: Boolean): Triple<Int, Int, Int> {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = camId(cm, front) ?: return Triple(0, 640, 480)
        val rot = try { cm.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90 } catch (_: Exception) { 90 }
        val s = pickSize(cm, id, hd)
        return Triple(rot, s.width, s.height)
    }

    /** Démarre (ou rejoint) le flux ; renvoie true dès que la première image est arrivée. */
    fun acquire(ctx: Context, front: Boolean, hd: Boolean): Boolean {
        val app = ctx.applicationContext
        synchronized(lock) {
            if (running && (front != curFront || hd != curHd)) stopLocked()
            users++
            if (!running) {
                lastError = ""
                startLocked(app, front, hd)
            }
            val end = System.currentTimeMillis() + 9_000
            while (running && seq == 0L && lastError.isEmpty() && System.currentTimeMillis() < end) {
                try { lock.wait(250) } catch (_: InterruptedException) { break }
            }
            if (seq == 0L) {
                users--
                if (lastError.isEmpty()) lastError = "la caméra ne répond pas (utilisée par une autre appli ?)"
                if (users <= 0) stopLocked()
                return false
            }
            return true
        }
    }

    fun release() {
        synchronized(lock) { users-- }
        main.postDelayed({ synchronized(lock) { if (users <= 0) stopLocked() } }, 3_000)
    }

    /** Attend une image plus récente que [since] ; renvoie (numéro, jpeg) ou null au bout de [waitMs]. */
    fun next(since: Long, waitMs: Long): Pair<Long, ByteArray>? {
        synchronized(lock) {
            val end = System.currentTimeMillis() + waitMs
            while (running && seq <= since && System.currentTimeMillis() < end) {
                try { lock.wait(100) } catch (_: InterruptedException) { return null }
            }
            val j = jpeg
            return if (j != null && seq > since) seq to j else null
        }
    }

    // ---------- Caméra ----------

    @SuppressLint("MissingPermission")
    private fun startLocked(ctx: Context, front: Boolean, hd: Boolean) {
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            lastError = "l'autorisation caméra n'est pas donnée sur le S22"
            return
        }
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = camId(cm, front)
        if (id == null) { lastError = "caméra introuvable"; return }
        running = true
        curFront = front; curHd = hd
        seq = 0L; jpeg = null; frames = 0; winStart = 0L
        val size = pickSize(cm, id, hd)
        val quality = if (hd) 55 else 60
        val t = HandlerThread("antivol-cam").also { it.start() }
        thread = t
        val h = Handler(t.looper)

        main.post {
            // Une fenêtre invisible d'1 pixel aide Android à autoriser la caméra quand l'appli est en arrière-plan.
            if (Settings.canDrawOverlays(ctx) && marker == null) {
                try {
                    val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    val v = View(ctx)
                    wm.addView(
                        v,
                        WindowManager.LayoutParams(
                            1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                            PixelFormat.TRANSLUCENT
                        )
                    )
                    marker = v
                } catch (_: Exception) {}
            }
            try {
                val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
                reader = r
                r.setOnImageAvailableListener({ ir ->
                    val img = try { ir.acquireLatestImage() } catch (_: Exception) { null }
                    if (img != null) {
                        try {
                            val bytes = encode(img, if (Relay.on) 38 else quality)
                            val now = System.currentTimeMillis()
                            synchronized(lock) {
                                jpeg = bytes
                                seq++
                                frames++
                                if (winStart == 0L) winStart = now
                                if (now - winStart >= 1000) { Lan.camFps = frames; frames = 0; winStart = now }
                                lock.notifyAll()
                            }
                        } catch (_: Exception) {
                        } finally {
                            img.close()
                        }
                    }
                }, h)

                cm.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(cam: CameraDevice) {
                        camera = cam
                        try {
                            @Suppress("DEPRECATION")
                            cam.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    session = s
                                    try {
                                        val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                        b.addTarget(r.surface)
                                        val ranges = cm.getCameraCharacteristics(id)
                                            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                                        val best = ranges?.filter { it.upper >= 30 }?.maxByOrNull { it.lower }
                                        if (best != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, best)
                                        s.setRepeatingRequest(b.build(), null, h)
                                    } catch (e: Exception) { fail("réglage caméra impossible : ${e.message}") }
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) = fail("configuration caméra refusée")
                            }, h)
                        } catch (e: Exception) { fail("session caméra impossible : ${e.message}") }
                    }
                    override fun onDisconnected(cam: CameraDevice) = fail("caméra déconnectée (prise par une autre appli)")
                    override fun onError(cam: CameraDevice, error: Int) = fail("erreur caméra $error (Android refuse l'accès en arrière-plan)")
                }, h)
            } catch (e: Exception) {
                fail("ouverture impossible : ${e.message}")
            }
        }
    }

    private fun fail(msg: String) {
        synchronized(lock) {
            lastError = msg
            lock.notifyAll()
        }
        main.post { synchronized(lock) { if (seq == 0L) stopLocked() } }
    }

    private fun stopLocked() {
        running = false
        Lan.camFps = 0
        try { session?.close() } catch (_: Exception) {}
        try { camera?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        session = null; camera = null; reader = null
        thread?.quitSafely(); thread = null
        marker?.let { v ->
            val m = v
            main.post {
                try { (m.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(m) } catch (_: Exception) {}
            }
        }
        marker = null
        seq = 0L; jpeg = null
        lock.notifyAll()
    }

    // ---------- Image -> JPEG ----------

    private fun encode(img: Image, quality: Int): ByteArray {
        val w = img.width; val h = img.height
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val nv = ByteArray(w * h * 3 / 2)
        val yBuf = yP.buffer
        var pos = 0
        if (yP.rowStride == w) {
            yBuf.get(nv, 0, w * h); pos = w * h
        } else {
            for (row in 0 until h) { yBuf.position(row * yP.rowStride); yBuf.get(nv, pos, w); pos += w }
        }
        val uBuf = uP.buffer; val vBuf = vP.buffer
        val rs = uP.rowStride; val ps = uP.pixelStride
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                val i = row * rs + col * ps
                nv[pos++] = vBuf.get(i)
                nv[pos++] = uBuf.get(i)
            }
        }
        val out = ByteArrayOutputStream(w * h / 6)
        YuvImage(nv, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), quality, out)
        return out.toByteArray()
    }
}
