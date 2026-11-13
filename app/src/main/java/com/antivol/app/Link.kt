package com.antivol.app

import android.content.Context
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
 * Canal permanent entre le téléphone et la page de contrôle, par un serveur relais public (MQTT).
 * Il double le service de messages ntfy, qui bloque (erreur 429) au-delà d'environ 250 messages par jour.
 * Ordres : sujet base/c (la page écrit, le téléphone lit). Réponses : base/r (le téléphone écrit, la page lit).
 * Tout est chiffré avec la clé de liaison ; le serveur ne voit rien de lisible.
 */
object Link {
    @Volatile var ready = false
    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var session = 0
    private var idx = 0
    private var fails = 0
    private var running = false
    private var base = ""
    private var onCommand: ((String) -> Unit)? = null
    private val client by lazy {
        Ntfy.client.newBuilder().readTimeout(0, TimeUnit.SECONDS).pingInterval(25, TimeUnit.SECONDS).build()
    }

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

    @Synchronized
    fun start(ctx: Context, cb: (String) -> Unit) {
        onCommand = cb
        if (running) return
        running = true
        base = "antivol/" + Crypto.topic(Prefs.secret(ctx.applicationContext), "m")
        session++
        connect(session)
        main.postDelayed(pinger, 30_000)
    }

    @Synchronized
    fun stop() {
        running = false
        session++
        ready = false
        main.removeCallbacks(pinger)
        try { ws?.close(1000, null) } catch (_: Throwable) {}
        ws = null
    }

    private val pinger = object : Runnable {
        override fun run() {
            if (!running) return
            if (ready) try { ws?.send(byteArrayOf(0xC0.toByte(), 0).toByteString()) } catch (_: Throwable) {}
            main.postDelayed(this, 30_000)
        }
    }

    /** Publie une réponse pour la page. Faux si le canal n'est pas prêt (on retombe alors sur ntfy). */
    fun send(sub: String, payload: ByteArray): Boolean {
        val w = ws
        if (!ready || w == null || w.queueSize() > 2_000_000) return false
        return try { w.send(packet(0x30, str("$base/$sub") + payload).toByteString()) } catch (_: Throwable) { false }
    }

    private fun connect(mine: Int) {
        if (!running || mine != session) return
        val url = Relay.BROKERS[idx % Relay.BROKERS.size]
        ws = client.newWebSocket(
            Request.Builder().url(url).header("Sec-WebSocket-Protocol", "mqtt").build(),
            object : WebSocketListener() {
                private var buf = ByteArray(0)
                private var dead = false

                private fun lost() {
                    if (dead) return
                    dead = true
                    if (mine != session) return
                    ready = false
                    fails++
                    idx++
                    main.postDelayed({ connect(mine) }, minOf(30_000L, 2_000L * fails))
                }

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val id = "al" + java.lang.Long.toHexString(System.nanoTime()).takeLast(8)
                    webSocket.send(packet(0x10, str("MQTT") + byteArrayOf(4, 2, 0, 90) + str(id)).toByteString())
                    webSocket.send(packet(0x82, byteArrayOf(0, 2) + str("$base/c") + byteArrayOf(0)).toByteString())
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
                        val first = buf[0].toInt() and 0xFF
                        val type = first shr 4
                        if (type == 2 && len >= 2) {
                            if (buf[i + 1].toInt() == 0) { ready = true; fails = 0 } else lost()
                        } else if (type == 3 && len >= 2) {
                            try {
                                val qos = (first shr 1) and 3
                                var o = i
                                val tl = ((buf[o].toInt() and 0xFF) shl 8) or (buf[o + 1].toInt() and 0xFF)
                                o += 2
                                val topic = String(buf, o, tl)
                                o += tl
                                if (qos > 0) o += 2
                                val payload = buf.copyOfRange(o, i + len)
                                if (topic.endsWith("/c")) onCommand?.invoke(String(payload))
                            } catch (_: Throwable) {}
                        }
                        buf = buf.copyOfRange(i + len, buf.size)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost()
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost()
            }
        )
    }
}
