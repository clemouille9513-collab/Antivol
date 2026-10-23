package com.antivol.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import okhttp3.Call
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.dnsoverhttps.DnsOverHttps
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Messagerie gratuite sans compte (ntfy.sh).
 * Les messages passent chiffrés (voir Crypto) sur un canal secret.
 *
 * Réseau renforcé :
 * - DNS : celui du téléphone d'abord, puis DNS chiffré (Cloudflare, Google) si le Wi-Fi le bloque ou le filtre.
 * - Adresses IPv4 d'abord (beaucoup de Wi-Fi ont un IPv6 cassé).
 * - Reconnexion avec attente progressive : ntfy.sh limite les requêtes (une toutes les 5 secondes
 *   en moyenne), un téléphone qui retente sans arrêt se fait bloquer.
 * - Envois réessayés plusieurs fois, au lieu d'être perdus à la première coupure.
 * - Le flux est relancé tout de suite quand le téléphone change de réseau (Wi-Fi <-> 4G/5G).
 */
object Ntfy {
    private const val DEFAULT_BASE = "https://ntfy.sh/"

    /** Serveur de messages. Vide dans les réglages = ntfy.sh ; sinon ton propre serveur ntfy. */
    @Volatile var baseUrl = DEFAULT_BASE

    fun configure(ctx: Context) {
        var b = Prefs.str(ctx, "ntfy_base").trim()
        if (b.isEmpty()) b = DEFAULT_BASE
        if (!b.startsWith("http://") && !b.startsWith("https://")) b = "https://$b"
        if (!b.endsWith("/")) b += "/"
        baseUrl = b
    }

    @Volatile var lastOk = 0L
    @Volatile var lastError = ""
    @Volatile var via = "système"
    @Volatile var netType = "?"

    private val subs = CopyOnWriteArrayList<Subscription>()

    // ---------- DNS ----------

    private fun usable(a: InetAddress) =
        !a.isAnyLocalAddress && !a.isLoopbackAddress && !a.isSiteLocalAddress && !a.isLinkLocalAddress

    private object SmartDns : Dns {
        private val bootstrap by lazy {
            OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()
        }

        private fun ip(vararg b: Int) = InetAddress.getByAddress(ByteArray(4) { b[it].toByte() })

        private val cloudflare by lazy {
            DnsOverHttps.Builder().client(bootstrap)
                .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
                .bootstrapDnsHosts(listOf(ip(1, 1, 1, 1), ip(1, 0, 0, 1)))
                .build()
        }
        private val google by lazy {
            DnsOverHttps.Builder().client(bootstrap)
                .url("https://dns.google/dns-query".toHttpUrl())
                .bootstrapDnsHosts(listOf(ip(8, 8, 8, 8), ip(8, 8, 4, 4)))
                .build()
        }

        @Volatile private var cache = mapOf<String, List<InetAddress>>()

        fun forget() { cache = emptyMap() }

        private fun ordered(l: List<InetAddress>) = l.filterIsInstance<Inet4Address>() + l.filter { it !is Inet4Address }

        override fun lookup(hostname: String): List<InetAddress> {
            var err: Exception? = null
            val steps = listOf<Pair<String, () -> List<InetAddress>>>(
                "système" to { Dns.SYSTEM.lookup(hostname) },
                "DNS chiffré Cloudflare" to { cloudflare.lookup(hostname) },
                "DNS chiffré Google" to { google.lookup(hostname) }
            )
            for ((name, f) in steps) {
                try {
                    val r = f().filter { usable(it) }
                    if (r.isNotEmpty()) {
                        val o = ordered(r)
                        cache = cache + (hostname to o)
                        via = name
                        return o
                    }
                } catch (e: Exception) {
                    err = e
                }
            }
            cache[hostname]?.let { via = "dernière adresse connue"; return it }
            throw err ?: UnknownHostException(hostname)
        }
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(SmartDns)
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val streamClient: OkHttpClient by lazy {
        // ntfy envoie un signal de vie toutes les 45 secondes : sans rien pendant 100 s, la connexion est morte.
        client.newBuilder().readTimeout(100, TimeUnit.SECONDS).build()
    }

    private fun describe(e: Exception): String = when (e) {
        is UnknownHostException -> "DNS : serveur de messages introuvable (Wi-Fi qui filtre ?)"
        is SocketTimeoutException -> "délai dépassé (réseau lent ou filtré)"
        is SSLException -> "connexion sécurisée refusée (Wi-Fi qui filtre ?)"
        else -> (e.javaClass.simpleName + (e.message?.let { " : $it" } ?: "")).take(120)
    }

    // ---------- Suivi du réseau ----------

    private var watching = false

