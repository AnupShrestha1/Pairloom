package com.anupshrestha.pairloom.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/** This phone's TLS identity: a non-exportable Keystore key and its self-signed certificate. */
class Identity(val privateKey: PrivateKey, val certificate: X509Certificate) {
    val fingerprint: ByteArray = Fingerprints.of(certificate.publicKey)
    val fingerprintHex: String = fingerprint.toHex()
}

/** The Keystore identity could not be created or failed its checks. */
class IdentityUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Creates or loads the identity key: EC P-256, sign only, digests NONE and SHA-256.
 *
 * DIGEST_NONE is required because the platform TLS stack signs pre-hashed data through Keystore
 * (`NONEwithECDSA`); see docs/security/phase2-secure-session.md. The key is used for TLS
 * authentication only and nothing else.
 */
object DeviceIdentity {
    const val ALIAS = "pairloom.identity.v1"
    private const val PROVIDER = "AndroidKeyStore"
    private val REQUIRED_DIGESTS = setOf(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)

    fun getOrCreate(): Identity {
        try {
            val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
            if (!ks.containsAlias(ALIAS)) generate()
            val key = ks.getKey(ALIAS, null) as? PrivateKey ?: throw IdentityUnavailableException("identity key missing")
            val cert = ks.getCertificate(ALIAS) as? X509Certificate ?: throw IdentityUnavailableException("identity certificate missing")
            check(key, cert)
            return Identity(key, cert)
        } catch (e: IdentityUnavailableException) {
            throw e
        } catch (e: Exception) {
            throw IdentityUnavailableException("Keystore error: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    private fun generate() {
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
            .setCertificateSubject(X500Principal("CN=Pairloom"))
            .setCertificateSerialNumber(BigInteger(63, SecureRandom()).add(BigInteger.ONE))
            .setCertificateNotBefore(Date(now - DAY_MS))
            .setCertificateNotAfter(Date(now + 30 * 365 * DAY_MS))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    private fun check(key: PrivateKey, cert: X509Certificate) {
        if (!Fingerprints.isP256(cert.publicKey)) throw IdentityUnavailableException("identity key is not EC P-256")
        try {
            cert.verify(cert.publicKey)
        } catch (e: Exception) {
            throw IdentityUnavailableException("identity certificate does not verify", e)
        }
        val info = KeyFactory.getInstance(key.algorithm, PROVIDER).getKeySpec(key, KeyInfo::class.java)
        if (info.purposes and KeyProperties.PURPOSE_SIGN == 0) throw IdentityUnavailableException("identity key cannot sign")
        if (!info.digests.toSet().containsAll(REQUIRED_DIGESTS)) {
            throw IdentityUnavailableException("identity key digests ${info.digests.toList()} lack $REQUIRED_DIGESTS")
        }
        if (key.encoded != null) throw IdentityUnavailableException("identity key is exportable")
    }

    private const val DAY_MS = 24L * 3600 * 1000
}
