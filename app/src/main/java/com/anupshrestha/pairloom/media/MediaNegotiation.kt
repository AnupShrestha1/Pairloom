package com.anupshrestha.pairloom.media

import com.anupshrestha.pairloom.net.ControlProtocol.Message
import com.anupshrestha.pairloom.security.hexToBytesOrNull
import com.anupshrestha.pairloom.security.toHex
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom

/**
 * Starts and stops media sessions over the authenticated control channel.
 *
 * Either phone may start: it binds a UDP socket on its Wi-Fi Direct address, creates a key for its
 * own sending direction and sends a MEDIA_OFFER; the peer answers with its own port and key, and
 * both start a [MediaSession] towards the address of the authenticated TLS peer. If both phones
 * offer at once, the TLS server's offer wins. All methods may block on network I/O, so they must
 * run off the main thread; they are synchronized.
 */
class MediaNegotiation(
    private val isServer: Boolean,
    private val localAddress: InetAddress,
    private val peerAddress: InetAddress,
    private val send: (Message) -> Unit,
    private val newSession: (DatagramSocket, InetSocketAddress, DirectionKey, DirectionKey) -> MediaSession =
        { s, p, out, `in` -> MediaSession(s, p, out, `in`) },
    private val log: (String) -> Unit = {},
    private val random: SecureRandom = SecureRandom(),
) {
    private class Offer(val socket: DatagramSocket, val key: DirectionKey)

    private var offer: Offer? = null

    @Volatile var session: MediaSession? = null
        private set

    val isOffering: Boolean @Synchronized get() = offer != null

    /** The user starts a session. Ignored if one is active or already offered. */
    @Synchronized
    fun start() {
        if (session != null || offer != null) return
        val socket = bind()
        val key = DirectionKey.random(random)
        offer = Offer(socket, key)
        log("Media: offering from port ${socket.localPort}")
        try {
            send(Message.MediaOffer(socket.localPort, key.key.toHex(), key.salt.toHex()))
        } catch (e: Exception) {
            offer = null
            socket.close()
            throw e
        }
    }

    /** The user stops the session; tells the peer. */
    @Synchronized
    fun stop() {
        val wasActive = session != null || offer != null
        close()
        if (wasActive) runCatching { send(Message.MediaStop) }
    }

    /** Ends everything without telling the peer (link teardown). */
    @Synchronized
    fun close(): MediaStats? {
        offer?.socket?.close()
        offer = null
        val stats = session?.stop()
        session = null
        return stats
    }

    /** A media-control message from the peer (called on the control channel's read thread). */
    @Synchronized
    fun onMessage(message: Message) {
        when (message) {
            is Message.MediaOffer -> onOffer(message)
            is Message.MediaAnswer -> onAnswer(message)
            Message.MediaStop -> {
                log("Media: the other phone stopped the session")
                close()
            }
            else -> Unit
        }
    }

    private fun onOffer(m: Message.MediaOffer) {
        val peerKey = key(m.keyHex, m.saltHex) ?: return log("Media: ignored an offer with a malformed key")
        if (offer != null) {
            if (isServer) return log("Media: both phones offered; keeping this phone's offer")
            log("Media: both phones offered; answering the other phone's offer")
            offer?.socket?.close()
            offer = null
        }
        if (session != null) {
            log("Media: the other phone restarted the session")
            session?.stop()
            session = null
        }
        val socket = bind()
        val own = DirectionKey.random(random)
        try {
            send(Message.MediaAnswer(socket.localPort, own.key.toHex(), own.salt.toHex()))
        } catch (e: Exception) {
            socket.close()
            throw e
        }
        begin(socket, m.port, own, peerKey)
    }

    private fun onAnswer(m: Message.MediaAnswer) {
        val pending = offer ?: return log("Media: ignored an answer without an offer")
        val peerKey = key(m.keyHex, m.saltHex) ?: return log("Media: ignored an answer with a malformed key")
        offer = null
        begin(pending.socket, m.port, pending.key, peerKey)
    }

    private fun begin(socket: DatagramSocket, peerPort: Int, sendKey: DirectionKey, receiveKey: DirectionKey) {
        val s = newSession(socket, InetSocketAddress(peerAddress, peerPort), sendKey, receiveKey)
        session = s
        s.start()
    }

    private fun bind(): DatagramSocket = DatagramSocket(InetSocketAddress(localAddress, 0))

    private fun key(keyHex: String, saltHex: String): DirectionKey? {
        val k = hexToBytesOrNull(keyHex, DirectionKey.KEY_BYTES) ?: return null
        val s = hexToBytesOrNull(saltHex, DirectionKey.SALT_BYTES) ?: return null
        return DirectionKey(k, s)
    }
}
