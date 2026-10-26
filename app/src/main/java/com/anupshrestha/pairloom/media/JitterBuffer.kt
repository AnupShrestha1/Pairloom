package com.anupshrestha.pairloom.media

import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.ceil

/** What the jitter buffer hands to playout for one frame period. */
sealed interface Playout {
    /** Still filling up before playout starts (or after a rebuffer); play silence. */
    data object Buffering : Playout

    data class Frame(val index: Long, val payload: ByteArray) : Playout

    /** The frame for [index] did not arrive in time; conceal it. */
    data class Missing(val index: Long) : Playout

    /**
     * Frames are arriving too late for the current delay: play one concealment frame without
     * advancing, which adds one frame of delay.
     */
    data object Stretch : Playout
}

enum class PushResult { ACCEPTED, LATE, DUPLICATE }

/**
 * Adaptive jitter buffer keyed by frame index (consecutive audio frames have consecutive indexes).
 * Not thread-safe; the caller synchronizes.
 *
 * Two delay measures: the RFC 3550 jitter (exponential average of inter-arrival deviation, reported
 * as a statistic) and the delay spread over the last [windowFrames] frames (the [spreadPercentile]
 * transit time minus the smallest). Bursty delivery, such as a radio time-sharing two channels, has a small
 * average jitter but a large spread, so the target depth follows the larger of the two, clamped to
 * [minFrames]..[maxFrames]. Playout starts once the target depth is buffered. If the buffer stays
 * more than two frames over the target, the oldest frame is dropped at most once per [trimEvery]
 * frame periods, so latency comes down gradually instead of throwing whole bursts away. After
 * [rebufferAfterMissing] consecutive missing frames with an empty buffer, playout stops and
 * buffers again.
 *
 * Push-to-talk sends talk spurts with silence between them. [endSpurt] marks the last frame of a
 * spurt: playout stops cleanly after it (no frames concealed for the silence), and a spurt shorter
 * than the target depth is still played.
 */
