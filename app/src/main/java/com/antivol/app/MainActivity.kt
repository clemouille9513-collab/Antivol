package com.antivol.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    private var tick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(0xFF0B0E14.toInt()))
        setContent { AntivolTheme { Home(this, tick) } }
    }

    override fun onResume() {
        super.onResume()
        ensureListening()
        tick++
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        tick++
    }

    // ---------- État ----------

    fun admin() = ComponentName(this, AdminReceiver::class.java)

    fun adminActive() = getSystemService(DevicePolicyManager::class.java).isAdminActive(admin())

    fun btGranted() = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun locGranted() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun bgLocGranted() = Build.VERSION.SDK_INT < 29 ||
        checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun bonded(): List<Pair<String, String>> {
        if (!btGranted()) return emptyList()
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return adapter.bondedDevices?.map { Pair(it.name ?: "Appareil", it.address) } ?: emptyList()
    }

    // ---------- Actions ----------

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    fun askAdmin() {
        startActivity(
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin())
                .putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Nécessaire pour verrouiller l'écran quand ta montre s'éloigne."
                )
        )
    }

    fun askOverlay() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    fun askPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) perms += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        perms += Manifest.permission.ACCESS_FINE_LOCATION
        perms += Manifest.permission.CAMERA
        perms += Manifest.permission.RECORD_AUDIO
        perms += Manifest.permission.SEND_SMS
        requestPermissions(perms.toTypedArray(), 1)
    }

    fun micGranted() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun smsGranted() = checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    fun camGranted() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Enregistre le visage du propriétaire : 4 photos de la caméra avant, comparées ensuite pendant une course. */
    fun enrollFace(done: (String) -> Unit) {
        if (!camGranted()) { askPermissions(); done("Autorise la caméra, puis recommence."); return }
        toast("Regarde la caméra avant, sans bouger, pendant 5 secondes")
        Thread {
            val shots = ArrayList<ByteArray>()
            for (i in 0 until 4) {
                val latch = CountDownLatch(1)
                var jpg: ByteArray? = null
                PhotoCapture(this).capture(true, 720) { b -> jpg = b; latch.countDown() }
                latch.await(12, TimeUnit.SECONDS)
                jpg?.let { shots += it }
                try { Thread.sleep(600) } catch (_: InterruptedException) {}
            }
            val msg = try { Biometrics.enrollFace(this, shots) } catch (e: Exception) { "Erreur : ${e.message ?: e.javaClass.simpleName}" }
            runOnUiThread { done(msg); tick++ }
        }.start()
    }

    /** Enregistre la voix du propriétaire (7 secondes de parole). */
    fun enrollVoice(done: (String) -> Unit) {
        if (!micGranted()) { askPermissions(); done("Autorise le micro, puis recommence."); return }
        toast("Parle normalement pendant 7 secondes (lis un texte à voix haute)")
        Thread {
            val msg = try { Biometrics.enrollVoice(this) } catch (e: Exception) { "Erreur : ${e.message ?: e.javaClass.simpleName}" }
            runOnUiThread { done(msg); tick++ }
        }.start()
    }

    fun askAlwaysLocation() {
        toast("Ouvre Autorisations, puis Position, puis « Toujours autoriser »")
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
    }

    fun batteryUnrestricted() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife")
    fun askBatteryUnrestricted() {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
    }

    fun askShield() {
        toast("Ouvre « Applications installées », choisis Antivol et active-le. Si Android refuse : Infos de l'appli, menu ⋮, « Autoriser les paramètres restreints ».")
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun openRemote() = startActivity(Intent(this, RemoteActivity::class.java))

    fun testLock() {
        if (!Prefs.isEnabled(this)) { toast("Active d'abord la protection"); return }
        startForegroundService(Intent(this, GuardService::class.java).putExtra("test", true))
        toast("Verrouillage dans 10 secondes")
    }

    /** Renvoie null si tout est prêt, sinon la liste de ce qui manque. */
    fun missing(): String? {
        val m = mutableListOf<String>()
        if (!adminActive()) m += "administrateur"
        if (!Settings.canDrawOverlays(this)) m += "affichage par-dessus"
        if (!btGranted()) m += "Bluetooth"
        if (!batteryUnrestricted()) m += "batterie sans restriction"
        if (!ShieldService.enabled(this)) m += "protection renforcée"
        if (Prefs.trustedDevices(this).isEmpty()) m += "appareil de confiance"
        if (CodeStore.count(this) == 0) m += "au moins un code"
        if (CodeStore.recoveryCount(this) == 0) m += "codes de secours"
        return if (m.isEmpty()) null else m.joinToString(", ")
    }

    fun enableProtection() {
        val m = missing()
        if (m != null) { toast("Il manque : $m"); return }
        Prefs.setEnabled(this, true)
        startForegroundService(Intent(this, GuardService::class.java))
        tick++
    }

    fun disableProtection() {
        Prefs.setEnabled(this, false)
        Prefs.setLocked(this, false)
        if (!Prefs.isListening(this)) stopService(Intent(this, GuardService::class.java))
        tick++
    }

    /** Fait écouter la télécommande au S22, même sans protection complète. */
    fun ensureListening() {
        if (!Prefs.isEnabled(this) && !Prefs.isListening(this)) return
        if (!btGranted()) return
        try { startForegroundService(Intent(this, GuardService::class.java)) } catch (_: Exception) {}
    }

    /** Appelé quand on affiche la clé de liaison : à partir de là, le S22 écoute les ordres. */
    fun startListening() {
        if (!btGranted()) {
            toast("Autorise d'abord Bluetooth, notifications et position, puis rouvre la clé")
            askPermissions()
            return
        }
        Prefs.setListening(this, true)
        ensureListening()
    }
}

