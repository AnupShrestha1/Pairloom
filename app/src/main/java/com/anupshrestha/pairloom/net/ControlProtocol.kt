package com.anupshrestha.pairloom.net

import com.anupshrestha.pairloom.p2p.GroupCredentials

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Wire format of the control channel, which runs inside mutual TLS from version 2 on.
 *
 * Every frame is a 4-byte big-endian length followed by that many bytes: one type byte and the
 * payload. The length covers the type byte and the payload, not the length field itself.
 * Fixed-size binary values (install IDs, pairing values) are carried as lowercase hex in the
 * message classes and as raw bytes on the wire.
 */
object ControlProtocol {
    const val MAGIC = "PLM1"
    const val VERSION = 7
    const val MAX_FRAME_BODY = 4096
    const val MAX_NAME_BYTES = 64
    const val INSTALL_ID_BYTES = 16
    const val PAIRING_VALUE_BYTES = 32

    private const val TYPE_HELLO = 1
    private const val TYPE_PING = 2
    private const val TYPE_PONG = 3
    private const val TYPE_BYE = 4
    private const val TYPE_STATUS = 5
    private const val TYPE_COMMIT = 6
    private const val TYPE_NONCE = 7
    private const val TYPE_REVEAL = 8
    private const val TYPE_CONFIRM = 9
    private const val TYPE_MEDIA_OFFER = 10
    private const val TYPE_MEDIA_ANSWER = 11
    private const val TYPE_MEDIA_STOP = 12
    private const val TYPE_GROUP_INFO = 13
    private const val TYPE_GROUP_JOIN = 14
    private const val TYPE_GROUP_WELCOME = 15
    private const val TYPE_GROUP_LEAVE = 16
    private const val TYPE_GROUP_ROSTER = 17
    private const val TYPE_FLOOR = 18
    private const val TYPE_RIDE_PAKE = 19

    /** A J-PAKE value is at most a 2048-bit number, signed (proofs may be negative); a round sends at most six. */
    const val MAX_PAKE_VALUE_BYTES = 257
    const val MAX_PAKE_VALUES = 6

    /** The most members a roster lists (with 64-byte names it still fits in one frame). */
    const val MAX_ROSTER = 12

    /** [Message.Floor] holder byte for "nobody". */
    private const val NOBODY = 255

    const val MEDIA_KEY_BYTES = 32
    const val MEDIA_SALT_BYTES = 4

    private const val PING_PAYLOAD_BYTES = 4 + 8

    sealed interface Message {
        data class Hello(val version: Int, val installIdHex: String, val deviceName: String) : Message
        data class Ping(val seq: Int, val sentNanos: Long) : Message
        data class Pong(val seq: Int, val sentNanos: Long) : Message
        data object Bye : Message

        /** How the sender classified the receiver after TLS (see [com.anupshrestha.pairloom.security.AuthStatus]). */
        data class Status(val code: Int) : Message

        /** Pairing: the server's commitment to its nonce. */
        data class Commit(val valueHex: String) : Message

        /** Pairing: the client's nonce. */
        data class Nonce(val valueHex: String) : Message

        /** Pairing: the server's nonce, revealed after the client's nonce arrived. */
        data class Reveal(val valueHex: String) : Message

        /** Pairing: whether the sender's user confirmed that the codes match. */
        data class Confirm(val accepted: Boolean) : Message

        /**
         * Starts a media session: what it carries, the audio codecs the sender can use (a bit mask,
         * bit n = codec code n), the sender's UDP port and the key and salt it will use for the
         * packets it sends. Only ever sent inside the TLS channel.
         */
        data class MediaOffer(val kind: Int, val codecs: Int, val port: Int, val keyHex: String, val saltHex: String) : Message

        /** Accepts a [MediaOffer] with the codec both directions use and the answerer's port, key and salt. */
        data class MediaAnswer(val codec: Int, val port: Int, val keyHex: String, val saltHex: String) : Message

        /** Ends the media session. */
        data object MediaStop : Message

