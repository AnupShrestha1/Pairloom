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
    private val ID = "00112233445566778899aabbccddeeff"
    private val value32 = "ab".repeat(32)

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
        val message = Message.Hello(ControlProtocol.VERSION, ID, "Redmi Note 13 Pro")
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
        val message = Message.Hello(ControlProtocol.VERSION, ID, "Téléphone 電話")
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
        val bytes = ControlProtocol.encode(Message.Hello(ControlProtocol.VERSION, ID, "x"))
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
        // type=1, "PLM1", version=2, 16-byte install ID, nameLength=5 but only 2 name bytes follow
        val body = intArrayOf(1, 'P'.code, 'L'.code, 'M'.code, '1'.code, ControlProtocol.VERSION) +
            IntArray(16) { it } + intArrayOf(5, 'a'.code, 'b'.code)
        assertThrows(ControlProtocol.ProtocolException::class.java) {
            ControlProtocol.read(ByteArrayInputStream(frame(*body)))
        }
    }

    @Test
    fun versionOneHelloIsReportedAsAVersionMismatch() {
        // Phase 1 layout: type=1, "PLM1", version=1, nameLength=1, 'x'
        val body = intArrayOf(1, 'P'.code, 'L'.code, 'M'.code, '1'.code, 1, 1, 'x'.code)
        val e = assertThrows(ControlProtocol.VersionMismatchException::class.java) {
            ControlProtocol.read(ByteArrayInputStream(frame(*body)))
        }
        assertEquals(1, e.peerVersion)
    }

    @Test
    fun installIdRoundTrips() {
        val decoded = roundTrip(Message.Hello(ControlProtocol.VERSION, ID.uppercase(), "x")) as Message.Hello
        assertEquals(ID, decoded.installIdHex)
    }

    @Test
    fun sessionAndPairingMessagesRoundTrip() {
        for (m in listOf(
            Message.Status(1), Message.Status(3),
            Message.Commit(value32), Message.Nonce("cd".repeat(32)), Message.Reveal("ef".repeat(32)),
            Message.Confirm(true), Message.Confirm(false),
            Message.MediaOffer(1, 3, 1, value32, "01020304"), Message.MediaOffer(0, 1, 2, value32, "01020304"),
            Message.MediaAnswer(1, 65535, "cd".repeat(32), "ffffffff"), Message.MediaAnswer(0, 9, value32, "00000000"),
            Message.GroupInfo("DIRECT-ab-Pairloom", "Abcdefgh12345678"), Message.GroupInfo("DIRECT-ab-Pairloom", "Abcdefgh12345678", 2437), Message.MediaStop,
        )) {
            assertEquals(m, roundTrip(m))
        }
    }

    @Test
    fun groupTalkMessagesRoundTrip() {
        val key = "cd".repeat(32); val salt = "01020304"
        val roster = Message.GroupRoster(
            3,
            listOf(
                ControlProtocol.RosterEntry(0, "Redmi", true),
                ControlProtocol.RosterEntry(3, "आशा", false),
            ),
        )
        for (m in listOf(
            Message.GroupJoin(3, 40_000, key, salt),
            Message.GroupWelcome(1, 7, 41_000, key, salt),
            Message.GroupLeave(ControlProtocol.LeaveReason.REFUSED),
            roster,
            Message.GroupRoster(0, emptyList()),
            Message.Floor(3),
            Message.Floor(null),
        )) {
            assertEquals(m, roundTrip(m))
        }
        // A full roster of long names still fits in one frame.
        val full = Message.GroupRoster(0, List(ControlProtocol.MAX_ROSTER) { ControlProtocol.RosterEntry(it, "ø".repeat(100), true) })
        assertTrue(ControlProtocol.encode(full).size - 4 <= ControlProtocol.MAX_FRAME_BODY)
        assertEquals(ControlProtocol.MAX_ROSTER, (roundTrip(full) as Message.GroupRoster).members.size)
    }

    @Test
    fun malformedGroupTalkMessagesAreRejected() {
        for (bad in listOf(
            frame(16),                       // GROUP_LEAVE without a reason
            frame(18, 1, 2),                 // FLOOR with two bytes
            frame(17, 1, 0, 0, 1, 5, 'a'.code), // ROSTER entry shorter than its name
            frame(17, 1, 0, 255, 0, 0),         // ROSTER entry with the "nobody" id
            frame(17, 0, 0, 9),                 // ROSTER with trailing bytes
            frame(17, 0, 255),                  // ROSTER without the receiver's id
            frame(15, 0, 255, 1, 1, *IntArray(36)), // WELCOME with the "nobody" id
        )) {
            assertThrows(ControlProtocol.ProtocolException::class.java) { ControlProtocol.read(ByteArrayInputStream(bad)) }
        }
    }

    @Test
    fun malformedSessionMessagesAreRejected() {
        val bad = listOf(
            frame(5),                       // STATUS without payload
            frame(5, 1, 2),                 // STATUS with two bytes
            frame(6, *IntArray(31)),        // COMMIT one byte short
            frame(7, *IntArray(33)),        // NONCE one byte long
            frame(8),                       // REVEAL empty
            frame(9, 2),                    // CONFIRM must be 0 or 1
            frame(9),                       // CONFIRM empty
            frame(10, *IntArray(39)),       // MEDIA_OFFER one byte short
            frame(10, 1),                   // MEDIA_OFFER with only a kind
            frame(11, 0, 0, 0, *IntArray(36)), // MEDIA_ANSWER with port 0
            frame(11),                      // MEDIA_ANSWER empty
            frame(12, 1),                   // MEDIA_STOP with payload
            frame(13),                      // GROUP_INFO empty
            frame(13, 3, 'a'.code, 'b'.code, 'c'.code, 8, *IntArray(8) { 'x'.code }), // not a Pairloom group name
            frame(13, 18, *"DIRECT-ab-Pairloom".map { it.code }.toIntArray(), 9, *IntArray(8) { 'x'.code }), // length runs past the end
            frame(13, 18, *"DIRECT-ab-Pairloom".map { it.code }.toIntArray(), 8, *IntArray(8) { 'x'.code }), // no frequency
        )
        for (bytes in bad) {
            assertThrows(ControlProtocol.ProtocolException::class.java) { ControlProtocol.read(ByteArrayInputStream(bytes)) }
        }
    }

    @Test
    fun encodingRejectsWrongSizedValues() {
        assertThrows(IllegalArgumentException::class.java) { ControlProtocol.encode(Message.Commit("ab")) }
        assertThrows(IllegalArgumentException::class.java) { ControlProtocol.encode(Message.Hello(ControlProtocol.VERSION, "zz", "x")) }
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
        val decoded = roundTrip(Message.Hello(ControlProtocol.VERSION, ID, name)) as Message.Hello
        assertTrue(decoded.deviceName.toByteArray(Charsets.UTF_8).size <= ControlProtocol.MAX_NAME_BYTES)
        assertTrue(decoded.deviceName.isNotEmpty())
        assertTrue(decoded.deviceName.all { it == 'é' })
    }

    @Test
    fun encodedFramesStayWithinTheFrameLimit() {
        val longest = ControlProtocol.encode(Message.Hello(ControlProtocol.VERSION, ID, "a".repeat(500)))
        assertTrue(longest.size - 4 <= ControlProtocol.MAX_FRAME_BODY)
    }
}
