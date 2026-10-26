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

/** Why a media session (or an attempt at one) ended, for the user. */
enum class MediaEnd {
    /** This phone's user ended or cancelled it. */
    ENDED_HERE,
    ENDED_BY_PEER,
    /** The other phone ended it before it was answered here (a missed call). */
    MISSED,
    DECLINED_HERE,
    DECLINED_BY_PEER,
    /** Opening the audio or codec failed on one of the phones. */
    FAILED,
    /** Another app or a phone call took the audio (set by the caller of [MediaNegotiation.stop]). */
    AUDIO_TAKEN,

    /** The link to the other phone was lost (set by the link owner). */
    LINK_LOST,
}

/**
 * Starts and stops media sessions over the authenticated control channel.
 *
 * Either phone may start: it binds a UDP socket on its Wi-Fi Direct address, creates a key for its
 * own sending direction and sends a MEDIA_OFFER; the peer answers with its own port and key, and
 * both start a [MediaSession] towards the address of the authenticated TLS peer. A voice offer
 * waits until the receiving user calls [accept] (or [decline]); a tone offer is answered at once.
 * If both phones offer at once, the TLS server's offer wins. The offer lists the codecs the offerer
 * can use and the answer picks one for both directions: Opus when both phones have it, otherwise
 * PCM (the test tone always uses PCM, as its frames are checked bit for bit). All methods may block on network I/O,
 * so they must run off the main thread; they are synchronized.
 */
class MediaNegotiation(
    private val isServer: Boolean,
    private val localAddress: InetAddress,
    private val peerAddress: InetAddress,
    private val send: (Message) -> Unit,
    private val newSession: (MediaKind, AudioCodec, DatagramSocket, InetSocketAddress, DirectionKey, DirectionKey) -> MediaSession =
        { _, _, s, p, out, `in` -> MediaSession(s, p, out, `in`) },
    /** Codecs this phone can use for voice now, as an [AudioCodec] bit mask (PCM is always added). */
    private val localCodecs: () -> Int = { AudioCodec.PCM.bit },
    private val log: (String) -> Unit = {},
    /** Called (with the lock held, so keep it short) when a session or an attempt ends. Not on [close]. */
    private val onEnded: (MediaKind, MediaEnd) -> Unit = { _, _ -> },
    private val random: SecureRandom = SecureRandom(),
) {
    private class Offer(val kind: MediaKind, val codecs: Int, val socket: DatagramSocket, val key: DirectionKey)

    private fun codecsFor(kind: MediaKind) = if (kind == MediaKind.TONE) AudioCodec.PCM.bit else localCodecs() or AudioCodec.PCM.bit

    /** The codec of the running session, if any. */
    @Volatile var codec: AudioCodec? = null
        private set

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
        val codecs = codecsFor(kind)
        offer = Offer(kind, codecs, socket, key)
        log("Media: offering $kind from port ${socket.localPort}")
        try {
            send(Message.MediaOffer(kind.code, codecs, socket.localPort, key.key.toHex(), key.salt.toHex()))
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
        val m = incoming ?: return
        incoming = null
        log("Media: declined the other phone's call")
        runCatching { send(Message.MediaStop) }
        MediaKind.fromCode(m.kind)?.let { onEnded(it, MediaEnd.DECLINED_HERE) }
    }

    /** The user stops the session (or cancels an offer); tells the peer. */
    @Synchronized
    fun stop(reason: MediaEnd = MediaEnd.ENDED_HERE) {
        val ending = currentKind()
        close()
        if (ending != null) {
            runCatching { send(Message.MediaStop) }
            onEnded(ending, reason)
        }
    }

    /** The kind of the session, offer or waiting call, if any. */
    private fun currentKind(): MediaKind? = kind ?: offer?.kind ?: incoming?.let { MediaKind.fromCode(it.kind) }

    /** Ends everything without telling the peer (link teardown). */
    @Synchronized
    fun close(): MediaStats? {
        offer?.socket?.close()
        offer = null
        incoming = null
        val stats = session?.stop()
        session = null
        kind = null
        codec = null
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
                val reason = when {
                    offer != null -> MediaEnd.DECLINED_BY_PEER
                    incoming != null -> MediaEnd.MISSED
                    else -> MediaEnd.ENDED_BY_PEER
                }
                val ending = currentKind()
                close()
                ending?.let { onEnded(it, reason) }
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
        val chosen = AudioCodec.choose(m.codecs, codecsFor(offeredKind))
        val socket = bind()
        val own = DirectionKey.random(random)
        try {
            send(Message.MediaAnswer(chosen.code, socket.localPort, own.key.toHex(), own.salt.toHex()))
        } catch (e: Exception) {
            socket.close()
            throw e
        }
        begin(offeredKind, chosen, socket, m.port, own, peerKey)
    }

    private fun onAnswer(m: Message.MediaAnswer) {
        val pending = offer ?: return log("Media: ignored an answer without an offer")
        val peerKey = key(m.keyHex, m.saltHex) ?: return log("Media: ignored an answer with a malformed key")
        val chosen = AudioCodec.fromCode(m.codec)?.takeIf { it.bit and pending.codecs != 0 }
        offer = null
        if (chosen == null) {
            pending.socket.close()
            log("Media: the answer chose codec ${m.codec}, which was not offered")
            runCatching { send(Message.MediaStop) }
            onEnded(pending.kind, MediaEnd.FAILED)
            return
        }
        begin(pending.kind, chosen, pending.socket, m.port, pending.key, peerKey)
    }

    private fun begin(k: MediaKind, c: AudioCodec, socket: DatagramSocket, peerPort: Int, sendKey: DirectionKey, receiveKey: DirectionKey) {
        starting = true // opening the microphone and speaker takes a moment
        try {
            val s = try {
                newSession(k, c, socket, InetSocketAddress(peerAddress, peerPort), sendKey, receiveKey)
            } catch (e: Exception) {
                socket.close()
                log("Media: could not start ($k, $c): ${e.message}")
                runCatching { send(Message.MediaStop) }
                onEnded(k, MediaEnd.FAILED)
                return
            }
            session = s
            kind = k
            codec = c
            log("Media: $k with $c")
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
