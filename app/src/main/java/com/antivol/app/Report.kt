package com.antivol.app

import android.content.Context

/** Envoie un événement chiffré à la télécommande (utilisable depuis n'importe quelle partie de l'appli). */
object Report {
    fun event(c: Context, type: String, detail: String) {
        val secret = Prefs.secret(c)
        val plain = "${System.currentTimeMillis() / 1000}|evt|$type|$detail"
        Ntfy.publish(Crypto.topic(secret, "rep"), Crypto.encrypt(secret, plain))
    }
}
