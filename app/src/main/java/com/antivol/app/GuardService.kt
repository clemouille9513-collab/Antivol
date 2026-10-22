package com.antivol.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.media.FaceDetector
import android.os.PowerManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

/**
 * Service qui tourne en permanence (notification "Antivol actif").
 * - Surveille le Bluetooth : si plus aucun appareil de confiance n'est connecté
 *   pendant X secondes, il verrouille le téléphone et affiche l'écran à codes.
 * - Écoute les ordres de la télécommande et du site (verrouiller, volé, alarme, parler, localiser,
 *   photo, libérer) et leur envoie un journal d'événements.
 */
class GuardService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val connected = mutableSetOf<String>()
    private var overlay: LockOverlay? = null
    private var registered = false
    private var subscription: Ntfy.Subscription? = null
    private var stolenMode: StolenMode? = null
    private var pendingReason = "Appareil de confiance perdu"
    private var eco = false
    private var simAbsent = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** Signal de vie envoyé toutes les 5 minutes : la télécommande sait que le S22 est joignable. */
    private val hbRunnable = object : Runnable {
        override fun run() {
            reply("hb|${if (Prefs.isLocked(this@GuardService)) 1 else 0}|${if (Prefs.isStolen(this@GuardService)) 1 else 0}|${battery()}|${Ntfy.netType}")
            handler.postDelayed(this, 300_000)
        }
    }

    /** Position envoyée toutes les 10 minutes pour dessiner les trajets. */
    private val trackRunnable = object : Runnable {
        override fun run() {
            if (Prefs.isEnabled(this@GuardService) && Prefs.isTracking(this@GuardService)) sendLocation()
            handler.postDelayed(this, if (eco || battery() < 15) 1_800_000 else 600_000)
        }
    }
    private var peekOverlay: PeekOverlay? = null
    private var peekBusy = false
    private var peekPausedUntil = 0L

    private val peekRunnable = object : Runnable {
        override fun run() {
            try { scanForPeekers() } catch (_: Exception) { peekBusy = false }
            handler.postDelayed(this, 30_000)
        }
    }

    private val lockRunnable = Runnable { triggerLock(pendingReason) }

    private fun locInterval(): Long = if (eco || battery() < 15) 600_000 else 180_000

    private val locRunnable = object : Runnable {
        override fun run() {
            if (Prefs.isLocked(this@GuardService) || Prefs.isStolen(this@GuardService)) {
                sendLocation()
                handler.postDelayed(this, locInterval())
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    if (intent.getBooleanExtra("state", false)) {
                        connected.clear()
                        event("airplane", "Mode avion activé")
                        triggerLock("Mode avion activé")
                    }
                    return
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                        connected.clear()
                        event("bluetooth", "Bluetooth coupé")
                        scheduleLock("Bluetooth coupé")
                    }
                    return
                }
                Intent.ACTION_BATTERY_LOW -> {
                    eco = true
                    event("battery", "Batterie faible : dernière position envoyée, mode éco")
                    sendLocation()
                    overlay?.dim(true)
                    return
                }
                Intent.ACTION_BATTERY_OKAY -> { eco = false; return }
                Intent.ACTION_SHUTDOWN -> {
                    event("shutdown", "Extinction du téléphone détectée")
                    sendLocation()
                    return
                }
                "android.intent.action.SIM_STATE_CHANGED" -> {
                    val ss = intent.getStringExtra("ss")
                    if (ss == "ABSENT" && !simAbsent) {
                        simAbsent = true
                        event("sim", "Carte SIM retirée")
                        sendLocation()
                        triggerLock("Carte SIM retirée")
                    } else if (ss == "LOADED" && simAbsent) {
                        simAbsent = false
                        event("sim", "Carte SIM insérée")
                    }
                    return
                }
            }

            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            val address = device?.address ?: return
            if (address !in Prefs.trustedDevices(context)) return

            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    connected.add(address)
                    handler.removeCallbacks(lockRunnable)
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    connected.remove(address)
                    if (connected.isEmpty()) {
                        event("bluetooth", "Appareil de confiance perdu")
                        scheduleLock("Appareil de confiance perdu")
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()

        // Garde le processeur éveillé pour que la connexion à la télécommande reste ouverte écran éteint.
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "antivol:garde").apply {
                setReferenceCounted(false)
                try { acquire() } catch (_: Exception) {}
            }
        }

        Ntfy.watchNetwork(this)
        if (wifiLock == null) {
            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wm.createWifiLock(mode, "antivol:wifi").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (_: Exception) {}
        }
        handler.removeCallbacks(hbRunnable)
        handler.postDelayed(hbRunnable, 20_000)
        handler.removeCallbacks(trackRunnable)
        handler.postDelayed(trackRunnable, 60_000)

        // Détection des regards : une photo avant toutes les 30 secondes quand l'écran est allumé.
        handler.removeCallbacks(peekRunnable)
        handler.postDelayed(peekRunnable, 30_000)

        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_BATTERY_OKAY)
                addAction(Intent.ACTION_SHUTDOWN)
                addAction("android.intent.action.SIM_STATE_CHANGED")
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
            registered = true
            refreshConnected()
        }

        // Écoute des ordres de la télécommande.
        if (subscription == null) {
            val secret = Prefs.secret(this)
            subscription = Ntfy.Subscription(Crypto.topic(secret, "cmd"), "6m") { body ->
                handleCommand(secret, body)
            }.start()
            event("service", "Protection démarrée, batterie ${battery()} %")
        }

        // Si le téléphone était déjà verrouillé (redémarrage de l'appli ou du téléphone), on remet l'écran à codes.
        if (Prefs.isLocked(this)) showOverlay()
        if (Prefs.isStolen(this)) {
            stolen().start(siren = false, withSpeech = true)
            handler.removeCallbacks(locRunnable)
            handler.post(locRunnable)
        }

        if (intent?.getBooleanExtra("boot", false) == true) checkAfterBoot()

        // Bouton "Tester" de l'appli : verrouillage dans 10 secondes.
        if (intent?.getBooleanExtra("test", false) == true) {
            pendingReason = "Test de verrouillage"
            handler.removeCallbacks(lockRunnable)
            handler.postDelayed(lockRunnable, 10_000)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        subscription?.stop()
        subscription = null
        stolenMode?.stop()
        stolenMode = null
        if (registered) {
            try { unregisterReceiver(receiver) } catch (_: Exception) {}
            registered = false
        }
        overlay?.hide()
        overlay = null
        peekOverlay?.hide()
        peekOverlay = null
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wifiLock = null
        super.onDestroy()
    }

    private fun stolen(): StolenMode {
        var s = stolenMode
        if (s == null) {
            s = StolenMode(this)
            stolenMode = s
        }
        return s
    }

    // ---------- Ordres de la télécommande ----------

    private fun handleCommand(secret: String, body: String) {
        val plain = Crypto.decrypt(secret, body) ?: return
        val p = plain.split("|", limit = 3)
        if (p.size < 2) return
        val ts = p[0].toLongOrNull() ?: return
        if (abs(System.currentTimeMillis() / 1000 - ts) > 300) return
        if (!Prefs.markSeen(this, plain)) return
        val cmd = p[1]
        val arg = p.getOrElse(2) { "" }.take(200)

        handler.post {
            when (cmd) {
                "lock" -> triggerLock("Ordre de la télécommande", force = true)
                "stolen" -> startStolen()
                "alarm" -> stolen().start(siren = true, withSpeech = false)
                "say" -> stolen().say(arg)
                "locate" -> sendLocation()
                "photo" -> takePhoto(arg != "back", "Demande de la télécommande")
                "free" -> release()
            }
            reply("ack|$cmd|${if (Prefs.isLocked(this)) 1 else 0}|${if (Prefs.isStolen(this)) 1 else 0}|${battery()}")
        }
    }

    private fun reply(text: String) {
        val secret = Prefs.secret(this)
        val plain = "${System.currentTimeMillis() / 1000}|$text"
        Ntfy.publish(Crypto.topic(secret, "rep"), Crypto.encrypt(secret, plain))
    }

    private fun event(type: String, detail: String) = reply("evt|$type|$detail")

    private fun battery(): Int =
        getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun startStolen() {
        Prefs.setStolen(this, true)
        event("stolen", "Mode volé activé")
        triggerLock("Mode volé")
        showOverlay()
        overlay?.dim(true)
        stolen().start(siren = false, withSpeech = true)
        sendLocation()
        handler.removeCallbacks(locRunnable)
        handler.postDelayed(locRunnable, locInterval())
    }

    /** Arrête tout : alarme, voix, écran de blocage. */
    private fun release() {
        if (Prefs.isLocked(this) || Prefs.isStolen(this)) event("unlocked", "Téléphone libéré")
        Prefs.setStolen(this, false)
        Prefs.setLocked(this, false)
        handler.removeCallbacks(lockRunnable)
        handler.removeCallbacks(locRunnable)
        stolenMode?.stop()
        overlay?.hide()
        overlay = null
    }

    // ---------- Position ----------

    @SuppressLint("MissingPermission")
    private fun sendLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = getSystemService(LocationManager::class.java) ?: return

        val best = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).mapNotNull {
            try { lm.getLastKnownLocation(it) } catch (_: Exception) { null }
        }.maxByOrNull { it.time }
        best?.let { replyLocation(it) }

        for (provider in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
            try {
                if (!lm.isProviderEnabled(provider)) continue
                lm.requestSingleUpdate(provider, object : LocationListener {
                    override fun onLocationChanged(location: Location) = replyLocation(location)
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }, Looper.getMainLooper())
            } catch (_: Exception) {
            }
        }
    }

    private fun replyLocation(l: Location) {
        reply("loc|${l.latitude}|${l.longitude}|${l.accuracy.toInt()}")
    }

    // ---------- Photos ----------

    /** Prend une photo (avant ou arrière) et l'envoie chiffrée en morceaux. */
    private fun takePhoto(front: Boolean, why: String) {
        // Une fenêtre invisible d'1 pixel aide Android à autoriser la caméra en arrière-plan.
        var marker: View? = null
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Settings.canDrawOverlays(this)) {
            try {
                marker = View(this)
                wm.addView(
                    marker,
                    WindowManager.LayoutParams(
                        1, 1,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT
                    )
                )
            } catch (_: Exception) {
                marker = null
            }
        }
        PhotoCapture(this).capture(front) { bytes ->
            handler.post { try { marker?.let { wm.removeView(it) } } catch (_: Exception) {} }
            if (bytes == null) {
                event("photo", "Photo impossible : Android a refusé l'accès à la caméra")
            } else {
                sendPhoto(bytes, front, why)
            }
        }
    }

    private fun sendPhoto(bytes: ByteArray, front: Boolean, why: String) {
        val secret = Prefs.secret(this)
        val enc = Crypto.encryptBytes(secret, bytes)
        val chunks = enc.chunked(2600)
        val id = System.currentTimeMillis() / 1000
        val facing = if (front) "front" else "back"
        event("photo", "Photo ${if (front) "avant" else "arrière"} envoyée ($why)")
        for ((i, c) in chunks.withIndex()) {
            reply("photo|$id|$i|${chunks.size}|$facing|$c")
        }
    }

    // ---------- Regards indiscrets ----------

    private fun scanForPeekers() {
        if (!Prefs.isPeekEnabled(this) || peekBusy) return
        if (System.currentTimeMillis() < peekPausedUntil) return
        if (Prefs.isLocked(this) || Prefs.isStolen(this)) return
        if (peekOverlay?.isShown == true) return
        if (!Settings.canDrawOverlays(this)) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        // Seulement quand quelqu'un utilise vraiment le téléphone : écran allumé et déverrouillé.
        if (!getSystemService(PowerManager::class.java).isInteractive) return
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) return

        peekBusy = true
        PhotoCapture(this).capture(true, 640) { bytes ->
            val faces = if (bytes == null) 0 else countFaces(bytes)
            handler.post {
                peekBusy = false
                if (faces >= 2) showPeekOverlay(faces)
            }
        }
    }

    /** Compte les visages d'une photo JPEG (détecteur intégré à Android, jusqu'à 5). */
    private fun countFaces(jpeg: ByteArray): Int = try {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        if (src == null) 0 else {
            // Le détecteur exige une largeur paire.
            val w = src.width - (src.width % 2)
            val bmp = if (w == src.width) src else Bitmap.createBitmap(src, 0, 0, w, src.height)
            val found = arrayOfNulls<FaceDetector.Face>(5)
            FaceDetector(bmp.width, bmp.height, found.size).findFaces(bmp, found)
                .let { n -> found.take(n).count { it != null && it.confidence() > 0.4f } }
        }
    } catch (_: Exception) { 0 }

    private fun showPeekOverlay(faces: Int) {
        if (peekOverlay?.isShown == true || Prefs.isLocked(this)) return
        event("peek", "$faces visages devant l'écran")
        val o = PeekOverlay(this) {
            peekOverlay = null
            // Après « Déverrouiller », on laisse une minute de tranquillité.
            peekPausedUntil = System.currentTimeMillis() + 60_000
        }
        peekOverlay = o
        try { o.show() } catch (_: Exception) { peekOverlay = null }
    }

    // ---------- Logique de verrouillage ----------

    private fun scheduleLock(reason: String) {
        if (Prefs.trustedDevices(this).isEmpty()) return
        pendingReason = reason
        handler.removeCallbacks(lockRunnable)
        handler.postDelayed(lockRunnable, Prefs.graceSeconds(this) * 1000L)
    }

    private fun triggerLock(reason: String, force: Boolean = false) {
        // Un ordre de la télécommande verrouille toujours l'écran, même si on l'était déjà.
        if (Prefs.isLocked(this) && !force) return
        Prefs.setLocked(this, true)
        event("locked", "Verrouillé : $reason")
        peekOverlay?.hide()
        peekOverlay = null
        showOverlay()
        val dpm = getSystemService(DevicePolicyManager::class.java)
        if (dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))) {
            dpm.lockNow()
        } else {
            event("admin", "Verrouillage impossible : l'administrateur n'est pas activé")
        }
        handler.removeCallbacks(locRunnable)
        handler.postDelayed(locRunnable, 5_000)
    }

    private fun showOverlay() {
        // Sécurité : sans code configuré ou sans autorisation, on ne bloque pas l'utilisateur dehors.
        if (CodeStore.count(this) == 0) { Prefs.setLocked(this, false); return }
        if (!Settings.canDrawOverlays(this)) return
        if (overlay != null) return
        overlay = LockOverlay(
            this,
            onWrong = { count ->
                event("wrong", "$count mauvais code(s) saisi(s)")
                if (count % 3 == 0) {
                    sendLocation()
                    takePhoto(true, "$count mauvais codes")
                }
            },
            onUnlocked = {
                overlay = null
                release()
            }
        ).also {
            it.show()
            if (Prefs.isStolen(this) || eco) it.dim(true)
        }
    }

    /** Regarde quels appareils de confiance sont déjà connectés au démarrage du service. */
    @SuppressLint("MissingPermission")
    private fun refreshConnected() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return
        val trusted = Prefs.trustedDevices(this)
        if (trusted.isEmpty()) return
        val manager = getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter ?: return

        connected.clear()
        try {
            manager.getConnectedDevices(BluetoothProfile.GATT)
                .filter { it.address in trusted }
                .forEach { connected.add(it.address) }
        } catch (_: Exception) {}

        for (profile in intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                    try {
                        proxy.connectedDevices
                            .filter { it.address in trusted }
                            .forEach { connected.add(it.address) }
                    } catch (_: Exception) {}
                    adapter.closeProfileProxy(p, proxy)
                }
                override fun onServiceDisconnected(p: Int) {}
            }, profile)
        }

        // Activer la protection ne verrouille jamais le téléphone sur le moment.
        // Seul un redémarrage du téléphone déclenche une vérification (voir "boot").
    }

    /** Après un redémarrage : si aucun appareil de confiance n'est là au bout d'1 minute, on verrouille. */
    private fun checkAfterBoot() {
        handler.postDelayed({
            if (connected.isEmpty()) scheduleLock("Aucun appareil de confiance après redémarrage")
        }, 60_000)
    }

    // ---------- Notification obligatoire d'un service permanent ----------

    private fun startAsForeground() {
        val channelId = "antivol_garde"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Protection antivol", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Antivol actif")
            .setContentText("Surveille tes appareils de confiance")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            val loc = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val cam = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            // Android refuse caméra et position quand le service démarre en arrière-plan (redémarrage du téléphone) :
            // on essaie du plus complet au plus simple, sans jamais planter.
            val tries = mutableListOf<Int>()
            if (loc && cam) tries += base or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (loc) tries += base or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            tries += base
            for (t in tries) {
                try { startForeground(1, notification, t); return } catch (_: Exception) {}
            }
            try { startForeground(1, notification) } catch (_: Exception) { stopSelf() }
        } else {
            startForeground(1, notification)
        }
    }
}
