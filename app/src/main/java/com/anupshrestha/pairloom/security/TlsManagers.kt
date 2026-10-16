package com.anupshrestha.pairloom.security

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/** Offers this phone's identity for EC handshakes and nothing else. */
class IdentityKeyManager(private val identity: Identity) : X509ExtendedKeyManager() {
    private fun pick(types: List<String?>): String? = if (types.any { it == "EC" }) ALIAS else null

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = pick(keyType?.toList().orEmpty())
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = pick(listOf(keyType))
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = pick(keyType?.toList().orEmpty())
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = pick(listOf(keyType))
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = if (keyType == "EC") arrayOf(ALIAS) else null
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = if (keyType == "EC") arrayOf(ALIAS) else null
    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = if (alias == ALIAS) arrayOf(identity.certificate) else null
    override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) identity.privateKey else null

    private companion object {
        const val ALIAS = "pairloom"
    }
}

/**
 * Handshake-time checks on the peer's certificate: exactly one self-signed EC P-256 certificate.
 * It records the key's fingerprint; whether that key belongs to a paired phone is decided right
 * after the handshake, before any application data (see the Phase 2 design). Anything malformed
 * is rejected here, which aborts the handshake.
 */
class PeerTrustManager : X509ExtendedTrustManager() {
    val calls = AtomicInteger(0)
    private val seen = AtomicReference<ByteArray?>(null)

    /** Fingerprint of the certificate accepted in this handshake, or null if none was. */
    fun acceptedFingerprint(): ByteArray? = seen.get()?.copyOf()

    private fun check(chain: Array<out X509Certificate>?) {
        calls.incrementAndGet()
        seen.set(null)
        val cert = chain?.singleOrNull() ?: throw CertificateException("expected exactly one peer certificate, got ${chain?.size ?: 0}")
        val key = cert.publicKey
        if (!Fingerprints.isP256(key)) throw CertificateException("peer key is not EC P-256")
        try {
            cert.verify(key)
        } catch (e: Exception) {
            throw CertificateException("peer certificate is not self-signed by its key", e)
        }
        seen.set(Fingerprints.of(key))
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
