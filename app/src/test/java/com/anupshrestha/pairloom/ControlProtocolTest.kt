package com.anupshrestha.pairloom

import com.anupshrestha.pairloom.net.ControlProtocol
import com.anupshrestha.pairloom.net.ControlProtocol.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream

class ControlProtocolTest {
    private fun roundTrip(message: Message): Message =
        ControlProtocol.read(ByteArrayInputStream(ControlProtocol.encode(message)))

    /** Returns at most one byte per read call, like a slow network. */
    private class TrickleStream(private val data: ByteArray) : InputStream() {
        private var position = 0
        override fun read(): Int = if (position < data.size) data[position++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val value = read()
            if (value < 0) return -1
            b[off] = value.toByte()
            return 1
        }
    }

    private fun frame(vararg body: Int): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(body.size)
            body.forEach { writeByte(it) }
        }
        return out.toByteArray()
    }

    @Test
    fun helloRoundTrips() {
        val message = Message.Hello(ControlProtocol.VERSION, "Redmi Note 13 Pro")
        assertEquals(message, roundTrip(message))
    }

    @Test
    fun pingPongAndByeRoundTrip() {
        assertEquals(Message.Ping(7, 123_456_789_012L), roundTrip(Message.Ping(7, 123_456_789_012L)))
        assertEquals(Message.Pong(-1, Long.MIN_VALUE), roundTrip(Message.Pong(-1, Long.MIN_VALUE)))
        assertEquals(Message.Bye, roundTrip(Message.Bye))
    }

    @Test
    fun nonAsciiDeviceNameRoundTrips() {
        val message = Message.Hello(1, "Téléphone 電話")
        assertEquals(message, roundTrip(message))
    }

    @Test
    fun partialReadsAreReassembled() {
        val bytes = ControlProtocol.encode(Message.Ping(3, 99L)) + ControlProtocol.encode(Message.Bye)
        val stream = TrickleStream(bytes)
        assertEquals(Message.Ping(3, 99L), ControlProtocol.read(stream))
        assertEquals(Message.Bye, ControlProtocol.read(stream))
    }

    @Test
    fun backToBackFramesAreReadInOrder() {
        val bytes = ControlProtocol.encode(Message.Ping(1, 1L)) +
            ControlProtocol.encode(Message.Pong(1, 1L)) +
            ControlProtocol.encode(Message.Bye)
        val stream = ByteArrayInputStream(bytes)
        assertEquals(Message.Ping(1, 1L), ControlProtocol.read(stream))
        assertEquals(Message.Pong(1, 1L), ControlProtocol.read(stream))
        assertEquals(Message.Bye, ControlProtocol.read(stream))
    }

    @Test
    fun oversizedLengthIsRejectedWithoutReadingTheBody() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).writeInt(ControlProtocol.MAX_FRAME_BODY + 1)
        assertThrows(ControlProtocol.ProtocolException::class.java) {
            ControlProtocol.read(ByteArrayInputStream(out.toByteArray()))
        }
        val huge = ByteArrayOutputStream()
        DataOutputStream(huge).writeInt(Int.MAX_VALUE)
        assertThrows(ControlProtocol.ProtocolException::class.java) {
            ControlProtocol.read(ByteArrayInputStream(huge.toByteArray()))
        }
    }

    @Test
    fun zeroAndNegativeLengthsAreRejected() {
        for (length in intArrayOf(0, -1, Int.MIN_VALUE)) {
            val out = ByteArrayOutputStream()
            DataOutputStream(out).writeInt(length)
            assertThrows(ControlProtocol.ProtocolException::class.java) {
                ControlProtocol.read(ByteArrayInputStream(out.toByteArray()))
            }
        }
    }

    @Test
    fun unknownMessageTypeIsRejected() {
        assertThrows(ControlProtocol.ProtocolException::class.java) {
            ControlProtocol.read(ByteArrayInputStream(frame(99)))
        }
        assertThrows(ControlProtocol.ProtocolException::class.java) {
            ControlProtocol.read(ByteArrayInputStream(frame(0)))
        }
    }

    @Test
    fun badMagicIsRejected() {
        val bytes = ControlProtocol.encode(Message.Hello(1, "x"))
        bytes[5] = 'X'.code.toByte() // first magic byte: 4 length bytes + 1 type byte
        assertThrows(ControlProtocol.ProtocolException::class.java) {
            ControlProtocol.read(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun wrongPingPayloadSizeIsRejected() {
        assertThrows(ControlProtocol.ProtocolException::class.java) {
            ControlProtocol.read(ByteArrayInputStream(frame(2, 0, 0, 0)))
        }
    }

    @Test
    fun byeWithPayloadIsRejected() {
        assertThrows(ControlProtocol.ProtocolException::class.java) {
            ControlProtocol.read(ByteArrayInputStream(frame(4, 1)))
        }
    }

    @Test
    fun helloWithInconsistentNameLengthIsRejected() {
        // type=1, "PLM1", version=1, nameLength=5 but only 2 name bytes follow
        val body = intArrayOf(1, 'P'.code, 'L'.code, 'M'.code, '1'.code, 1, 5, 'a'.code, 'b'.code)
        assertThrows(ControlProtocol.ProtocolException::class.java) {
            ControlProtocol.read(ByteArrayInputStream(frame(*body)))
        }
    }

    @Test
    fun truncatedFrameEndsWithEof() {
        val bytes = ControlProtocol.encode(Message.Ping(1, 1L))
        assertThrows(EOFException::class.java) {
            ControlProtocol.read(ByteArrayInputStream(bytes.copyOf(bytes.size - 3)))
        }
        assertThrows(EOFException::class.java) {
            ControlProtocol.read(ByteArrayInputStream(ByteArray(0)))
        }
    }

    @Test
    fun longDeviceNameIsTruncatedOnEncodeWithoutBreakingCharacters() {
        val name = "é".repeat(100) // 2 bytes each, 200 bytes in total
        val decoded = roundTrip(Message.Hello(1, name)) as Message.Hello
        assertTrue(decoded.deviceName.toByteArray(Charsets.UTF_8).size <= ControlProtocol.MAX_NAME_BYTES)
        assertTrue(decoded.deviceName.isNotEmpty())
        assertTrue(decoded.deviceName.all { it == 'é' })
    }

    @Test
    fun encodedFramesStayWithinTheFrameLimit() {
        val longest = ControlProtocol.encode(Message.Hello(1, "a".repeat(500)))
        assertTrue(longest.size - 4 <= ControlProtocol.MAX_FRAME_BODY)
    }
}
