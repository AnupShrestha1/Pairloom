package com.anupshrestha.pairloom.media

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

private const val MIME = MediaFormat.MIMETYPE_AUDIO_OPUS
private const val US_PER_FRAME = 10_000L

/** Whether this phone has both an Opus encoder and decoder (the platform encoder came with Android 10). */
object OpusSupport {
    fun available(): Boolean = runCatching {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val format = MediaFormat.createAudioFormat(MIME, Opus.SAMPLE_RATE, 1)
        list.findEncoderForFormat(format) != null && list.findDecoderForFormat(format) != null
    }.getOrDefault(false)

    fun pair(log: (String) -> Unit): CodecPair =
        CodecPair(AudioCodec.OPUS, { OpusEncoder(it, log = log) }, { OpusDecoder(it, log) })
}

/** Time from queuing input until the matching output arrived, for the log. */
private class Turnaround {
    private var total = 0L
    private var count = 0
    private var max = 0L

    @Synchronized fun add(nanos: Long) { total += nanos; count++; if (nanos > max) max = nanos }

    @Synchronized override fun toString() = if (count == 0) "none" else
        String.format(java.util.Locale.US, "avg %.1f ms, max %.1f ms over %d", total / 1e6 / count, max / 1e6, count)
}

/**
 * A MediaCodec in asynchronous mode. The platform software codecs run in another process and take
 * a few milliseconds per packet, sometimes more than 10; waiting for them on the media threads
 * made packets miss their slot. Here outputs are delivered on the codec's own thread the moment
 * they are ready. Free input buffers are queued as the codec offers them.
 */
private abstract class AsyncCodec(name: String, private val log: (String) -> Unit) {
    private val thread = HandlerThread(name, Process.THREAD_PRIORITY_URGENT_AUDIO).apply { start() }
    abstract val codec: MediaCodec
    private val freeInputs = ArrayBlockingQueue<Int>(64)
    @Volatile private var open = true
    private var droppedInputs = 0

    fun startWith(format: MediaFormat, flags: Int) {
        // Realtime priority, and for the decoder low-latency mode: without it the platform decoder
        // was seen holding output for hundreds of milliseconds.
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        if (flags and MediaCodec.CONFIGURE_FLAG_ENCODE == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(c: MediaCodec, index: Int) { freeInputs.offer(index) }

            override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (!open) return
                val bytes = ByteArray(info.size)
                runCatching { c.getOutputBuffer(index)?.apply { position(info.offset); get(bytes) } }
                runCatching { c.releaseOutputBuffer(index, false) }
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 || bytes.isEmpty()) return
                runCatching { onOutput(bytes, info.presentationTimeUs) }.onFailure { log("${codec.name} output failed: ${it.message}") }
            }

            override fun onError(c: MediaCodec, e: MediaCodec.CodecException) { log("${codec.name} error: ${e.diagnosticInfo}") }

            override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) = Unit
        }, Handler(thread.looper))
        try {
            codec.configure(format, null, null, flags)
            codec.start()
        } catch (e: Exception) {
            release()
            throw e
        }
    }

    /** Queues [data]; returns false (and drops it) if the codec has no free input buffer in time. */
    fun queue(data: ByteArray, ptsUs: Long): Boolean {
        val i = freeInputs.poll(5, TimeUnit.MILLISECONDS)
        if (i == null) {
            if (droppedInputs++ % 100 == 0) log("${codec.name} busy; dropped inputs: $droppedInputs")
            return false
        }
        codec.getInputBuffer(i)!!.apply { clear(); put(data) }
        codec.queueInputBuffer(i, 0, data.size, ptsUs, 0)
        return true
    }

    /** Called on the codec thread for every non-config output. */
    protected abstract fun onOutput(bytes: ByteArray, ptsUs: Long)

    fun release() {
        open = false
        runCatching { codec.stop() }
        runCatching { codec.release() }
        thread.quitSafely()
    }
}

/**
 * Opus encoder: 10 ms PCM frames go in on the send thread; the platform encoder makes 20 ms
 * packets, delivered on the codec thread. The frames fed are remembered in order, so each packet
 * is labelled with the index of its first frame without relying on the codec's timestamps.
 */