        /**
         * The sender's own Wi-Fi Direct group name and passphrase, so that the receiver (an
         * authenticated, paired phone) can later join the sender's group directly, without the
         * system's connection prompt. Only ever sent inside the TLS channel.
         */
        data class GroupInfo(
            val networkName: String,
            val passphrase: String,
            /** The sender's normal Wi-Fi channel in MHz, 0 if not connected: a host can share it. */
            val staFrequencyMhz: Int = 0,
        ) : Message {
            override fun toString(): String =
                "GroupInfo(networkName=$networkName, passphrase=<hidden>, staFrequencyMhz=$staFrequencyMhz)"
        }

        /**
         * Group push-to-talk (Phase 7), member to host: join the group talk. Like a [MediaOffer]:
         * the codecs the member can use, its UDP port and the key and salt of the packets it sends.
         */
        data class GroupJoin(val codecs: Int, val port: Int, val keyHex: String, val saltHex: String) : Message

        /**
         * Host to member: admitted, with the group's codec, the member's id in the roster, and the
         * hub's UDP port, key and salt for the packets the member receives.
         */
        data class GroupWelcome(val codec: Int, val memberId: Int, val port: Int, val keyHex: String, val saltHex: String) : Message

        /** Either way: leaving the group talk (member), or not admitted / group talk over (host). */
        data class GroupLeave(val reason: Int) : Message

        /**
         * Host to each phone on the link: every phone on it (the host included), whether each is in
         * the group talk, and which entry is the receiving phone.
         */
        data class GroupRoster(val yourId: Int, val members: List<RosterEntry>) : Message

        /** Host to members in the group talk: who has the floor (null: nobody). */
        data class Floor(val holderId: Int?) : Message

        /** Ride codes: one J-PAKE round's numbers (session set-up only, inside TLS). */
        data class RidePake(val round: Int, val values: List<java.math.BigInteger>) : Message
    }

    /**
     * One phone in a [Message.GroupRoster]: whether it is in the group talk, whether it is the
     * hosting phone, and how well the hub hears it ([LinkQuality]).
     */
    data class RosterEntry(
        val id: Int,
        val name: String,
        val inTalk: Boolean,
        val isHost: Boolean = false,
        val quality: Int = LinkQuality.UNKNOWN,
    )

    /** How well the hub hears a member, from the packets it received over the last seconds. */
    object LinkQuality {
        const val UNKNOWN = 0
        const val GOOD = 1
        const val FAIR = 2
        const val WEAK = 3

        /** Nothing heard: gone out of range (or left without a goodbye). */
        const val LOST = 4
    }

    /** [Message.GroupLeave] reasons. */
    object LeaveReason {
        const val LEFT = 0
        const val REFUSED = 1
        const val ENDED = 2
    }

    /** The peer sent something that is not valid protocol data. */
    open class ProtocolException(message: String) : IOException(message)

    /** The peer speaks a different protocol version. */
    class VersionMismatchException(val peerVersion: Int) :
        ProtocolException("Protocol version $peerVersion is not supported (expected $VERSION)")

