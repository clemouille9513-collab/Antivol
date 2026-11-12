package com.antivol.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Surface
import org.webrtc.AudioSource
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Vidéo (écran ou caméra) + son du micro en direct, par internet, de pair à pair (WebRTC).
 * Fonctionne à des kilomètres, sans Wi-Fi commun : la 4G/5G suffit.
 *
 * Aucun serveur à toi : la page web envoie son offre (chiffrée avec la clé de liaison, découpée en morceaux)
 * par le canal ntfy habituel, le S22 répond de la même façon, puis les deux se connectent directement.
 * Des serveurs STUN gratuits (Google) trouvent l'adresse publique ; un relais TURN gratuit sert de secours
 * quand l'opérateur bloque la connexion directe.
 */
object Rtc {
    private val main = Handler(Looper.getMainLooper())

    private fun servers(extra: List<PeerConnection.IceServer> = emptyList()): List<PeerConnection.IceServer> = extra + listOf(
        PeerConnection.IceServer.builder(listOf("turn:freestun.net:3478", "turns:freestun.net:5349"))
            .setUsername("free").setPassword("free").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder(
            listOf(
                "turn:openrelay.metered.ca:80",
                "turn:openrelay.metered.ca:443",
                "turn:openrelay.metered.ca:443?transport=tcp"
            )
        ).setUsername("openrelayproject").setPassword("openrelayproject").createIceServer()
    )

    private var factory: PeerConnectionFactory? = null
    private var egl: EglBase? = null
    private var pc: PeerConnection? = null
    private var capturer: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var vSource: VideoSource? = null
    private var aSource: AudioSource? = null
    private var screenSurface: Surface? = null
    private var sent = false
    private var session = 0
    private val parts = HashMap<String, Array<String?>>()

