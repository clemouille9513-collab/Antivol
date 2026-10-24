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
    private var alarmOn by mutableStateOf(false)
    private var liveOn by mutableStateOf(false)
    private var mapView: TileMapView? = null
    private var keyText by mutableStateOf("")
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)
    private val chat = mutableStateListOf<Pair<Boolean, String>>()
    private var thinking by mutableStateOf(false)
    private var netInfo by mutableStateOf("")
    private var photo by mutableStateOf<Bitmap?>(null)
    private var photoInfo by mutableStateOf("")
    private var lan: Lan.Client? = null
    private var screenBmp by mutableStateOf<Bitmap?>(null)
    private var screenInfo by mutableStateOf("")
    @Volatile private var framesOn = false
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
                bot("Pas de réponse du S22. Sur le S22 : ouvre Antivol, appuie sur « Clé de liaison » (autorise Bluetooth et notifications), et vérifie qu'il a internet et la même clé qu'ici. Si les deux téléphones sont sur le même Wi-Fi, regarde la ligne « direct Wi-Fi » dans l'état.")
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
        "free" -> waitFor("ack:free", 25000) { send("free", Prefs.str(this, "remote_free_code")) }
        "alarm" -> waitFor("ack:alarm", 25000) { send("alarm", if (a.optBoolean("on", true)) "on" else "off") }
        "stolen" -> waitFor("ack:stolen", 25000) { send("stolen", "") }
        "status" -> waitFor("ack:status", 25000) { send("status", "") }
        "locate" -> waitFor("loc", 45000) { send("locate", "") }
        "photo" -> waitFor("photo", 60000) { send("photo", if (a.optString("camera") == "back") "back" else "front") }
        "say" -> waitFor("ack:say", 25000) { send("say", a.optString("text").take(200)) }
        "airplane" -> waitFor("ack:avion", 25000) { send("avion", if (a.optBoolean("on")) "on" else "off") }
        "screen" -> startScreen()
        "open_app" -> waitFor("evt:act", 30000) { send("app", a.optString("name").take(100)) }
        "open_url" -> waitFor("evt:act", 30000) { send("url", a.optString("url").take(200)) }
        "torch" -> waitFor("evt:act", 30000) { send("torch", if (a.optBoolean("on")) "on" else "off") }
        "volume" -> waitFor("evt:act", 30000) { send("volume", a.optInt("percent", 50).toString()) }
        "read_screen" -> waitFor("evt:act", 30000) { send("ui", "read") }
        "tap" -> waitFor("evt:act", 30000) { send("ui", "tap:" + a.optString("text").take(120)) }
        "type_text" -> waitFor("evt:act", 30000) { send("ui", "type:" + a.optString("text").take(150)) }
        "press" -> waitFor("evt:act", 30000) { send("ui", "press:" + a.optString("key")) }
        "swipe" -> waitFor("evt:act", 30000) { send("ui", "swipe:" + a.optString("direction")) }
        else -> JSONObject().put("erreur", "outil inconnu").toString()
    }

    /** Écran en direct si le S22 est joignable sur le même Wi-Fi ; sinon une capture d'écran unique par internet. */
    private fun startScreen(): String {
        val l = lan
        if (l != null && l.connected()) {
            send("screen", "on")
            startFrames()
            screenInfo = "Connexion à l'écran du S22…"
            return "L'écran du S22 s'affiche en direct dans la télécommande (carte « Écran du S22 »)."
        }
        send("shot", "")
        screenInfo = "Pas de liaison Wi-Fi directe : capture unique demandée."
        return "Pas de liaison Wi-Fi directe avec le S22 : le direct demande le même Wi-Fi. J'ai demandé une capture d'écran unique, elle s'affichera dans la télécommande."
    }

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
            localChat(t)
            return
        }
        thinking = true
        val hist = chat.takeLast(12).map { (mine, m) -> (if (mine) "user" else "assistant") to m }
        Thread {
            val reply: String? = try {
                Ai.ask(this, hist) { name, args -> runTool(name, args) }
            } catch (e: Exception) {
                bot("L'IA ne répond pas, je passe en mode gratuit.")
                localChat(t)
                null
            }
            runOnUiThread {
                thinking = false
                if (reply != null) bot(reply)
            }
        }.start()
    }

    /** Distance entre deux mots (pour comprendre les fautes de frappe). */
    private fun lev(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        return d[a.length][b.length]
    }

    /** Vrai si un mot de la phrase ressemble à l'un des mots-clés (avec une faute tolérée). */
    private fun hasWord(words: List<String>, vararg roots: String): Boolean = words.any { w ->
        roots.any { r ->
            w.startsWith(r) || (r.length >= 5 && w.length >= r.length - 1 && lev(w.take(r.length), r) <= 1)
        }
    }

    /**
     * Chatbot gratuit, sans clé ni internet : comprend des phrases libres en français,
     * plusieurs ordres d'un coup (« verrouille et fais sonner »), et les fautes de frappe.
     */
    private fun localChat(t: String) {
        val clauses = t.split(Regex("(?i)\\s*(?:,|;|\\bet\\b|\\bpuis\\b|\\bensuite\\b|\\bapres\\b|\\baprès\\b)\\s*"))
            .map { it.trim() }.filter { it.isNotEmpty() }
        if (clauses.isEmpty()) return
        var done = 0
        for ((idx, c) in clauses.withIndex()) {
            val n = norm(c)
            val w = n.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
            if (w.isEmpty()) continue

            // « dis … » : le reste de la phrase est dit à voix haute (sauf « dis-moi … »).
            if (w[0] in listOf("dis", "dit", "parle", "repete", "annonce") && w.getOrNull(1) !in listOf("moi", "ou", "est")) {
                val rest = clauses.drop(idx).joinToString(" et ")
                val phrase = rest.replace(Regex("(?i)^\\s*(dis|dit|parle|repete|répète|annonce)\\s*(que|:)?\\s*"), "").trim().take(200)
                if (phrase.isNotEmpty()) {
                    order("say", phrase, "Je fais dire « $phrase » au S22.")
                    return
                }
            }

            fun any(vararg r: String) = hasWord(w, *r)
            val handled = when {
                w[0] in listOf("ouvre", "ouvrir", "lance", "lancer", "demarre", "va") && w.size > 1 -> {
                    val target = c.replace(Regex("(?i)^\\s*(ouvre|ouvrir|lance|lancer|démarre|demarre|va sur|va)\\s+(l'appli(cation)?|le site|la page|sur)?\\s*"), "").trim()
                    if (target.contains(".") && !target.contains(" ")) order("url", target, "J'ouvre $target sur le S22.")
                    else order("app", target, "J'ouvre « $target » sur le S22.")
                    true
                }
                any("lampe", "torche", "lumiere", "flash") -> {
                    val off = any("eteins", "eteint", "coupe", "ferme", "off")
                    order("torch", if (off) "off" else "on", if (off) "J'éteins la lampe du S22." else "J'allume la lampe du S22.")
                    true
                }
                any("volume", "son") && Regex("\\d+").containsMatchIn(n) -> {
                    val v = Regex("\\d+").find(n)!!.value
                    order("volume", v, "Je règle le volume du S22 à $v %.")
                    true
                }
                any("avion") -> {
                    val off = any("coupe", "desactiv", "enlev", "retir", "eteins", "stoppe")
                    order("avion", if (off) "off" else "on",
                        if (off) "Je coupe le mode avion du S22." else "J'active le mode avion. Attention : le S22 ne sera plus joignable.")
                    true
                }
                any("direct", "ecran", "regard", "montre") && any("direct", "voir", "vois", "regard", "affich", "montre", "ecran") && !any("verrouill") -> { bot(startScreen()); true }
                any("vole", "volee", "vol") && !any("volume") -> { order("stolen", "", "Mode volé envoyé : le S22 se verrouille, parle en boucle et passe en mode éco."); true }
                any("alarme", "sirene", "sonnerie", "bruit") && any("arrete", "stoppe", "stop", "coupe", "eteins", "desactiv", "tais", "silence") -> {
                    order("alarm", "off", "J'arrête la sirène du S22."); true
                }
                any("libere", "liberer", "deverrouill", "debloqu", "arrete", "stoppe", "stop", "tais", "silence", "coupe") -> {
                    order("free", Prefs.str(this, "remote_free_code"), "Je libère le S22 : alarme et blocage arrêtés."); true
                }
                any("verrouill", "bloqu", "ferme", "protege", "securis") -> { order("lock", "", "Je verrouille le S22."); true }
                any("sonne", "sonner", "alarme", "sirene", "bruit", "cri", "hurle", "fort") -> { order("alarm", "on", "Je fais sonner le S22 au maximum."); true }
                any("batterie", "charge", "niveau", "pourcent", "reste") -> { order("status", "", "Je demande la batterie du S22."); true }
                any("photo", "selfie", "camera", "cheese", "prends") -> {
                    val back = any("arriere", "derriere", "dos", "principale")
                    order("photo", if (back) "back" else "front", "Je prends une photo ${if (back) "arrière" else "avant"}.")
                    true
                }
                any("ou", "localis", "position", "trouv", "carte", "gps", "est") && (any("ou", "localis", "position", "trouv", "carte", "gps")) -> {
                    order("locate", "", "Je cherche où est le S22."); true
                }
                any("salut", "bonjour", "coucou", "hello", "merci") -> { bot("Salut ! Dis-moi ce que je dois faire sur le S22."); true }
                else -> false
            }
            if (handled) done++
        }
        if (done == 0) {
            bot("Je n'ai pas compris. Je sais faire : verrouille, libère, fais sonner, ouvre YouTube, lampe, volume 50, batterie, où est mon téléphone, photo (avant ou arrière), dis … (phrase à voix haute), volé, mode avion, écran en direct. Tu peux en enchaîner plusieurs : « verrouille et fais sonner ».")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ntfy.watchNetwork(this)
        val k = Prefs.remoteSecret(this)
        if (k.isNotEmpty()) keyText = Crypto.pretty(k)
        liveOn = Prefs.bool(this, "live_on") && System.currentTimeMillis() - Prefs.long(this, "live_since") < 7_200_000L
        refreshPath()
        setContent { AntivolTheme { Screen() } }
    }

    override fun onStart() {
        super.onStart()
        startSub()
        startLan()
    }

    override fun onStop() {
        sub?.stop()
        sub = null
        if (framesOn) send("screen", "off")
        stopLan()
        super.onStop()
    }

    private fun startLan() {
        val secret = Prefs.remoteSecret(this)
        if (secret.isEmpty() || lan != null) return
        lan = Lan.Client(this, secret) { body -> handleReply(secret, body) }.start()
    }

    private fun stopLan() {
        framesOn = false
        lan?.stop()
        lan = null
    }

    // ---------- Écran en direct ----------

    private fun startFrames() {
        if (framesOn) return
        framesOn = true
        val secret = Prefs.remoteSecret(this)
        Thread {
            var since = 0L
            var misses = 0
            while (framesOn) {
                val r = lan?.frame(since)
                if (r == null) {
                    misses++
                    if (misses > 10) {
                        framesOn = false
                        runOnUiThread { screenInfo = "Direct Wi-Fi perdu : l'écran en direct est arrêté." }
                        break
                    }
                    try { Thread.sleep(600) } catch (_: InterruptedException) {}
                    continue
                }
                misses = 0
                since = r.first
                val data = r.second ?: continue
                val bytes = Crypto.decryptBytes(secret, data) ?: continue
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                runOnUiThread { screenBmp = bmp; screenInfo = "En direct · ${fmt.format(Date())}" }
            }
        }.start()
    }

    private fun stopScreen() {
        framesOn = false
        send("screen", "off")
        screenInfo = "Écran en direct arrêté."
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    @Composable
    private fun Screen() {
        var sayText by remember { mutableStateOf("") }
        var freeCode by remember { mutableStateOf(Prefs.str(this, "remote_free_code")) }
        var armed by remember { mutableStateOf(false) }
        LaunchedEffect(armed) {
            if (armed) {
                delay(4000)
                armed = false
            }
        }
        LaunchedEffect(Unit) {
            while (true) {
                netInfo = Ntfy.summary() + "\n" + (lan?.status ?: "direct Wi-Fi : pas de clé enregistrée")
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
                        onClick = { alarmOn = !alarmOn; send("alarm", if (alarmOn) "on" else "off") },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text(if (alarmOn) "Arrêter l'alarme" else "Alarme") }
                }
                FilledTonalButton(
                    onClick = { send("status", "") },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Batterie") }
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
                OutlinedTextField(
                    value = freeCode,
                    onValueChange = { freeCode = it.trim().take(40); Prefs.setStr(this@RemoteActivity, "remote_free_code", freeCode) },
                    singleLine = true,
                    label = { Text("Code de libération (choisi sur le S22)") },
                    modifier = Modifier.fillMaxWidth()
                )
                FilledTonalButton(
                    onClick = { send("free", freeCode) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Libérer") }

                AppCard {
                    Text("Écran du S22", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("En direct sur le même Wi-Fi. Ailleurs : une capture à la demande.", color = cDim, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { bot(startScreen()) },
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)
                        ) { Text("Voir") }
                        OutlinedButton(
                            onClick = { send("shot", ""); screenInfo = "Capture demandée…" },
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)
                        ) { Text("Capture") }
                        OutlinedButton(
                            onClick = { stopScreen() },
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)
                        ) { Text("Arrêter") }
                    }
                    screenBmp?.let { bmp ->
                        Spacer(Modifier.height(8.dp))
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Écran du S22",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        )
                    }
                    if (screenInfo.isNotEmpty()) Text(screenInfo, color = cDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    Text("Demande « Protection renforcée » activée sur le S22.", color = cDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }

                AppCard {
                    var motion by remember { mutableStateOf(true) }
                    var lanIp by remember { mutableStateOf(pref("lan_ip")) }
                    var server by remember { mutableStateOf(pref("ntfy_base")) }
                    Text("Liaison et vérification", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Vérifier visage et voix quand le S22 détecte une course", color = cDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Switch(checked = motion, onCheckedChange = {
                            motion = it
                            send("cfg", "motion_guard=${if (it) 1 else 0}")
                        })
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = lanIp,
                        onValueChange = { lanIp = it.trim() },
                        singleLine = true,
                        label = { Text("IP du S22 (vide = automatique)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it.trim() },
                        singleLine = true,
                        label = { Text("Serveur de messages (vide = ntfy.sh)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            setPref("lan_ip", lanIp)
                            setPref("ntfy_base", server)
                            Ntfy.configure(this@RemoteActivity)
                            stopLan(); startLan()
                            sub?.stop(); sub = null; startSub()
                            toast("Réglages réseau enregistrés")
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Enregistrer") }
                }

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
                        if (connected) "Connecté à ${when (provider) { "anthropic" -> "Claude"; "gemini" -> "Gemini"; else -> "GPT" }} : il exécute tes ordres sur le S22."
                        else "Pas de clé : mode gratuit simple. Pour une vraie IA gratuite : Réglages IA, choisis Gemini et colle ta clé (aistudio.google.com/apikey).",
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
                        if (provider == "gemini") Button(onClick = {}, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("Gemini (gratuit)") }
                        else OutlinedButton(onClick = { provider = "gemini"; setPref("ai_provider", "gemini") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("Gemini (gratuit)") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                val url = when (provider) {
                                    "gemini" -> "https://aistudio.google.com/apikey"
                                    "anthropic" -> "https://console.anthropic.com/settings/keys"
                                    else -> "https://platform.openai.com/api-keys"
                                }
                                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Créer ma clé (ouvre le site)") }
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
                            label = { Text(when (provider) { "anthropic" -> "Modèle (vide = claude-haiku-5-5)"; "gemini" -> "Modèle (vide = gemini-2.5-flash)"; else -> "Modèle (vide = gpt-4o-mini)" }) },
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

                Section("POSITION")
                AppCard {
                    @Suppress("UNUSED_VARIABLE") val refresh = points
                    val lastFix = history().lastOrNull()
                    val acc = Prefs.history(this@RemoteActivity).lastOrNull()?.split(",")?.getOrNull(3)?.toDoubleOrNull()
                    Text(
                        if (lastFix == null) "Aucune position reçue pour l'instant."
                        else "Dernière mesure : il y a ${formatAge(System.currentTimeMillis() / 1000 - lastFix.first)}" +
                            (if (acc != null && acc > 0) " · précision ±${acc.toInt()} m" else ""),
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    AndroidView(
                        factory = { ctx -> TileMapView(ctx).also { mapView = it } },
                        update = { v -> @Suppress("UNUSED_VARIABLE") val t = points; v.setFixes(mapFixes(), liveOn) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(340.dp)
                            .clip(RoundedCornerShape(14.dp))
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        OutlinedButton(onClick = { mapView?.zoomBy(1.0) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("+") }
                        OutlinedButton(onClick = { mapView?.zoomBy(-1.0) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("−") }
                        OutlinedButton(onClick = { mapView?.recenter() }, modifier = Modifier.weight(2f), shape = RoundedCornerShape(12.dp)) { Text("Centrer sur le S22") }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(
                            onClick = { send("locate", "") },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("Localiser") }
                        FilledTonalButton(
                            onClick = { setLive(!liveOn) },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text(if (liveOn) "Arrêter le suivi" else "Suivre en direct") }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { openMaps() },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Ouvrir dans Google Maps") }
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
        stopLan()
        startLan()
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
        val enc = Crypto.encrypt(secret, plain)
        Thread {
            // D'abord en direct sur le Wi-Fi (instantané, sans limite) ; sinon par internet.
            val direct = lan?.post(enc) == true
            if (!direct) Ntfy.publish(Crypto.topic(secret, "cmd"), enc)
            runOnUiThread {
                info = "Ordre « $cmd » envoyé ${if (direct) "en direct (Wi-Fi)" else "par internet"} à ${fmt.format(Date())}. En attente du S22…"
            }
        }.start()
    }

    private fun startSub() {
        val secret = Prefs.remoteSecret(this)
        if (secret.isEmpty() || sub != null) return
        sub = Ntfy.Subscription(Crypto.topic(secret, "rep"), "12h") { body -> handleReply(secret, body) }.start()
    }

    private val seenBodies = ArrayDeque<Int>()

    /** Traite un message du S22, qu'il arrive par internet (ntfy) ou en direct sur le Wi-Fi. */
    private fun handleReply(secret: String, body: String) {
        synchronized(seenBodies) {
            val h = body.hashCode()
            if (h in seenBodies) return
            seenBodies.addLast(h)
            if (seenBodies.size > 500) seenBodies.removeFirst()
        }
        val plain = Crypto.decrypt(secret, body) ?: return
        val p = plain.split("|")
        if (p.size < 2) return
        val ts = p[0].toLongOrNull() ?: return
        when (p[1]) {
            "loc" -> if (p.size >= 5) {
                Prefs.addHistory(this, "$ts,${p[2]},${p[3]},${p[4]}")
                refreshPath()
                waiters["loc"]?.complete(
                    JSONObject().put("latitude", p[2]).put("longitude", p[3]).put("precision_m", p[4]).toString()
                )
            }
            "hb" -> if (p.size >= 6) {
                p.getOrNull(6)?.let { lan?.hint(it) }
                runOnUiThread {
                    info = "Signal du S22 à ${fmt.format(Date(ts * 1000))} (${p[5]})\n" +
                        "Verrouillé : ${if (p[2] == "1") "oui" else "non"}\n" +
                        "Mode volé : ${if (p[3] == "1") "oui" else "non"}\n" +
                        "Batterie : ${p[4]} %"
                }
            }
            "photo" -> if (p.size >= 7) {
                val id = p[2]
                val idx = p[3].toIntOrNull() ?: return
                val n = p[4].toIntOrNull() ?: return
                if (n <= 0 || n > 400) return
                val parts = synchronized(assembling) { assembling.getOrPut(id) { arrayOfNulls(n) } }
                if (idx in parts.indices) parts[idx] = p.drop(6).joinToString("|")
                if (parts.all { it != null }) {
                    synchronized(assembling) { assembling.remove(id) }
                    val bytes = Crypto.decryptBytes(secret, parts.joinToString(""))
                    val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    if (bmp != null) {
                        runOnUiThread {
                            photo = bmp
                            photoInfo = "${when (p[5]) { "front" -> "Caméra avant"; "back" -> "Caméra arrière"; else -> "Capture d'écran" }} · ${fmt.format(Date(ts * 1000))}"
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
                if (p[2] == "act") {
                    waiters["evt:act"]?.complete(JSONObject().put("resultat", detail).toString())
                    if (waiters["evt:act"] == null) bot("S22 : $detail")
                }
            }
            "ack" -> if (p.size >= 6) {
                p.getOrNull(6)?.let { lan?.hint(it) }
                p.getOrNull(7)?.let { v -> runOnUiThread { alarmOn = v == "1" } }
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

    /** Suivi en direct : reste actif même quand on quitte l'écran ou qu'on ouvre Maps (le S22 l'arrête après 2 h). */
    private fun setLive(on: Boolean) {
        liveOn = on
        Prefs.setBool(this, "live_on", on)
        Prefs.setLong(this, "live_since", System.currentTimeMillis())
        send("live", if (on) "on" else "off")
    }

    private fun formatAge(sec: Long): String = when {
        sec < 5 -> "quelques secondes"
        sec < 90 -> "$sec s"
        sec < 5400 -> "${sec / 60} min"
        else -> "${sec / 3600} h"
    }

    private fun mapFixes(): List<TileMapView.Fix> = Prefs.history(this).mapNotNull {
        val f = it.split(",")
        val la = f.getOrNull(1)?.toDoubleOrNull()
        val lo = f.getOrNull(2)?.toDoubleOrNull()
        if (la == null || lo == null) null else TileMapView.Fix(la, lo, f.getOrNull(3)?.toDoubleOrNull() ?: 20.0)
    }.takeLast(150)

    private fun refreshPath() {
        val pts = history().map { Pair(it.second, it.third) }
        runOnUiThread { points = pts }
    }

    private fun openMaps() {
        val last = history().lastOrNull()
        if (last == null) { toast("Aucune position reçue. Appuie sur « Localiser »."); return }
        if (!liveOn) { setLive(true); toast("Suivi en direct activé : il continue pendant que Maps est ouvert") }
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

