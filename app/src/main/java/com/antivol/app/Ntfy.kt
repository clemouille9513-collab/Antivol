package com.antivol.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Messagerie gratuite sans compte (ntfy.sh).
 * Les messages passent chiffrés (voir Crypto) sur un canal secret.
 */
object Ntfy {
    private const val BASE = "https://ntfy.sh/"

    fun publish(topic: String, body: String) {
        Thread {
            try {
                val c = URL(BASE + topic).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 10000
                c.readTimeout = 10000
                c.outputStream.use { it.write(body.toByteArray()) }
                c.responseCode
                c.disconnect()
            } catch (_: Exception) {
            }
        }.start()
    }

    /** Écoute un canal en continu et se reconnecte toute seule en cas de coupure. */
    class Subscription(
        private val topic: String,
        private val since: String,
        private val onMessage: (String) -> Unit
    ) {
        @Volatile private var running = true
        @Volatile private var conn: HttpURLConnection? = null
        @Volatile private var lastTime = 0L
        private val seen = ArrayDeque<String>()
        private val thread = Thread { loop() }

        fun start(): Subscription {
            thread.start()
            return this
        }

        fun stop() {
            running = false
            try { conn?.disconnect() } catch (_: Exception) {}
            thread.interrupt()
        }

        private fun loop() {
            var first = true
            while (running) {
                try {
                    // Après une coupure, on reprend juste avant le dernier message reçu :
                    // aucun ordre n'est perdu (les doublons sont écartés avec l'identifiant).
                    val from = if (first || lastTime == 0L) since else (lastTime - 5).toString()
                    val c = URL("$BASE$topic/json?since=$from").openConnection() as HttpURLConnection
                    c.connectTimeout = 15000
                    c.readTimeout = 120000
                    conn = c
                    c.inputStream.bufferedReader().use { r ->
                        first = false
                        while (running) {
                            val line = r.readLine() ?: break
                            val o = JSONObject(line)
                            if (o.optString("event") != "message") continue
                            val t = o.optLong("time", 0L)
                            if (t > lastTime) lastTime = t
                            val id = o.optString("id")
                            if (id in seen) continue
                            seen.addLast(id)
                            if (seen.size > 200) seen.removeFirst()
                            try { onMessage(o.optString("message")) } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {
                }
                if (running) {
                    try { Thread.sleep(5000) } catch (_: InterruptedException) {}
                }
            }
        }
    }
}
