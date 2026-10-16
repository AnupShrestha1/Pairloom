package com.anupshrestha.pairloom.security

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Numeric-comparison pairing with a commitment (see docs/security/phase2-secure-session.md).
 * `serverFp`/`clientFp` are the TLS key fingerprints as this phone observed them.
 */
object PairingCrypto {
    const val NONCE_BYTES = 32
    private val COMMIT_LABEL = "pairloom-commit-v1".toByteArray(Charsets.US_ASCII)
    private val SAS_LABEL = "pairloom-sas-v1".toByteArray(Charsets.US_ASCII)

    fun newNonce(random: SecureRandom): ByteArray = ByteArray(NONCE_BYTES).also(random::nextBytes)

    fun commitment(serverFp: ByteArray, clientFp: ByteArray, serverNonce: ByteArray): ByteArray {
        requireSizes(serverFp, clientFp, serverNonce)
        return sha256(COMMIT_LABEL, serverFp, clientFp, serverNonce)
    }

    fun commitmentMatches(commitment: ByteArray, serverFp: ByteArray, clientFp: ByteArray, serverNonce: ByteArray): Boolean =
        MessageDigest.isEqual(commitment, commitment(serverFp, clientFp, serverNonce))

    /** The 6-digit comparison code, zero-padded. */
    fun code(serverFp: ByteArray, clientFp: ByteArray, serverNonce: ByteArray, clientNonce: ByteArray): String {
        requireSizes(serverFp, clientFp, serverNonce)
        require(clientNonce.size == NONCE_BYTES) { "client nonce must be $NONCE_BYTES bytes" }
        val v = sha256(SAS_LABEL, serverFp, clientFp, serverNonce, clientNonce)
        val x = v.take(8).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        return "%06d".format(java.lang.Long.remainderUnsigned(x, 1_000_000L))
    }

    /** "123456" -> "123 456". */
    fun display(code: String): String = code.take(3) + " " + code.drop(3)

    private fun requireSizes(serverFp: ByteArray, clientFp: ByteArray, serverNonce: ByteArray) {
        require(serverFp.size == Fingerprints.BYTES && clientFp.size == Fingerprints.BYTES) { "fingerprints must be ${Fingerprints.BYTES} bytes" }
        require(serverNonce.size == NONCE_BYTES) { "server nonce must be $NONCE_BYTES bytes" }
    }

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }
}
