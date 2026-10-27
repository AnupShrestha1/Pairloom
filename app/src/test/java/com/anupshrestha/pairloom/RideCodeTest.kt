package com.anupshrestha.pairloom

import com.anupshrestha.pairloom.net.ControlChannel
import com.anupshrestha.pairloom.net.ControlProtocol
import com.anupshrestha.pairloom.net.ControlProtocol.Message
import com.anupshrestha.pairloom.security.RideCode
import com.anupshrestha.pairloom.session.FailureKind
import com.anupshrestha.pairloom.session.RideGuard
import com.anupshrestha.pairloom.session.RideJoin
import com.anupshrestha.pairloom.session.SecurityFailure
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Ride codes (Phase 7, G6): the words, the J-PAKE join over a real socket, and the guard. */
class RideCodeTest {
    private val sockets = mutableListOf<Socket>()

    @After
    fun closeSockets() = sockets.forEach { runCatching { it.close() } }

    @Test
    fun codesAreThreeListWordsAndParseLeniently() {
        val c = RideCode.generate()
        assertEquals(3, c.words.size)
        assertTrue(c.words.all { it in RideCode.WORDS })
        assertEquals(c, RideCode.parse(c.spoken))
        assertEquals(c, RideCode.parse(c.text.uppercase()))
        assertNull(RideCode.parse("maple river tractor")) // "tractor" is not a list word
        val ok = RideCode.parse("Maple  lotus-OTTER")
        assertNotNull(ok)
        assertEquals("maple-lotus-otter", ok!!.text)
        assertNull(RideCode.parse("maple lotus"))
        assertNull(RideCode.parse("maple lotus otter owl"))
        assertEquals("RideCode(<hidden>)", ok.toString())
        // Generation covers the list (a rough check that every word can come up).
        val seen = HashSet<String>()
        val r = SecureRandom()
        repeat(2_000) { seen += RideCode.generate(r).words }
        assertTrue("saw ${seen.size} of 256 words", seen.size > 240)
    }

    private fun channels(): Pair<ControlChannel, ControlChannel> {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val client = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        val accepted = server.accept()
        server.close()
        sockets += client
        sockets += accepted
        return ControlChannel(accepted) {} to ControlChannel(client) {}
    }

    private val hostFp = "aa".repeat(32)
    private val memberFp = "bb".repeat(32)

    /** Runs the host and the member at once; each result is the shared secret or the failure. */
    private fun join(
        hostCode: RideCode, memberCode: RideCode,
        hostSees: String = memberFp, memberSees: String = hostFp,
    ): Pair<Result<ByteArray>, Result<ByteArray>> {
        val (hc, mc) = channels()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val h = pool.submit(Callable { runCatching { RideJoin(hostCode, hostFp, hostSees).run(hc, isHost = true) }.also { if (it.isFailure) hc.close() } })
            val m = pool.submit(Callable { runCatching { RideJoin(memberCode, memberFp, memberSees).run(mc, isHost = false) }.also { if (it.isFailure) mc.close() } })
            return h.get(30, TimeUnit.SECONDS) to m.get(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun kind(r: Result<ByteArray>): FailureKind? = (r.exceptionOrNull() as? SecurityFailure)?.kind

    @Test
    fun theSameCodeGivesBothPhonesTheSameSecret() {
        val code = RideCode.generate()
        val (h, m) = join(code, RideCode.parse(code.spoken)!!)
        assertArrayEquals(h.getOrThrow(), m.getOrThrow())
        assertEquals(32, h.getOrThrow().size)
        // A second join with the same code gives a different secret (fresh randomness every time).
        val (h2, _) = join(code, code)
        assertFalse(h.getOrThrow().contentEquals(h2.getOrThrow()))
    }

    @Test
    fun aWrongCodeIsRejectedOnBothSides() {
        val code = RideCode.parse("maple lotus otter")!!
        val (h, m) = join(code, RideCode.parse("maple lotus owl")!!)
        assertEquals(FailureKind.RIDE_CODE_REJECTED, kind(h))
        assertTrue(m.isFailure) // rejected, or cut off when the host gives up
    }

    @Test
    fun someoneInTheMiddleWithTheirOwnTlsKeysFailsEvenWithTheCode() {
        // Each side sees a different peer key than the other side has: the link is not one TLS session.
        val code = RideCode.generate()
        val (h, m) = join(code, code, hostSees = "cc".repeat(32))
        assertEquals(FailureKind.RIDE_CODE_REJECTED, kind(h))
        assertTrue(m.isFailure)
    }

    @Test
    fun theGuardSlowsDownRepeatedWrongCodes() {
        var now = 0L
        val g = RideGuard(maxFailures = 3, windowMs = 60_000) { now }
        repeat(3) { assertTrue(g.mayTry()); g.failed() }
        assertFalse(g.mayTry())
        now = 59_999
        assertFalse(g.mayTry())
        now = 60_000
        assertTrue(g.mayTry())
    }

    @Test
    fun pakeMessagesRoundTripAndBadOnesAreRejected() {
        val big = BigInteger.ONE.shiftLeft(2047).add(BigInteger.valueOf(12345)) // a full 2048-bit value
        val m = Message.RidePake(1, listOf(big, BigInteger.ONE, BigInteger.ZERO, big.negate(), big, BigInteger.valueOf(-5)))
        val bytes = ControlProtocol.encode(m)
        assertTrue(bytes.size - 4 <= ControlProtocol.MAX_FRAME_BODY)
        assertEquals(m, ControlProtocol.read(ByteArrayInputStream(bytes)))
        fun frame(vararg body: Int) = ByteArray(4 + body.size).also { b ->
            b[3] = body.size.toByte(); body.forEachIndexed { i, v -> b[4 + i] = v.toByte() }
        }
        for (bad in listOf(
            frame(19, 4, 1, 0, 1, 7),     // round 4
            frame(19, 1, 7),              // seven values
            frame(19, 1, 1, 0, 0),        // empty value
            frame(19, 1, 1, 0, 2, 7),     // value shorter than its length
            frame(19, 1, 1, 0, 1, 7, 9),  // trailing byte
        )) {
            assertThrows(ControlProtocol.ProtocolException::class.java) { ControlProtocol.read(ByteArrayInputStream(bad)) }
        }
        assertThrows(IllegalArgumentException::class.java) { ControlProtocol.encode(Message.RidePake(1, listOf(BigInteger.ONE.shiftLeft(3000)))) }
    }
}
