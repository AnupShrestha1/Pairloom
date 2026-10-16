package com.anupshrestha.pairloom

import com.anupshrestha.pairloom.media.DirectionKey
import com.anupshrestha.pairloom.media.MediaHeader
import com.anupshrestha.pairloom.media.FrameSink
import com.anupshrestha.pairloom.media.FrameSource
import com.anupshrestha.pairloom.media.MediaKind
import com.anupshrestha.pairloom.media.MediaNegotiation
import com.anupshrestha.pairloom.media.MediaSealer
import com.anupshrestha.pairloom.media.MediaSession
import com.anupshrestha.pairloom.media.PacketKind
import com.anupshrestha.pairloom.media.TestTone
import com.anupshrestha.pairloom.net.ControlProtocol.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/** Media over real loopback UDP on the desktop JVM (no audio hardware). */
class MediaSessionTest {
    private val lo: InetAddress = InetAddress.getLoopbackAddress()
    private fun socket() = DatagramSocket(InetSocketAddress(lo, 0))

    @Test
    fun twoSessionsExchangeIntactAuthenticatedFrames() {
        val sa = socket(); val sb = socket()
        val ka = DirectionKey.random(); val kb = DirectionKey.random()
        val a = MediaSession(sa, InetSocketAddress(lo, sb.localPort), ka, kb)
        val b = MediaSession(sb, InetSocketAddress(lo, sa.localPort), kb, ka)
        a.start(); b.start()
        Thread.sleep(1_500)
        val st = listOf(a.stop(), b.stop())
        for (s in st) {
            assertTrue("received ${s.received}", s.received > 100)
            assertTrue("played ${s.played}", s.played > 80)
            assertEquals(s.played, s.intact)
            assertEquals(0L, s.corrupted)
            assertEquals(0L, s.authFailures)
            assertEquals(0L, s.wrongSource)
            assertTrue("loss ${s.lossPercent}", s.lossPercent < 5.0)
            assertNotNull(s.rttAvgMs)
            assertTrue("rtt ${s.rttAvgMs}", s.rttAvgMs!! < 50.0)
        }
    }

    @Test
    fun pushToTalkSendsOnlyWhileTalkingAndCountsSpurts() {
        val sa = socket(); val sb = socket()
        val ka = DirectionKey.random(); val kb = DirectionKey.random()
        val a = MediaSession(sa, InetSocketAddress(lo, sb.localPort), ka, kb, pushToTalk = true, hangoverFrames = 5)
        val b = MediaSession(sb, InetSocketAddress(lo, sa.localPort), kb, ka, pushToTalk = true, hangoverFrames = 5)
        a.start(); b.start()
        Thread.sleep(300) // nobody talks: only probes
        assertEquals(0L, b.stats().played)
        a.talking = true
        Thread.sleep(500)
        assertTrue(a.stats().talking)
        assertTrue("peer talking", b.stats().peerTalking)
        a.talking = false
        Thread.sleep(500)
        assertTrue(!a.stats().talking && !b.stats().peerTalking)
        a.talking = true
        Thread.sleep(300)
        a.talking = false
        Thread.sleep(500)
        val sa2 = a.stop(); val s = b.stop()
        assertEquals(2L, s.spurts)
        assertEquals(0L, sa2.played) // b never talked
        assertTrue("played ${s.played}", s.played in 60L..110L) // ~80 talking + 2 x 5 hangover
        assertEquals(s.played, s.intact)
        assertTrue("concealed ${s.concealed}", s.concealed <= 3)
        assertTrue("loss ${s.lossPercent}", s.lossPercent < 5.0)
        assertNotNull(s.rttAvgMs) // probes keep running between spurts
    }

    @Test
    fun packetsFromOtherSourcesOrKeysAreRejected() {
        val sa = socket(); val peer = socket(); val stranger = socket()
        val ka = DirectionKey.random(); val kPeer = DirectionKey.random()
        val a = MediaSession(sa, InetSocketAddress(lo, peer.localPort), ka, kPeer)
        a.start()
        val target = InetSocketAddress(lo, sa.localPort)
        // A correctly keyed packet from the wrong source port.
        val good = MediaSealer(kPeer).seal(MediaHeader(PacketKind.AUDIO, 0, 0), TestTone().frame(0))
        stranger.send(DatagramPacket(good, good.size, target))
        // A packet from the right source but sealed with another key.
        val forged = MediaSealer(DirectionKey.random()).seal(MediaHeader(PacketKind.AUDIO, 1, 480), TestTone().frame(1))
        peer.send(DatagramPacket(forged, forged.size, target))
        // The genuine packet twice from the right source: the second is a replay.
        peer.send(DatagramPacket(good, good.size, target))
        peer.send(DatagramPacket(good, good.size, target))
        Thread.sleep(300)
        val s = a.stop()
        assertEquals(1L, s.wrongSource)
        assertEquals(1L, s.authFailures)
        assertEquals(1L, s.duplicates)
        assertEquals(1L, s.received)
        listOf(peer, stranger).forEach { it.close() }
    }

