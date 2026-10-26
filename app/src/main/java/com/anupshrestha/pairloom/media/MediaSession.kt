package com.anupshrestha.pairloom.media

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/** Counters and gauges for one media session, as seen by this phone. */
data class MediaStats(
    val uptimeMs: Long = 0,
    val sent: Long = 0,
    val received: Long = 0,
    /** Audio frames that never arrived (gaps in the frame index). */
    val lost: Long = 0,
    val late: Long = 0,
    val duplicates: Long = 0,
    val authFailures: Long = 0,
    val wrongSource: Long = 0,
    val played: Long = 0,
    val concealed: Long = 0,
    /** Played frames that were bit-identical to the expected test tone. */
    val intact: Long = 0,
    val corrupted: Long = 0,
    val droppedForLatency: Long = 0,
    val bufferMs: Double = 0.0,
    val targetMs: Double = 0.0,
    val jitterMs: Double = 0.0,
    /** Delay spread (97th percentile minus smallest transit) over the last 2 s; shows bursty delivery. */
    val spreadMs: Double = 0.0,
    val rttLastMs: Double? = null,
    val rttAvgMs: Double? = null,
    val rttMinMs: Double? = null,
    val rttMaxMs: Double? = null,
    /** Measured output delay of the speaker (AudioTrack timestamps); null for the tone or if unknown. */
    val outputLatencyMs: Double? = null,
    /** Push-to-talk: this phone is sending (button held, or the short hangover after release). */
    val talking: Boolean = false,
    /** Push-to-talk: the other phone's voice is being played right now. */
    val peerTalking: Boolean = false,
    /** Push-to-talk: talk spurts received so far. */
    val spurts: Long = 0,
    val codec: AudioCodec = AudioCodec.PCM,
    /** Delay the codec adds on the sending phone that the buffer does not measure (Opus look-ahead). */
    val codecDelayMs: Double = 0.0,
    /** Average time from a packet's arrival until its decoded frames were ready (0 for PCM). */
    val decodeDelayMs: Double = 0.0,
    /**
     * Time since the last authenticated packet of any kind (probes arrive every 200 ms even in
     * push-to-talk silence), or since the start if none came yet.
     */
    val silentMs: Long = 0,
) {
    /** Nothing has arrived for a second: the link is probably failing. */
    val signalLost: Boolean get() = silentMs >= SIGNAL_LOST_MS

    companion object {
        const val SIGNAL_LOST_MS = 1_000L
    }

    /**
     * One-way estimate: half the round trip, the codec's look-ahead and decoding time, the jitter
     * buffer's target playout delay (what playout converges to) and, for calls, the measured speaker delay plus one 10 ms microphone frame.
     * The microphone's own hardware delay is not included.
     */
    val estimatedOneWayMs: Double? get() = rttAvgMs?.let { it / 2 + targetMs + codecDelayMs + decodeDelayMs + (outputLatencyMs?.let { o -> o + 10.0 } ?: 0.0) }

    val lossPercent: Double
        get() = if (received + lost == 0L) 0.0 else 100.0 * lost / (received + lost)
}

/**
 * One media session over UDP: sends a frame every 10 ms (the test tone in milestone A1) plus a
 * round-trip probe every [probeEvery] frames, receives and authenticates the peer's packets, and
 * plays them out of a [JitterBuffer] every frame period. Packets from anywhere but [peer] are
 * dropped before decryption.
 *
 * Three threads (send, receive, playout); [stop] closes the socket and joins them.
 *
 * With [pushToTalk], the source is still read every frame (the microphone keeps its clock and its
 * echo canceller keeps adapting), but audio is sent only while [talking] is set and for
 * [hangoverFrames] after it is cleared, so the end of the last word is not cut. When a spurt ends,
 * a TALK_END packet (sent three times) tells the peer the index of its last frame. Probes are sent
 * throughout, so the round trip stays measured and the radio stays awake between spurts.
 */
