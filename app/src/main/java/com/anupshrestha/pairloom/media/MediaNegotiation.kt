package com.anupshrestha.pairloom.media

import com.anupshrestha.pairloom.net.ControlProtocol.Message
import com.anupshrestha.pairloom.security.hexToBytesOrNull
import com.anupshrestha.pairloom.security.toHex
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom

/** What a media session carries. */
enum class MediaKind(val code: Int) {
    /** The A1 diagnostic tone; answered automatically. */
    TONE(0),

    /** A voice call; the receiving user must accept it. */
    VOICE(1),

    /** Push-to-talk (walkie-talkie); the receiving user must accept it, as it opens the microphone. */
    PTT(2),
    ;

    /** Uses the microphone, so the receiving user must accept. */
    val needsAccept: Boolean get() = this != TONE

    companion object {
        fun fromCode(code: Int): MediaKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Starts and stops media sessions over the authenticated control channel.
 *
 * Either phone may start: it binds a UDP socket on its Wi-Fi Direct address, creates a key for its
 * own sending direction and sends a MEDIA_OFFER; the peer answers with its own port and key, and
 * both start a [MediaSession] towards the address of the authenticated TLS peer. A voice offer
 * waits until the receiving user calls [accept] (or [decline]); a tone offer is answered at once.
 * If both phones offer at once, the TLS server's offer wins. All methods may block on network I/O,
 * so they must run off the main thread; they are synchronized.
 */
class MediaNegotiation(
    private val isServer: Boolean,
    private val localAddress: InetAddress,
    private val peerAddress: InetAddress,
    private val send: (Message) -> Unit,
    private val newSession: (MediaKind, DatagramSocket, InetSocketAddress, DirectionKey, DirectionKey) -> MediaSession =
        { _, s, p, out, `in` -> MediaSession(s, p, out, `in`) },
    private val log: (String) -> Unit = {},
    private val random: SecureRandom = SecureRandom(),
) {
    private class Offer(val kind: MediaKind, val socket: DatagramSocket, val key: DirectionKey)

    private var offer: Offer? = null
    private var incoming: Message.MediaOffer? = null

    @Volatile var session: MediaSession? = null
        private set

    @Volatile var kind: MediaKind? = null
        private set

    val isOffering: Boolean @Synchronized get() = offer != null

    @Volatile private var starting = false

    /** True from an offer (sent or received) until the session has ended. */
    val isBusy: Boolean get() = starting || session != null || isOffering || incomingKind != null

    /** The kind of a peer's offer waiting for the local user, if any. */
    val incomingKind: MediaKind? @Synchronized get() = incoming?.let { MediaKind.fromCode(it.kind) }

    /** The user starts a session. Ignored if one is active, offered or waiting for an answer. */
    @Synchronized
    fun start(kind: MediaKind) {
        if (session != null || offer != null || incoming != null) return
        val socket = bind()
        val key = DirectionKey.random(random)
        offer = Offer(kind, socket, key)
        log("Media: offering $kind from port ${socket.localPort}")
        try {
            send(Message.MediaOffer(kind.code, socket.localPort, key.key.toHex(), key.salt.toHex()))
        } catch (e: Exception) {
            offer = null
            socket.close()
            throw e
        }
    }

    /** The local user accepts the waiting offer. */
    @Synchronized
    fun accept() {
        val m = incoming ?: return
        incoming = null
        answer(m)
    }

    /** The local user declines the waiting offer. */
    @Synchronized
    fun decline() {
        if (incoming == null) return
        incoming = null
        log("Media: declined the other phone's call")
        runCatching { send(Message.MediaStop) }
    }

    /** The user stops the session (or cancels an offer); tells the peer. */
    @Synchronized
    fun stop() {
        val wasActive = session != null || offer != null || incoming != null
        close()
        if (wasActive) runCatching { send(Message.MediaStop) }
    }

    /** Ends everything without telling the peer (link teardown). */
    @Synchronized
    fun close(): MediaStats? {
        offer?.socket?.close()
        offer = null
        incoming = null
        val stats = session?.stop()
        session = null
        kind = null
        return stats
    }

    /** A media-control message from the peer (called on the control channel's read thread). */
    @Synchronized
    fun onMessage(message: Message) {
        when (message) {
            is Message.MediaOffer -> onOffer(message)
            is Message.MediaAnswer -> onAnswer(message)
            Message.MediaStop -> {
                log("Media: the other phone ended the session")
                close()
            }
            else -> Unit
        }
    }

    private fun onOffer(m: Message.MediaOffer) {
        val offeredKind = MediaKind.fromCode(m.kind) ?: return log("Media: ignored an offer of unknown kind ${m.kind}")
        if (key(m.keyHex, m.saltHex) == null) return log("Media: ignored an offer with a malformed key")
        if (offer != null) {
            if (isServer) return log("Media: both phones offered; keeping this phone's offer")
            log("Media: both phones offered; taking the other phone's offer")
            offer?.socket?.close()
            offer = null
        }
        if (session != null) {
            log("Media: the other phone restarted the session")
            session?.stop()
            session = null
            kind = null
        }
        if (offeredKind.needsAccept) {
            incoming = m
            log("Media: incoming $offeredKind; waiting for the user")
        } else {
            answer(m)
        }
    }

    private fun answer(m: Message.MediaOffer) {
        val offeredKind = MediaKind.fromCode(m.kind) ?: return
        val peerKey = key(m.keyHex, m.saltHex) ?: return
        val socket = bind()
        val own = DirectionKey.random(random)
        try {
            send(Message.MediaAnswer(socket.localPort, own.key.toHex(), own.salt.toHex()))
        } catch (e: Exception) {
            socket.close()
            throw e
        }
        begin(offeredKind, socket, m.port, own, peerKey)
    }

    private fun onAnswer(m: Message.MediaAnswer) {
        val pending = offer ?: return log("Media: ignored an answer without an offer")
        val peerKey = key(m.keyHex, m.saltHex) ?: return log("Media: ignored an answer with a malformed key")
        offer = null
        begin(pending.kind, pending.socket, m.port, pending.key, peerKey)
    }

    private fun begin(k: MediaKind, socket: DatagramSocket, peerPort: Int, sendKey: DirectionKey, receiveKey: DirectionKey) {
        starting = true // opening the microphone and speaker takes a moment
        try {
            val s = try {
                newSession(k, socket, InetSocketAddress(peerAddress, peerPort), sendKey, receiveKey)
            } catch (e: Exception) {
                socket.close()
                log("Media: could not start ($k): ${e.message}")
                runCatching { send(Message.MediaStop) }
                return
            }
            session = s
            kind = k
            s.start()
        } finally {
            starting = false
        }
    }

    private fun bind(): DatagramSocket = DatagramSocket(InetSocketAddress(localAddress, 0))

    private fun key(keyHex: String, saltHex: String): DirectionKey? {
        val k = hexToBytesOrNull(keyHex, DirectionKey.KEY_BYTES) ?: return null
        val s = hexToBytesOrNull(saltHex, DirectionKey.SALT_BYTES) ?: return null
        return DirectionKey(k, s)
    }
}
