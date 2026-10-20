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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings

/**
 * Service qui tourne en permanence (notification "Antivol actif").
 * Il écoute le Bluetooth : si plus aucun appareil de confiance n'est connecté
 * pendant X secondes, il verrouille le téléphone et affiche l'écran à codes.
 */
class GuardService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val connected = mutableSetOf<String>()
    private var overlay: LockOverlay? = null
    private var registered = false

    private val lockRunnable = Runnable { triggerLock() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
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
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
            registered = true
            refreshConnected()
        }

        // Si le téléphone était déjà verrouillé (redémarrage de l'appli ou du téléphone), on remet l'écran à codes.
        if (Prefs.isLocked(this)) showOverlay()

        // Bouton "Tester" de l'appli : verrouillage dans 10 secondes.
        if (intent?.getBooleanExtra("test", false) == true) {
            handler.removeCallbacks(lockRunnable)
            handler.postDelayed(lockRunnable, 10_000)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (registered) {
            try { unregisterReceiver(receiver) } catch (_: Exception) {}
            registered = false
        }
        overlay?.hide()
        overlay = null
        super.onDestroy()
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
    }

    private fun showOverlay() {
        // Sécurité : sans code configuré ou sans autorisation, on ne bloque pas l'utilisateur dehors.
        if (CodeStore.count(this) == 0) { Prefs.setLocked(this, false); return }
        if (!Settings.canDrawOverlays(this)) return
        if (overlay != null) return
        overlay = LockOverlay(this) {
            Prefs.setLocked(this, false)
            overlay = null
        }.also { it.show() }
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
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }
    }
}
