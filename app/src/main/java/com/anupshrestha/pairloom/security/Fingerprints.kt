package com.anupshrestha.pairloom.security

import java.security.AlgorithmParameters
import java.security.MessageDigest
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec

/** Key fingerprints and hex helpers. Pure Java APIs only, so this is unit-testable on the JVM. */
object Fingerprints {
    const val BYTES = 32

    /** SHA-256 of the key's SubjectPublicKeyInfo encoding. */
    fun of(key: PublicKey): ByteArray = MessageDigest.getInstance("SHA-256").digest(key.encoded)

    /** True only if [key] is EC on exactly the standard secp256r1 (P-256) curve. */
    fun isP256(key: PublicKey): Boolean {
        val params = (key as? ECPublicKey)?.params ?: return false
        val p256 = AlgorithmParameters.getInstance("EC")
            .apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        return params.curve == p256.curve &&
            params.generator == p256.generator &&
            params.order == p256.order &&
            params.cofactor == p256.cofactor
    }

    /** First 16 hex characters in groups of four, for showing a key to a person. */
    fun short(hex: String): String = hex.take(16).chunked(4).joinToString(" ").uppercase()
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Parses lowercase or uppercase hex of exactly [bytes] bytes, or returns null. */
fun hexToBytesOrNull(text: String, bytes: Int): ByteArray? {
    val clean = text.lowercase()
    if (clean.length != bytes * 2 || !clean.all { it in '0'..'9' || it in 'a'..'f' }) return null
    return ByteArray(bytes) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
