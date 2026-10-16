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
    /** Delay spread (largest minus smallest transit) over the last 2 s; shows bursty delivery. */
    val spreadMs: Double = 0.0,
    val rttLastMs: Double? = null,
    val rttAvgMs: Double? = null,
    val rttMinMs: Double? = null,
    val rttMaxMs: Double? = null,
) {
    /**
     * Rough one-way estimate: half the round trip plus the jitter buffer's target playout delay
     * (no audio I/O included). The target, not the momentary depth, is what playout converges to.
     */
    val estimatedOneWayMs: Double? get() = rttAvgMs?.let { it / 2 + targetMs }

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
 */
class MediaSession(
    private val socket: DatagramSocket,
    private val peer: InetSocketAddress,
    sendKey: DirectionKey,
    receiveKey: DirectionKey,
    private val tone: TestTone = TestTone(),
    private val frameNanos: Long = 10_000_000L,
    private val samplesPerFrame: Int = 480,
    private val probeEvery: Int = 20,
    private val threadSetup: () -> Unit = {},
    private val log: (String) -> Unit = {},
) {
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
        threads += Thread({ threadSetup(); sendLoop() }, "pairloom-media-send")
        threads += Thread({ threadSetup(); receiveLoop() }, "pairloom-media-recv")
        threads += Thread({ threadSetup(); playoutLoop() }, "pairloom-media-play")
        threads.forEach { it.isDaemon = true; it.start() }
        log("Media started: local ${socket.localSocketAddress} -> peer $peer")
    }

    fun stop(): MediaStats {
        running = false
        runCatching { socket.close() }
        threads.forEach { it.join(1_000) }
        val s = stats()
        log("Media stopped: ${describe(s)}")
        return s
    }

    fun stats(): MediaStats = synchronized(lock) {
        val lost = if (firstIndex < 0) 0 else (highestIndex - firstIndex + 1 - audioReceived).coerceAtLeast(0)
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
        )
    }

    private var sequence = 0L

    private fun send(kind: PacketKind, timestamp: Int, payload: ByteArray) {
        val bytes = sealer.seal(MediaHeader(kind, sequence++, timestamp), payload)
        try {
            socket.send(DatagramPacket(bytes, bytes.size, peer))
            sent.incrementAndGet()
        } catch (e: Exception) {
            if (running) log("Media send failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // The send loop is the only user of the sealer and the sequence counter. Probe replies are
    // queued here so that every packet is sealed on this one thread.
    private val pendingReplies = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()

    private fun sendLoop() {
        var index = 0L
        val t0 = System.nanoTime()
        while (running) {
            val due = t0 + index * frameNanos
            val wait = due - System.nanoTime()
            if (wait > 0) LockSupport.parkNanos(wait)
            if (!running) break
            while (true) send(PacketKind.PROBE_REPLY, 0, pendingReplies.poll() ?: break)
            send(PacketKind.AUDIO, (index * samplesPerFrame).toInt(), tone.frame(index))
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
            when (opened.header.kind) {
                PacketKind.AUDIO -> onAudio(opened, now)
                PacketKind.PROBE -> if (opened.payload.size == 8) pendingReplies.add(opened.payload)
                PacketKind.PROBE_REPLY -> if (opened.payload.size == 8) onProbeReply(ByteBuffer.wrap(opened.payload).long, now)
            }
        }
    }

    private fun onAudio(p: OpenedPacket, now: Long) {
        val index = (p.header.timestamp.toLong() and 0xFFFF_FFFFL) / samplesPerFrame
        synchronized(lock) {
            when (buffer.push(index, p.payload, now)) {
                PushResult.ACCEPTED -> {
                    audioReceived++
                    if (firstIndex < 0 || index < firstIndex) firstIndex = index
                    if (index > highestIndex) highestIndex = index
                }
                PushResult.LATE -> {
                    audioReceived++
                    late.incrementAndGet()
                }
                PushResult.DUPLICATE -> duplicates.incrementAndGet()
            }
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
        while (running) {
            val due = t0 + tick * frameNanos
            val wait = due - System.nanoTime()
            if (wait > 0) LockSupport.parkNanos(wait)
            if (!running) break
            tick++
            val out = synchronized(lock) { buffer.pop() }
            when (out) {
                Playout.Buffering -> Unit
                is Playout.Frame -> {
                    played.incrementAndGet()
                    concealer.good(out.payload)
                    if (out.payload.contentEquals(tone.frame(out.index))) intact.incrementAndGet() else corrupted.incrementAndGet()
                }
                is Playout.Missing -> {
                    concealed.incrementAndGet()
                    concealer.conceal(tone.frameBytes)
                }
            }
        }
    }

    companion object {
        fun describe(s: MediaStats): String = String.format(
            java.util.Locale.US,
            "%d s, sent %d, received %d, lost %d (%.2f%%), late %d, dup %d, auth-fail %d, wrong-source %d, played %d (intact %d, corrupted %d), concealed %d, dropped %d, buffer %.0f/%.0f ms, jitter %.1f ms, spread %.0f ms, rtt avg %s min %s max %s ms, est. one-way %s ms",
            s.uptimeMs / 1000, s.sent, s.received, s.lost, s.lossPercent, s.late, s.duplicates, s.authFailures, s.wrongSource,
            s.played, s.intact, s.corrupted, s.concealed, s.droppedForLatency, s.bufferMs, s.targetMs, s.jitterMs, s.spreadMs,
            s.rttAvgMs?.let { "%.1f".format(java.util.Locale.US, it) } ?: "-",
            s.rttMinMs?.let { "%.1f".format(java.util.Locale.US, it) } ?: "-",
            s.rttMaxMs?.let { "%.1f".format(java.util.Locale.US, it) } ?: "-",
            s.estimatedOneWayMs?.let { "%.0f".format(java.util.Locale.US, it) } ?: "-",
        )
    }
}
