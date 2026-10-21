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
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import kotlin.math.abs

/**
 * Service qui tourne en permanence (notification "Antivol actif").
 * - Surveille le Bluetooth : si plus aucun appareil de confiance n'est connecté
 *   pendant X secondes, il verrouille le téléphone et affiche l'écran à codes.
 * - Écoute les ordres de la télécommande (verrouiller, volé, alarme, parler, localiser, libérer).
 */
class GuardService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val connected = mutableSetOf<String>()
    private var overlay: LockOverlay? = null
    private var registered = false
    private var subscription: Ntfy.Subscription? = null
    private var stolenMode: StolenMode? = null

    private val lockRunnable = Runnable { triggerLock() }

    private val locRunnable = object : Runnable {
        override fun run() {
            if (Prefs.isLocked(this@GuardService) || Prefs.isStolen(this@GuardService)) {
                sendLocation()
                handler.postDelayed(this, 180_000)
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_AIRPLANE_MODE_CHANGED) {
                if (intent.getBooleanExtra("state", false)) {
                    connected.clear()
                    triggerLock()
                }
                return
            }
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    // Bluetooth coupé : on considère que les appareils de confiance sont perdus.
                    connected.clear()
                    scheduleLock()
                }
                return
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
                    if (connected.isEmpty()) scheduleLock()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()

        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
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
            subscription = Ntfy.Subscription(Crypto.topic(secret, "cmd"), "1m") { body ->
                handleCommand(secret, body)
            }.start()
        }

        // Si le téléphone était déjà verrouillé (redémarrage de l'appli ou du téléphone), on remet l'écran à codes.
        if (Prefs.isLocked(this)) showOverlay()
        if (Prefs.isStolen(this)) {
            stolen().start(siren = false, withSpeech = true)
            handler.removeCallbacks(locRunnable)
            handler.post(locRunnable)
        }

        // Bouton "Tester" de l'appli : verrouillage dans 10 secondes.
        if (intent?.getBooleanExtra("test", false) == true) {
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
        val cmd = p[1]
        val arg = p.getOrElse(2) { "" }.take(200)

        handler.post {
            when (cmd) {
                "lock" -> triggerLock()
                "stolen" -> startStolen()
                "alarm" -> stolen().start(siren = true, withSpeech = false)
                "say" -> stolen().say(arg)
                "locate" -> sendLocation()
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

    private fun battery(): Int =
        getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun startStolen() {
        Prefs.setStolen(this, true)
        triggerLock()
        showOverlay()
        overlay?.dim(true)
        stolen().start(siren = false, withSpeech = true)
        sendLocation()
        handler.removeCallbacks(locRunnable)
        handler.postDelayed(locRunnable, 180_000)
    }

    /** Arrête tout : alarme, voix, écran de blocage. */
    private fun release() {
        Prefs.setStolen(this, false)
        Prefs.setLocked(this, false)
        handler.removeCallbacks(lockRunnable)
        handler.removeCallbacks(locRunnable)
        stolenMode?.stop()
        overlay?.hide()
        overlay = null
    }

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

    // ---------- Logique de verrouillage ----------

    private fun scheduleLock() {
        if (Prefs.trustedDevices(this).isEmpty()) return
        handler.removeCallbacks(lockRunnable)
        handler.postDelayed(lockRunnable, Prefs.graceSeconds(this) * 1000L)
    }

    private fun triggerLock() {
        if (Prefs.isLocked(this)) return
        Prefs.setLocked(this, true)
        showOverlay()
        val dpm = getSystemService(DevicePolicyManager::class.java)
        if (dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))) {
            dpm.lockNow()
        }
        handler.removeCallbacks(locRunnable)
        handler.postDelayed(locRunnable, 5_000)
    }

    private fun showOverlay() {
        // Sécurité : sans code configuré ou sans autorisation, on ne bloque pas l'utilisateur dehors.
        if (CodeStore.count(this) == 0) { Prefs.setLocked(this, false); return }
        if (!Settings.canDrawOverlays(this)) return
        if (overlay != null) return
        overlay = LockOverlay(this) {
            overlay = null
            release()
        }.also {
            it.show()
            if (Prefs.isStolen(this)) it.dim(true)
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

        // Après 6 secondes, si aucun appareil de confiance n'est détecté : compte à rebours de verrouillage.
        handler.postDelayed({
            if (connected.isEmpty()) scheduleLock()
        }, 6_000)
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
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            startForeground(1, notification, type)
        } else {
            startForeground(1, notification)
        }
    }
}