// ============================ Interface ============================

private sealed class Dlg {
    class Verify(val action: () -> Unit) : Dlg()
    object AddCode : Dlg()
    object Devices : Dlg()
    class Recovery(val codes: List<String>) : Dlg()
    object Key : Dlg()
}

@Composable
private fun Home(act: MainActivity, tick: Int) {
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    var local by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val refreshKey = tick + local

    val enabled = Prefs.isEnabled(act)
    val trusted = Prefs.trustedDevices(act).size
    val codes = CodeStore.count(act)
    val recovery = CodeStore.recoveryCount(act)
    val missing = act.missing()

    fun verifyThen(action: () -> Unit) {
        if (CodeStore.count(act) == 0) action() else dlg = Dlg.Verify(action)
    }

    Surface(color = cBg, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Header("Antivol", "Protection de ton Samsung S22")

            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(if (enabled) cGreen else cRed)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (enabled) "Protection active" else "Protection inactive",
                        fontSize = 20.sp, fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        enabled -> "Le téléphone se verrouille si tes appareils de confiance s'éloignent."
                        missing != null -> "Il manque : $missing"
                        else -> "Tout est prêt. Tu peux activer la protection."
                    },
                    color = cDim, fontSize = 14.sp
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        if (enabled) verifyThen { act.disableProtection() } else act.enableProtection()
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (enabled) cPanel else cBlue)
                ) { Text(if (enabled) "Désactiver la protection" else "Activer la protection", fontSize = 16.sp) }
                TextButton(onClick = { act.testLock() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Tester : verrouillage dans 10 secondes", color = cDim)
                }
            }

            Section("CONFIGURATION")
            AppCard {
                StepRow(act.adminActive(), "Administrateur de l'appareil",
                    "Permet de verrouiller l'écran", "Activer") { act.askAdmin() }
                StepRow(Settings.canDrawOverlays(act), "Affichage par-dessus les applis",
                    "Pour l'écran de blocage", "Autoriser") { act.askOverlay() }
                StepRow(act.btGranted() && act.locGranted(), "Bluetooth, notifications, position",
                    "Autorisations de base", "Autoriser") { act.askPermissions() }
                StepRow(act.bgLocGranted(), "Position « tout le temps »",
                    "Pour te localiser écran éteint", "Régler") { act.askAlwaysLocation() }
                StepRow(ShieldService.enabled(act), "Protection renforcée",
                    "Bloque barre de notifications, réglages et désinstallation", "Activer") { act.askShield() }
                StepRow(act.batteryUnrestricted(), "Batterie sans restriction",
                    "Pour que la télécommande marche écran éteint", "Autoriser") { act.askBatteryUnrestricted() }
                StepRow(trusted > 0, "Appareils de confiance",
                    if (trusted > 0) "$trusted choisi(s)" else "Montre, AirPods...", "Choisir") {
                    verifyThen { dlg = Dlg.Devices }
                }
                StepRow(codes > 0, "Codes de déverrouillage",
                    if (codes > 0) "$codes code(s), saisis dans l'ordre" else "Au moins un code", "Ajouter") {
                    verifyThen { dlg = Dlg.AddCode }
                }
                StepRow(recovery > 0, "Codes de secours",
                    if (recovery > 0) "$recovery restant(s)" else "Pour si tu oublies un code", "Générer", last = true) {
                    verifyThen { dlg = Dlg.Recovery(CodeStore.generateRecovery(act)) }
                }
            }
            if (codes > 0) {
                TextButton(onClick = {
                    verifyThen { CodeStore.clearCodes(act); local++ }
                }) { Text("Effacer tous les codes", color = cRed) }
            }

            Section("REGARDS INDISCRETS")
            AppCard {
                var peek by remember { mutableStateOf(Prefs.isPeekEnabled(act)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Détecter les curieux", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                        Text(
                            "La caméra avant regarde toutes les 30 secondes. Si plusieurs visages sont devant l'écran, il se cache.",
                            color = cDim, fontSize = 13.sp
                        )
                    }
                    Switch(checked = peek, onCheckedChange = {
                        peek = it
                        Prefs.setPeekEnabled(act, it)
                    })
                }
            }

            Section("RECONNAISSANCE DU PROPRIÉTAIRE")
            AppCard {
                var recoMsg by remember { mutableStateOf("") }
                var motion by remember { mutableStateOf(Prefs.bool(act, "motion_guard", true)) }
                StepRow(Biometrics.faceEnrolled(act), "Mon visage",
                    "4 photos de la caméra avant, gardées sous forme d'empreinte", "Enregistrer") {
                    verifyThen { act.enrollFace { recoMsg = it } }
                }
                StepRow(Biometrics.voiceEnrolled(act), "Ma voix",
                    "7 secondes de parole, gardées sous forme d'empreinte", "Enregistrer") {
                    verifyThen { act.enrollVoice { recoMsg = it } }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Vérifier quand je cours", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                        Text(
                            "Si le téléphone détecte une course, il regarde qui le tient : visage ou voix inconnus, il se verrouille.",
                            color = cDim, fontSize = 13.sp
                        )
                    }
                    Switch(checked = motion, onCheckedChange = {
                        motion = it
                        Prefs.setBool(act, "motion_guard", it)
                    })
                }
                if (recoMsg.isNotEmpty()) Text(recoMsg, color = cDim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                if (Biometrics.faceEnrolled(act) || Biometrics.voiceEnrolled(act)) {
                    TextButton(onClick = {
                        verifyThen { Biometrics.clearFace(act); Biometrics.clearVoice(act); local++ }
                    }) { Text("Effacer mon visage et ma voix", color = cRed) }
                }
            }

            Section("CONTACTS ET ALERTES")
            AppCard {
                var email by remember { mutableStateOf(Prefs.str(act, "owner_email")) }
                var phone by remember { mutableStateOf(Prefs.str(act, "owner_phone")) }
                var alertNum by remember { mutableStateOf(Prefs.str(act, "alert_number")) }
                var freeCode by remember { mutableStateOf(Prefs.str(act, "remote_code")) }
                Text("Ces infos restent sur ce téléphone. L'e-mail sert à recevoir un code de secours depuis l'écran de blocage. Les numéros reçoivent un SMS avec la position quand tu actives « Volé ».",
                    color = cDim, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = email, onValueChange = { email = it.trim() }, singleLine = true,
                    label = { Text("Mon e-mail") }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it.trim() }, singleLine = true,
                    label = { Text("Mon numéro (l'autre téléphone)") }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = alertNum, onValueChange = { alertNum = it.trim() }, singleLine = true,
                    label = { Text("Numéro à prévenir en plus (proche, commissariat)") }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = freeCode, onValueChange = { freeCode = it.trim().take(40) }, singleLine = true,
                    label = { Text("Code pour libérer le S22 à distance (vide = aucun)") }, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        verifyThen {
                            Prefs.setStr(act, "remote_code", freeCode)
                            Prefs.setStr(act, "owner_email", email)
                            Prefs.setStr(act, "owner_phone", phone)
                            Prefs.setStr(act, "alert_number", alertNum)
                            if ((phone.isNotEmpty() || alertNum.isNotEmpty()) && !act.smsGranted()) act.askPermissions()
                            act.toast("Contacts enregistrés")
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Enregistrer") }
            }

            Section("RÉSEAU")
            AppCard {
                var server by remember { mutableStateOf(Prefs.str(act, "ntfy_base")) }
                Text("Liaison directe Wi-Fi : IP de ce téléphone sur ce réseau : ${Lan.localIp().ifEmpty { "pas de Wi-Fi" }}.",
                    color = cDim, fontSize = 13.sp)
                Text("Sur le même Wi-Fi, la télécommande trouve ce S22 toute seule et lui parle sans passer par internet.",
                    color = cDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = server, onValueChange = { server = it.trim() }, singleLine = true,
                    label = { Text("Serveur de messages (avancé, vide = ntfy.sh)") }, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        Prefs.setStr(act, "ntfy_base", server)
                        Ntfy.configure(act)
                        act.toast("Serveur enregistré : ${Ntfy.baseUrl}")
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Enregistrer le serveur") }
            }

            Section("TÉLÉCOMMANDE")
            AppCard {
                Text("Contrôle ce téléphone depuis un autre : localiser, verrouiller, alarme, mode volé.",
                    color = cDim, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { verifyThen { act.startListening(); dlg = Dlg.Key } },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Clé de liaison") }
                    OutlinedButton(
                        onClick = { act.openRemote() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Télécommande") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    when (val d = dlg) {
        is Dlg.Verify -> VerifyDialog(
            act, CodeStore.count(act),
            onOk = { dlg = null; d.action() },
            onCancel = { dlg = null }
        )
        Dlg.AddCode -> AddCodeDialog(act) { dlg = null; local++ }
        Dlg.Devices -> DevicesDialog(act) { dlg = null; local++ }
        is Dlg.Recovery -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Codes de secours") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Note-les sur papier, dans un endroit sûr. Ils ne seront plus affichés. Chacun ne marche qu'une fois.",
                        color = cDim, fontSize = 14.sp)
                    d.codes.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 22.sp) }
                }
            },
            confirmButton = { TextButton({ dlg = null; local++ }) { Text("C'est noté") } }
        )
        Dlg.Key -> AlertDialog(
            onDismissRequest = { dlg = null },
            title = { Text("Clé de liaison") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(Crypto.pretty(Prefs.secret(act)), fontFamily = FontFamily.Monospace, fontSize = 26.sp)
                    Text("Tape cette clé dans la télécommande, sur l'autre téléphone. Ce S22 écoute maintenant les ordres. Ne la partage avec personne.",
                        color = cDim, fontSize = 14.sp)
                    Text("Sur le même Wi-Fi, la télécommande trouve ce S22 toute seule (IP : ${Lan.localIp().ifEmpty { "pas de Wi-Fi" }}).",
                        color = cDim, fontSize = 13.sp)
                }
            },
            confirmButton = { TextButton({ dlg = null }) { Text("OK") } }
        )
        null -> {}
    }
}