class MediaSession(
    private val socket: DatagramSocket,
    private val peer: InetSocketAddress,
    sendKey: DirectionKey,
    receiveKey: DirectionKey,
    private val source: FrameSource = ToneSource(),
    private val sink: FrameSink = DiscardSink,
    /** When set, every played frame is checked against this tone (A1 diagnostics); null for voice. */
    private val verify: TestTone? = TestTone(),
    private val frameNanos: Long = 10_000_000L,
    private val samplesPerFrame: Int = 480,
    private val probeEvery: Int = 20,
    private val threadSetup: () -> Unit = {},
    private val log: (String) -> Unit = {},
    /** Called once at the end of [stop], after the source and sink were closed. */
    private val onStopped: () -> Unit = {},
    private val pushToTalk: Boolean = false,
    private val hangoverFrames: Int = 15,
    /** The codec both directions use (the encoder runs on the send thread, the decoder on the receive thread). */
    codecs: CodecPair = CodecPair.PCM,
) {
    private val codec = codecs.codec
    private val encoder: AudioEncoder = codecs.newEncoder { first, payload -> send(PacketKind.AUDIO, (first * samplesPerFrame).toInt(), payload) }
    private val decoder: AudioDecoder = try {
        codecs.newDecoder { index, pcm -> onFrame(index, pcm, System.nanoTime()) }
    } catch (e: Exception) {
        encoder.close()
        throw e
    }

    /** Push-to-talk: the local user holds the talk button. Ignored without [pushToTalk]. */
    @Volatile var talking = false

    @Volatile private var sendingSpurt = false
    @Volatile private var lastPeerFrameNanos = 0L
    @Volatile private var lastRxNanos = 0L

    private val sealer = MediaSealer(sendKey)
    private val opener = MediaOpener(receiveKey)
    private val replay = ReplayWindow()
    private val buffer = JitterBuffer(frameNanos)
    private val concealer = Concealer()
    private val lock = Any()

    @Volatile private var running = false
    private val threads = mutableListOf<Thread>()
    private var startNanos = 0L

    private val sent = AtomicLong()
    private val received = AtomicLong()
    private val late = AtomicLong()
    private val duplicates = AtomicLong()
    private val authFailures = AtomicLong()
    private val wrongSource = AtomicLong()
    private val played = AtomicLong()
    private val concealed = AtomicLong()
    private val intact = AtomicLong()
    private val corrupted = AtomicLong()
    private var firstIndex = -1L
    private var highestIndex = -1L
    private var audioReceived = 0L

    // Loss is counted per talk spurt in push-to-talk, so the silence between spurts is not loss.
    private var lostInEndedSpurts = 0L
    private var spurts = 0L

    private var rttCount = 0
    private var rttSum = 0.0
    private var rttLast: Double? = null
    private var rttMin: Double? = null
    private var rttMax: Double? = null

    fun start() {
        check(!running) { "already started" }
        running = true
        startNanos = System.nanoTime()
        socket.soTimeout = 200
        threads += Thread({ threadSetup(); sendLoop() }, "pl-media-send")
        threads += Thread({ threadSetup(); receiveLoop() }, "pl-media-recv")
        threads += Thread({ threadSetup(); playoutLoop() }, "pl-media-play")
        threads.forEach { it.isDaemon = true; it.start() }
        log("Media started: local ${socket.localSocketAddress} -> peer $peer")
    }

    fun stop(): MediaStats {
        running = false
        runCatching { socket.close() }
        threads.forEach { it.join(1_000) }
        runCatching { encoder.close() }
        runCatching { decoder.close() }
        runCatching { source.close() }
        runCatching { sink.close() }
        runCatching { onStopped() }
        val s = stats()
        log("Media stopped: ${describe(s)}")
        return s
    }

    fun stats(): MediaStats = synchronized(lock) {
        val lost = lostInEndedSpurts + if (firstIndex < 0) 0 else (highestIndex - firstIndex + 1 - audioReceived).coerceAtLeast(0)
        val ms = frameNanos / 1e6
        MediaStats(
            uptimeMs = if (startNanos == 0L) 0 else (System.nanoTime() - startNanos) / 1_000_000,
            sent = sent.get(),
            received = received.get(),
            lost = lost,
            late = late.get(),
            duplicates = duplicates.get(),
            authFailures = authFailures.get(),
            wrongSource = wrongSource.get(),
            played = played.get(),
            concealed = concealed.get(),
            intact = intact.get(),
            corrupted = corrupted.get(),
            droppedForLatency = buffer.dropped,
            bufferMs = buffer.depthFrames * ms,
            targetMs = buffer.targetFrames * ms,
            jitterMs = buffer.jitterNanos / 1e6,
            spreadMs = buffer.spreadNanos / 1e6,
            rttLastMs = rttLast,
            rttAvgMs = if (rttCount == 0) null else rttSum / rttCount,
            rttMinMs = rttMin,
            rttMaxMs = rttMax,
            outputLatencyMs = lastOutputLatencyMs,
            talking = sendingSpurt,
            peerTalking = lastPeerFrameNanos != 0L && System.nanoTime() - lastPeerFrameNanos < PEER_TALKING_NANOS,
            spurts = spurts,
            codec = codec,
            codecDelayMs = encoder.delayMs,
            decodeDelayMs = if (decodeCount == 0) 0.0 else decodeNanos / 1e6 / decodeCount,
            silentMs = if (startNanos == 0L) 0 else (System.nanoTime() - maxOf(lastRxNanos, startNanos)) / 1_000_000,
        )
    }

    private var sequence = 0L

    private val sendLock = Any()

    // Called on the send thread and, for Opus, on the encoder's thread: the sequence number must
    // never repeat under the key, so sealing is serialized.
    private fun send(kind: PacketKind, timestamp: Int, payload: ByteArray) {
        val bytes = synchronized(sendLock) { sealer.seal(MediaHeader(kind, sequence++, timestamp), payload) }
        try {
            socket.send(DatagramPacket(bytes, bytes.size, peer))
            sent.incrementAndGet()
        } catch (e: Exception) {
            if (running) log("Media send failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // Probe replies are queued for the send loop, so the receive thread never sends.
    private val pendingReplies = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()

    /** Last speaker delay measured during playout (the sink cannot report it once closed). */
    @Volatile private var lastOutputLatencyMs: Double? = null

    private var hangover = 0
    private var spurtFrames = 0

    private fun encode(index: Long, frame: ByteArray) {
        try {
            encoder.encode(index, frame)
        } catch (e: Exception) {
            if (running) log("Media encode failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
    private var lastSentIndex = -1L

    // TALK_END waits a few frame periods after the spurt's last frame, so that a codec running on
    // its own thread (Opus) has sent the last packet first.
    private var talkEndIndex = -1L
    private var talkEndDue = Long.MAX_VALUE

    private fun sendTalkEnd() {
        val end = ByteBuffer.allocate(8).putLong(talkEndIndex).array()
        repeat(3) { send(PacketKind.TALK_END, 0, end) }
        log("Media: talk spurt ends at frame $talkEndIndex")
        talkEndDue = Long.MAX_VALUE
    }

    private fun sendLoop() {
        var index = 0L
        val t0 = System.nanoTime()
        while (running) {
            if (!source.paced) {
                val wait = t0 + index * frameNanos - System.nanoTime()
                if (wait > 0) LockSupport.parkNanos(wait)
            }
            val frame = try {
                source.read(index) // blocks for a paced source (the microphone)
            } catch (e: Exception) {
                if (running) log("Media source failed: ${e.javaClass.simpleName}: ${e.message}")
                break
            }
            if (!running) break
            while (true) send(PacketKind.PROBE_REPLY, 0, pendingReplies.poll() ?: break)
            if (!pushToTalk) {
                encode(index, frame)
            } else {
                if (talking) hangover = hangoverFrames
                // A spurt also runs on to a whole packet, so the codec holds no frame of it back.
                if (talking || hangover > 0 || (sendingSpurt && spurtFrames % encoder.framesPerPacket != 0)) {
                    if (!talking && hangover > 0) hangover--
                    if (!sendingSpurt) {
                        if (talkEndDue != Long.MAX_VALUE) sendTalkEnd() // the previous spurt's
                        log("Media: talk spurt starts at frame $index")
                        spurtFrames = 0
                    }
                    sendingSpurt = true
                    encode(index, frame)
                    spurtFrames++
                    lastSentIndex = index
                } else if (sendingSpurt) {
                    sendingSpurt = false
                    talkEndIndex = lastSentIndex
                    talkEndDue = index + TALK_END_DELAY_FRAMES
                }
                if (index >= talkEndDue) sendTalkEnd()
            }
            if (index % probeEvery == 0L) {
                send(PacketKind.PROBE, 0, ByteBuffer.allocate(8).putLong(System.nanoTime()).array())
            }
            index++
        }
    }

    private fun receiveLoop() {
        val buf = ByteArray(2048)
        val packet = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                socket.receive(packet)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: SocketException) {
                break
            }
            val now = System.nanoTime()
            if (packet.address != peer.address || packet.port != peer.port) {
                wrongSource.incrementAndGet()
                continue
            }
            val opened = opener.open(buf, packet.length)
            if (opened == null) {
                authFailures.incrementAndGet()
                continue
            }
            if (!replay.accept(opened.header.sequence)) {
                duplicates.incrementAndGet()
                continue
            }
            received.incrementAndGet()
            lastRxNanos = now
            when (opened.header.kind) {
                PacketKind.AUDIO -> onAudio(opened, now)
                PacketKind.PROBE -> if (opened.payload.size == 8) pendingReplies.add(opened.payload)
                PacketKind.PROBE_REPLY -> if (opened.payload.size == 8) onProbeReply(ByteBuffer.wrap(opened.payload).long, now)
                PacketKind.TALK_END -> if (opened.payload.size == 8) onTalkEnd(ByteBuffer.wrap(opened.payload).long)
            }
        }
    }

    // Decode delay: arrival time of recent packets by first frame index (receive thread writes,
    // decoder output reads), matched when that frame comes out of the decoder.
    private val arrivals = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private var decodeNanos = 0L
    private var decodeCount = 0

    private fun onAudio(p: OpenedPacket, now: Long) {
        val index = (p.header.timestamp.toLong() and 0xFFFF_FFFFL) / samplesPerFrame
        if (arrivals.size > 500) arrivals.clear() // outputs that never came
        arrivals[index] = now
        try {
            decoder.decode(index, p.payload)
        } catch (e: Exception) {
            if (running) log("Media decode failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** One decoded 10 ms frame (receive thread). */
    private fun onFrame(index: Long, payload: ByteArray, now: Long) {
        val arrived = arrivals.remove(index)
        synchronized(lock) {
            if (arrived != null) {
                decodeNanos += now - arrived
                decodeCount++
            }
            if (pushToTalk && index > endedSpurtIndex && (firstIndex < 0 || index > highestIndex + SPURT_GAP_FRAMES)) {
                closeSpurt() // a TALK_END may have been lost
                spurts++
            }
            when (buffer.push(index, payload, now)) {
                PushResult.ACCEPTED -> if (index > endedSpurtIndex) {
                    audioReceived++
                    if (firstIndex < 0 || index < firstIndex) firstIndex = index
                    if (index > highestIndex) highestIndex = index
                }
                PushResult.LATE -> {
                    if (index > endedSpurtIndex) audioReceived++
                    late.incrementAndGet()
                }
                PushResult.DUPLICATE -> duplicates.incrementAndGet()
            }
        }
    }

    /** Last frame of the most recent spurt the peer announced as ended (push-to-talk). */
    private var endedSpurtIndex = -1L

    /** Called with [lock] held: closes the loss count of the current spurt. */
    private fun closeSpurt() {
        if (firstIndex >= 0) lostInEndedSpurts += (highestIndex - firstIndex + 1 - audioReceived).coerceAtLeast(0)
        firstIndex = -1
        highestIndex = -1
        audioReceived = 0
    }

    private fun onTalkEnd(lastIndex: Long) {
        if (!pushToTalk) return
        synchronized(lock) {
            if (lastIndex <= endedSpurtIndex) return // repeated or stale marker
            endedSpurtIndex = lastIndex
            buffer.endSpurt(lastIndex)
            // Frames lost at the end of the spurt count as lost too.
            if (firstIndex >= 0 && lastIndex >= highestIndex) highestIndex = lastIndex
            closeSpurt()
        }
    }

    private fun onProbeReply(sentNanos: Long, now: Long) {
        val rtt = (now - sentNanos) / 1e6
        if (rtt < 0 || rtt > 10_000) return
        synchronized(lock) {
            rttCount++
            rttSum += rtt
            rttLast = rtt
            rttMin = minOf(rttMin ?: rtt, rtt)
            rttMax = maxOf(rttMax ?: rtt, rtt)
        }
    }

    private fun playoutLoop() {
        var tick = 0L
        val t0 = System.nanoTime()
        val silence = ByteArray(source.frameBytes)
        while (running) {
            if (!sink.paced) {
                val wait = t0 + tick * frameNanos - System.nanoTime()
                if (wait > 0) LockSupport.parkNanos(wait)
            }
            if (!running) break
            tick++
            val out = synchronized(lock) { buffer.pop() }
            val frame = when (out) {
                Playout.Buffering -> silence
                is Playout.Frame -> {
                    played.incrementAndGet()
                    lastPeerFrameNanos = System.nanoTime()
                    concealer.good(out.payload)
                    verify?.let { if (out.payload.contentEquals(it.frame(out.index))) intact.incrementAndGet() else corrupted.incrementAndGet() }
                    out.payload
                }
                Playout.Stretch -> {
                    concealed.incrementAndGet()
                    concealer.conceal(source.frameBytes)
                }
                is Playout.Missing -> {
                    concealed.incrementAndGet()
                    concealer.conceal(source.frameBytes)
                }
            }
            try {
                sink.write(frame) // blocks at the device rate for a paced sink (the speaker)
                if (tick % 50 == 0L) runCatching { sink.latencyMs }.getOrNull()?.let { lastOutputLatencyMs = it }
            } catch (e: Exception) {
                if (running) log("Media sink failed: ${e.javaClass.simpleName}: ${e.message}")
                break
            }
        }
    }

    companion object {
        /** In push-to-talk, a jump of more than this many frames starts a new spurt (TALK_END lost). */
        private const val SPURT_GAP_FRAMES = 50
        private const val PEER_TALKING_NANOS = 150_000_000L
        private const val TALK_END_DELAY_FRAMES = 3

        fun describe(s: MediaStats): String = String.format(
            java.util.Locale.US,
            "%s, %d s, sent %d, received %d, lost %d (%.2f%%), late %d, dup %d, auth-fail %d, wrong-source %d, played %d (intact %d, corrupted %d), concealed %d, dropped %d, buffer %.0f/%.0f ms, jitter %.1f ms, spread %.0f ms, rtt avg %s min %s max %s ms, speaker %s ms, decode %.1f ms, est. one-way %s ms",
            s.codec, s.uptimeMs / 1000, s.sent, s.received, s.lost, s.lossPercent, s.late, s.duplicates, s.authFailures, s.wrongSource,
            s.played, s.intact, s.corrupted, s.concealed, s.droppedForLatency, s.bufferMs, s.targetMs, s.jitterMs, s.spreadMs,
            s.rttAvgMs?.let { "%.1f".format(java.util.Locale.US, it) } ?: "-",
            s.rttMinMs?.let { "%.1f".format(java.util.Locale.US, it) } ?: "-",
            s.rttMaxMs?.let { "%.1f".format(java.util.Locale.US, it) } ?: "-",
            s.outputLatencyMs?.let { "%.0f".format(java.util.Locale.US, it) } ?: "-",
            s.decodeDelayMs,
            s.estimatedOneWayMs?.let { "%.0f".format(java.util.Locale.US, it) } ?: "-",
        )
    }
}
