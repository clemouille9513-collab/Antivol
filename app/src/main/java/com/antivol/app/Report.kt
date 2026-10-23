package com.antivol.app

import android.content.Context

/**
 * Envoie un message chiffré à la télécommande (utilisable depuis n'importe quelle partie de l'appli).
 * Il part en direct sur le Wi-Fi si la télécommande y est branchée, et par internet (ntfy) sinon.
 * Les messages lourds (position, batterie, photos) ne passent pas par internet quand le direct marche :
 * ça évite de saturer les limites de ntfy.sh, partagées par les deux téléphones sur un même Wi-Fi.
 */
object Report {
    fun send(c: Context, text: String) {
        val secret = Prefs.secret(c)
        val enc = Crypto.encrypt(secret, "${System.currentTimeMillis() / 1000}|$text")
        Lan.push(enc)
        val heavy = text.startsWith("loc|") || text.startsWith("ack|") || text.startsWith("hb|") || text.startsWith("photo|")
        if (!(heavy && Lan.active())) Ntfy.publish(Crypto.topic(secret, "rep"), enc)
    }

    fun event(c: Context, type: String, detail: String) = send(c, "evt|$type|$detail")
}
