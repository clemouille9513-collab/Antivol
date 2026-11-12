package com.antivol.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Image + son en direct qui passent TOUJOURS, quel que soit le réseau (4G, 5G, Wi-Fi d'hôtel...).
 * Pas de connexion directe entre téléphones : le S22 et la page web se connectent chacun vers un
 * serveur public gratuit (courtier MQTT, sans compte) qui fait passer les données.
 * Tout est chiffré avec la clé de liaison avant de partir : le serveur ne voit rien.
 *
 * Sujets : base/v (images JPEG), base/a (son µ-law 16 kHz), base/k (la page dit « je regarde encore »).
 * Sans signal de la page pendant 25 secondes, tout s'arrête.
 */
object Relay {
    val BROKERS = listOf("wss://broker.emqx.io:8084/mqtt", "wss://broker.hivemq.com:8884/mqtt")

    @Volatile var on = false
    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var lastKeep = 0L
    private var startedAt = 0L
    private var base = ""
    private var secret = ""
    private var src = ""
    private var camThread: Thread? = null
    private var micThread: Thread? = null
    private var appCtx: Context? = null
    private var session = 0

    private fun varLen(n: Int): ByteArray {
        var x = n
        val o = ByteArrayOutputStream()
        do {
            var b = x % 128
            x /= 128
            if (x > 0) b = b or 128
            o.write(b)
        } while (x > 0)
        return o.toByteArray()
    }

    private fun packet(first: Int, body: ByteArray) = byteArrayOf(first.toByte()) + varLen(body.size) + body
    private fun str(s: String): ByteArray {
        val b = s.toByteArray()
        return byteArrayOf((b.size shr 8).toByte(), b.size.toByte()) + b
    }

    private fun publish(sub: String, payload: ByteArray) {
        val w = ws ?: return
        if (!ready || w.queueSize() > 250_000) return // réseau lent : on saute des images plutôt que d'accumuler du retard
        val enc = Crypto.encryptRaw(secret, payload)
        w.send(packet(0x30, str("$base/$sub") + enc).toByteString())
    }

    /** Image d'écran venant de ProjectionService. */
    fun frame(jpeg: ByteArray) {
        if (on && src == "screen") publish("v", byteArrayOf(0) + jpeg)
    }

    fun start(ctx: Context, source: String, brokerIdx: Int, mic: Boolean = true) {
        stop()
        val app = ctx.applicationContext
        appCtx = app
        val mine = ++session
        secret = Prefs.secret(app)
        base = "antivol/" + Crypto.topic(secret, "m")
        src = source
        ready = false
        on = true
        startedAt = System.currentTimeMillis()
        lastKeep = startedAt

        if (source == "screen") {
            val pj = ProjectionService.instance
            if (pj == null || !pj.ready()) { fail(app, "Écran impossible : autorise d'abord « Écran en direct » dans l'appli du S22"); return }
        }

        val url = BROKERS[brokerIdx.coerceIn(0, BROKERS.size - 1)]
        val client = Ntfy.client.newBuilder().readTimeout(0, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()
        ws = client.newWebSocket(
            Request.Builder().url(url).header("Sec-WebSocket-Protocol", "mqtt").build(),
            object : WebSocketListener() {
                private var buf = ByteArray(0)
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val id = "av" + java.lang.Long.toHexString(System.nanoTime()).takeLast(8)
                    webSocket.send(packet(0x10, str("MQTT") + byteArrayOf(4, 2, 0, 40) + str(id)).toByteString())
                    webSocket.send(packet(0x82, byteArrayOf(0, 1) + str("$base/k") + byteArrayOf(0)).toByteString())
                    ready = true
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    buf += bytes.toByteArray()
                    while (buf.size >= 2) {
                        var i = 1
                        var len = 0
                        var mul = 1
                        while (true) {
                            if (i >= buf.size) return
                            val b = buf[i++].toInt() and 0xFF
                            len += (b and 127) * mul
                            mul *= 128
                            if (b and 128 == 0) break
                            if (i > 4) { buf = ByteArray(0); return }
                        }
                        if (buf.size < i + len) return
                        val type = (buf[0].toInt() and 0xFF) shr 4
                        if (type == 2 && len >= 2 && buf[i + 1].toInt() != 0) {
                            main.post { if (mine == session) fail(app, "Serveur relais : connexion refusée") }
                        }
                        if (type == 3) lastKeep = System.currentTimeMillis() // la page regarde encore
                        buf = buf.copyOfRange(i + len, buf.size)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    main.post { if (mine == session && on) fail(app, "Serveur relais injoignable (${t.javaClass.simpleName})") }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    main.post { if (mine == session && on) fail(app, "Serveur relais fermé") }
                }
            }
        )

        if (mic) startMic(app, mine)
        if (source != "screen") startCam(app, source == "front", mine) else ProjectionService.instance?.setStreaming(true)

        val watchdog = object : Runnable {
            override fun run() {
                if (mine != session || !on) return
                val now = System.currentTimeMillis()
                if (now - lastKeep > 25_000 || now - startedAt > 2 * 3600_000L) { Report.event(app, "rtc", "Direct arrêté (plus personne ne regarde)"); stop(); return }
                ws?.send(byteArrayOf(0xC0.toByte(), 0).toByteString())
                main.postDelayed(this, 10_000)
            }
        }
        main.postDelayed(watchdog, 10_000)
        Report.event(app, "rtc", "Direct par le relais internet démarré ($source)")
    }

    private fun fail(app: Context, msg: String) {
        Report.send(app, "rtc|relay|err|0|$msg")
        Report.event(app, "rtc", msg)
        stop()
    }

    private fun startCam(app: Context, front: Boolean, mine: Int) {
        camThread = Thread {
            if (!CamStream.acquire(app, front, false)) {
                main.post { if (mine == session) fail(app, "Caméra impossible : ${CamStream.lastError}") }
                return@Thread
            }
            try {
                // Octet d'en-tête : quart de tour à appliquer (image redressée à la verticale) + 4 si caméra avant (miroir).
                val rot = try { CamStream.info(app, front, false).first } catch (_: Exception) { 90 }
                val head = byteArrayOf((((rot / 90) and 3) or (if (front) 4 else 0)).toByte())
                var since = 0L
                var last = 0L
                while (on && mine == session) {
                    val r = CamStream.next(since, 600)
                    if (r == null) { Thread.sleep(40); continue }
                    since = r.first
                    val now = System.currentTimeMillis()
                    if (now - last < 28) continue
                    last = now
                    publish("v", head + r.second)
                }
            } finally {
                CamStream.release()
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun ulaw(sample: Int): Byte {
        var x = sample
        val sign = if (x < 0) { x = -x; 0x80 } else 0
        if (x > 32635) x = 32635
        x += 132
        var exp = 7
        var m = 0x4000
        while (exp > 0 && (x and m) == 0) { exp--; m = m shr 1 }
        val man = (x shr (exp + 3)) and 0x0F
        return (sign or (exp shl 4) or man).inv().toByte()
    }

    private fun startMic(app: Context, mine: Int) {
        micThread = Thread {
            var rec: AudioRecord? = null
            try {
                val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                @Suppress("MissingPermission")
                rec = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
                if (rec.state != AudioRecord.STATE_INITIALIZED) { Report.event(app, "rtc", "Micro indisponible : le direct continue sans son"); return@Thread }
                rec.startRecording()
                val pcm = ShortArray(1600)
                while (on && mine == session) {
                    var got = 0
                    while (got < pcm.size && on) {
                        val n = rec.read(pcm, got, pcm.size - got)
                        if (n <= 0) break
                        got += n
                    }
                    if (got < pcm.size) break
                    publish("a", ByteArray(pcm.size) { ulaw(pcm[it].toInt()) })
                }
            } catch (_: Throwable) {
            } finally {
                try { rec?.stop() } catch (_: Throwable) {}
                try { rec?.release() } catch (_: Throwable) {}
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        if (!on && ws == null) return
        session++
        on = false
        ready = false
        try { ws?.close(1000, null) } catch (_: Throwable) {}
        ws = null
        if (src == "screen") {
            try { ProjectionService.instance?.setStreaming(false) } catch (_: Throwable) {}
        }
        camThread = null
        micThread = null
    }
}
