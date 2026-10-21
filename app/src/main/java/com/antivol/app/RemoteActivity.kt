package com.antivol.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Télécommande : à utiliser sur l'AUTRE téléphone pour contrôler le S22. */
class RemoteActivity : Activity() {

    private lateinit var keyField: EditText
    private lateinit var sayField: EditText
    private lateinit var info: TextView
    private lateinit var path: PathView
    private lateinit var stolenButton: Button
    private var sub: Ntfy.Subscription? = null
    private var armed = false
    private val handler = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(col) })

        col.addView(TextView(this).apply { text = "Télécommande Antivol"; textSize = 26f })

        keyField = EditText(this).apply {
            hint = "Clé de liaison du S22 (XXXX-XXXX-XXXX)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            val k = Prefs.remoteSecret(this@RemoteActivity)
            if (k.isNotEmpty()) setText(Crypto.pretty(k))
        }
        col.addView(keyField)
        button(col, "Enregistrer la clé") { saveKey() }

        info = TextView(this).apply {
            textSize = 15f
            text = "Aucune réponse du S22 pour l'instant."
            setPadding(0, dp(12), 0, dp(12))
        }
        col.addView(info)

        stolenButton = button(col, "VOLÉ (verrouille, voix en boucle, éco)") { onStolen() }
        stolenButton.setBackgroundColor(0xFFB3001B.toInt())
        stolenButton.setTextColor(Color.WHITE)

        button(col, "Verrouiller") { send("lock", "") }
        button(col, "Alarme sonore") { send("alarm", "") }
        button(col, "Localiser maintenant") { send("locate", "") }

        sayField = EditText(this).apply { hint = "Phrase à dire à voix haute sur le S22" }
        col.addView(sayField)
        button(col, "Faire parler le S22") {
            val t = sayField.text.toString().trim()
            if (t.isNotEmpty()) send("say", t)
        }

        button(col, "Tout arrêter et libérer le S22") { send("free", "") }

        path = PathView(this)
        col.addView(path, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(300)
        ).apply { topMargin = dp(16) })
        button(col, "Ouvrir la dernière position dans Maps") { openMaps() }

        refreshPath()
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

    private fun button(parent: LinearLayout, label: String, onClick: () -> Unit): Button {
        val b = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        parent.addView(b)
        return b
    }

    private fun saveKey() {
        val k = Crypto.normalize(keyField.text.toString())
        if (k.length != 12) { toast("La clé fait 12 caractères"); return }
        Prefs.setRemoteSecret(this, k)
        keyField.setText(Crypto.pretty(k))
        toast("Clé enregistrée")
        sub?.stop()
        startSub()
    }

    private fun onStolen() {
        if (!armed) {
            armed = true
            stolenButton.text = "Appuie encore pour confirmer"
            handler.postDelayed({
                armed = false
                stolenButton.text = "VOLÉ (verrouille, voix en boucle, éco)"
            }, 4000)
            return
        }
        armed = false
        stolenButton.text = "VOLÉ (verrouille, voix en boucle, éco)"
        send("stolen", "")
    }

    private fun send(cmd: String, arg: String) {
        val secret = Prefs.remoteSecret(this)
        if (secret.isEmpty()) { toast("Enregistre d'abord la clé de liaison"); return }
        val plain = "${System.currentTimeMillis() / 1000}|$cmd|$arg"
        Ntfy.publish(Crypto.topic(secret, "cmd"), Crypto.encrypt(secret, plain))
        info.text = "Ordre « $cmd » envoyé à ${fmt.format(Date())}. En attente du S22…"
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
                    runOnUiThread { refreshPath() }
                }
                "ack" -> if (p.size >= 6) runOnUiThread {
                    info.text = "S22 a reçu « ${p[2]} » à ${fmt.format(Date(ts * 1000))}\n" +
                        "Verrouillé : ${if (p[3] == "1") "oui" else "non"}  |  " +
                        "Mode volé : ${if (p[4] == "1") "oui" else "non"}  |  Batterie : ${p[5]} %"
                }
            }
        }.start()
    }

    private fun points(): List<Triple<Long, Double, Double>> =
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
        path.points = points().map { Pair(it.second, it.third) }
    }

    private fun openMaps() {
        val last = points().lastOrNull()
        if (last == null) { toast("Aucune position reçue. Appuie sur « Localiser »."); return }
        try {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("geo:${last.second},${last.third}?q=${last.second},${last.third}(Mon+S22)")
                )
            )
        } catch (_: Exception) {
            AlertDialog.Builder(this)
                .setMessage("Dernière position : ${last.second}, ${last.third}")
                .setPositiveButton("OK", null).show()
        }
    }
}
