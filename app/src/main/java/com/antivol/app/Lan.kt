package com.antivol.app

import android.content.Context
import android.net.wifi.WifiManager
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Liaison DIRECTE entre les deux téléphones quand ils sont sur le même Wi-Fi.
 *
 * Pourquoi : sur un Wi-Fi, les deux téléphones sortent par la même adresse internet et se partagent
 * les limites de ntfy.sh (nombre de messages par jour, une requête toutes les 5 secondes). Ici, les ordres et les
 * réponses ne passent plus par internet du tout : c'est instantané, sans limite, et c'est ce qui permet
 * de voir l'écran en direct.
 *
 * Tout reste chiffré avec la clé de liaison (AES-GCM), exactement comme pour ntfy.sh.
 * Le S22 ouvre un petit serveur (port 8765) et annonce sa présence toutes les 6 secondes sur le réseau (port 8766).
 */
object Lan {
    const val PORT = 8765
    const val BEACON = 8766

    class Addr(val ip: String, val broadcast: String)

    /** Adresses locales (Wi-Fi, point d'accès, câble) de ce téléphone. */
    fun addrs(): List<Addr> {
        val out = ArrayList<Addr>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                val n = ni.name.lowercase()
                if (!(n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("eth") || n.startsWith("rndis"))) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address
                    if (a !is Inet4Address || !a.isSiteLocalAddress) continue
                    val raw = a.address
                    val ip = ((raw[0].toInt() and 255) shl 24) or ((raw[1].toInt() and 255) shl 16) or
                        ((raw[2].toInt() and 255) shl 8) or (raw[3].toInt() and 255)
                    val prefix = ia.networkPrefixLength.toInt()
                    val mask = if (prefix <= 0) 0 else (-1 shl (32 - prefix))
                    val b = ip or mask.inv()
                    out += Addr(
                        a.hostAddress ?: continue,
                        "${(b ushr 24) and 255}.${(b ushr 16) and 255}.${(b ushr 8) and 255}.${b and 255}"
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun localIp(): String = addrs().firstOrNull()?.ip ?: ""

    // =====================================================================
    // Côté S22 : serveur local
    // =====================================================================

    private val lock = Object()
    private val outbox = ArrayDeque<Pair<Long, String>>()
    private var seq = 0L
    private var frame: String? = null
    private var frameSeq = 0L

    @Volatile var lastPoll = 0L
    @Volatile var lastFrameFetch = 0L
    @Volatile var serverError = ""
    @Volatile private var server: ServerSocket? = null
    @Volatile private var beaconOn = false
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }

    /** Vrai si la télécommande est branchée en direct en ce moment. */
    fun active() = System.currentTimeMillis() - lastPoll < 30_000

    /** Vrai si quelqu'un regarde l'écran en direct. */
    fun watching() = System.currentTimeMillis() - lastFrameFetch < 15_000

    /** Met un message chiffré à disposition de la télécommande. */
    fun push(enc: String) {
        synchronized(lock) {
            seq++
            outbox.addLast(seq to enc)
            while (outbox.size > 300) outbox.removeFirst()
            lock.notifyAll()
        }
    }

    fun pushFrame(enc: String) {
        synchronized(lock) {
            frame = enc
            frameSeq++
            lock.notifyAll()
        }
    }

    @Synchronized
    fun startServer(ctx: Context, onCommand: (String) -> Boolean) {
        if (server != null) return
        val app = ctx.applicationContext
        try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(PORT))
            server = s
            serverError = ""
        } catch (e: Exception) {
            serverError = "port $PORT occupé : ${e.message}"
            return
        }
        Thread {
            val s = server ?: return@Thread
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                pool.execute { try { serve(app, c, onCommand) } catch (_: Exception) {} }
            }
        }.apply { isDaemon = true }.start()

        if (!beaconOn) {
            beaconOn = true
            Thread { beaconLoop(app) }.apply { isDaemon = true }.start()
        }
    }

    @Synchronized
    fun stopServer() {
        beaconOn = false
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun beaconLoop(ctx: Context) {
        var sock: DatagramSocket? = null
        try {
            sock = DatagramSocket().apply { broadcast = true }
            while (beaconOn) {
                val secret = Prefs.secret(ctx)
                for (a in addrs()) {
                    val data = Crypto.encrypt(secret, "${System.currentTimeMillis() / 1000}|${a.ip}|$PORT").toByteArray()
                    for (dest in listOf(a.broadcast, "255.255.255.255")) {
                        try { sock.send(DatagramPacket(data, data.size, InetAddress.getByName(dest), BEACON)) } catch (_: Exception) {}
                    }
                }
                try { Thread.sleep(6000) } catch (_: InterruptedException) { break }
            }
        } catch (_: Exception) {
        } finally {
            try { sock?.close() } catch (_: Exception) {}
        }
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = i.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(b.toChar())
        }
    }

    private fun authorized(secret: String, header: String?): Boolean {
        if (header.isNullOrEmpty()) return false
        val ts = Crypto.decrypt(secret, header)?.toLongOrNull() ?: return false
        return abs(System.currentTimeMillis() / 1000 - ts) < 600
    }

    private fun serve(ctx: Context, s: Socket, onCommand: (String) -> Boolean) {
        s.soTimeout = 15_000
        s.use { sock ->
            val inp = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()
            val reqLine = readLine(inp) ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val l = readLine(inp) ?: return
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            val parts = reqLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore('?')
            val query = parts[1].substringAfter('?', "")
            val since = query.split("&").firstOrNull { it.startsWith("since=") }
                ?.substringAfter("=")?.toLongOrNull() ?: -1L

            fun respond(code: Int, text: String) {
                val b = text.toByteArray()
                out.write("HTTP/1.1 $code X\r\nContent-Type: text/plain\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(b)
                out.flush()
            }

            val secret = Prefs.secret(ctx)
            when {
                method == "POST" && path == "/c" -> {
                    val len = headers["content-length"]?.toIntOrNull() ?: 0
                    if (len <= 0 || len > 100_000) { respond(400, "taille"); return }
                    val buf = ByteArray(len)
                    var read = 0
                    while (read < len) {
                        val n = inp.read(buf, read, len - read)
                        if (n < 0) break
                        read += n
                    }
                    val ok = try { onCommand(String(buf, 0, read)) } catch (_: Exception) { false }
                    respond(if (ok) 200 else 403, if (ok) "ok" else "refus")
                }
                method == "GET" && path == "/r" -> {
                    if (!authorized(secret, headers["x-k"])) { respond(403, "refus"); return }
                    lastPoll = System.currentTimeMillis()
                    val sb = StringBuilder()
                    var latest: Long
                    synchronized(lock) {
                        if (since >= 0) {
                            val end = System.currentTimeMillis() + 20_000
                            while (seq <= since && System.currentTimeMillis() < end) {
                                try { lock.wait(1000) } catch (_: InterruptedException) { break }
                            }
                        }
                        latest = seq
                        if (since >= 0) outbox.filter { it.first > since }.forEach { sb.append(it.first).append('\t').append(it.second).append('\n') }
                    }
                    lastPoll = System.currentTimeMillis()
                    respond(200, "$latest\n$sb")
                }
                method == "GET" && path == "/f" -> {
                    if (!authorized(secret, headers["x-k"])) { respond(403, "refus"); return }
                    lastFrameFetch = System.currentTimeMillis()
                    var data: String? = null
                    var fs: Long
                    synchronized(lock) {
                        val end = System.currentTimeMillis() + 8_000
                        while (frameSeq <= since && System.currentTimeMillis() < end) {
                            try { lock.wait(500) } catch (_: InterruptedException) { break }
                        }
                        fs = frameSeq
                        if (frameSeq > since) data = frame
                    }
                    lastFrameFetch = System.currentTimeMillis()
                    respond(200, "$fs\n${data ?: ""}")
                }
                else -> respond(404, "")
            }
        }
    }

    // =====================================================================
    // Côté télécommande : trouve le S22 et lui parle directement
    // =====================================================================

    class Client(private val ctx: Context, private val secret: String, private val onMessage: (String) -> Unit) {
        @Volatile private var running = true
        @Volatile var ip: String = ""
        @Volatile var lastOk = 0L
        @Volatile var status = "recherche du S22 sur ce Wi-Fi…"
        private var multicast: WifiManager.MulticastLock? = null

        private fun manual() = Prefs.str(ctx, "lan_ip").trim()
        fun target(): String = manual().ifBlank { ip }

        /** Adresse apprise par un message internet du S22. */
        fun hint(newIp: String) {
            if (newIp.isNotBlank() && manual().isEmpty()) ip = newIp
        }

        fun connected() = System.currentTimeMillis() - lastOk < 40_000

        fun start(): Client {
            try {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicast = wm.createMulticastLock("antivol:lan").apply { setReferenceCounted(false); acquire() }
            } catch (_: Exception) {}
            Thread { listenBeacon() }.apply { isDaemon = true }.start()
            Thread { pollLoop() }.apply { isDaemon = true }.start()
            return this
        }

        fun stop() {
            running = false
            try { multicast?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        }

        private fun listenBeacon() {
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(BEACON))
                    soTimeout = 3000
                }
                val buf = ByteArray(1024)
                while (running) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        sock.receive(p)
                        val plain = Crypto.decrypt(secret, String(p.data, 0, p.length)) ?: continue
                        val f = plain.split("|")
                        val ts = f.getOrNull(0)?.toLongOrNull() ?: continue
                        if (abs(System.currentTimeMillis() / 1000 - ts) > 600) continue
                        val found = f.getOrNull(1) ?: continue
                        if (manual().isEmpty() && found != ip) ip = found
                    } catch (_: java.net.SocketTimeoutException) {
                    }
                }
            } catch (e: Exception) {
                status = "écoute du réseau impossible : ${e.message}"
            } finally {
                try { sock?.close() } catch (_: Exception) {}
            }
        }

        private fun open(path: String, post: String?, readMs: Int): Pair<Int, String> {
            val c = URL("http://${target()}:$PORT$path").openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 3000
                c.readTimeout = readMs
                c.setRequestProperty("X-K", Crypto.encrypt(secret, "${System.currentTimeMillis() / 1000}"))
                if (post != null) {
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.outputStream.use { it.write(post.toByteArray()) }
                }
                val code = c.responseCode
                val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                return code to text
            } finally {
                c.disconnect()
            }
        }

        /** Envoie un ordre chiffré au S22. Vrai s'il l'a reçu. */
        fun post(body: String): Boolean {
            if (target().isEmpty()) return false
            return try {
                val (code, _) = open("/c", body, 4000)
                if (code == 200) { lastOk = System.currentTimeMillis(); true } else false
            } catch (_: Exception) { false }
        }

        /** Dernière image de l'écran (chiffrée), ou null s'il n'y en a pas de nouvelle. */
        fun frame(since: Long): Pair<Long, String?>? {
            if (target().isEmpty()) return null
            return try {
                val (code, text) = open("/f?since=$since", null, 12_000)
                if (code != 200) return null
                lastOk = System.currentTimeMillis()
                val n = text.substringBefore('\n').trim().toLongOrNull() ?: return null
                val data = text.substringAfter('\n', "").trim()
                n to data.ifEmpty { null }
            } catch (_: Exception) { null }
        }

        private fun pollLoop() {
            var since = -1L
            var fails = 0
            while (running) {
                if (target().isEmpty()) {
                    status = "S22 pas encore trouvé sur ce Wi-Fi (il s'annonce toutes les 6 s)"
                    try { Thread.sleep(2000) } catch (_: InterruptedException) {}
                    continue
                }
                try {
                    val (code, text) = open("/r?since=$since", null, 30_000)
                    if (code != 200) throw IllegalStateException("réponse $code")
                    fails = 0
                    lastOk = System.currentTimeMillis()
                    status = "direct Wi-Fi : connecté (${target()})"
                    val lines = text.split("\n")
                    val latest = lines.firstOrNull()?.trim()?.toLongOrNull() ?: since
                    if (since >= 0) {
                        for (l in lines.drop(1)) {
                            val i = l.indexOf('\t')
                            if (i <= 0) continue
                            try { onMessage(l.substring(i + 1)) } catch (_: Exception) {}
                        }
                    }
                    since = if (latest < since) -1L else latest
                } catch (e: Exception) {
                    fails++
                    status = "direct Wi-Fi : ${target()} ne répond pas (${e.message ?: e.javaClass.simpleName}). Même Wi-Fi ? Isolation des appareils ?"
                    if (fails >= 4 && manual().isEmpty()) ip = ""
                    since = -1L
                    try { Thread.sleep(minOf(5000L, 1000L * fails)) } catch (_: InterruptedException) {}
                }
            }
        }
    }
}
