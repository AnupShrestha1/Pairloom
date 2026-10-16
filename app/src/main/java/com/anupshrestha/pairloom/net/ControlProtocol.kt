package com.anupshrestha.pairloom.net

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
    const val VERSION = 2
    const val MAX_FRAME_BODY = 256
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

    private fun decodePingPayload(payload: ByteArray): Pair<Int, Long> {
        if (payload.size != PING_PAYLOAD_BYTES) {
            throw ProtocolException("PING/PONG payload must be $PING_PAYLOAD_BYTES bytes")
        }
        val data = DataInputStream(payload.inputStream())
        return data.readInt() to data.readLong()
    }

    private fun decodeFixed(payload: ByteArray, size: Int, what: String): String {
        if (payload.size != size) throw ProtocolException("$what must carry $size bytes")
        return payload.joinToString("") { "%02x".format(it) }
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
