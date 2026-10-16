package com.anupshrestha.pairloom

import com.anupshrestha.pairloom.net.ControlChannel
import com.anupshrestha.pairloom.net.ControlProtocol
import com.anupshrestha.pairloom.net.ControlProtocol.Message
import com.anupshrestha.pairloom.security.AuthStatus
import com.anupshrestha.pairloom.security.PairedDevice
import com.anupshrestha.pairloom.security.PairedDeviceStore
import com.anupshrestha.pairloom.security.toHex
import com.anupshrestha.pairloom.session.FailureKind
import com.anupshrestha.pairloom.session.PairingPrompt
import com.anupshrestha.pairloom.session.SecurityFailure
import com.anupshrestha.pairloom.session.SessionNegotiator
import com.anupshrestha.pairloom.session.SessionPeer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Random

/**
 * The session start over real loopback sockets, with two simulated phones in one JVM (no TLS:
 * the fingerprints stand in for the keys each phone would have seen in its TLS session).
 */
class SessionNegotiatorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val rng = Random(7)
    private val sockets = mutableListOf<Socket>()

    private class Phone(val fingerprint: ByteArray, val installId: String, val name: String, val store: PairedDeviceStore)

    private fun phone(name: String) = Phone(
        ByteArray(32).also(rng::nextBytes),
        ByteArray(16).also(rng::nextBytes).toHex(),
        name,
        PairedDeviceStore(tmp.newFolder().resolve("paired")),
    )

    private class Prompt(private val answer: Boolean?, private val delayMs: Long = 0) : PairingPrompt {
        @Volatile var shown: String? = null
        @Volatile var waited = false
        override suspend fun confirm(peerName: String, code: String): Boolean {
            shown = code
            delay(delayMs)
            return answer ?: awaitCancellation()
        }
        override suspend fun waitingForPeer() {
            waited = true
        }
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

    @After
    fun closeSockets() = sockets.forEach { runCatching { it.close() } }

    private fun negotiator(p: Phone, confirmTimeoutMs: Long = 5_000) =
        SessionNegotiator(p.fingerprint, p.installId, p.name, p.store, {}, confirmTimeoutMs = confirmTimeoutMs, messageTimeoutMs = 5_000)

    /** Runs both sides; each result is the peer or the failure. */
    private fun run(
        server: Phone, client: Phone, serverPrompt: PairingPrompt, clientPrompt: PairingPrompt, confirmTimeoutMs: Long = 5_000,
    ): Pair<Result<SessionPeer>, Result<SessionPeer>> = runBlocking {
        val (sc, cc) = channels()
        withTimeout(20_000) {
            val s = async(Dispatchers.IO) { runCatching { negotiator(server, confirmTimeoutMs).negotiate(sc, true, client.fingerprint, serverPrompt) } }
            val c = async(Dispatchers.IO) { runCatching { negotiator(client, confirmTimeoutMs).negotiate(cc, false, server.fingerprint, clientPrompt) } }
            s.await() to c.await()
        }
    }

    private fun kind(r: Result<SessionPeer>): FailureKind? = (r.exceptionOrNull() as? SecurityFailure)?.kind

    @Test
    fun firstContactPairsBothPhonesWhenBothUsersConfirm() {
        val a = phone("A"); val b = phone("B")
        val pa = Prompt(true); val pb = Prompt(true, delayMs = 200)
        val (ra, rb) = run(a, b, pa, pb)
        assertTrue(ra.isSuccess && rb.isSuccess)
        assertTrue(ra.getOrThrow().newlyPaired && rb.getOrThrow().newlyPaired)
        assertEquals(pa.shown, pb.shown)
        assertTrue(pa.shown!!.matches(Regex("\\d{3} \\d{3}")))
        assertTrue(pa.waited) // A answered first and waited for B
        assertEquals(listOf(b.fingerprint.toHex()), a.store.load().map { it.fingerprintHex })
        assertEquals(listOf(a.fingerprint.toHex()), b.store.load().map { it.fingerprintHex })
        assertEquals("B", a.store.load().single().name)
    }

    @Test
    fun pairedPhonesAuthenticateWithoutPrompting() {
        val a = phone("A"); val b = phone("B")
        run(a, b, Prompt(true), Prompt(true))
        val pa = Prompt(null); val pb = Prompt(null)
        val (ra, rb) = run(a, b, pa, pb)
        assertFalse(ra.getOrThrow().newlyPaired)
        assertFalse(rb.getOrThrow().newlyPaired)
        assertEquals(null, pa.shown)
        assertEquals(null, pb.shown)
    }

    @Test
    fun aRejectionOnEitherSideStoresNothing() {
        for ((sa, sb) in listOf(false to true, true to false, false to false)) {
            val a = phone("A"); val b = phone("B")
            val (ra, rb) = run(a, b, Prompt(sa, delayMs = if (sa) 300 else 0), Prompt(sb, delayMs = if (sb) 300 else 0))
            assertEquals(FailureKind.PAIRING_REJECTED, kind(ra))
            assertEquals(FailureKind.PAIRING_REJECTED, kind(rb))
            assertTrue(a.store.load().isEmpty() && b.store.load().isEmpty())
        }
    }

    @Test
    fun anUnansweredPromptTimesOutAndStoresNothing() {
        val a = phone("A"); val b = phone("B")
        val (ra, rb) = run(a, b, Prompt(null), Prompt(true), confirmTimeoutMs = 500)
        assertEquals(FailureKind.PAIRING_TIMEOUT, kind(ra))
        assertEquals(FailureKind.PAIRING_REJECTED, kind(rb))
        assertTrue(a.store.load().isEmpty() && b.store.load().isEmpty())
    }

    @Test
    fun aChangedKeyIsAHardStopOnBothSides() {
        val a = phone("A"); val b = phone("B")
        // A paired with B's install earlier, under a different key.
        a.store.add(PairedDevice("f".repeat(64), b.installId, "B", 1))
        b.store.add(PairedDevice(a.fingerprint.toHex(), a.installId, "A", 1))
        val pa = Prompt(true); val pb = Prompt(true)
        val (ra, rb) = run(a, b, pa, pb)
        assertEquals(FailureKind.IDENTITY_CHANGED, kind(ra))
        assertEquals(FailureKind.IDENTITY_CHANGED, kind(rb))
        assertEquals(null, pa.shown)
        assertEquals(null, pb.shown)
        assertEquals("f".repeat(64), a.store.load().single().fingerprintHex) // nothing overwritten
    }

    @Test
    fun aPhoneThatForgotTheOtherPairsAgain() {
        val a = phone("A"); val b = phone("B")
        run(a, b, Prompt(true), Prompt(true))
        assertTrue(b.store.remove(a.fingerprint.toHex()))
        val pa = Prompt(true); val pb = Prompt(true)
        val (ra, rb) = run(a, b, pa, pb)
        assertTrue(ra.isSuccess && rb.isSuccess)
        assertEquals(pa.shown, pb.shown)
        assertEquals(1, a.store.load().size)
        assertEquals(1, b.store.load().size)
    }

    @Test
    fun aCorruptStoreRefusesTheSession() {
        val a = phone("A"); val b = phone("B")
        val bad = tmp.newFolder().resolve("paired").apply { writeText("garbage") }
        val broken = Phone(a.fingerprint, a.installId, "A", PairedDeviceStore(bad))
        val (ra, _) = run(broken, b, Prompt(true), Prompt(true))
        assertEquals(FailureKind.TRUST_STORE_ERROR, kind(ra))
    }

    @Test
    fun aBrokenCommitmentFromTheServerFailsPairingWithoutPrompting() = runBlocking {
        val b = phone("B")
        val (fakeServer, cc) = channels()
        val prompt = Prompt(true)
        val serverFp = ByteArray(32).also(rng::nextBytes)
        val client = async(Dispatchers.IO) { runCatching { negotiator(b).negotiate(cc, false, serverFp, prompt) } }
        // A server that commits to one nonce and reveals another.
        fakeServer.sendMessage(Message.Hello(ControlProtocol.VERSION, "9".repeat(32), "Evil"))
        fakeServer.expect<Message.Hello>(5_000)
        fakeServer.sendMessage(Message.Status(AuthStatus.NEEDS_PAIRING.code))
        fakeServer.expect<Message.Status>(5_000)
        fakeServer.sendMessage(Message.Commit("11".repeat(32)))
        fakeServer.expect<Message.Nonce>(5_000)
        fakeServer.sendMessage(Message.Reveal("22".repeat(32)))
        val r = client.await()
        assertEquals(FailureKind.PAIRING_FAILED, kind(r))
        assertEquals(null, prompt.shown)
        assertTrue(b.store.load().isEmpty())
    }

    @Test
    fun theClientSendsNothingBeforeTheServerSpeaks() = runBlocking {
        val b = phone("B")
        val (fakeServer, cc) = channels()
        val client = async(Dispatchers.IO) { runCatching { negotiator(b).negotiate(cc, false, ByteArray(32), Prompt(true)) } }
        // The server stays silent: the client must not have sent its HELLO.
        val early = runCatching { fakeServer.receive(500) }
        assertTrue("client spoke first: ${early.getOrNull()}", early.isFailure)
        fakeServer.close()
        assertTrue(client.await().isFailure)
    }
}