    fun encode(message: Message): ByteArray {
        val body = ByteArrayOutputStream()
        val out = DataOutputStream(body)
        when (message) {
            is Message.Hello -> {
                out.writeByte(TYPE_HELLO)
                out.write(MAGIC.toByteArray(Charsets.US_ASCII))
                out.writeByte(message.version)
                out.write(fixed(message.installIdHex, INSTALL_ID_BYTES))
                val name = truncateUtf8(message.deviceName, MAX_NAME_BYTES)
                out.writeByte(name.size)
                out.write(name)
            }

            is Message.Ping -> {
                out.writeByte(TYPE_PING)
                out.writeInt(message.seq)
                out.writeLong(message.sentNanos)
            }

            is Message.Pong -> {
                out.writeByte(TYPE_PONG)
                out.writeInt(message.seq)
                out.writeLong(message.sentNanos)
            }

            Message.Bye -> out.writeByte(TYPE_BYE)
            is Message.Status -> {
                require(message.code in 0..255) { "status code out of range" }
                out.writeByte(TYPE_STATUS)
                out.writeByte(message.code)
            }

            is Message.Commit -> {
                out.writeByte(TYPE_COMMIT)
                out.write(fixed(message.valueHex, PAIRING_VALUE_BYTES))
            }

            is Message.Nonce -> {
                out.writeByte(TYPE_NONCE)
                out.write(fixed(message.valueHex, PAIRING_VALUE_BYTES))
            }

            is Message.Reveal -> {
                out.writeByte(TYPE_REVEAL)
                out.write(fixed(message.valueHex, PAIRING_VALUE_BYTES))
            }

            is Message.Confirm -> {
                out.writeByte(TYPE_CONFIRM)
                out.writeByte(if (message.accepted) 1 else 0)
            }

            is Message.MediaOffer -> {
                require(message.kind in 0..255) { "media kind out of range" }
                require(message.codecs in 0..255) { "codec mask out of range" }
                out.writeByte(TYPE_MEDIA_OFFER)
                out.writeByte(message.kind)
                out.writeByte(message.codecs)
                writeMedia(out, message.port, message.keyHex, message.saltHex)
            }

            is Message.MediaAnswer -> {
                require(message.codec in 0..255) { "codec out of range" }
                out.writeByte(TYPE_MEDIA_ANSWER)
                out.writeByte(message.codec)
                writeMedia(out, message.port, message.keyHex, message.saltHex)
            }

            Message.MediaStop -> out.writeByte(TYPE_MEDIA_STOP)

            is Message.GroupJoin -> {
                require(message.codecs in 0..255) { "codec mask out of range" }
                out.writeByte(TYPE_GROUP_JOIN)
                out.writeByte(message.codecs)
                writeMedia(out, message.port, message.keyHex, message.saltHex)
            }

            is Message.GroupWelcome -> {
                require(message.codec in 0..255) { "codec out of range" }
                require(message.memberId in 0 until NOBODY) { "member id out of range" }
                out.writeByte(TYPE_GROUP_WELCOME)
                out.writeByte(message.codec)
                out.writeByte(message.memberId)
                writeMedia(out, message.port, message.keyHex, message.saltHex)
            }

            is Message.GroupLeave -> {
                require(message.reason in 0..255) { "reason out of range" }
                out.writeByte(TYPE_GROUP_LEAVE)
                out.writeByte(message.reason)
            }

            is Message.GroupRoster -> {
                require(message.members.size <= MAX_ROSTER) { "roster too long" }
                require(message.yourId in 0 until NOBODY) { "member id out of range" }
                out.writeByte(TYPE_GROUP_ROSTER)
                out.writeByte(message.members.size)
                out.writeByte(message.yourId)
                for (m in message.members) {
                    require(m.id in 0 until NOBODY) { "member id out of range" }
                    val name = truncateUtf8(m.name, MAX_NAME_BYTES)
                    require(m.quality in LinkQuality.UNKNOWN..LinkQuality.LOST) { "link quality out of range" }
                    out.writeByte(m.id)
                    // Flags: bit 0 in the talk, bit 1 host, bits 2-4 link quality.
                    out.writeByte((if (m.inTalk) 1 else 0) or (if (m.isHost) 2 else 0) or (m.quality shl 2))
                    out.writeByte(name.size)
                    out.write(name)
                }
            }

            is Message.Floor -> {
                require(message.holderId == null || message.holderId in 0 until NOBODY) { "member id out of range" }
                out.writeByte(TYPE_FLOOR)
                out.writeByte(message.holderId ?: NOBODY)
            }

            is Message.RidePake -> {
                require(message.round in 1..3) { "J-PAKE round out of range" }
                require(message.values.size in 1..MAX_PAKE_VALUES) { "wrong number of J-PAKE values" }
                out.writeByte(TYPE_RIDE_PAKE)
                out.writeByte(message.round)
                out.writeByte(message.values.size)
                for (v in message.values) {
                    val bytes = v.toByteArray() // two's complement, so the sign survives
                    require(bytes.size <= MAX_PAKE_VALUE_BYTES) { "J-PAKE value too large" }
                    out.writeShort(bytes.size)
                    out.write(bytes)
                }
            }

            is Message.GroupInfo -> {
                require(GroupCredentials.isValid(message.networkName, message.passphrase)) { "invalid group credentials" }
                out.writeByte(TYPE_GROUP_INFO)
                require(message.staFrequencyMhz in 0..65535) { "frequency out of range" }
                for (v in listOf(message.networkName, message.passphrase)) {
                    val bytes = v.toByteArray(Charsets.US_ASCII)
                    out.writeByte(bytes.size)
                    out.write(bytes)
                }
                out.writeShort(message.staFrequencyMhz)
            }
        }
        val bytes = body.toByteArray()
        val frame = ByteArrayOutputStream(4 + bytes.size)
        DataOutputStream(frame).apply {
            writeInt(bytes.size)
            write(bytes)
        }
        return frame.toByteArray()
    }