    @Test
    fun negotiationStartsBothSidesAndStopEndsBoth() {
        val sides = arrayOfNulls<MediaNegotiation>(2)
        val log = java.util.Collections.synchronizedList(mutableListOf<String>())
        fun make(i: Int, server: Boolean) = MediaNegotiation(server, lo, lo, send = { m -> sides[1 - i]!!.onMessage(m) }, log = { log += "$i $it" })
        sides[0] = make(0, server = true)
        sides[1] = make(1, server = false)
        sides[1]!!.start(MediaKind.TONE) // the client offers
        assertNotNull(sides[0]!!.session)
        assertNotNull(sides[1]!!.session)
        Thread.sleep(800)
        val stats = sides[0]!!.session!!.stats()
        assertTrue("server received ${stats.received}", stats.received > 30)
        assertEquals(stats.played, stats.intact)
        sides[0]!!.stop()
        assertNull(sides[0]!!.session)
        assertNull(sides[1]!!.session)
    }

    @Test
    fun simultaneousOffersResolveToTheServersOffer() {
        val sides = arrayOfNulls<MediaNegotiation>(2)
        val queue = java.util.concurrent.LinkedBlockingQueue<Pair<Int, Message>>()
        // Messages are delivered later, so both offers are in flight at the same time.
        fun make(i: Int, server: Boolean) = MediaNegotiation(server, lo, lo, send = { m -> queue.add(1 - i to m) })
        sides[0] = make(0, server = true)
        sides[1] = make(1, server = false)
        sides[0]!!.start(MediaKind.TONE)
        sides[1]!!.start(MediaKind.TONE)
        while (true) {
            val (to, m) = queue.poll() ?: break
            sides[to]!!.onMessage(m)
        }
        assertNotNull(sides[0]!!.session)
        assertNotNull(sides[1]!!.session)
        Thread.sleep(500)
        assertTrue(sides[1]!!.session!!.stats().received > 10)
        sides.forEach { it!!.close() }
    }

    /** Two negotiations wired together; messages are delivered synchronously. */
    private fun pairedNegotiations(): Array<MediaNegotiation> {
        val sides = arrayOfNulls<MediaNegotiation>(2)
        fun make(i: Int, server: Boolean) = MediaNegotiation(server, lo, lo, send = { m -> sides[1 - i]!!.onMessage(m) })
        sides[0] = make(0, server = true)
        sides[1] = make(1, server = false)
        return arrayOf(sides[0]!!, sides[1]!!)
    }

    @Test
    fun aVoiceCallWaitsForTheReceiverToAccept() {
        val (a, b) = pairedNegotiations()
        a.start(MediaKind.VOICE)
        assertTrue(a.isOffering)
        assertEquals(MediaKind.VOICE, b.incomingKind)
        assertNull(a.session)
        assertNull(b.session)
        b.accept()
        assertNotNull(a.session)
        assertNotNull(b.session)
        assertEquals(MediaKind.VOICE, a.kind)
        assertEquals(MediaKind.VOICE, b.kind)
        assertNull(b.incomingKind)
        b.stop()
        assertNull(a.session)
        assertNull(b.session)
    }

    @Test
    fun aDeclinedCallStartsNothingAndClearsTheCallersOffer() {
        val (a, b) = pairedNegotiations()
        b.start(MediaKind.VOICE)
        assertEquals(MediaKind.VOICE, a.incomingKind)
        a.decline()
        assertNull(a.incomingKind)
        assertTrue(!b.isOffering)
        assertNull(a.session)
        assertNull(b.session)
    }

    @Test
    fun theCallerCanCancelBeforeTheAnswer() {
        val (a, b) = pairedNegotiations()
        a.start(MediaKind.VOICE)
        a.stop()
        assertNull(b.incomingKind)
        assertTrue(!a.isOffering)
    }

    /** A paced source and sink (like a microphone and speaker) drive the timing themselves. */
    @Test
    fun pacedSourceAndSinkDriveTheSession() {
        class Paced : FrameSource, FrameSink {
            val written = java.util.concurrent.atomic.AtomicLong()
            override val paced = true
            override val frameBytes = 960
            override fun read(index: Long): ByteArray { Thread.sleep(10); return ByteArray(960) { index.toByte() } }
            override fun write(frame: ByteArray) { Thread.sleep(10); written.incrementAndGet() }
            override fun close() = Unit
        }
        val sa = socket(); val sb = socket()
        val ka = DirectionKey.random(); val kb = DirectionKey.random()
        val pa = Paced(); val pb = Paced()
        val stopped = java.util.concurrent.atomic.AtomicInteger()
        val a = MediaSession(sa, InetSocketAddress(lo, sb.localPort), ka, kb, source = pa, sink = pa, verify = null, onStopped = { stopped.incrementAndGet() })
        val b = MediaSession(sb, InetSocketAddress(lo, sa.localPort), kb, ka, source = pb, sink = pb, verify = null)
        a.start(); b.start()
        Thread.sleep(1_000)
        val sta = a.stop(); b.stop()
        assertTrue("played ${sta.played}", sta.played > 40)
        assertEquals(0L, sta.intact + sta.corrupted) // no tone verification for voice
        assertTrue("sink writes ${pa.written.get()}", pa.written.get() > 50)
        assertEquals(1, stopped.get())
    }

    @Test
    fun malformedOffersAreIgnored() {
        val sent = mutableListOf<Message>()
        val n = MediaNegotiation(false, lo, lo, send = { sent += it })
        n.onMessage(Message.MediaAnswer(1234, "ab".repeat(32), "01020304")) // answer without offer
        assertNull(n.session)
        assertTrue(sent.isEmpty())
        n.onMessage(Message.MediaStop)
        assertNull(n.session)
    }
}