class OpusEncoder(
    private val out: PacketSink,
    bitrate: Int = 32_000,
    private val log: (String) -> Unit = {},
) : AudioEncoder {
    private val pending = ArrayDeque<Pair<Long, Long>>() // (frame index, nanos queued), guarded by itself
    private val turnaround = Turnaround()

    @Volatile override var framesPerPacket = 2
        private set

    // Packet assembly (the first frame of a packet waits for the second) already shows up in the
    // receiver's delay spread and so in its buffer target; only the look-ahead is extra.
    override val delayMs: Double get() = Opus.LOOKAHEAD_MS

    private val impl = object : AsyncCodec("pl-opus-enc", log) {
        override val codec: MediaCodec = MediaCodec.createEncoderByType(MIME)
        override fun onOutput(bytes: ByteArray, ptsUs: Long) = onPacket(bytes)
    }

    init {
        val format = MediaFormat.createAudioFormat(MIME, Opus.SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        }
        impl.startWith(format, MediaCodec.CONFIGURE_FLAG_ENCODE)
        log("Opus encoder: ${impl.codec.name}, $bitrate bit/s")
    }

    override fun encode(index: Long, pcm: ByteArray) {
        synchronized(pending) { pending.addLast(index to System.nanoTime()) }
        if (!impl.queue(pcm, index * US_PER_FRAME)) synchronized(pending) { pending.removeLastOrNull() }
    }

    private fun onPacket(bytes: ByteArray) {
        val frames = ((Opus.packetSamples(bytes) ?: return) / Opus.FRAME_SAMPLES).coerceAtLeast(1)
        if (frames != framesPerPacket) {
            log("Opus encoder makes $frames-frame packets")
            framesPerPacket = frames
        }
        val (first, last) = synchronized(pending) {
            val f = pending.firstOrNull() ?: return
            var l = f
            repeat(frames) { pending.removeFirstOrNull()?.let { l = it } }
            f to l
        }
        turnaround.add(System.nanoTime() - last.second)
        out.packet(first.first, bytes)
    }

    override fun close() {
        impl.release()
        log("Opus encoder closed; last frame in to packet out: $turnaround")
    }
}

/**
 * Opus decoder: packets go in on the receive thread; the decoded PCM, delivered on the codec
 * thread, is split into 10 ms frames indexed from the packet's first frame. Outputs are matched to
 * packets in order ([DecodeOrder]), as not every platform decoder keeps the presentation times.
 */
class OpusDecoder(private val out: PcmFrameSink, private val log: (String) -> Unit = {}) : AudioDecoder {
    private val order = DecodeOrder()
    private val turnaround = Turnaround()
    private var oddOutputs = 0

    private val impl = object : AsyncCodec("pl-opus-dec", log) {
        override val codec: MediaCodec = MediaCodec.createDecoderByType(MIME)
        override fun onOutput(bytes: ByteArray, ptsUs: Long) = onPcm(bytes, ptsUs)
    }

    init {
        val format = MediaFormat.createAudioFormat(MIME, Opus.SAMPLE_RATE, 1)
        Opus.decoderConfig().forEachIndexed { i, b -> format.setByteBuffer("csd-$i", ByteBuffer.wrap(b)) }
        impl.startWith(format, 0)
        log("Opus decoder: ${impl.codec.name}")
    }

    override fun decode(firstIndex: Long, payload: ByteArray) {
        val frames = Opus.packetSamples(payload)?.let { (it + Opus.FRAME_SAMPLES - 1) / Opus.FRAME_SAMPLES } ?: 2
        order.queued(firstIndex, frames, System.nanoTime())
        if (!impl.queue(payload, firstIndex * US_PER_FRAME)) order.notQueued(firstIndex)
    }

    private fun onPcm(pcm: ByteArray, ptsUs: Long) {
        if (pcm.size % Opus.FRAME_BYTES != 0 && oddOutputs++ % 100 == 0) log("Opus decoder: output of ${pcm.size} bytes is not whole 10 ms frames")
        val before = order.mismatches
        val (first, queuedAt) = order.output(ptsUs / US_PER_FRAME, pcm.size / Opus.FRAME_BYTES)
        queuedAt?.let { turnaround.add(System.nanoTime() - it) }
        if (order.mismatches != before && (before == 0L || order.mismatches % 500 == 0L)) {
            log("Opus decoder: output time ${ptsUs / US_PER_FRAME} is not its packet's ($first); matched by order (${order.mismatches} so far)")
        }
        for (k in 0 until pcm.size / Opus.FRAME_BYTES) {
            out.frame(first + k, pcm.copyOfRange(k * Opus.FRAME_BYTES, (k + 1) * Opus.FRAME_BYTES))
        }
    }

    override fun close() {
        impl.release()
        log("Opus decoder closed; packet in to PCM out: $turnaround; outputs matched by order: ${order.mismatches}")
    }
}