    /**
     * Blocks until one whole frame has been read. Partial reads are handled by [DataInputStream]
     * looping until every byte has arrived.
     *
     * @throws java.io.EOFException if the stream ends before a full frame arrives
     * @throws ProtocolException if the frame is malformed
     */
    fun read(input: InputStream): Message {
        val data = DataInputStream(input)
        val length = data.readInt()
        if (length < 1 || length > MAX_FRAME_BODY) {
            throw ProtocolException("Invalid frame length $length")
        }
        val body = ByteArray(length)
        data.readFully(body)
        return decode(body)
    }

    fun decode(body: ByteArray): Message {
        if (body.isEmpty()) throw ProtocolException("Empty frame")
        val type = body[0].toInt() and 0xFF
        val payload = body.copyOfRange(1, body.size)
        return when (type) {
            TYPE_HELLO -> decodeHello(payload)
            TYPE_PING -> {
                val (seq, nanos) = decodePingPayload(payload)
                Message.Ping(seq, nanos)
            }

            TYPE_PONG -> {
                val (seq, nanos) = decodePingPayload(payload)
                Message.Pong(seq, nanos)
            }

            TYPE_BYE -> {
                if (payload.isNotEmpty()) throw ProtocolException("BYE must have no payload")
                Message.Bye
            }

            TYPE_STATUS -> {
                if (payload.size != 1) throw ProtocolException("STATUS must have a 1-byte payload")
                Message.Status(payload[0].toInt() and 0xFF)
            }

            TYPE_COMMIT -> Message.Commit(decodeFixed(payload, PAIRING_VALUE_BYTES, "COMMIT"))
            TYPE_NONCE -> Message.Nonce(decodeFixed(payload, PAIRING_VALUE_BYTES, "NONCE"))
            TYPE_REVEAL -> Message.Reveal(decodeFixed(payload, PAIRING_VALUE_BYTES, "REVEAL"))
            TYPE_MEDIA_OFFER -> {
                if (payload.size < 2) throw ProtocolException("MEDIA_OFFER is too short")
                val kind = payload[0].toInt() and 0xFF
                val codecs = payload[1].toInt() and 0xFF
                readMedia(payload.copyOfRange(2, payload.size), "MEDIA_OFFER").let { (p, k, s) -> Message.MediaOffer(kind, codecs, p, k, s) }
            }
            TYPE_MEDIA_ANSWER -> {
                if (payload.isEmpty()) throw ProtocolException("MEDIA_ANSWER is empty")
                val codec = payload[0].toInt() and 0xFF
                readMedia(payload.copyOfRange(1, payload.size), "MEDIA_ANSWER").let { (p, k, s) -> Message.MediaAnswer(codec, p, k, s) }
            }
            TYPE_MEDIA_STOP -> {
                if (payload.isNotEmpty()) throw ProtocolException("MEDIA_STOP must have no payload")
                Message.MediaStop
            }

            TYPE_GROUP_INFO -> {
                val name = readShortAscii(payload, 0, "GROUP_INFO")
                val pass = readShortAscii(payload, 1 + name.length, "GROUP_INFO")
                val end = 2 + name.length + pass.length
                if (end + 2 != payload.size) throw ProtocolException("GROUP_INFO has the wrong size")
                if (!GroupCredentials.isValid(name, pass)) throw ProtocolException("GROUP_INFO is not a valid group")
                val frequency = ((payload[end].toInt() and 0xFF) shl 8) or (payload[end + 1].toInt() and 0xFF)
                Message.GroupInfo(name, pass, frequency)
            }

            TYPE_GROUP_JOIN -> {
                if (payload.isEmpty()) throw ProtocolException("GROUP_JOIN is empty")
                val codecs = payload[0].toInt() and 0xFF
                readMedia(payload.copyOfRange(1, payload.size), "GROUP_JOIN").let { (p, k, s) -> Message.GroupJoin(codecs, p, k, s) }
            }

            TYPE_GROUP_WELCOME -> {
                if (payload.size < 2) throw ProtocolException("GROUP_WELCOME is too short")
                val codec = payload[0].toInt() and 0xFF
                val id = payload[1].toInt() and 0xFF
                if (id == NOBODY) throw ProtocolException("GROUP_WELCOME has no member id")
                readMedia(payload.copyOfRange(2, payload.size), "GROUP_WELCOME").let { (p, k, s) -> Message.GroupWelcome(codec, id, p, k, s) }
            }

            TYPE_GROUP_LEAVE -> {
                if (payload.size != 1) throw ProtocolException("GROUP_LEAVE must have a 1-byte payload")
                Message.GroupLeave(payload[0].toInt() and 0xFF)
            }

            TYPE_GROUP_ROSTER -> decodeRoster(payload)

            TYPE_RIDE_PAKE -> decodeRidePake(payload)

            TYPE_FLOOR -> {
                if (payload.size != 1) throw ProtocolException("FLOOR must have a 1-byte payload")
                Message.Floor((payload[0].toInt() and 0xFF).takeIf { it != NOBODY })
            }

            TYPE_CONFIRM -> {
                if (payload.size != 1 || (payload[0].toInt() and 0xFF) > 1) throw ProtocolException("CONFIRM must be 0 or 1")
                Message.Confirm(payload[0].toInt() == 1)
            }

            else -> throw ProtocolException("Unknown message type $type")
        }
    }

