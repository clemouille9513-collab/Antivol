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

    /** Clé de liaison du S22 (créée au premier besoin). */
    fun secret(c: Context): String {
        var s = sp(c).getString("secret", null)
        if (s == null) {
            s = Crypto.newSecret()
            sp(c).edit().putString("secret", s).apply()
        }
        return s
    }

    /** Clé saisie sur le téléphone qui sert de télécommande. */
    fun remoteSecret(c: Context): String = sp(c).getString("rsecret", "") ?: ""
    fun setRemoteSecret(c: Context, v: String) = sp(c).edit().putString("rsecret", v).apply()

    /** Mode "volé" actif (reste actif même sans réseau). */
    fun isStolen(c: Context) = sp(c).getBoolean("stolen", false)
    fun setStolen(c: Context, v: Boolean) = sp(c).edit().putBoolean("stolen", v).apply()

    /** Positions reçues par la télécommande : "horodatage,lat,lon,précision". */
    fun history(c: Context): List<String> {
        val raw = sp(c).getString("history", "") ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split(";")
    }

    @Synchronized
    fun addHistory(c: Context, entry: String) {
        val h = history(c)
        val ts = entry.substringBefore(",")
        if (h.any { it.substringBefore(",") == ts }) return
        val n = (h + entry).sortedBy { it.substringBefore(",").toLongOrNull() ?: 0L }.takeLast(300)
        sp(c).edit().putString("history", n.joinToString(";")).apply()
    }

    /** Détection des regards indiscrets (caméra avant, toutes les 30 secondes). */
    fun isPeekEnabled(c: Context) = sp(c).getBoolean("peek", true)
    fun setPeekEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("peek", v).apply()

    /** Secondes d'attente avant de verrouiller quand l'appareil disparaît. */
    fun graceSeconds(c: Context) = sp(c).getInt("grace", 30)
}
