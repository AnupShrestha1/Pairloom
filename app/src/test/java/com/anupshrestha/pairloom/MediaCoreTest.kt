package com.anupshrestha.pairloom

import com.anupshrestha.pairloom.media.AudioCodec
import com.anupshrestha.pairloom.media.Concealer
import com.anupshrestha.pairloom.media.Opus
import com.anupshrestha.pairloom.media.DirectionKey
import com.anupshrestha.pairloom.media.JitterBuffer
import com.anupshrestha.pairloom.media.MediaHeader
import com.anupshrestha.pairloom.media.MediaOpener
import com.anupshrestha.pairloom.media.MediaSealer
import com.anupshrestha.pairloom.media.PacketKind
import com.anupshrestha.pairloom.media.Pcm
import com.anupshrestha.pairloom.media.Playout
import com.anupshrestha.pairloom.media.PushResult
import com.anupshrestha.pairloom.media.ReplayWindow
import com.anupshrestha.pairloom.media.TestTone
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class MediaCoreTest {
    private val ms = 1_000_000L

    // --- header --------------------------------------------------------------------------------

    @Test
    fun headerRoundTripsAndRejectsGarbage() {
        val h = MediaHeader(PacketKind.AUDIO, 123_456_789_012L, -5)
        assertEquals(h, MediaHeader.decode(h.encode()))
        assertEquals(MediaHeader.SIZE, h.encode().size)
        assertNull(MediaHeader.decode(ByteArray(13)))
        assertNull(MediaHeader.decode(h.encode().also { it[0] = 2 })) // other version
        assertNull(MediaHeader.decode(h.encode().also { it[1] = 9 })) // unknown kind
        assertNull(MediaHeader.decode(MediaHeader(PacketKind.AUDIO, 0, 0).encode().also { it[2] = 0x80.toByte() })) // negative sequence
    }

    // --- AES-GCM ---------------------------------------------------------------------------------

    private val key = DirectionKey.random(SecureRandom())

    @Test
    fun sealedPacketsOpenWithTheRightKeyOnly() {
        val payload = TestTone().frame(3)
        val packet = MediaSealer(key).seal(MediaHeader(PacketKind.AUDIO, 7, 1440), payload)
        assertEquals(MediaHeader.SIZE + payload.size + MediaSealer.TAG_BYTES, packet.size)
        val opened = MediaOpener(key).open(packet)
        assertNotNull(opened)
        assertArrayEquals(payload, opened!!.payload)
        assertEquals(7L, opened.header.sequence)
        assertNull(MediaOpener(DirectionKey.random()).open(packet))
        assertNull(MediaOpener(DirectionKey(key.key, byteArrayOf(9, 9, 9, 9))).open(packet)) // wrong salt
    }

    @Test
    fun anyTamperingIsDetected() {
        val packet = MediaSealer(key).seal(MediaHeader(PacketKind.AUDIO, 1, 480), ByteArray(960) { it.toByte() })
        val opener = MediaOpener(key)
        for (i in packet.indices step 37) {
            val bad = packet.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull("byte $i", opener.open(bad))
        }
        assertNull(opener.open(packet.copyOf(packet.size - 1)))
        assertNull(opener.open(ByteArray(10)))
        // Changing the sequence in the header changes the nonce and the AAD: rejected.
        val moved = packet.copyOf().also { it[9] = (it[9] + 1).toByte() }
        assertNull(opener.open(moved))
    }

    @Test
    fun openRespectsTheReceivedLength() {
        val packet = MediaSealer(key).seal(MediaHeader(PacketKind.PROBE, 2, 0), ByteArray(8))
        val buffer = packet + ByteArray(100) // like a reused receive buffer
        assertNotNull(MediaOpener(key).open(buffer, packet.size))
    }

    @Test
    fun keysAndSaltsHaveFixedSizes() {
        assertTrue(runCatching { DirectionKey(ByteArray(16), ByteArray(4)) }.isFailure)
        assertTrue(runCatching { DirectionKey(ByteArray(32), ByteArray(8)) }.isFailure)
    }

    // --- replay window -------------------------------------------------------------------------

    @Test
    fun replayWindowAcceptsEachSequenceOnce() {
        val w = ReplayWindow(64)
        assertTrue(w.accept(0))
        assertFalse(w.accept(0))
        assertTrue(w.accept(5))
        assertTrue(w.accept(3)) // out of order inside the window
        assertFalse(w.accept(3))
        assertTrue(w.accept(1000)) // big jump
        assertFalse(w.accept(5)) // far behind now
        assertFalse(w.accept(1000 - 64))
        assertTrue(w.accept(1000 - 63))
        assertFalse(w.accept(-1))
    }

    @Test
    fun replayWindowSlidesCorrectly() {
        val w = ReplayWindow(8)
        for (s in 0L..100L) assertTrue(w.accept(s))
        for (s in 93L..100L) assertFalse(w.accept(s))
        assertFalse(w.accept(92))
        assertTrue(w.accept(102))
        assertTrue(w.accept(101))
    }

    // --- jitter buffer -------------------------------------------------------------------------

    private fun frame(i: Long) = byteArrayOf(i.toByte())

    @Test
    fun playoutStartsAfterTheTargetDepthAndPlaysInOrder() {
        val jb = JitterBuffer(10 * ms, minFrames = 3)
        assertEquals(Playout.Buffering, jb.pop())
        for (i in 0L..2L) assertEquals(PushResult.ACCEPTED, jb.push(i, frame(i), i * 10 * ms))
        val first = jb.pop() as Playout.Frame
        assertEquals(0L, first.index)
        assertEquals(1L, (jb.pop() as Playout.Frame).index)
    }

    @Test
    fun missingFramesAreReportedAndLateOnesRejected() {
        val jb = JitterBuffer(10 * ms, minFrames = 3)
        for (i in listOf(0L, 1L, 3L)) jb.push(i, frame(i), i * 10 * ms)
        assertEquals(0L, (jb.pop() as Playout.Frame).index)
        assertEquals(1L, (jb.pop() as Playout.Frame).index)
        assertEquals(Playout.Missing(2), jb.pop())
        assertEquals(PushResult.LATE, jb.push(2, frame(2), 40 * ms))
        assertEquals(Playout.Stretch, jb.pop()) // too little delay: one frame of delay is added
        assertEquals(3L, (jb.pop() as Playout.Frame).index)
        assertEquals(PushResult.DUPLICATE, jb.push(5, frame(5), 50 * ms).let { jb.push(5, frame(5), 51 * ms) })
    }

    @Test
    fun targetGrowsWithJitterAndStaysClamped() {
        val steady = JitterBuffer(10 * ms, minFrames = 3, maxFrames = 12)
        for (i in 0L..200L) steady.push(i, frame(i), i * 10 * ms)
        assertEquals(3, steady.targetFrames)
        val jittery = JitterBuffer(10 * ms, minFrames = 3, maxFrames = 12)
        val rng = java.util.Random(1)
        for (i in 0L..400L) jittery.push(i, frame(i), i * 10 * ms + rng.nextInt(40) * ms)
        assertTrue("target ${jittery.targetFrames}", jittery.targetFrames in 4..12)
        val wild = JitterBuffer(10 * ms, minFrames = 3, maxFrames = 12)
        for (i in 0L..400L) wild.push(i, frame(i), i * 10 * ms + (if (i % 2 == 0L) 0 else 500) * ms)
        assertEquals(12, wild.targetFrames)
    }

    @Test
    fun burstyDeliveryRaisesTheTargetBeyondTheAverageJitter() {
        // Frames arrive in bursts of 10 every 100 ms (like a radio switching channels): the RFC 3550
        // jitter stays modest, but the spread shows the 90 ms the oldest frame of a burst waited.
        val jb = JitterBuffer(10 * ms, minFrames = 3, maxFrames = 30)
        for (i in 0L until 400L) jb.push(i, frame(i), (i / 10 + 1) * 100 * ms)
        assertTrue("spread ${jb.spreadNanos / ms}", jb.spreadNanos / ms in 85..95)
        assertTrue("target ${jb.targetFrames}", jb.targetFrames >= 10)
    }

    @Test
    fun aRareSpikeDoesNotHoldTheBufferHigh() {
        // Steady delivery with one 600 ms spike (as seen on the phones): the 97th-percentile
        // spread ignores it, so the target stays at the minimum and that one frame arrives late.
        val jb = JitterBuffer(10 * ms, minFrames = 3, maxFrames = 30)
        for (i in 0L until 200L) jb.push(i, frame(i), i * 10 * ms + if (i == 150L) 600 * ms else 0)
        assertEquals(3, jb.targetFrames)
        // Frequent delay (every 10th frame 80 ms late) is not a rare spike and still counts.
        val often = JitterBuffer(10 * ms, minFrames = 3, maxFrames = 30)
        for (i in 0L until 200L) often.push(i, frame(i), i * 10 * ms + if (i % 10 == 0L) 80 * ms else 0)
        assertTrue("target ${often.targetFrames}", often.targetFrames >= 8)
    }

    @Test
    fun trimmingIsGradual() {
        val jb = JitterBuffer(10 * ms, minFrames = 3, maxFrames = 3, trimEvery = 10)
        for (i in 0L..29L) jb.push(i, frame(i), i * 10 * ms) // steady, but 30 frames queued
        repeat(20) { jb.pop() }
        assertEquals(2L, jb.dropped) // one at the start, one 10 periods later
    }

    @Test
    fun excessDepthIsTrimmedAndLongGapsRebuffer() {
        val jb = JitterBuffer(10 * ms, minFrames = 3, maxFrames = 6, rebufferAfterMissing = 4)
        for (i in 0L..19L) jb.push(i, frame(i), 0) // a burst: 20 frames at once
        val p = jb.pop() as Playout.Frame
        assertTrue("trimmed to near target, played ${p.index}", p.index > 0)
        assertTrue(jb.dropped > 0)
        while (jb.depthFrames > 0) jb.pop()
        repeat(4) { assertTrue(jb.pop() is Playout.Missing) }
        assertEquals(Playout.Buffering, jb.pop()) // stopped after 4 missing with an empty buffer
    }

    @Test
    fun lateFramesStretchTheDelayUpToTheTarget() {
        // Playout starts with the minimum delay (3 frames), then frames come in bursts of 5 that
        // land up to 70 ms after their own time, more than the delay allows. Each late frame
        // stretches playout by one frame until the delay covers the bursts; after that nothing is late.
        val jb = JitterBuffer(10 * ms, minFrames = 3, maxFrames = 30)
        fun arrivalPeriod(i: Long): Long = if (i < 3) i else (i / 5) * 5 + 7
        var lateEarly = 0
        var lateLater = 0
        for (period in 0L until 300L) {
            for (i in 0L until 400L) {
                if (arrivalPeriod(i) == period && jb.push(i, frame(i), period * 10 * ms) == PushResult.LATE) {
                    if (period < 150) lateEarly++ else lateLater++
                }
            }
            jb.pop()
        }
        assertTrue("stretched ${jb.stretched}", jb.stretched > 0)
        assertTrue("late at first ${lateEarly}", lateEarly > 0)
        assertEquals("late once the delay has grown", 0, lateLater)
    }

    @Test
    fun aRebufferClearsPendingStretches() {
        // Late frames earn stretch credit, then the stream stops long enough to rebuffer. After the
        // rebuffer the delay is rebuilt by buffering, so no stretch frames may follow.
        val jb = JitterBuffer(10 * ms, minFrames = 3, maxFrames = 30, rebufferAfterMissing = 4)
        for (i in 0L..2L) jb.push(i, frame(i), i * 10 * ms)
        repeat(3) { jb.pop() } // plays 0..2
        jb.pop(); jb.pop() // 3 and 4 missing
        jb.push(3, frame(3), 60 * ms); jb.push(4, frame(4), 60 * ms) // late: credit 2
        repeat(4) { jb.pop() } // 5.. missing, empty buffer: rebuffer
        for (i in 100L..102L) jb.push(i, frame(i), i * 10 * ms)
        val out = (1..3).map { jb.pop() }
        assertTrue("no stretch after a rebuffer: $out", out.none { it == Playout.Stretch })
        assertEquals(100L, (out.first() as Playout.Frame).index)
    }

    // --- push-to-talk spurts ------------------------------------------------------------------

    private fun drain(jb: JitterBuffer, periods: Int) = (1..periods).map { jb.pop() }

    @Test
    fun aSpurtEndStopsPlayoutWithoutConcealingTheSilence() {
        val jb = JitterBuffer(10 * ms, minFrames = 3)
        for (i in 0L..19L) jb.push(i, frame(i), i * 10 * ms)
        jb.endSpurt(19)
        val out = drain(jb, 40)
        assertEquals(20, out.count { it is Playout.Frame })
        assertEquals(0, out.count { it is Playout.Missing })
        assertEquals(Playout.Buffering, out.last())
        // The next spurt (the sender's frame index kept counting through the silence) plays again.
        for (i in 300L..302L) jb.push(i, frame(i), i * 10 * ms)
        assertEquals(300L, (jb.pop() as Playout.Frame).index)
    }

    @Test
    fun aSpurtShorterThanTheTargetIsStillPlayed() {
        val jb = JitterBuffer(10 * ms, minFrames = 5)
        jb.push(0, frame(0), 0)
        jb.push(1, frame(1), 10 * ms)
        assertEquals(Playout.Buffering, jb.pop())
        jb.endSpurt(1)
        assertEquals(0L, (jb.pop() as Playout.Frame).index)
        assertEquals(1L, (jb.pop() as Playout.Frame).index)
        assertEquals(Playout.Buffering, jb.pop())
    }

    @Test
    fun aMissingTailFrameIsConcealedAndStaleMarkersAreIgnored() {
        val jb = JitterBuffer(10 * ms, minFrames = 3)
        for (i in 0L..8L) jb.push(i, frame(i), i * 10 * ms) // frame 9 lost
        jb.endSpurt(9)
        val out = drain(jb, 12)
        assertEquals(9, out.count { it is Playout.Frame })
        assertEquals(listOf(Playout.Missing(9)), out.filterIsInstance<Playout.Missing>())
        // A late copy of the marker, and a straggler of the ended spurt, change nothing.
        jb.endSpurt(9)
        assertEquals(PushResult.LATE, jb.push(5, frame(5), 200 * ms))
        for (i in 100L..102L) jb.push(i, frame(i), i * 10 * ms)
        assertEquals(listOf(100L, 101L, 102L), drain(jb, 3).map { (it as Playout.Frame).index })
    }

    // --- codec helpers --------------------------------------------------------------------------

    @Test
    fun codecChoiceNeedsBothPhones() {
        val both = AudioCodec.mask(listOf(AudioCodec.PCM, AudioCodec.OPUS))
        assertEquals(3, both)
        assertEquals(AudioCodec.OPUS, AudioCodec.choose(both, both))
        assertEquals(AudioCodec.PCM, AudioCodec.choose(both, AudioCodec.PCM.bit))
        assertEquals(AudioCodec.PCM, AudioCodec.choose(AudioCodec.PCM.bit, both))
        assertEquals(AudioCodec.PCM, AudioCodec.choose(0, 0))
        assertNull(AudioCodec.fromCode(7))
    }

    @Test
    fun opusPacketDurationsComeFromTheTocByte() {
        fun toc(config: Int, code: Int) = ((config shl 3) or code).toByte()
        assertEquals(960, Opus.packetSamples(byteArrayOf(toc(31, 0)))) // CELT FB 20 ms
        assertEquals(480, Opus.packetSamples(byteArrayOf(toc(30, 0)))) // CELT FB 10 ms
        assertEquals(960, Opus.packetSamples(byteArrayOf(toc(1, 0)))) // SILK NB 20 ms
        assertEquals(960, Opus.packetSamples(byteArrayOf(toc(13, 0)))) // hybrid SWB 20 ms
        assertEquals(1920, Opus.packetSamples(byteArrayOf(toc(31, 1)))) // two frames
        assertEquals(2880, Opus.packetSamples(byteArrayOf(toc(31, 3), 3))) // code 3, three frames
        assertNull(Opus.packetSamples(byteArrayOf(toc(31, 3)))) // code 3 without a count
        assertNull(Opus.packetSamples(ByteArray(0)))
    }

    @Test
    fun opusDecoderConfigIsMono48kWithoutPreSkip() {
        val (head, delay, preroll) = Opus.decoderConfig()
        assertEquals(19, head.size)
        assertEquals("OpusHead", String(head, 0, 8, Charsets.US_ASCII))
        assertEquals(1, head[9].toInt()) // channels
        assertEquals(0, head[10].toInt() or head[11].toInt()) // pre-skip
        assertEquals(48_000, java.nio.ByteBuffer.wrap(head, 12, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(0L, java.nio.ByteBuffer.wrap(delay).order(java.nio.ByteOrder.LITTLE_ENDIAN).long)
        assertEquals(80_000_000L, java.nio.ByteBuffer.wrap(preroll).order(java.nio.ByteOrder.LITTLE_ENDIAN).long)
    }

    // --- concealment, PCM, tone -----------------------------------------------------------------

    @Test
    fun concealmentFadesThenGoesSilent() {
        val c = Concealer(maxRepeats = 3)
        assertArrayEquals(ByteArray(4), c.conceal(4)) // nothing yet
        val loud = Pcm.scale(byteArrayOf(0x00, 0x40, 0x00, 0x40), 1.0) // 16384, 16384
        c.good(loud)
        val levels = (1..4).map { c.conceal(4).let { f -> (f[0].toInt() and 0xFF) or (f[1].toInt() shl 8) } }
        assertTrue(levels[0] < 16384 && levels[1] < levels[0] && levels[2] < levels[1])
        assertEquals(0, levels[3])
    }

    @Test
    fun pcmScalingClampsAndKeepsSign() {
        val frame = byteArrayOf(0xFF.toByte(), 0x7F, 0x00, 0x80.toByte()) // 32767, -32768
        val doubled = Pcm.scale(frame, 2.0)
        assertArrayEquals(frame, doubled)
        val half = Pcm.scale(frame, 0.5)
        assertEquals(16383, (half[0].toInt() and 0xFF) or (half[1].toInt() shl 8))
        assertEquals(-16384, (half[2].toInt() and 0xFF) or (half[3].toInt() shl 8))
    }

    @Test
    fun toneIsDeterministicContinuousAndAudible() {
        val tone = TestTone()
        assertArrayEquals(tone.frame(1234), TestTone().frame(1234))
        assertEquals(960, tone.frameBytes)
        val f = tone.frame(0)
        val peak = (0 until 480).maxOf { kotlin.math.abs((f[2 * it].toInt() and 0xFF) or (f[2 * it + 1].toInt() shl 8)) }
        assertTrue(peak in 9000..10000) // amplitude 0.3
        assertFalse(tone.frame(0).contentEquals(tone.frame(1)))
    }
}
