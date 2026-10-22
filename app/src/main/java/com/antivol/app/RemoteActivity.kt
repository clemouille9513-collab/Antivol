package com.antivol.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Télécommande : à utiliser sur l'AUTRE téléphone pour contrôler le S22. */
class RemoteActivity : ComponentActivity() {

    private var sub: Ntfy.Subscription? = null
    private var info by mutableStateOf("Aucune réponse du S22 pour l'instant.")
    private var points by mutableStateOf<List<Pair<Double, Double>>>(emptyList())
    private var keyText by mutableStateOf("")
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)
    private val chat = mutableStateListOf<Pair<Boolean, String>>()
    private var thinking by mutableStateOf(false)
    private var netInfo by mutableStateOf("")
    private var photo by mutableStateOf<Bitmap?>(null)
    private var photoInfo by mutableStateOf("")
    private var awaiting = false
    private var pinging = false
    private var token = 0
    private val ui = Handler(Looper.getMainLooper())

    /** Ordres en attente d'une réponse du S22 (utilisé par l'IA pour répondre avec le vrai résultat). */
    private val waiters = ConcurrentHashMap<String, CompletableFuture<String>>()
    private val assembling = HashMap<String, Array<String?>>()

    private fun pref(k: String, d: String = "") = Prefs.str(this, k, d)
    private fun setPref(k: String, v: String) = Prefs.setStr(this, k, v)

    /** Attend la réponse du S22 ; sinon explique quoi vérifier. */
    private fun expectReply() {
        awaiting = true
        val t = ++token
        ui.postDelayed({
            if (awaiting && t == token) {
                awaiting = false
                pinging = false
                bot("Pas de réponse du S22. Sur le S22 : ouvre Antivol, appuie sur « Clé de liaison » (autorise Bluetooth et notifications), et vérifie qu'il a internet et la même clé que ici.")
            }
        }, 20000)
    }

    private fun bot(text: String) { runOnUiThread { chat.add(false to text) } }

    // ---------- Outils de l'IA : un ordre au S22, puis attente de sa vraie réponse ----------

    private fun waitFor(key: String, timeoutMs: Long, start: () -> Unit): String {
        val f = CompletableFuture<String>()
        waiters[key] = f
        start()
        return try {
            f.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            JSONObject().put("erreur", "le S22 n'a pas répondu (hors ligne, sans internet ou clé différente)").toString()
        } finally {
            waiters.remove(key)
        }
    }

    private fun runTool(name: String, a: JSONObject): String = when (name) {
        "lock" -> waitFor("ack:lock", 25000) { send("lock", "") }
        "free" -> waitFor("ack:free", 25000) { send("free", "") }
        "alarm" -> waitFor("ack:alarm", 25000) { send("alarm", "") }
        "stolen" -> waitFor("ack:stolen", 25000) { send("stolen", "") }
        "status" -> waitFor("ack:status", 25000) { send("status", "") }
        "locate" -> waitFor("loc", 45000) { send("locate", "") }
        "photo" -> waitFor("photo", 60000) { send("photo", if (a.optString("camera") == "back") "back" else "front") }
        "say" -> waitFor("ack:say", 25000) { send("say", a.optString("text").take(200)) }
        "airplane" -> waitFor("ack:avion", 25000) { send("avion", if (a.optBoolean("on")) "on" else "off") }
        "screen" -> startScreen()
        else -> JSONObject().put("erreur", "outil inconnu").toString()
    }

    /** Remplacé par l'écran en direct. */
    private fun startScreen(): String =
        JSONObject().put("erreur", "l'écran en direct n'est pas encore disponible").toString()

    // ---------- Chatbot ----------

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.FRANCE), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun order(cmd: String, arg: String, answer: String) {
        expectReply()
        send(cmd, arg)
        bot(answer)
    }

    private fun chatSend(text: String) {
        val t = text.trim()
        if (t.isEmpty() || thinking) return
        chat.add(true to t)
        if (Prefs.remoteSecret(this).isEmpty()) {
            bot("Enregistre d'abord la clé de liaison, en haut de l'écran.")
            return
        }
        if (!Ai.configured(this)) {
            bot("L'IA n'est pas connectée (ajoute ta clé dans « Réglages IA »). Mode simple :")
            localChat(t)
            return
        }
        thinking = true
        val hist = chat.takeLast(12).map { (mine, m) -> (if (mine) "user" else "assistant") to m }
        Thread {
            val reply: String? = try {
                Ai.ask(this, hist) { name, args -> runTool(name, args) }
            } catch (e: Exception) {
                bot("IA indisponible : ${e.message ?: "erreur"}. Mode simple :")
                localChat(t)
                null
            }
            runOnUiThread {
                thinking = false
                if (reply != null) bot(reply)
            }
        }.start()
    }

    /** Mode simple, sans IA : comprend quelques phrases en français. */
    private fun localChat(t: String) {
        val n = norm(t)
        val say = Regex("^(dis|dit|parle|repete)\\s*(que|:)?\\s+(.+)$").find(n)
        if (say != null) {
            val phrase = t.substring(say.groups[3]!!.range.first).take(200)
            order("say", phrase, "Je fais dire « $phrase » au S22.")
            return
        }
        fun has(vararg w: String) = w.any { n.contains(it) }

        when {
            has("avion") -> order("avion", if (has("coupe", "desactive", "enleve", "off")) "off" else "on", "J'envoie l'ordre mode avion. Attention : en l'activant, le S22 n'est plus joignable.")
            has("ecran en direct", "voir l'ecran", "voir ecran") -> bot(startScreen())
            has("vole") -> order("stolen", "", "Mode volé envoyé : le S22 se verrouille, parle en boucle et passe en mode éco.")
            has("libere", "arrete", "stop", "deverrouille", "debloque") -> order("free", "", "Je libère le S22 : alarme et blocage arrêtés.")
            has("verrouill", "bloque") -> order("lock", "", "Je verrouille le S22.")
            has("sonne", "alarme", "sirene", "bruit") -> order("alarm", "", "Je fais sonner le S22 au maximum.")
            has("batterie", "charge", "niveau") -> order("status", "", "Je demande la batterie du S22.")
            has("photo", "selfie") -> {
                val back = has("arriere", "derriere", "dos")
                order("photo", if (back) "back" else "front", "Je prends une photo ${if (back) "arrière" else "avant"}.")
            }
            has("ou ", "localis", "position", "trouve", "carte") -> order("locate", "", "Je cherche où est le S22.")
            else -> bot("Je comprends : verrouille, sonne, batterie, où est mon téléphone, photo (avant ou arrière), dis … (phrase), volé, libère, mode avion.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ntfy.watchNetwork(this)
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
        LaunchedEffect(Unit) {
            while (true) {
                netInfo = Ntfy.summary()
                delay(3000)
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
                        onValueChange = { keyText = it.uppercase().take(20) },
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
                    Spacer(Modifier.height(8.dp))
                    Text(netInfo, color = cDim, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { Ntfy.check { r -> bot(r) } },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Tester le réseau") }
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
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = { send("photo", "front") },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Photo avant") }
                    FilledTonalButton(
                        onClick = { send("photo", "back") },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Photo arrière") }
                }
                FilledTonalButton(
                    onClick = { send("free", "") },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Libérer") }

                photo?.let { bmp ->
                    AppCard {
                        Text("Dernière photo", color = cDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Photo du S22",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        )
                        Text(photoInfo, color = cDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }

                AppCard {
                    var lockAir by remember { mutableStateOf(true) }
                    Text("Mode avion", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { send("avion", "on") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("Activer") }
                        OutlinedButton(
                            onClick = { send("avion", "off") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("Couper") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Verrouiller le S22 si quelqu'un active le mode avion", color = cDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Switch(checked = lockAir, onCheckedChange = {
                            lockAir = it
                            send("cfg", "lock_airplane=${if (it) 1 else 0}")
                        })
                    }
                    Text("Activer le mode avion coupe la connexion : le S22 ne reçoit plus d'ordre jusqu'à ce qu'il la retrouve.", color = cDim, fontSize = 12.sp)
                }

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

                AppCard {
                    var chatText by remember { mutableStateOf("") }
                    var cfg by remember { mutableStateOf(false) }
                    var provider by remember { mutableStateOf(pref("ai_provider", "openai")) }
                    var aiKey by remember { mutableStateOf(pref("ai_key")) }
                    var aiModel by remember { mutableStateOf(pref("ai_model")) }
                    var connected by remember { mutableStateOf(Ai.configured(this@RemoteActivity)) }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Chatbot IA", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { cfg = !cfg }) { Text(if (cfg) "Fermer" else "Réglages IA", color = cBlue) }
                    }
                    Text(
                        if (connected) "Connecté à ${if (provider == "anthropic") "Claude" else "GPT"} : il exécute tes ordres sur le S22."
                        else "Pas de clé : mode simple (mots-clés). Ajoute une clé dans les réglages pour parler librement.",
                        color = cDim, fontSize = 13.sp
                    )
                    if (cfg) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (provider == "openai") Button(onClick = {}, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("GPT (OpenAI)") }
                            else OutlinedButton(onClick = { provider = "openai"; setPref("ai_provider", "openai") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("GPT (OpenAI)") }
                            if (provider == "anthropic") Button(onClick = {}, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("Claude") }
                            else OutlinedButton(onClick = { provider = "anthropic"; setPref("ai_provider", "anthropic") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("Claude") }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = aiKey,
                            onValueChange = { aiKey = it.trim() },
                            singleLine = true,
                            label = { Text("Clé API") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = aiModel,
                            onValueChange = { aiModel = it.trim() },
                            singleLine = true,
                            label = { Text(if (provider == "anthropic") "Modèle (vide = claude-haiku-5-5)" else "Modèle (vide = gpt-4o-mini)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                setPref("ai_key", aiKey)
                                setPref("ai_model", aiModel)
                                setPref("ai_provider", provider)
                                connected = aiKey.isNotBlank()
                                toast(if (connected) "IA enregistrée" else "Clé effacée")
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Enregistrer") }
                        Text("La clé reste sur ce téléphone et sert seulement à parler au fournisseur choisi.", color = cDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    if (chat.isEmpty()) {
                        Text("Écris un ordre : « verrouille mon téléphone », « fais sonner », « il lui reste combien de batterie ? »…",
                            color = cDim, fontSize = 14.sp)
                    }
                    chat.takeLast(10).forEach { (mine, msg) ->
                        Text(
                            (if (mine) "Toi : " else "Bot : ") + msg,
                            color = if (mine) Color.White else cDim,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(vertical = 3.dp)
                        )
                    }
                    if (thinking) Text("Bot : …", color = cDim, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = chatText,
                            onValueChange = { chatText = it.take(300) },
                            placeholder = { Text("Ton ordre") },
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { chatSend(chatText); chatText = "" },
                            modifier = Modifier.height(56.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("OK") }
                    }
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
        // Test de liaison : on demande l'état au S22 et on attend sa réponse.
        pinging = true
        bot("Test de liaison avec le S22…")
        expectReply()
        send("status", "")
    }

    private fun send(cmd: String, arg: String) {
        val secret = Prefs.remoteSecret(this)
        if (secret.isEmpty()) { toast("Enregistre d'abord la clé de liaison"); return }
        val plain = "${System.currentTimeMillis() / 1000}|$cmd|$arg"
        Ntfy.publish(Crypto.topic(secret, "cmd"), Crypto.encrypt(secret, plain))
        runOnUiThread { info = "Ordre « $cmd » envoyé à ${fmt.format(Date())}. En attente du S22…" }
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
                    waiters["loc"]?.complete(
                        JSONObject().put("latitude", p[2]).put("longitude", p[3]).put("precision_m", p[4]).toString()
                    )
                }
                "hb" -> if (p.size >= 6) {
                    runOnUiThread {
                        info = "Signal du S22 à ${fmt.format(Date(ts * 1000))} (${p[5]})\n" +
                            "Verrouillé : ${if (p[2] == "1") "oui" else "non"}\n" +
                            "Mode volé : ${if (p[3] == "1") "oui" else "non"}\n" +
                            "Batterie : ${p[4]} %"
                    }
                }
                "photo" -> if (p.size >= 7) {
                    val id = p[2]
                    val idx = p[3].toIntOrNull() ?: return@Subscription
                    val n = p[4].toIntOrNull() ?: return@Subscription
                    if (n <= 0 || n > 400) return@Subscription
                    val parts = synchronized(assembling) { assembling.getOrPut(id) { arrayOfNulls(n) } }
                    if (idx in parts.indices) parts[idx] = p.drop(6).joinToString("|")
                    if (parts.all { it != null }) {
                        synchronized(assembling) { assembling.remove(id) }
                        val bytes = Crypto.decryptBytes(secret, parts.joinToString(""))
                        val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        if (bmp != null) {
                            runOnUiThread {
                                photo = bmp
                                photoInfo = "${if (p[5] == "front") "Caméra avant" else "Caméra arrière"} · ${fmt.format(Date(ts * 1000))}"
                            }
                            waiters["photo"]?.complete(
                                JSONObject().put("ok", true).put("photo", "reçue, elle est affichée dans la télécommande").toString()
                            )
                        }
                    }
                }
                "evt" -> if (p.size >= 4) {
                    val detail = p.drop(3).joinToString("|")
                    if (p[2] == "photo" && detail.contains("impossible")) {
                        waiters["photo"]?.complete(JSONObject().put("erreur", detail).toString())
                    }
                    if (p[2] == "airplane") bot("S22 : $detail")
                }
                "ack" -> if (p.size >= 6) {
                    val answer = JSONObject().put("ok", true).put("ordre", p[2])
                        .put("verrouille", p[3] == "1").put("mode_vole", p[4] == "1").put("batterie_pourcent", p[5].toIntOrNull() ?: -1)
                        .toString()
                    waiters["ack:${p[2]}"]?.complete(answer)
                    if (awaiting) {
                        awaiting = false
                        val link = if (pinging) "Liaison réussie ! " else ""
                        pinging = false
                        val msg = link + "Le S22 a répondu : ${if (p[3] == "1") "verrouillé" else "libre"}, " +
                            "mode volé ${if (p[4] == "1") "oui" else "non"}, batterie ${p[5]} %."
                        bot(msg)
                    }
                    runOnUiThread {
                        info = "S22 a reçu « ${p[2]} » à ${fmt.format(Date(ts * 1000))}\n" +
                            "Verrouillé : ${if (p[3] == "1") "oui" else "non"}\n" +
                            "Mode volé : ${if (p[4] == "1") "oui" else "non"}\n" +
                            "Batterie : ${p[5]} %"
                    }
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
        val pts = history().map { Pair(it.second, it.third) }
        runOnUiThread { points = pts }
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
