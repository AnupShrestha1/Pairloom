package com.anupshrestha.pairloom.security

import android.net.ssl.SSLSockets
import android.os.Build
import java.io.IOException
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager

/** The mutual TLS handshake failed or produced inconsistent evidence. */
class SecureHandshakeException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A finished mutual TLS connection and the key the peer proved it holds. */
class SecureConnection(
    val socket: SSLSocket,
    val peerFingerprint: ByteArray,
    val protocol: String,
    val cipherSuite: String,
)

/**
 * Wraps a connected TCP socket in mutual TLS. Every call uses a new SSLContext and turns session
 * tickets off where the API exists, so sessions are never resumed.
 */
object SecureTransport {
    private val PROTOCOLS = listOf("TLSv1.3", "TLSv1.2")
    private val SUITES = listOf(
        "TLS_AES_128_GCM_SHA256",
        "TLS_AES_256_GCM_SHA384",
        "TLS_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
    )

    /**
     * Runs the handshake as server (group owner, requires a client certificate) or client.
     * [plain] stays owned by the returned socket (closing either closes both).
     */
    fun establish(plain: Socket, isServer: Boolean, identity: Identity, timeoutMs: Int): SecureConnection {
        val trust = PeerTrustManager()
        val context = SSLContext.getInstance("TLS").apply {
            init(arrayOf<KeyManager>(IdentityKeyManager(identity)), arrayOf<TrustManager>(trust), SecureRandom())
        }
        val ssl = context.socketFactory.createSocket(plain, plain.inetAddress.hostAddress, plain.port, true) as SSLSocket
        try {
            ssl.useClientMode = !isServer
            if (isServer) ssl.needClientAuth = true
            ssl.enabledProtocols = PROTOCOLS.filter { it in ssl.supportedProtocols }.toTypedArray()
            val suites = SUITES.filter { it in ssl.supportedCipherSuites }
            if (ssl.enabledProtocols.isEmpty() || suites.isEmpty()) throw SecureHandshakeException("no supported TLS protocol or cipher suite")
            ssl.enabledCipherSuites = suites.toTypedArray()
            if (Build.VERSION.SDK_INT >= 29 && SSLSockets.isSupportedSocket(ssl)) SSLSockets.setUseSessionTickets(ssl, false)
            plain.soTimeout = timeoutMs
            try {
                ssl.startHandshake()
            } catch (e: IOException) {
                throw SecureHandshakeException("TLS handshake failed: ${e.message}", e)
            }
            val session = ssl.session
            val peerCert = runCatching { session.peerCertificates.single() as X509Certificate }.getOrNull()
                ?: throw SecureHandshakeException("no peer certificate after the handshake")
            val fingerprint = Fingerprints.of(peerCert.publicKey)
            val checked = trust.acceptedFingerprint()
            if (trust.calls.get() != 1 || checked == null || !MessageDigest.isEqual(checked, fingerprint)) {
                throw SecureHandshakeException("the peer certificate was not checked exactly once during the handshake")
            }
            plain.soTimeout = 0
            return SecureConnection(ssl, fingerprint, session.protocol, session.cipherSuite)
        } catch (e: Exception) {
            runCatching { ssl.close() }
            throw if (e is SecureHandshakeException) e else SecureHandshakeException("TLS setup failed: ${e.message}", e)
        }
    }
}