class JitterBuffer(
    private val frameNanos: Long,
    private val minFrames: Int = 3,
    private val maxFrames: Int = 30,
    private val rebufferAfterMissing: Int = 10,
    private val windowFrames: Int = 200,
    private val trimEvery: Int = 10,
    /**
     * Which transit-time percentile the spread is measured to. Below 1.0 a rare spike (seen up to
     * 600 ms on the test phones) no longer holds the buffer high; frames beyond it arrive late and
     * are concealed instead.
     */
    private val spreadPercentile: Double = 0.97,
) {
    private val frames = TreeMap<Long, ByteArray>()
    private var playing = false
    private var nextIndex = 0L
    private var consecutiveMissing = 0
    private var lastIndex = -1L
    private var lastArrivalNanos = 0L

    // Transit times of the last [windowFrames] frames in a ring of primitive longs (sorting boxed
    // values on every packet showed up in a profile of the receive thread).
    private val transits = LongArray(windowFrames)
    private var transitCount = 0
    private var transitNext = 0
    private val scratch = LongArray(windowFrames)
    private var spreadDirty = false
    private var sinceTrim = Int.MAX_VALUE
    private var spurtEnd: Long? = null

    /** [spreadPercentile] minus smallest transit time over the recent window, in nanoseconds. */
    var spreadNanos = 0L
        get() {
            if (spreadDirty) {
                spreadDirty = false
                System.arraycopy(transits, 0, scratch, 0, transitCount)
                java.util.Arrays.sort(scratch, 0, transitCount)
                val high = scratch[((transitCount - 1) * spreadPercentile).toInt().coerceIn(0, transitCount - 1)]
                field = high - scratch[0]
            }
            return field
        }
        private set

    /** Current inter-arrival jitter estimate in nanoseconds. */
    var jitterNanos = 0.0
        private set

    var dropped = 0L
        private set

    /** Concealment frames inserted to grow the delay ([Playout.Stretch]). */
    var stretched = 0L
        private set

    private var stretchCredit = 0

    /** Frames of an ended spurt that arrive afterwards are not a sign of too little delay. */
    private var endedBefore = -1L

    val depthFrames: Int get() = frames.size

    val targetFrames: Int
        get() {
            val byJitter = ceil((2 * jitterNanos + frameNanos) / frameNanos).toInt()
            val bySpread = ceil((spreadNanos + frameNanos).toDouble() / frameNanos).toInt()
            return maxOf(byJitter, bySpread).coerceIn(minFrames, maxFrames)
        }

    fun push(index: Long, payload: ByteArray, arrivalNanos: Long): PushResult {
        if (index < nextIndex) {
            // Late while playing below the target delay: let playout fall back one frame (see pop).
            if (playing && index > endedBefore) stretchCredit = minOf(stretchCredit + 1, maxFrames)
            return PushResult.LATE // also after a spurt ended or a rebuffer
        }
        if (frames.containsKey(index)) return PushResult.DUPLICATE
        if (lastIndex >= 0 && index > lastIndex) {
            val d = (arrivalNanos - lastArrivalNanos) - (index - lastIndex) * frameNanos
            jitterNanos += (abs(d.toDouble()) - jitterNanos) / 16.0
        }
        if (index > lastIndex) {
            lastIndex = index
            lastArrivalNanos = arrivalNanos
        }
        transits[transitNext] = arrivalNanos - index * frameNanos
        transitNext = (transitNext + 1) % windowFrames
        if (transitCount < windowFrames) transitCount++
        spreadDirty = true
        frames[index] = payload
        return PushResult.ACCEPTED
    }

    /** The sender says [lastIndex] is the last frame of a talk spurt (may arrive more than once). */
    fun endSpurt(lastIndex: Long) {
        if (spurtEnd?.let { it >= lastIndex } == true) return
        if (playing) {
            if (lastIndex >= nextIndex) {
                spurtEnd = lastIndex
            } else if (frames.isEmpty()) {
                playing = false // already played past it (concealing the silence): stop now
                stretchCredit = 0
            }
        } else if (frames.isNotEmpty() && frames.firstKey() <= lastIndex) {
            spurtEnd = lastIndex // a spurt still buffering; play it even if it is short
        }
    }

    /** Called once per frame period by playout. */
    fun pop(): Playout {
        spurtEnd?.let { end ->
            if (playing && nextIndex > end) {
                // The spurt has been played; wait for the next one.
                playing = false
                spurtEnd = null
                stretchCredit = 0
                endedBefore = end
                while (frames.isNotEmpty() && frames.firstKey() <= end) frames.remove(frames.firstKey())
            }
        }
        if (!playing) {
            if (frames.isEmpty()) return Playout.Buffering
            val end = spurtEnd
            if (end != null && frames.firstKey() > end) spurtEnd = null // stale marker
            val shortSpurtDone = spurtEnd != null
            if (frames.size < targetFrames && !shortSpurtDone) return Playout.Buffering
            playing = true
            nextIndex = frames.firstKey()
            consecutiveMissing = 0
        }
        // Frames arrived late and the buffer holds less than the target: the delay is too short (as at
        // the start of a push-to-talk spurt, before the spread is known). Stretch by one frame per late
        // frame until the target is reached; the trim below brings the delay back down later.
        if (stretchCredit > 0) {
            if (frames.size < targetFrames) {
                stretchCredit--
                stretched++
                return Playout.Stretch
            }
            stretchCredit = 0
        }
        // Persistently over target: drop one old frame now and then to bring latency down. Not
        // once the spurt's end is known: the rest of it is played whole, then playout pauses anyway.
        if (sinceTrim < Int.MAX_VALUE) sinceTrim++
        if (frames.size > targetFrames + 2 && sinceTrim >= trimEvery && spurtEnd == null) {
            val first = frames.firstKey()
            frames.remove(first)
            dropped++
            sinceTrim = 0
            if (first >= nextIndex) nextIndex = first + 1
        }
        val index = nextIndex++
        val payload = frames.remove(index)
        if (payload != null) {
            consecutiveMissing = 0
            return Playout.Frame(index, payload)
        }
        consecutiveMissing++
        if (consecutiveMissing >= rebufferAfterMissing && frames.isEmpty()) {
            playing = false
            stretchCredit = 0 // the rebuffer itself restores the delay
        }
        return Playout.Missing(index)
    }
}

/**
 * Packet-loss concealment for 16-bit PCM: repeat the last good frame with a fade for up to
 * [maxRepeats] frames, then silence.
 */
class Concealer(private val maxRepeats: Int = 3) {
    private var last: ByteArray? = null
    private var repeats = 0

    fun good(frame: ByteArray) {
        last = frame
        repeats = 0
    }

    fun conceal(frameBytes: Int): ByteArray {
        val source = last
        if (source == null || source.size != frameBytes || repeats >= maxRepeats) return ByteArray(frameBytes)
        repeats++
        val gain = 1.0 - repeats.toDouble() / (maxRepeats + 1)
        return Pcm.scale(source, gain)
    }
}

/** 16-bit little-endian mono PCM helpers. */
object Pcm {
    fun scale(frame: ByteArray, gain: Double): ByteArray {
        val out = ByteArray(frame.size)
        for (i in 0 until frame.size / 2) {
            val s = (frame[2 * i].toInt() and 0xFF) or (frame[2 * i + 1].toInt() shl 8)
            val v = (s * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[2 * i] = v.toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }
}

/**
 * Deterministic test tone: frame [index] of a continuous sine. StrictMath makes it bit-identical on
 * every device, so the receiver can check that each delivered frame is exactly what was sent.
 */
class TestTone(
    private val sampleRate: Int = 48_000,
    private val samplesPerFrame: Int = 480,
    private val frequencyHz: Double = 440.0,
    private val amplitude: Double = 0.3,
) {
    val frameBytes: Int get() = samplesPerFrame * 2

    fun frame(index: Long): ByteArray {
        val out = ByteArray(frameBytes)
        val start = index * samplesPerFrame
        for (i in 0 until samplesPerFrame) {
            val n = start + i
            val v = (StrictMath.sin(2 * Math.PI * frequencyHz * n / sampleRate) * amplitude * Short.MAX_VALUE).toInt()
            out[2 * i] = v.toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }
}