    private fun decodeHello(payload: ByteArray): Message.Hello {
        if (payload.size < MAGIC.length + 1) throw ProtocolException("HELLO too short")
        val magic = String(payload, 0, MAGIC.length, Charsets.US_ASCII)
        if (magic != MAGIC) throw ProtocolException("Bad magic")
        val version = payload[MAGIC.length].toInt() and 0xFF
        // An older peer's HELLO has a different layout; report the version before parsing further.
        if (version != VERSION) throw VersionMismatchException(version)
        val idStart = MAGIC.length + 1
        val nameLengthAt = idStart + INSTALL_ID_BYTES
        if (payload.size < nameLengthAt + 1) throw ProtocolException("HELLO too short")
        val nameLength = payload[nameLengthAt].toInt() and 0xFF
        if (nameLength > MAX_NAME_BYTES) throw ProtocolException("Device name too long")
        if (payload.size != nameLengthAt + 1 + nameLength) {
            throw ProtocolException("HELLO length does not match its name length")
        }
        val installId = payload.copyOfRange(idStart, nameLengthAt).joinToString("") { "%02x".format(it) }
        val name = String(payload, nameLengthAt + 1, nameLength, Charsets.UTF_8)
        return Message.Hello(version, installId, name)
    }

    private fun decodeRidePake(payload: ByteArray): Message.RidePake {
        if (payload.size < 2) throw ProtocolException("RIDE_PAKE is too short")
        val round = payload[0].toInt() and 0xFF
        val count = payload[1].toInt() and 0xFF
        if (round !in 1..3 || count !in 1..MAX_PAKE_VALUES) throw ProtocolException("RIDE_PAKE header is malformed")
        var at = 2
        val values = List(count) {
            if (at + 2 > payload.size) throw ProtocolException("RIDE_PAKE is too short")
            val n = ((payload[at].toInt() and 0xFF) shl 8) or (payload[at + 1].toInt() and 0xFF)
            if (n == 0 || n > MAX_PAKE_VALUE_BYTES || at + 2 + n > payload.size) throw ProtocolException("RIDE_PAKE value is malformed")
            val v = java.math.BigInteger(payload.copyOfRange(at + 2, at + 2 + n))
            at += 2 + n
            v
        }
        if (at != payload.size) throw ProtocolException("RIDE_PAKE has the wrong size")
        return Message.RidePake(round, values)
    }

