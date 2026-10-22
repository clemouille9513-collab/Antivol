package com.antivol.app

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Permet à l'appli d'être "administrateur de l'appareil" pour verrouiller l'écran.
 * Android ne laisse pas une appli exiger un code avant la désactivation, mais on prévient
 * la télécommande dès que quelqu'un essaie.
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        report(context, "Tentative de désactivation de l'administrateur")
        return "Cela coupe la protection antivol de ce téléphone."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        report(context, "Administrateur désactivé : l'appli peut maintenant être désinstallée")
    }

    /** Mauvais code sur l'écran de verrouillage Samsung : photo de l'intrus. */
    override fun onPasswordFailed(context: Context, intent: Intent) {
        if (!Prefs.isEnabled(context)) return
        try {
            context.startForegroundService(Intent(context, GuardService::class.java).putExtra("intruder", true))
        } catch (_: Exception) {
        }
    }

    private fun report(context: Context, text: String) {
        if (!Prefs.isEnabled(context)) return
        val secret = Prefs.secret(context)
        val plain = "${System.currentTimeMillis() / 1000}|evt|admin|$text"
        Ntfy.publish(Crypto.topic(secret, "rep"), Crypto.encrypt(secret, plain))
    }
}
