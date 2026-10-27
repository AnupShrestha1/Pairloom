package com.anupshrestha.pairloom.media

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Audio codecs a media session can use. [code] is the wire value; the offer lists codecs as a bit mask. */
enum class AudioCodec(val code: Int) {
    /** 16-bit PCM, one 10 ms frame (960 bytes) per packet. Always available. */
    PCM(0),

    /** Opus through the platform's MediaCodec (encoder from Android 10). */
    OPUS(1),
    ;

    val bit: Int get() = 1 shl code

    companion object {
        fun fromCode(code: Int): AudioCodec? = entries.firstOrNull { it.code == code }

        fun mask(codecs: Collection<AudioCodec>): Int = codecs.fold(0) { m, c -> m or c.bit }

        /** The answerer's choice: Opus if both phones can use it, else PCM (which both always have). */
        fun choose(offered: Int, local: Int): AudioCodec = if (offered and local and OPUS.bit != 0) OPUS else PCM
    }
}

/** Receives encoded packets: the payload and the index of the first 10 ms frame it holds. */
fun interface PacketSink {
    fun packet(firstIndex: Long, payload: ByteArray)
}

/** Receives decoded 10 ms PCM frames by frame index. */
fun interface PcmFrameSink {
    fun frame(index: Long, pcm: ByteArray)
}

/**
 * Encodes consecutive 10 ms PCM frames. Packets are handed to the sink given at creation, either
 * on the calling thread or on the codec's own thread (Opus), once their last frame has gone in.
 */
interface AudioEncoder {
    /** 10 ms frames per packet (PCM 1, Opus 2 with the platform's 20 ms frames). */
    val framesPerPacket: Int

    /**
     * Extra one-way delay the codec adds that the receiver's buffer does not measure (look-ahead),
     * for the latency estimate.
     */
    val delayMs: Double

    fun encode(index: Long, pcm: ByteArray)

    fun close() {}
}

/** Decodes packets into 10 ms PCM frames, handed to the sink on the calling thread or the codec's. */
interface AudioDecoder {
    fun decode(firstIndex: Long, payload: ByteArray)

    fun close() {}
}

/** Creates the encoder and decoder of one session (each runs on one thread). */
class CodecPair(
    val codec: AudioCodec,
    val newEncoder: (PacketSink) -> AudioEncoder,
    val newDecoder: (PcmFrameSink) -> AudioDecoder,
) {
    companion object {
        val PCM = CodecPair(AudioCodec.PCM, ::PcmEncoder, ::PcmDecoder)
    }
}

class PcmEncoder(private val out: PacketSink) : AudioEncoder {
    override val framesPerPacket = 1
    override val delayMs = 0.0
    override fun encode(index: Long, pcm: ByteArray) = out.packet(index, pcm)
}

class PcmDecoder(private val out: PcmFrameSink) : AudioDecoder {
    override fun decode(firstIndex: Long, payload: ByteArray) {
        if (payload.size == Opus.FRAME_BYTES) out.frame(firstIndex, payload)
    }
}

/** Opus helpers that do not need Android. */
object Opus {
    const val SAMPLE_RATE = 48_000
    const val FRAME_SAMPLES = 480
    const val FRAME_BYTES = FRAME_SAMPLES * 2

    /** Look-ahead of the Opus encoder in its usual (restricted low-delay off) mode. */
    const val LOOKAHEAD_MS = 6.5

    /**
     * Samples at 48 kHz in an Opus packet, from its TOC byte and frame count code (RFC 6716 section
     * 3.1), or null if the packet is malformed.
     */
    fun packetSamples(packet: ByteArray): Int? {
        if (packet.isEmpty()) return null
        val toc = packet[0].toInt() and 0xFF
        val config = toc shr 3
        val frameSamples = when {
            config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3] // SILK: 10, 20, 40, 60 ms
            config < 16 -> intArrayOf(480, 960)[config and 1] // hybrid: 10, 20 ms
            else -> intArrayOf(120, 240, 480, 960)[config and 3] // CELT: 2.5, 5, 10, 20 ms
        }
        val frames = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (packet.size < 2) return null else (packet[1].toInt() and 0x3F).takeIf { it > 0 } ?: return null
        }
        return frameSamples * frames
    }

    /**
     * Decoder set-up data (what MediaCodec expects as csd-0, csd-1 and csd-2): an OpusHead for mono
     * 48 kHz with no pre-skip, so every decoded packet maps exactly onto the frames it was made
     * from (the encoder's look-ahead then simply delays the audio by 6.5 ms), a codec delay of zero
     * and the usual 80 ms seek pre-roll.
     */
    fun decoderConfig(): List<ByteArray> {
        val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusHead".toByteArray(Charsets.US_ASCII))
            .put(1) // version
            .put(1) // channels
            .putShort(0) // pre-skip
            .putInt(SAMPLE_RATE)
            .putShort(0) // output gain
            .put(0) // mapping family
            .array()
        fun nanos(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
        return listOf(head, nanos(0), nanos(80_000_000))
    }
}

/**
 * Labels a decoder's output with the frames of the packet it came from. Some platform decoders do
 * not carry input timestamps across a jump in them (a pause between push-to-talk spurts, or a new
 * talker in a group): their output timestamps keep counting on from before, which put frames
 * seconds away from where they belong (seen on the A03s in Phase 7). A decoder emits packets in
 * the order they went in, so outputs are matched to queued packets in order. An output timestamp
 * that equals a queued packet's first frame is still trusted, and packets queued before it are
 * taken to have produced nothing. Thread-safe.
 */
class DecodeOrder(private val maxPending: Int = 200) {
    private class Entry(val first: Long, val frames: Int, val queuedNanos: Long) {
        var done = 0
    }

    private val pending = ArrayDeque<Entry>()

    /** Outputs whose timestamp did not match the packet they came from. */
    var mismatches = 0L
        private set

    @Synchronized
    fun queued(firstIndex: Long, frames: Int, nanos: Long) {
        pending.addLast(Entry(firstIndex, frames.coerceAtLeast(1), nanos))
        while (pending.size > maxPending) pending.removeFirst()
    }

    /** The packet last queued did not reach the decoder after all. */
    @Synchronized
    fun notQueued(firstIndex: Long) {
        if (pending.lastOrNull()?.first == firstIndex) pending.removeLast()
    }

    /**
     * An output of [frames] frames whose timestamp says it starts at [reportedIndex]. Returns its
     * real first frame index, and when its packet was queued (on that packet's first output only).
     */
    @Synchronized
    fun output(reportedIndex: Long, frames: Int): Pair<Long, Long?> {
        val head = pending.firstOrNull() ?: return reportedIndex to null
        val entry = if (head.done > 0 && reportedIndex == head.first + head.done) {
            head // the rest of a packet the decoder split
        } else {
            val exact = pending.indexOfFirst { it.done == 0 && it.first == reportedIndex }
            if (exact >= 0) {
                repeat(exact) { pending.removeFirst() }
            } else {
                mismatches++
            }
            pending.first()
        }
        val start = entry.first + entry.done
        val nanos = if (entry.done == 0) entry.queuedNanos else null
        entry.done += frames
        if (entry.done >= entry.frames) pending.removeFirst()
        return start to nanos
    }
}