    private fun decodeRoster(payload: ByteArray): Message.GroupRoster {
        if (payload.size < 2) throw ProtocolException("GROUP_ROSTER is too short")
        val count = payload[0].toInt() and 0xFF
        val yourId = payload[1].toInt() and 0xFF
        if (count > MAX_ROSTER) throw ProtocolException("GROUP_ROSTER lists too many members")
        if (yourId == NOBODY) throw ProtocolException("GROUP_ROSTER has no id for the receiver")
        var at = 2
        val members = List(count) {
            if (at + 3 > payload.size) throw ProtocolException("GROUP_ROSTER is too short")
            val id = payload[at].toInt() and 0xFF
            val flags = payload[at + 1].toInt() and 0xFF
            val n = payload[at + 2].toInt() and 0xFF
            val quality = flags shr 2
            if (id == NOBODY || quality > LinkQuality.LOST || n > MAX_NAME_BYTES) throw ProtocolException("GROUP_ROSTER entry is malformed")
            if (at + 3 + n > payload.size) throw ProtocolException("GROUP_ROSTER is too short")
            val name = String(payload, at + 3, n, Charsets.UTF_8)
            at += 3 + n
            RosterEntry(id, name, flags and 1 != 0, flags and 2 != 0, quality)
        }
        if (at != payload.size) throw ProtocolException("GROUP_ROSTER has the wrong size")
        return Message.GroupRoster(yourId, members)
    }

    private fun decodePingPayload(payload: ByteArray): Pair<Int, Long> {
        if (payload.size != PING_PAYLOAD_BYTES) {
            throw ProtocolException("PING/PONG payload must be $PING_PAYLOAD_BYTES bytes")
        }
        val data = DataInputStream(payload.inputStream())
        return data.readInt() to data.readLong()
    }

    /** A one-byte length then that many ASCII bytes, starting at [offset]. */
    private fun readShortAscii(payload: ByteArray, offset: Int, what: String): String {
        if (offset >= payload.size) throw ProtocolException("$what is too short")
        val n = payload[offset].toInt() and 0xFF
        if (offset + 1 + n > payload.size) throw ProtocolException("$what is too short")
        val bytes = payload.copyOfRange(offset + 1, offset + 1 + n)
        if (bytes.any { it < 0x21 || it > 0x7E }) throw ProtocolException("$what is not printable ASCII")
        return String(bytes, Charsets.US_ASCII)
    }

    private fun decodeFixed(payload: ByteArray, size: Int, what: String): String {
        if (payload.size != size) throw ProtocolException("$what must carry $size bytes")
        return payload.joinToString("") { "%02x".format(it) }
    }

    private fun writeMedia(out: DataOutputStream, port: Int, keyHex: String, saltHex: String) {
        require(port in 1..65535) { "port out of range" }
        out.writeShort(port)
        out.write(fixed(keyHex, MEDIA_KEY_BYTES))
        out.write(fixed(saltHex, MEDIA_SALT_BYTES))
    }

    private fun readMedia(payload: ByteArray, what: String): Triple<Int, String, String> {
        if (payload.size != 2 + MEDIA_KEY_BYTES + MEDIA_SALT_BYTES) throw ProtocolException("$what has the wrong size")
        val port = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        if (port == 0) throw ProtocolException("$what has port 0")
        val key = payload.copyOfRange(2, 2 + MEDIA_KEY_BYTES).joinToString("") { "%02x".format(it) }
        val salt = payload.copyOfRange(2 + MEDIA_KEY_BYTES, payload.size).joinToString("") { "%02x".format(it) }
        return Triple(port, key, salt)
    }

    private fun fixed(hex: String, size: Int): ByteArray {
        val clean = hex.lowercase()
        require(clean.length == size * 2 && clean.all { it in '0'..'9' || it in 'a'..'f' }) { "expected $size bytes of hex" }
        return ByteArray(size) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** Truncates to at most [maxBytes] of UTF-8 without cutting a character in half. */
    private fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var used = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val bytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
            if (used + bytes.size > maxBytes) break
            out.write(bytes)
            used += bytes.size
            index += Character.charCount(codePoint)
        }
        return out.toByteArray()
    }
}
