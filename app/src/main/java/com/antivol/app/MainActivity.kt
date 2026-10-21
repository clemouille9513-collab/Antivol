package com.antivol.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var container: LinearLayout
    private lateinit var toggleButton: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun admin() = ComponentName(this, AdminReceiver::class.java)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(container) })

        container.addView(TextView(this).apply {
            text = "Antivol"
            textSize = 28f
        })
        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(12), 0, dp(12))
        }
        container.addView(status)

        addButton("1. Activer l'administrateur de l'appareil") {
            startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin())
                    .putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Nécessaire pour verrouiller l'écran quand ta montre s'éloigne."
                    )
            )
        }
        addButton("2. Autoriser l'affichage par-dessus les autres applis") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        addButton("3. Autoriser le Bluetooth et les notifications") {
            val perms = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= 31) perms += Manifest.permission.BLUETOOTH_CONNECT
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            perms += Manifest.permission.ACCESS_FINE_LOCATION
            if (perms.isNotEmpty()) requestPermissions(perms.toTypedArray(), 1)
        }
        addButton("4. Choisir les appareils de confiance (montre, AirPods...)") {
            verifyThen { chooseDevices() }
        }
        addButton("Position : autoriser « tout le temps »") {
            toast("Ouvre Autorisations, puis Position, puis « Toujours autoriser »")
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
        addButton("Clé de liaison pour la télécommande") { verifyThen { showKey() } }
        addButton("Utiliser ce téléphone comme télécommande") {
            startActivity(Intent(this, RemoteActivity::class.java))
        }
        addButton("5. Ajouter un code") { verifyThen { addCodeDialog() } }
        addButton("Effacer tous les codes") {
            verifyThen {
                CodeStore.clearCodes(this)
                refresh()
            }
        }
        addButton("6. Générer des codes de secours") {
            verifyThen { showRecoveryCodes() }
        }
        toggleButton = addButton("7. Activer la protection") { toggleProtection() }
        addButton("Tester (verrouillage dans 10 secondes)") { testLock() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun addButton(label: String, onClick: () -> Unit): Button {
        val b = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        container.addView(b)
        return b
    }

    // ---------- État ----------

    private fun adminActive() =
        getSystemService(DevicePolicyManager::class.java).isAdminActive(admin())

    private fun btGranted() = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun mark(ok: Boolean) = if (ok) "✔" else "✘"

    private fun refresh() {
        val trusted = Prefs.trustedDevices(this).size
        val codes = CodeStore.count(this)
        val recovery = CodeStore.recoveryCount(this)
        status.text = buildString {
            appendLine("${mark(adminActive())} Administrateur de l'appareil")
            appendLine("${mark(Settings.canDrawOverlays(this@MainActivity))} Affichage par-dessus les applis")
            appendLine("${mark(btGranted())} Bluetooth autorisé")
            appendLine("${mark(trusted > 0)} Appareils de confiance : $trusted")
            appendLine("${mark(codes > 0)} Codes enregistrés : $codes")
            appendLine("${mark(recovery > 0)} Codes de secours restants : $recovery")
            append("${mark(Prefs.isEnabled(this@MainActivity))} Protection active")
        }
        toggleButton.text =
            if (Prefs.isEnabled(this)) "7. Désactiver la protection" else "7. Activer la protection"
    }

    // ---------- Demande des codes avant une action sensible ----------

    private fun passwordField() = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    /** Si des codes existent, il faut les saisir tous dans l'ordre avant de continuer. */
    private fun verifyThen(action: () -> Unit) {
        val n = CodeStore.count(this)
        if (n == 0) { action(); return }

        fun step(i: Int) {
            val field = passwordField()
            AlertDialog.Builder(this)
                .setTitle("Code ${i + 1} sur $n")
                .setView(field)
                .setPositiveButton("OK") { _, _ ->
                    if (CodeStore.check(this, i, field.text.toString())) {
                        if (i + 1 < n) step(i + 1) else action()
                    } else {
                        toast("Code incorrect")
                    }
                }
                .setNegativeButton("Annuler", null)
                .show()
        }
        step(0)
    }

    // ---------- Actions ----------

    @SuppressLint("MissingPermission")
    private fun chooseDevices() {
        if (!btGranted()) { toast("Autorise d'abord le Bluetooth (étape 3)"); return }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val bonded = adapter?.bondedDevices?.toList() ?: emptyList()
        if (bonded.isEmpty()) {
            toast("Aucun appareil appairé. Appaire ta montre ou tes AirPods dans les réglages Bluetooth.")
            return
        }
        val names = bonded.map { "${it.name ?: "Appareil"} (${it.address})" }.toTypedArray()
        val trusted = Prefs.trustedDevices(this)
        val checked = BooleanArray(bonded.size) { bonded[it].address in trusted }

        AlertDialog.Builder(this)
            .setTitle("Appareils de confiance")
            .setMultiChoiceItems(names, checked) { _, i, v -> checked[i] = v }
            .setPositiveButton("OK") { _, _ ->
                val chosen = bonded.indices.filter { checked[it] }.map { bonded[it].address }.toSet()
                Prefs.setTrusted(this, chosen)
                refresh()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun addCodeDialog() {
        val field = passwordField()
        AlertDialog.Builder(this)
            .setTitle("Nouveau code (4 à 12 chiffres)")
            .setView(field)
            .setPositiveButton("Ajouter") { _, _ ->
                val code = field.text.toString()
                if (code.length in 4..12) {
                    CodeStore.addCode(this, code)
                    toast("Code ${CodeStore.count(this)} enregistré")
                    refresh()
                } else {
                    toast("Le code doit faire entre 4 et 12 chiffres")
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showKey() {
        AlertDialog.Builder(this)
            .setTitle("Clé de liaison")
            .setMessage(
                Crypto.pretty(Prefs.secret(this)) +
                    "\n\nTape cette clé dans la télécommande, sur l'autre téléphone. " +
                    "Ne la partage avec personne."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showRecoveryCodes() {
        val codes = CodeStore.generateRecovery(this)
        AlertDialog.Builder(this)
            .setTitle("Codes de secours")
            .setMessage(
                "Note-les sur papier, dans un endroit sûr. Ils ne seront plus affichés. " +
                    "Chacun ne marche qu'une fois.\n\n" + codes.joinToString("\n")
            )
            .setCancelable(false)
            .setPositiveButton("C'est noté") { _, _ -> refresh() }
            .show()
    }

    private fun toggleProtection() {
        if (Prefs.isEnabled(this)) {
            verifyThen {
                Prefs.setEnabled(this, false)
                Prefs.setLocked(this, false)
                stopService(Intent(this, GuardService::class.java))
                refresh()
            }
            return
        }
        val missing = mutableListOf<String>()
        if (!adminActive()) missing += "administrateur"
        if (!Settings.canDrawOverlays(this)) missing += "affichage par-dessus"
        if (!btGranted()) missing += "Bluetooth"
        if (Prefs.trustedDevices(this).isEmpty()) missing += "appareil de confiance"
        if (CodeStore.count(this) == 0) missing += "au moins un code"
        if (CodeStore.recoveryCount(this) == 0) missing += "codes de secours"
        if (missing.isNotEmpty()) {
            toast("Il manque : " + missing.joinToString(", "))
            return
        }
        Prefs.setEnabled(this, true)
        startForegroundService(Intent(this, GuardService::class.java))
        refresh()
    }

    private fun testLock() {
        if (!Prefs.isEnabled(this)) { toast("Active d'abord la protection (étape 7)"); return }
        startForegroundService(Intent(this, GuardService::class.java).putExtra("test", true))
        toast("Verrouillage dans 10 secondes")
    }
}
