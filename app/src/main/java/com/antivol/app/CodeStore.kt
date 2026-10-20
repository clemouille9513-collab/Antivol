package com.antivol.app

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Stocke les codes sous forme de hachages (jamais en clair).
 * - codes : la suite de codes à saisir dans l'ordre pour débloquer
 * - codes de secours : à usage unique, n'importe lequel débloque tout
 */
object CodeStore {
    private const val KEY_CODES = "codes"
    private const val KEY_RECOVERY = "recovery"

    private fun sp(c: Context) = c.getSharedPreferences("antivol_codes", Context.MODE_PRIVATE)

    private fun hash(code: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(code.toCharArray(), salt, 30000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun makeEntry(code: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(salt, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(hash(code, salt), Base64.NO_WRAP)
    }

    private fun matches(code: String, entry: String): Boolean {
        val parts = entry.split(":")
        if (parts.size != 2) return false
        val salt = Base64.decode(parts[0], Base64.NO_WRAP)
        val expected = Base64.decode(parts[1], Base64.NO_WRAP)
        return MessageDigest.isEqual(hash(code, salt), expected)
    }

    private fun load(c: Context, key: String): List<String> {
        val raw = sp(c).getString(key, "") ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split(";")
    }

    private fun save(c: Context, key: String, list: List<String>) {
        sp(c).edit().putString(key, list.joinToString(";")).apply()
    }

    // ----- codes à la suite -----

    fun count(c: Context) = load(c, KEY_CODES).size

    fun addCode(c: Context, code: String) {
        save(c, KEY_CODES, load(c, KEY_CODES) + makeEntry(code))
    }

    fun clearCodes(c: Context) = save(c, KEY_CODES, emptyList())

    /** Vérifie le code numéro [index] (0 = premier code). */
    fun check(c: Context, index: Int, code: String): Boolean {
        val list = load(c, KEY_CODES)
        if (index !in list.indices) return false
        return matches(code, list[index])
    }

    // ----- codes de secours -----

    fun recoveryCount(c: Context) = load(c, KEY_RECOVERY).size

    /** Génère 5 nouveaux codes de secours (8 chiffres). Les anciens sont remplacés. */
    fun generateRecovery(c: Context): List<String> {
        val rnd = SecureRandom()
        val codes = List(5) { String.format("%08d", rnd.nextInt(100_000_000)) }
        save(c, KEY_RECOVERY, codes.map { makeEntry(it) })
        return codes
    }

    /** Vrai si le code est un code de secours valide. Il est alors consommé. */
    fun checkRecovery(c: Context, code: String): Boolean {
        val list = load(c, KEY_RECOVERY)
        val found = list.indexOfFirst { matches(code, it) }
        if (found < 0) return false
        save(c, KEY_RECOVERY, list.filterIndexed { i, _ -> i != found })
        return true
    }
}