    /** À appeler une fois : relance les flux quand le téléphone change de réseau. */
    @Synchronized
    fun watchNetwork(ctx: Context) {
        configure(ctx)
        if (watching) return
        try {
            val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { SmartDns.forget(); kick() }
                override fun onLost(network: Network) { netType = "aucun" }
                override fun onCapabilitiesChanged(network: Network, c: NetworkCapabilities) {
                    val t = when {
                        c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                        c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "4G/5G"
                        c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "câble"
                        else -> "autre"
                    }
                    if (t != netType) { netType = t; kick() }
                }
            })
            watching = true
        } catch (_: Exception) {
        }
    }

    private fun kick() = subs.forEach { it.kick() }

    // ---------- Envoi ----------

    private val sender = Executors.newFixedThreadPool(2)

    /** Envoie un message ; réessaie plusieurs fois si le réseau est coupé ou si ntfy.sh limite. */
    fun publish(topic: String, body: String, headers: Map<String, String> = emptyMap()) {
        sender.execute { sendWithRetry(topic, body, headers) }
    }

    private fun sendWithRetry(topic: String, body: String, headers: Map<String, String>) {
        val waits = longArrayOf(0, 3_000, 8_000, 20_000, 45_000, 90_000)
        for (w in waits) {
            if (w > 0) try { Thread.sleep(w) } catch (_: InterruptedException) { return }
            try {
                val rb = body.toRequestBody("text/plain; charset=utf-8".toMediaType())
                val req = Request.Builder().url(baseUrl + topic).post(rb).apply {
                    headers.forEach { (k, v) -> header(k, v) }
                }.build()
                client.newCall(req).execute().use { r ->
                    if (r.isSuccessful) {
                        lastOk = System.currentTimeMillis()
                        lastError = ""
                        return
                    }
                    lastError = "ntfy.sh a répondu ${r.code}" + if (r.code == 429) " (trop de requêtes)" else ""
                    if (r.code == 429) { try { Thread.sleep(12_000) } catch (_: InterruptedException) { return } }
                    else if (r.code in 400..499) return
                }
            } catch (e: Exception) {
                lastError = describe(e)
            }
        }
    }

    // ---------- Test ----------

    /** Teste la connexion à ntfy.sh et dit en clair ce qui ne va pas. */
    fun check(onDone: (String) -> Unit) {
        Thread {
            val net = "Réseau : $netType"
            val msg = try {
                val req = Request.Builder().url(baseUrl + "v1/health").build()
                client.newCall(req).execute().use { r ->
                    if (r.isSuccessful) "$net · ntfy.sh joignable (DNS : $via)"
                    else "$net · ntfy.sh a répondu ${r.code}"
                }
            } catch (e: Exception) {
                "$net · ntfy.sh injoignable : ${describe(e)}"
            }
            onDone(msg)
        }.start()
    }

    fun summary(): String {
        val ok = if (lastOk == 0L) "jamais" else "il y a ${(System.currentTimeMillis() - lastOk) / 1000} s"
        return "Réseau : $netType · dernier échange avec ntfy.sh : $ok" +
            (if (lastError.isNotEmpty()) " · problème : $lastError" else "")
    }

    // ---------- Écoute ----------

    /** Écoute un canal en continu et se reconnecte toute seule en cas de coupure. */
    class Subscription(
        private val topic: String,
        private val since: String,
        private val onMessage: (String) -> Unit
    ) {
        @Volatile private var running = true
        @Volatile private var call: Call? = null
        @Volatile private var lastTime = 0L
        private val seen = ArrayDeque<String>()
        private val wake = Object()
        private val thread = Thread { loop() }.apply { isDaemon = true }

        fun start(): Subscription {
            subs.add(this)
            thread.start()
            return this
        }

        fun stop() {
            running = false
            subs.remove(this)
            try { call?.cancel() } catch (_: Exception) {}
            synchronized(wake) { wake.notifyAll() }
        }

        /** Coupe la connexion actuelle pour repartir tout de suite (changement de réseau). */
        fun kick() {
            try { call?.cancel() } catch (_: Exception) {}
            synchronized(wake) { wake.notifyAll() }
        }

        private fun loop() {
            var fails = 0
            var first = true
            while (running) {
                // Après une coupure, on reprend juste avant le dernier message reçu : aucun ordre perdu.
                val from = if (first || lastTime == 0L) since else (lastTime - 5).toString()
                try {
                    val req = Request.Builder().url("$baseUrl$topic/json?since=$from").build()
                    val c = streamClient.newCall(req)
                    call = c
                    c.execute().use { r ->
                        if (!r.isSuccessful) {
                            lastError = "ntfy.sh a répondu ${r.code}" + if (r.code == 429) " (trop de requêtes)" else ""
                            fails = if (r.code == 429) maxOf(fails + 1, 4) else fails + 1
                            return@use
                        }
                        first = false
                        fails = 0
                        lastOk = System.currentTimeMillis()
                        lastError = ""
                        val src = r.body!!.source()
                        while (running) {
                            val line = src.readUtf8Line() ?: break
                            lastOk = System.currentTimeMillis()
                            val o = try { JSONObject(line) } catch (_: Exception) { continue }
                            if (o.optString("event") != "message") continue
                            val t = o.optLong("time", 0L)
                            if (t > lastTime) lastTime = t
                            val id = o.optString("id")
                            if (id in seen) continue
                            seen.addLast(id)
                            if (seen.size > 300) seen.removeFirst()
                            try { onMessage(o.optString("message")) } catch (_: Exception) {}
                        }
                    }
                } catch (e: IOException) {
                    if (running) { fails++; lastError = describe(e) }
                } catch (e: Exception) {
                    if (running) { fails++; lastError = describe(e) }
                }
                if (!running) break
                // Attente progressive : 1,5 s, 4 s, 8 s, 16 s, puis 30 s au maximum.
                val wait = when {
                    fails <= 0 -> 1_500L
                    else -> minOf(30_000L, 2_000L shl minOf(fails, 4))
                }
                synchronized(wake) { try { wake.wait(wait) } catch (_: InterruptedException) {} }
            }
        }
    }
}
