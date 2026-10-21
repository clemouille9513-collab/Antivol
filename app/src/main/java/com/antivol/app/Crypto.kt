package com.antivol.app

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Liaison entre le S22 et la télécommande.
 * Les deux appareils partagent une clé secrète (12 caractères).
 * - Le nom du "canal" de messages est dérivé de la clé : personne ne peut le deviner.
 * - Chaque message est chiffré (AES-GCM) avec la clé : le serveur ne voit rien de lisible
 *   et un message falsifié est rejeté.
 */
object Crypto {
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    private fun sha(s: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())

    fun newSecret(): String {
        val rnd = SecureRandom()
        return String(CharArray(12) { ALPHABET[rnd.nextInt(ALPHABET.length)] })
    }

    /** Ne garde que les lettres et chiffres, en majuscules (pour la saisie à la main). */
    fun normalize(s: String): String = s.uppercase().filter { it.isLetterOrDigit() }

    fun pretty(secret: String): String = secret.chunked(4).joinToString("-")

    fun topic(secret: String, kind: String): String =
        "antivol-" + sha("$secret:$kind").joinToString("") { "%02x".format(it) }.take(32)

    fun encrypt(secret: String, plain: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(sha("$secret:enc"), "AES"),
            GCMParameterSpec(128, iv)
        )
        return Base64.encodeToString(iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    fun decrypt(secret: String, b64: String): String? = try {
        val raw = Base64.decode(b64.trim(), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(sha("$secret:enc"), "AES"),
            GCMParameterSpec(128, raw.copyOfRange(0, 12))
        )
        String(cipher.doFinal(raw, 12, raw.size - 12))
    } catch (e: Exception) {
        null
    }
}
