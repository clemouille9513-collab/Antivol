package com.antivol.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.ByteArrayOutputStream

/**
 * Prend une photo sans aperçu avec la caméra avant ou arrière.
 * Renvoie une petite image JPEG (480 px de large) ou null si Android refuse l'accès à la caméra.
 */
class PhotoCapture(private val ctx: Context) {

    @SuppressLint("MissingPermission")
    fun capture(front: Boolean, onResult: (ByteArray?) -> Unit) {
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onResult(null); return
        }
        val cm = ctx.getSystemService(CameraManager::class.java)
        val wanted = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = try {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wanted }
        } catch (_: Exception) { null }
        if (id == null) { onResult(null); return }
        val sensor = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        val thread = HandlerThread("photo").also { it.start() }
        val h = Handler(thread.looper)
        val reader = ImageReader.newInstance(1280, 960, ImageFormat.JPEG, 1)
        val dummy = SurfaceTexture(0).apply { setDefaultBufferSize(640, 480) }
        val dummySurface = Surface(dummy)
        var done = false
        var camera: CameraDevice? = null

        fun finish(bytes: ByteArray?) {
            if (done) return
            done = true
            try { camera?.close() } catch (_: Exception) {}
            try { reader.close() } catch (_: Exception) {}
            try { dummySurface.release(); dummy.release() } catch (_: Exception) {}
            thread.quitSafely()
            onResult(bytes)
        }

        reader.setOnImageAvailableListener({ r ->
            try {
                val img = r.acquireLatestImage()
                if (img != null) {
                    val buf = img.planes[0].buffer
                    val raw = ByteArray(buf.remaining())
                    buf.get(raw)
                    img.close()
                    finish(shrink(raw, sensor))
                }
            } catch (_: Exception) { finish(null) }
        }, h)

        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    camera = cam
                    try {
                        @Suppress("DEPRECATION")
                        cam.createCaptureSession(
                            listOf(dummySurface, reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    try {
                                        val preview = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                            .apply { addTarget(dummySurface) }.build()
                                        s.setRepeatingRequest(preview, null, h)
                                        // Laisse 1 seconde à l'exposition pour se régler, puis prend la photo.
                                        h.postDelayed({
                                            try {
                                                val still = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                                    .apply { addTarget(reader.surface) }.build()
                                                s.capture(still, null, h)
                                            } catch (_: Exception) { finish(null) }
                                        }, 1000)
                                    } catch (_: Exception) { finish(null) }
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) = finish(null)
                            }, h
                        )
                    } catch (_: Exception) { finish(null) }
                }
                override fun onDisconnected(cam: CameraDevice) = finish(null)
                override fun onError(cam: CameraDevice, error: Int) = finish(null)
            }, h)
        } catch (_: Exception) {
            finish(null)
        }
        // Sécurité : abandon après 8 secondes.
        h.postDelayed({ finish(null) }, 8000)
    }

    private fun shrink(raw: ByteArray, rotation: Int): ByteArray {
        val src = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return raw
        val w = 480
        val h = (src.height * (w.toFloat() / src.width)).toInt()
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, m, true)
        val out = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, 55, out)
        return out.toByteArray()
    }
}
