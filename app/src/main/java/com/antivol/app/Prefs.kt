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

    /** Le S22 écoute la télécommande même si la protection complète n'est pas activée. */
    fun isListening(c: Context) = sp(c).getBoolean("listen", false)
    fun setListening(c: Context, v: Boolean) = sp(c).edit().putBoolean("listen", v).apply()

    /** Détection des regards indiscrets (caméra avant, toutes les 30 secondes). */
    fun isPeekEnabled(c: Context) = sp(c).getBoolean("peek", true)
    fun setPeekEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("peek", v).apply()

    /** Suivi des trajets : une position envoyée toutes les 10 minutes quand la protection est active. */
    fun isTracking(c: Context) = sp(c).getBoolean("track", true)
    fun setTracking(c: Context, v: Boolean) = sp(c).edit().putBoolean("track", v).apply()

    /** Mémorise les derniers ordres reçus pour ne jamais exécuter deux fois le même. Vrai si l'ordre est nouveau. */
    @Synchronized
    fun markSeen(c: Context, plain: String): Boolean {
        val h = Integer.toHexString(plain.hashCode()) + "x" + plain.length
        val list = (sp(c).getString("seen", "") ?: "").split(",").filter { it.isNotEmpty() }
        if (h in list) return false
        sp(c).edit().putString("seen", (list + h).takeLast(40).joinToString(",")).apply()
        return true
    }

    fun str(c: Context, key: String, def: String = "") = sp(c).getString(key, def) ?: def
    fun setStr(c: Context, key: String, v: String) = sp(c).edit().putString(key, v).apply()
    fun bool(c: Context, key: String, def: Boolean = false) = sp(c).getBoolean(key, def)
    fun setBool(c: Context, key: String, v: Boolean) = sp(c).edit().putBoolean(key, v).apply()
    fun long(c: Context, key: String, def: Long = 0L) = sp(c).getLong(key, def)
    fun setLong(c: Context, key: String, v: Long) = sp(c).edit().putLong(key, v).apply()

    /** Secondes d'attente avant de verrouiller quand l'appareil disparaît. */
    fun graceSeconds(c: Context) = sp(c).getInt("grace", 30)
}