    @Synchronized
    private fun factory(app: Context): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions()
        )
        val e = EglBase.create()
        egl = e
        val f = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(e.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(e.eglBaseContext))
            .createPeerConnectionFactory()
        factory = f
        return f
    }

    // ---------- Compression (offre/réponse de quelques Ko, la limite d'un message est 4 Ko) ----------

    private fun pack(s: String): String {
        val d = Deflater(9, true)
        d.setInput(s.toByteArray())
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(2048)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return "z" + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun unpack(t: String): String? = try {
        if (t.startsWith("r")) {
            String(Base64.decode(t.substring(1), Base64.NO_WRAP))
        } else {
            val inf = Inflater(true)
            inf.setInput(Base64.decode(t.substring(1), Base64.NO_WRAP) + byteArrayOf(0))
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
            }
            inf.end()
            String(out.toByteArray())
        }
    } catch (e: Exception) {
        null
    }

    // ---------- Réception des morceaux de l'offre ----------

    /** arg = "stop" ou "id:source:index:total:donnée" (source : screen, front ou back). */
    fun chunk(ctx: Context, arg: String) {
        if (arg == "stop") { stop(); return }
        val p = arg.split(":", limit = 5)
        if (p.size < 5) return
        val id = p[0]
        val i = p[2].toIntOrNull() ?: return
        val n = p[3].toIntOrNull() ?: return
        if (n !in 1..12 || i !in 0 until n) return
        val arr = parts.getOrPut(id) { arrayOfNulls(n) }
        if (arr.size != n) return
        arr[i] = p[4]
        if (arr.all { it != null }) {
            parts.remove(id)
            val raw = unpack(arr.joinToString("")) ?: return
            var sdp = raw
            val extra = ArrayList<PeerConnection.IceServer>()
            try {
                if (raw.startsWith("{")) {
                    val o = org.json.JSONObject(raw)
                    sdp = o.getString("s")
                    val a = o.optJSONArray("i")
                    for (k in 0 until (a?.length() ?: 0)) {
                        val e = a!!.getJSONObject(k)
                        val u = e.opt("urls")
                        val urls = if (u is org.json.JSONArray) List(u.length()) { j -> u.getString(j) } else listOf(u.toString())
                        val b = PeerConnection.IceServer.builder(urls)
                        if (e.has("username")) b.setUsername(e.getString("username"))
                        if (e.has("credential")) b.setPassword(e.getString("credential"))
                        extra.add(b.createIceServer())
                    }
                }
            } catch (_: Exception) {}
            start(ctx.applicationContext, id, p[1], sdp, extra)
        }
        if (parts.size > 4) parts.clear()
    }

    private fun fail(app: Context, id: String, msg: String) {
        Report.send(app, "rtc|$id|err|0|$msg")
        Report.event(app, "rtc", msg)
        stop()
    }

    private open class Sdp : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }

    private fun start(app: Context, id: String, src: String, offer: String, extra: List<PeerConnection.IceServer>) {
        stop()
        val mine = ++session
        sent = false
        try {
            val f = factory(app)
            val proj = ProjectionService.instance
            if (src == "screen" && (proj == null || !proj.ready())) {
                fail(app, id, "Écran impossible : autorise d'abord « Écran en direct » dans l'appli du S22"); return
            }

            val cfg = PeerConnection.RTCConfiguration(servers(extra)).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            }
            val obs = object : PeerConnection.Observer {
                override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                    if (s == PeerConnection.IceConnectionState.FAILED) Report.event(app, "rtc", "Direct : les deux réseaux n'arrivent pas à se joindre (il faut un relais, voir le bouton « Relais » de la page)")
                }
                override fun onIceConnectionReceivingChange(b: Boolean) {}
                override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
                    if (s == PeerConnection.IceGatheringState.COMPLETE) main.post { if (mine == session) sendAnswer(app, id) }
                }
                override fun onIceCandidate(c: IceCandidate?) {}
                override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
                override fun onAddStream(s: MediaStream?) {}
                override fun onRemoveStream(s: MediaStream?) {}
                override fun onDataChannel(d: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {
                    when (s) {
                        PeerConnection.PeerConnectionState.CONNECTED -> Report.event(app, "rtc", "Direct connecté")
                        PeerConnection.PeerConnectionState.FAILED,
                        PeerConnection.PeerConnectionState.CLOSED -> main.post { if (mine == session) stop() }
                        else -> {}
                    }
                }
            }
            val p = f.createPeerConnection(cfg, obs) ?: run { fail(app, id, "Direct impossible : connexion non créée"); return }
            pc = p

            // Son du micro
            val aSrc = f.createAudioSource(MediaConstraints())
            aSource = aSrc
            val aTrack = f.createAudioTrack("a0", aSrc)

            // Image : écran ou caméra
            val screen = src == "screen"
            val vSrc = f.createVideoSource(screen)
            vSource = vSrc
            val h = SurfaceTextureHelper.create("antivol-rtc", egl!!.eglBaseContext)
            helper = h
            if (screen) {
                val sw = 720
                val m = app.resources.displayMetrics
                val sh = (sw.toFloat() * m.heightPixels / m.widthPixels).toInt().let { it - it % 2 }
                h.setTextureSize(sw, sh)
                val surf = Surface(h.surfaceTexture)
                screenSurface = surf
                vSrc.capturerObserver.onCapturerStarted(true)
                h.startListening { fr -> vSrc.capturerObserver.onFrameCaptured(fr) }
                proj!!.redirectTo(surf, sw, sh)
            } else {
                val en = Camera2Enumerator(app)
                val name = en.deviceNames.firstOrNull { if (src == "front") en.isFrontFacing(it) else en.isBackFacing(it) }
                    ?: run { fail(app, id, "Caméra introuvable"); return }
                val cap = en.createCapturer(name, null) ?: run { fail(app, id, "Caméra impossible à ouvrir"); return }
                capturer = cap
                cap.initialize(h, app, vSrc.capturerObserver)
                cap.startCapture(1280, 720, 30)
            }
            val vTrack = f.createVideoTrack("v0", vSrc)

            p.setRemoteDescription(object : Sdp() {
                override fun onSetSuccess() {
                    p.addTrack(vTrack, listOf("s"))
                    p.addTrack(aTrack, listOf("s"))
                    p.createAnswer(object : Sdp() {
                        override fun onCreateSuccess(d: SessionDescription?) {
                            if (d != null) p.setLocalDescription(Sdp(), d)
                        }
                        override fun onCreateFailure(e: String?) { main.post { fail(app, id, "Direct impossible : $e") } }
                    }, MediaConstraints())
                }
                override fun onSetFailure(e: String?) { main.post { fail(app, id, "Offre refusée : $e") } }
            }, SessionDescription(SessionDescription.Type.OFFER, offer))

            // Si la recherche d'adresses traîne, on répond avec ce qu'on a.
            main.postDelayed({ if (mine == session) sendAnswer(app, id) }, 6000)
            // Pas de connexion au bout d'une minute : on coupe tout.
            main.postDelayed({
                if (mine == session && pc?.connectionState() != PeerConnection.PeerConnectionState.CONNECTED) {
                    Report.event(app, "rtc", "Direct : pas de connexion possible (réseau bloqué ?)"); stop()
                }
            }, 60_000)
        } catch (e: Throwable) {
            fail(app, id, "Direct impossible : ${e.javaClass.simpleName} ${e.message ?: ""}".take(120))
        }
    }

    private fun sendAnswer(app: Context, id: String) {
        if (sent) return
        val sdp = pc?.localDescription?.description ?: return
        sent = true
        val packed = pack(sdp)
        val pieces = packed.chunked(2400)
        pieces.forEachIndexed { i, piece -> Report.send(app, "rtc|$id|$i|${pieces.size}|$piece") }
    }

    fun stop() {
        session++
        try { ProjectionService.instance?.redirectTo(null, 0, 0) } catch (_: Throwable) {}
        try { capturer?.stopCapture() } catch (_: Throwable) {}
        try { capturer?.dispose() } catch (_: Throwable) {}
        capturer = null
        try { helper?.stopListening() } catch (_: Throwable) {}
        try { pc?.close() } catch (_: Throwable) {}
        try { pc?.dispose() } catch (_: Throwable) {}
        pc = null
        try { vSource?.dispose() } catch (_: Throwable) {}
        vSource = null
        try { aSource?.dispose() } catch (_: Throwable) {}
        aSource = null
        try { helper?.dispose() } catch (_: Throwable) {}
        helper = null
        try { screenSurface?.release() } catch (_: Throwable) {}
        screenSurface = null
    }
}
