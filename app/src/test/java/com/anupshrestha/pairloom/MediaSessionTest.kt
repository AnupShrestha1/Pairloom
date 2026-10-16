package com.anupshrestha.pairloom

import com.anupshrestha.pairloom.media.DirectionKey
import com.anupshrestha.pairloom.media.MediaHeader
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
        sides[1]!!.start() // the client offers
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
        sides[0]!!.start()
        sides[1]!!.start()
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
