package com.antivol.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Télécommande : à utiliser sur l'AUTRE téléphone pour contrôler le S22. */
class RemoteActivity : ComponentActivity() {

    private var sub: Ntfy.Subscription? = null
    private var info by mutableStateOf("Aucune réponse du S22 pour l'instant.")
    private var points by mutableStateOf<List<Pair<Double, Double>>>(emptyList())
    private var keyText by mutableStateOf("")
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val k = Prefs.remoteSecret(this)
        if (k.isNotEmpty()) keyText = Crypto.pretty(k)
        refreshPath()
        setContent { AntivolTheme { Screen() } }
    }

    override fun onStart() {
        super.onStart()
        startSub()
    }

    override fun onStop() {
        sub?.stop()
        sub = null
        super.onStop()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    @Composable
    private fun Screen() {
        var sayText by remember { mutableStateOf("") }
        var armed by remember { mutableStateOf(false) }
        LaunchedEffect(armed) {
            if (armed) {
                delay(4000)
                armed = false
            }
        }

        Surface(color = cBg, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Header("Télécommande", "Contrôle ton S22 à distance")

                AppCard {
                    Text("Clé de liaison", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = keyText,
                        onValueChange = { keyText = it.uppercase().take(14) },
                        singleLine = true,
                        placeholder = { Text("XXXX-XXXX-XXXX") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { saveKey() },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Enregistrer la clé") }
                }

                AppCard {
                    Text("État du S22", color = cDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(info, fontSize = 15.sp)
                }

                Button(
                    onClick = {
                        if (!armed) armed = true else { armed = false; send("stolen", "") }
                    },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = cRed, contentColor = Color.White)
                ) {
                    Text(
                        if (armed) "Appuie encore pour confirmer" else "VOLÉ",
                        fontSize = 20.sp, fontWeight = FontWeight.Bold
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = { send("lock", "") },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Verrouiller") }
                    FilledTonalButton(
                        onClick = { send("alarm", "") },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Alarme") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = { send("status", "") },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Batterie") }
                    FilledTonalButton(
                        onClick = { send("locate", "") },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Localiser") }
                }
                FilledTonalButton(
                    onClick = { send("free", "") },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Libérer") }

                AppCard {
                    Text("Faire parler le S22", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sayText,
                        onValueChange = { sayText = it.take(200) },
                        placeholder = { Text("Phrase dite à voix haute") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val t = sayText.trim()
                            if (t.isNotEmpty()) send("say", t)
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Envoyer") }
                }

                Section("TRAJET")
                AppCard {
                    AndroidView(
                        factory = { PathView(it) },
                        update = { it.points = points },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .clip(RoundedCornerShape(14.dp))
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { openMaps() },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Ouvrir la dernière position dans Maps") }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    private fun saveKey() {
        val k = Crypto.normalize(keyText)
        if (k.length != 12) { toast("La clé fait 12 caractères"); return }
        Prefs.setRemoteSecret(this, k)
        keyText = Crypto.pretty(k)
        toast("Clé enregistrée")
        sub?.stop()
        sub = null
        startSub()
    }

    private fun send(cmd: String, arg: String) {
        val secret = Prefs.remoteSecret(this)
        if (secret.isEmpty()) { toast("Enregistre d'abord la clé de liaison"); return }
        val plain = "${System.currentTimeMillis() / 1000}|$cmd|$arg"
        Ntfy.publish(Crypto.topic(secret, "cmd"), Crypto.encrypt(secret, plain))
        info = "Ordre « $cmd » envoyé à ${fmt.format(Date())}. En attente du S22…"
    }

    private fun startSub() {
        val secret = Prefs.remoteSecret(this)
        if (secret.isEmpty() || sub != null) return
        sub = Ntfy.Subscription(Crypto.topic(secret, "rep"), "12h") { body ->
            val plain = Crypto.decrypt(secret, body) ?: return@Subscription
            val p = plain.split("|")
            if (p.size < 2) return@Subscription
            val ts = p[0].toLongOrNull() ?: return@Subscription
            when (p[1]) {
                "loc" -> if (p.size >= 5) {
                    Prefs.addHistory(this, "$ts,${p[2]},${p[3]},${p[4]}")
                    refreshPath()
                }
                "ack" -> if (p.size >= 6) {
                    info = "S22 a reçu « ${p[2]} » à ${fmt.format(Date(ts * 1000))}\n" +
                        "Verrouillé : ${if (p[3] == "1") "oui" else "non"}\n" +
                        "Mode volé : ${if (p[4] == "1") "oui" else "non"}\n" +
                        "Batterie : ${p[5]} %"
                }
            }
        }.start()
    }

    private fun history(): List<Triple<Long, Double, Double>> =
        Prefs.history(this).mapNotNull {
            val f = it.split(",")
            if (f.size < 3) null else {
                val ts = f[0].toLongOrNull()
                val la = f[1].toDoubleOrNull()
                val lo = f[2].toDoubleOrNull()
                if (ts == null || la == null || lo == null) null else Triple(ts, la, lo)
            }
        }

    private fun refreshPath() {
        points = history().map { Pair(it.second, it.third) }
    }

    private fun openMaps() {
        val last = history().lastOrNull()
        if (last == null) { toast("Aucune position reçue. Appuie sur « Localiser »."); return }
        try {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("geo:${last.second},${last.third}?q=${last.second},${last.third}(Mon+S22)")
                )
            )
        } catch (_: Exception) {
            toast("Dernière position : ${last.second}, ${last.third}")
        }
    }
}
