package com.antivol.app

import android.content.Context

/** Réglages simples de l'appli. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("antivol", Context.MODE_PRIVATE)

    fun isEnabled(c: Context) = sp(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    /** Vrai tant que l'écran de blocage n'a pas été débloqué avec tous les codes. */
    fun isLocked(c: Context) = sp(c).getBoolean("locked", false)
    fun setLocked(c: Context, v: Boolean) = sp(c).edit().putBoolean("locked", v).apply()

    /** Adresses Bluetooth des appareils de confiance (montre, AirPods...). */
    fun trustedDevices(c: Context): Set<String> =
        sp(c).getStringSet("trusted", emptySet())?.toSet() ?: emptySet()

    fun setTrusted(c: Context, s: Set<String>) =
        sp(c).edit().putStringSet("trusted", HashSet(s)).apply()

    /** Secondes d'attente avant de verrouiller quand l'appareil disparaît. */
    fun graceSeconds(c: Context) = sp(c).getInt("grace", 30)
}