@Composable
private fun StepRow(
    ok: Boolean, title: String, subtitle: String, action: String,
    last: Boolean = false, onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (ok) cGreen else cDim,
            modifier = Modifier.size(26.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Text(subtitle, color = cDim, fontSize = 13.sp)
        }
        TextButton(onClick = onClick) { Text(if (ok) "Modifier" else action, color = cBlue) }
    }
    if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(cPanel))
}

@Composable
private fun VerifyDialog(act: MainActivity, n: Int, onOk: () -> Unit, onCancel: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Code ${step + 1} sur $n") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(40) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
        },
        confirmButton = {
            TextButton({
                if (CodeStore.check(act, step, value)) {
                    value = ""
                    if (step + 1 < n) step++ else onOk()
                } else {
                    act.toast("Code incorrect")
                    value = ""
                    step = 0
                }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onCancel) { Text("Annuler") } }
    )
}

@Composable
private fun AddCodeDialog(act: MainActivity, onDone: () -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Nouveau code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("6 à 40 caractères : lettres, chiffres et symboles", color = cDim, fontSize = 14.sp)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(40) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
            }
        },
        confirmButton = {
            TextButton({
                if (value.length in 6..40) {
                    CodeStore.addCode(act, value)
                    act.toast("Code ${CodeStore.count(act)} enregistré")
                    onDone()
                } else {
                    act.toast("Le code doit faire entre 6 et 40 caractères")
                }
            }) { Text("Ajouter") }
        },
        dismissButton = { TextButton(onDone) { Text("Annuler") } }
    )
}

@Composable
private fun DevicesDialog(act: MainActivity, onClose: () -> Unit) {
    val list = remember { act.bonded() }
    val sel = remember { mutableStateListOf<String>().apply { addAll(Prefs.trustedDevices(act)) } }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Appareils de confiance") },
        text = {
            if (list.isEmpty()) {
                Text("Aucun appareil appairé. Appaire ta montre ou tes AirPods dans les réglages Bluetooth, et autorise le Bluetooth ici.",
                    color = cDim, fontSize = 14.sp)
            } else {
                Column {
                    list.forEach { (name, addr) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { if (addr in sel) sel.remove(addr) else sel.add(addr) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = addr in sel,
                                onCheckedChange = { if (it) sel.add(addr) else sel.remove(addr) }
                            )
                            Text(name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                Prefs.setTrusted(act, sel.toSet())
                onClose()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClose) { Text("Annuler") } }
    )
}

@Suppress("unused")
private val keepColor = Color.Unspecified
