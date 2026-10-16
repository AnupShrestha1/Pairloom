package com.anupshrestha.pairloom.net

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Wire format of the TCP control channel.
 *
 * Every frame is a 4-byte big-endian length followed by that many bytes: one type byte and the
 * payload. The length covers the type byte and the payload, not the length field itself.
 */
object ControlProtocol {
    const val MAGIC = "PLM1"
    const val VERSION = 1
    const val MAX_FRAME_BODY = 256
    const val MAX_NAME_BYTES = 64

    private const val TYPE_HELLO = 1
    private const val TYPE_PING = 2
    private const val TYPE_PONG = 3
    private const val TYPE_BYE = 4

    private const val PING_PAYLOAD_BYTES = 4 + 8

    sealed interface Message {
        data class Hello(val version: Int, val deviceName: String) : Message
        data class Ping(val seq: Int, val sentNanos: Long) : Message
        data class Pong(val seq: Int, val sentNanos: Long) : Message
        data object Bye : Message
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

            else -> throw ProtocolException("Unknown message type $type")
        }
    }

    private fun decodeHello(payload: ByteArray): Message.Hello {
        if (payload.size < MAGIC.length + 2) throw ProtocolException("HELLO too short")
        val magic = String(payload, 0, MAGIC.length, Charsets.US_ASCII)
        if (magic != MAGIC) throw ProtocolException("Bad magic")
        val version = payload[MAGIC.length].toInt() and 0xFF
        val nameLength = payload[MAGIC.length + 1].toInt() and 0xFF
        if (nameLength > MAX_NAME_BYTES) throw ProtocolException("Device name too long")
        if (payload.size != MAGIC.length + 2 + nameLength) {
            throw ProtocolException("HELLO length does not match its name length")
        }
        // The version is checked by the caller so that a mismatch can be reported distinctly, but
        // only after the frame has been proven well formed.
        val name = String(payload, MAGIC.length + 2, nameLength, Charsets.UTF_8)
        return Message.Hello(version, name)
    }

    private fun decodePingPayload(payload: ByteArray): Pair<Int, Long> {
        if (payload.size != PING_PAYLOAD_BYTES) {
            throw ProtocolException("PING/PONG payload must be $PING_PAYLOAD_BYTES bytes")
        }
        val data = DataInputStream(payload.inputStream())
        return data.readInt() to data.readLong()
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
