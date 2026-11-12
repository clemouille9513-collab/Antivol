package com.antivol.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Vraie position, comme sur Maps : GPS haute précision via les services Google (le même fournisseur que le point bleu de Maps).
 * On n'envoie JAMAIS une ancienne position gardée en mémoire : seulement des mesures fraîches.
 */
object Locator {
    private const val MAX_AGE_MS = 20_000L
    @Volatile private var liveCb: LocationCallback? = null
    @Volatile private var liveListener: LocationListener? = null

    private fun granted(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun fresh(l: Location) = System.currentTimeMillis() - l.time < MAX_AGE_MS

    /**
     * Filtre de Kalman : un téléphone immobile ne fait plus « sauter » le point.
     * Chaque mesure est pondérée par sa précision ; un mouvement réel (vitesse) rend le filtre plus réactif.
     */
    private class Smoother {
        private var lat = 0.0
        private var lon = 0.0
        private var variance = -1.0
        private var t = 0L

        fun reset() { variance = -1.0 }

        fun add(l: Location): Location {
            val now = System.currentTimeMillis()
            val acc = l.accuracy.coerceIn(3f, 500f).toDouble()
            var jump = false
            if (variance >= 0) {
                val d = FloatArray(1)
                Location.distanceBetween(lat, lon, l.latitude, l.longitude, d)
                jump = d[0] > 60 + acc * 3
            }
            if (variance < 0 || now - t > 90_000 || jump) {
                lat = l.latitude; lon = l.longitude; variance = acc * acc
            } else {
                val dt = ((now - t) / 1000.0).coerceIn(0.0, 30.0)
                val speed = if (l.hasSpeed()) l.speed.toDouble() else 1.0
                val q = maxOf(speed, 0.5)
                variance += dt * q * q
                val k = variance / (variance + acc * acc)
                lat += k * (l.latitude - lat)
                lon += k * (l.longitude - lon)
                variance *= (1 - k)
            }
            t = now
            return Location(l).apply {
                latitude = lat
                longitude = lon
                accuracy = maxOf(Math.sqrt(variance).toFloat(), 6f)
                time = now
            }
        }
    }

    private val smoother = Smoother()

    /**
     * Cherche la meilleure position pendant 25 secondes au maximum.
     * [onFix] est appelé dès qu'une mesure correcte arrive, puis encore si une bien plus précise suit.
     */
    @SuppressLint("MissingPermission")
    fun fresh(ctx: Context, onFix: (Location) -> Unit) {
        if (!granted(ctx)) return
        val h = Handler(Looper.getMainLooper())
        var best: Location? = null
        var sent: Location? = null
        var finished = false

        fun consider(raw: Location, stop: () -> Unit) {
            if (!fresh(raw)) return
            val l = smoother.add(raw)
            best = l
            val s = sent
            if (s == null && l.accuracy <= 30f) { sent = l; onFix(l) }
            else if (s != null && l.accuracy < s.accuracy * 0.7f) { sent = l; onFix(l) }
            if (l.accuracy <= 10f && !finished) { finished = true; stop() }
        }

        try {
            val client = LocationServices.getFusedLocationProviderClient(ctx)
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(500L)
                .setDurationMillis(25_000L)
                .build()
            lateinit var cb: LocationCallback
            val stop = { try { client.removeLocationUpdates(cb) } catch (_: Exception) {} ; Unit }
            cb = object : LocationCallback() {
                override fun onLocationResult(r: LocationResult) { for (l in r.locations) consider(l, stop) }
            }
            client.requestLocationUpdates(req, cb, Looper.getMainLooper())
            // Si le GPS tarde (intérieur), on envoie quand même la meilleure mesure correcte après 6 s.
            h.postDelayed({
                val b = best
                if (sent == null && b != null && b.accuracy <= 150f) { sent = b; onFix(b) }
            }, 6_000L)
            h.postDelayed({
                stop()
                val b = best; val s = sent
                if (b != null && (s == null || b.accuracy < s.accuracy)) onFix(b)
            }, 26_000L)
        } catch (_: Throwable) {
            fallback(ctx, h, onFix)
        }
    }

    /** Sans les services Google : GPS et réseau, mesures fraîches uniquement. */
    @SuppressLint("MissingPermission")
    private fun fallback(ctx: Context, h: Handler, onFix: (Location) -> Unit) {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        var sent = false
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) { if (fresh(location)) { sent = true; onFix(location) } }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try { if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 1000L, 0f, l, Looper.getMainLooper()) } catch (_: Exception) {}
        }
        h.postDelayed({ try { lm.removeUpdates(l) } catch (_: Exception) {}; if (!sent) { /* rien de frais */ } }, 25_000L)
    }

    /** Suivi en direct : une mesure toutes les 2 secondes tant que [stopLive] n'est pas appelé. */
    @SuppressLint("MissingPermission")
    fun live(ctx: Context, onFix: (Location) -> Unit) {
        stopLive(ctx)
        if (!granted(ctx)) return
        try {
            val client = LocationServices.getFusedLocationProviderClient(ctx)
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).setMinUpdateIntervalMillis(1000L).build()
            val cb = object : LocationCallback() {
                override fun onLocationResult(r: LocationResult) {
                    r.lastLocation?.let { if (fresh(it)) onFix(smoother.add(it)) }
                }
            }
            liveCb = cb
            client.requestLocationUpdates(req, cb, Looper.getMainLooper())
        } catch (_: Throwable) {
            val lm = ctx.getSystemService(LocationManager::class.java) ?: return
            val l = object : LocationListener {
                override fun onLocationChanged(location: Location) { if (fresh(location)) onFix(location) }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
            }
            liveListener = l
            try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, l, Looper.getMainLooper()) } catch (_: Exception) {}
        }
    }

    fun stopLive(ctx: Context) {
        liveCb?.let { try { LocationServices.getFusedLocationProviderClient(ctx).removeLocationUpdates(it) } catch (_: Throwable) {} }
        liveCb = null
        liveListener?.let { try { ctx.getSystemService(LocationManager::class.java)?.removeUpdates(it) } catch (_: Exception) {} }
        liveListener = null
    }
}
