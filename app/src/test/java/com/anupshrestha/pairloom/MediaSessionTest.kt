package com.anupshrestha.pairloom

import com.anupshrestha.pairloom.media.AudioCodec
import com.anupshrestha.pairloom.media.AudioDecoder
import com.anupshrestha.pairloom.media.AudioEncoder
import com.anupshrestha.pairloom.media.CodecPair
import com.anupshrestha.pairloom.media.DirectionKey
import com.anupshrestha.pairloom.media.PacketSink
import com.anupshrestha.pairloom.media.PcmFrameSink
import com.anupshrestha.pairloom.media.MediaHeader
import com.anupshrestha.pairloom.media.FrameSink
import com.anupshrestha.pairloom.media.FrameSource
import com.anupshrestha.pairloom.media.MediaEnd
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

    // --- codecs ---------------------------------------------------------------------------------

    /** A stand-in for Opus: two 10 ms frames per packet, bit-exact, so the tone can be checked. */
    private class TwoFrameEncoder(private val out: PacketSink) : AudioEncoder {
        override val framesPerPacket = 2
        override val delayMs = 0.0
        private var held: Pair<Long, ByteArray>? = null
        override fun encode(index: Long, pcm: ByteArray) {
            val h = held
            if (h == null || h.first + 1 != index) { held = index to pcm; return }
            held = null
            out.packet(h.first, h.second + pcm)
        }
    }

    private class TwoFrameDecoder(private val out: PcmFrameSink) : AudioDecoder {
        override fun decode(firstIndex: Long, payload: ByteArray) {
            for (k in 0 until payload.size / 960) out.frame(firstIndex + k, payload.copyOfRange(k * 960, (k + 1) * 960))
        }
    }

    private val twoFrame = CodecPair(AudioCodec.OPUS, ::TwoFrameEncoder, ::TwoFrameDecoder)

    @Test
    fun multiFramePacketsArriveIntact() {
        val sa = socket(); val sb = socket()
        val ka = DirectionKey.random(); val kb = DirectionKey.random()
        val a = MediaSession(sa, InetSocketAddress(lo, sb.localPort), ka, kb, codecs = twoFrame)
        val b = MediaSession(sb, InetSocketAddress(lo, sa.localPort), kb, ka, codecs = twoFrame)
        a.start(); b.start()
        Thread.sleep(1_200)
        for (s in listOf(a.stop(), b.stop())) {
            assertEquals(AudioCodec.OPUS, s.codec)
            assertTrue("played ${s.played}", s.played > 60)
            assertEquals(s.played, s.intact)
            assertTrue("loss ${s.lossPercent}", s.lossPercent < 5.0)
            // Two frames per audio packet: far fewer packets than frames.
            assertTrue("received ${s.received}", s.received < 0.75 * (s.played + s.concealed + 40))
        }
    }

    @Test
    fun pushToTalkSpurtsEndOnAWholePacket() {
        val sa = socket(); val sb = socket()
        val ka = DirectionKey.random(); val kb = DirectionKey.random()
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        val a = MediaSession(sa, InetSocketAddress(lo, sb.localPort), ka, kb, pushToTalk = true, hangoverFrames = 3, codecs = twoFrame, log = { logs += it })
        val b = MediaSession(sb, InetSocketAddress(lo, sa.localPort), kb, ka, pushToTalk = true, codecs = twoFrame)
        a.start(); b.start()
        repeat(3) {
            a.talking = true
            Thread.sleep(205L + 10 * it) // odd and even frame counts
            a.talking = false
            Thread.sleep(400)
        }
        a.stop(); val s = b.stop()
        assertEquals(3L, s.spurts)
        assertEquals(s.played, s.intact)
        assertTrue("concealed ${s.concealed}", s.concealed <= 3)
        assertTrue("loss ${s.lossPercent}", s.lossPercent < 5.0)
        val starts = logs.mapNotNull { Regex("starts at frame (\\d+)").find(it)?.groupValues?.get(1)?.toLong() }
        val ends = logs.mapNotNull { Regex("ends at frame (\\d+)").find(it)?.groupValues?.get(1)?.toLong() }
        assertEquals(3, ends.size)
        for ((st, en) in starts.zip(ends)) assertEquals("spurt $st..$en", 0L, (en - st + 1) % 2)
    }

    private fun opusNegotiations(aCodecs: Int, bCodecs: Int): Array<MediaNegotiation> {
        val sides = arrayOfNulls<MediaNegotiation>(2)
        val masks = intArrayOf(aCodecs, bCodecs)
        fun make(i: Int, server: Boolean) = MediaNegotiation(
            server, lo, lo, send = { m -> sides[1 - i]!!.onMessage(m) },
            newSession = { _, c, s, p, out, inKey -> MediaSession(s, p, out, inKey, codecs = if (c == AudioCodec.OPUS) twoFrame else CodecPair.PCM) },
            localCodecs = { masks[i] },
        )
        sides[0] = make(0, true); sides[1] = make(1, false)
        return sides.requireNoNulls()
    }

    @Test
    fun opusIsChosenOnlyWhenBothPhonesHaveIt() {
        for ((masks, expected) in listOf(
            (AudioCodec.OPUS.bit to AudioCodec.OPUS.bit) to AudioCodec.OPUS,
            (AudioCodec.OPUS.bit to 0) to AudioCodec.PCM,
            (0 to AudioCodec.OPUS.bit) to AudioCodec.PCM,
        )) {
            val (a, b) = opusNegotiations(masks.first, masks.second)
            a.start(MediaKind.PTT)
            b.accept()
            assertEquals(expected, a.codec)
            assertEquals(expected, b.codec)
            a.stop()
            assertNull(b.session)
        }
        // The test tone is always PCM, as its frames are checked bit for bit.
        val (a, b) = opusNegotiations(AudioCodec.OPUS.bit, AudioCodec.OPUS.bit)
        a.start(MediaKind.TONE)
        assertEquals(AudioCodec.PCM, a.codec)
        assertEquals(AudioCodec.PCM, b.codec)
        a.stop()
    }

    @Test
    fun anAnswerWithACodecThatWasNotOfferedEndsTheAttempt() {
        val sent = mutableListOf<Message>()
        val n = MediaNegotiation(false, lo, lo, send = { sent += it })
        n.start(MediaKind.VOICE) // offers PCM only
        n.onMessage(Message.MediaAnswer(AudioCodec.OPUS.code, 1234, "ab".repeat(32), "01020304"))
        assertNull(n.session)
        assertTrue(!n.isOffering)
        assertEquals(Message.MediaStop, sent.last())
    }

    @Test
    fun bothPhonesLearnWhyACallEnded() {
        val ends = java.util.Collections.synchronizedList(mutableListOf<String>())
        val sides = arrayOfNulls<MediaNegotiation>(2)
        fun make(i: Int, server: Boolean) = MediaNegotiation(
            server, lo, lo, send = { m -> sides[1 - i]!!.onMessage(m) },
            onEnded = { kind, reason -> ends += "$i $kind $reason" },
        )
        sides[0] = make(0, true); sides[1] = make(1, false)
        val (a, b) = sides.requireNoNulls()
        // Answered, then ended by the caller.
        a.start(MediaKind.VOICE); b.accept(); a.stop()
        assertEquals(listOf("0 VOICE ENDED_HERE", "1 VOICE ENDED_BY_PEER"), ends.sorted())
        // Declined by the callee.
        ends.clear(); a.start(MediaKind.PTT); b.decline()
        assertEquals(listOf("0 PTT DECLINED_BY_PEER", "1 PTT DECLINED_HERE"), ends.sorted())
        // Cancelled by the caller before the answer: a missed call on the other phone.
        ends.clear(); a.start(MediaKind.VOICE); a.stop()
        assertEquals(listOf("0 VOICE ENDED_HERE", "1 VOICE MISSED"), ends.sorted())
        // Taken by another app (the reason comes from the caller of stop).
        ends.clear(); a.start(MediaKind.VOICE); b.accept(); b.stop(MediaEnd.AUDIO_TAKEN)
        assertEquals(listOf("0 VOICE ENDED_BY_PEER", "1 VOICE AUDIO_TAKEN"), ends.sorted())
        // Stopping with nothing running reports nothing; link teardown (close) reports nothing.
        ends.clear(); a.stop(); a.start(MediaKind.VOICE); b.accept(); a.close(); b.close()
        assertTrue(ends.isEmpty())
    }

    @Test
    fun silenceIsReportedWhenNothingArrives() {
        val s = MediaSession(socket(), InetSocketAddress(lo, socket().localPort), DirectionKey.random(), DirectionKey.random())
        s.start()
        Thread.sleep(1_200)
        val st = s.stop()
        assertTrue("silent ${st.silentMs}", st.silentMs >= 1_000)
        assertTrue(st.signalLost)
    }

    @Test
    fun malformedOffersAreIgnored() {
        val sent = mutableListOf<Message>()
        val n = MediaNegotiation(false, lo, lo, send = { sent += it })
        n.onMessage(Message.MediaAnswer(0, 1234, "ab".repeat(32), "01020304")) // answer without offer
        assertNull(n.session)
        assertTrue(sent.isEmpty())
        n.onMessage(Message.MediaStop)
        assertNull(n.session)
    }
}
