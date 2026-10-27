package com.anupshrestha.pairloom.media

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/**
 * Who may talk in a push-to-talk group: one member at a time. The floor goes to whoever is heard
 * first while it is free, and is given back by that member's TALK_END, after [idleNanos] without
 * their audio (a lost TALK_END, or a phone that went away), or after [maxHoldNanos].
 *
 * A member who starts talking while someone else has the floor stays shut out for the rest of that
 * spurt, even if the floor frees up meanwhile (like a walkie-talkie: press again). So does a member
 * whose floor was taken back for holding it too long. Not thread-safe; the hub calls it under its lock.
 */
class Floor(
    private val idleNanos: Long = 500_000_000L,
    private val maxHoldNanos: Long = 60_000_000_000L,
    private val onChange: (String?) -> Unit = {},
) {
    var holder: String? = null
        private set

    private var since = 0L
    private var lastAudio = 0L
    private val shutOut = mutableSetOf<String>()

    /** Audio from [member] at [now]: true if it may be relayed. */
    fun audio(member: String, now: Long): Boolean {
        if (holder == member) {
            lastAudio = now
            return true
        }
        if (holder != null || member in shutOut) {
            shutOut += member
            return false
        }
        holder = member
        since = now
        lastAudio = now
        onChange(member)
        return true
    }

    /** [member] ended a spurt (their TALK_END, or a gap in their audio that starts a new one). */
    fun spurtEnded(member: String) {
        shutOut -= member
        if (holder == member) free()
    }

    /** A member left the group. */
    fun left(member: String) = spurtEnded(member)

    /** Returns the member whose floor was taken back at [now], if any. */
    fun tick(now: Long): String? {
        val h = holder ?: return null
        val idle = now - lastAudio >= idleNanos
        if (!idle && now - since < maxHoldNanos) return null
        if (!idle) shutOut += h // held too long: shut out until they let go
        free()
        return h
    }

    private fun free() {
        holder = null
        onChange(null)
    }
}

/**
 * Gives listeners one rising frame count across talkers. Each phone numbers frames from its own
 * microphone clock, so without this a new talker's frames could look older than the last talker's.
 * A spurt is placed at the hub's clock ([start]), and always at least [minGap] frames after the
 * previous one, so a listener also sees a new spurt when the previous TALK_END was lost.
 */
class Restamper(private val minGap: Long = 64) {
    private var offset = 0L
    private var lastOut = -1L
    private var previousEnd = -1L

    /** A new spurt whose first frame is [firstIndex] (talker's count), at hub frame [clockIndex]. */
    fun start(firstIndex: Long, clockIndex: Long) {
        previousEnd = lastOut
        val at = maxOf(clockIndex, if (lastOut < 0) 0 else lastOut + minGap)
        offset = at - firstIndex
    }

    /** The listeners' index for the talker's frame [index], or null for a straggler from before this spurt. */
    fun map(index: Long): Long? {
        val out = index + offset
        if (out <= previousEnd || out < 0) return null
        if (out > lastOut) lastOut = out
        return out
    }

    /** The listeners' index of the last frame relayed. */
    val lastIndex: Long get() = lastOut
}

/** What the hub saw from and sent to one member. */
data class MemberStats(
    val received: Long,
    val relayedFrom: Long,
    val sentTo: Long,
    val shutOut: Long,
    val authFailures: Long,
    val duplicates: Long,
    val rttAvgMs: Double?,
)

/**
 * The hosting phone's relay for group push-to-talk (Phase 7). Members run an ordinary
 * push-to-talk [MediaSession] towards the hub's [socket]; the hub opens each packet with that
 * member's key, and relays the audio of whoever has the [Floor] to every other member, sealed again
 * with each listener's own key. Frames are re-stamped ([Restamper]) and never decoded, so the
 * whole group uses one codec.
 *
 * The hub answers members' probes itself (each member measures its own hop) and probes each member
 * every [probeEveryNanos]. The hosting phone's own user joins like any other member, through a
 * [MediaSession] on a loopback socket, so the relay has no special case for it.
 *
 * One thread; [stop] closes the socket and joins it.
 */
class GroupHub(
    private val socket: DatagramSocket,
    private val frameNanos: Long = 10_000_000L,
    private val samplesPerFrame: Int = 480,
    private val probeEveryNanos: Long = 200_000_000L,
    floorIdleNanos: Long = 500_000_000L,
    floorMaxHoldNanos: Long = 60_000_000_000L,
    private val threadSetup: () -> Unit = {},
    private val log: (String) -> Unit = {},
    /** Who has the floor now (null: nobody). Called on the hub thread. */
    private val onFloor: (String?) -> Unit = {},
) {
    private class Member(
        val id: String,
        val address: InetSocketAddress,
        fromMember: DirectionKey,
        toMember: DirectionKey,
    ) {
        val opener = MediaOpener(fromMember)
        val sealer = MediaSealer(toMember)
        val replay = ReplayWindow()
        var sequence = 0L
        var lastAudioIndex = -1L
        var endedAt = -1L
        var received = 0L
        var relayedFrom = 0L
        var sentTo = 0L
        var shutOut = 0L
        var authFailures = 0L
        var duplicates = 0L
        var rttCount = 0
        var rttSum = 0.0
    }

    private val lock = Any()
    private val members = LinkedHashMap<String, Member>()
    private val byAddress = HashMap<InetSocketAddress, Member>()
    private val floor = Floor(floorIdleNanos, floorMaxHoldNanos) { onFloor(it) }
    private val restamper = Restamper()

    /** The member whose audio the current (or last) spurt relays. */
    private var talker: Member? = null
    private var unknownSource = 0L

    @Volatile private var running = false
    private var thread: Thread? = null
    private var startNanos = 0L

    val port: Int get() = socket.localPort

    /** Adds a member who sends from [address] with [fromMember] and hears the group with [toMember]. */
    fun addMember(id: String, address: InetSocketAddress, fromMember: DirectionKey, toMember: DirectionKey) = synchronized(lock) {
        removeLocked(id)
        val m = Member(id, address, fromMember, toMember)
        members[id] = m
        byAddress[address] = m
        log("Group: $id joined from $address (${members.size} members)")
    }

    fun removeMember(id: String) = synchronized(lock) { removeLocked(id) }

    private fun removeLocked(id: String) {
        val m = members.remove(id) ?: return
        byAddress.remove(m.address)
        if (floor.holder == id) endSpurt(m)
        floor.left(id)
        log("Group: $id left (${members.size} members)")
    }

    val holder: String? get() = synchronized(lock) { floor.holder }

    val memberIds: List<String> get() = synchronized(lock) { members.keys.toList() }

    fun stats(): Map<String, MemberStats> = synchronized(lock) {
        members.mapValues { (_, m) ->
            MemberStats(m.received, m.relayedFrom, m.sentTo, m.shutOut, m.authFailures, m.duplicates, if (m.rttCount == 0) null else m.rttSum / m.rttCount)
        }
    }

    fun start() {
        check(!running) { "already started" }
        running = true
        startNanos = System.nanoTime()
        socket.soTimeout = 20
        thread = Thread({ threadSetup(); loop() }, "pl-group-hub").also { it.isDaemon = true; it.start() }
        log("Group hub started on ${socket.localSocketAddress}")
    }

    fun stop() {
        running = false
        runCatching { socket.close() }
        thread?.join(1_000)
        log("Group hub stopped; unknown source $unknownSource, members ${stats()}")
    }

    private fun loop() {
        val buf = ByteArray(2048)
        val packet = DatagramPacket(buf, buf.size)
        var nextProbe = System.nanoTime()
        while (running) {
            var got = true
            try {
                socket.receive(packet)
            } catch (e: SocketTimeoutException) {
                got = false
            } catch (e: SocketException) {
                break
            }
            val now = System.nanoTime()
            synchronized(lock) {
                if (got) handle(InetSocketAddress(packet.address, packet.port), buf, packet.length, now)
                floor.tick(now)?.let { id -> members[id]?.let(::endSpurt) }
                if (now >= nextProbe) {
                    nextProbe = now + probeEveryNanos
                    val probe = ByteBuffer.allocate(8).putLong(now).array()
                    members.values.forEach { send(it, PacketKind.PROBE, 0, probe) }
                }
            }
        }
    }

    private fun handle(from: InetSocketAddress, buf: ByteArray, length: Int, now: Long) {
        val m = byAddress[from]
        if (m == null) {
            unknownSource++
            return
        }
        val opened = m.opener.open(buf, length)
        if (opened == null) {
            m.authFailures++
            return
        }
        if (!m.replay.accept(opened.header.sequence)) {
            m.duplicates++
            return
        }
        m.received++
        val p = opened.payload
        when (opened.header.kind) {
            PacketKind.AUDIO -> onAudio(m, (opened.header.timestamp.toLong() and 0xFFFF_FFFFL) / samplesPerFrame, p, now)
            PacketKind.TALK_END -> if (p.size == 8) onTalkEnd(m, ByteBuffer.wrap(p).long)
            PacketKind.PROBE -> if (p.size == 8) send(m, PacketKind.PROBE_REPLY, 0, p)
            PacketKind.PROBE_REPLY -> if (p.size == 8) {
                val rtt = (now - ByteBuffer.wrap(p).long) / 1e6
                if (rtt in 0.0..10_000.0) {
                    m.rttCount++
                    m.rttSum += rtt
                }
            }
        }
    }

    private fun onAudio(m: Member, index: Long, payload: ByteArray, now: Long) {
        if (index <= m.endedAt) return // a straggler from a spurt that already ended
        // A jump means a new spurt whose TALK_END never came.
        if (m.lastAudioIndex >= 0 && index > m.lastAudioIndex + SPURT_GAP_FRAMES) {
            if (floor.holder == m.id) endSpurt(m)
            floor.spurtEnded(m.id)
        }
        if (index > m.lastAudioIndex) m.lastAudioIndex = index
        val wasHolder = floor.holder == m.id
        if (!floor.audio(m.id, now)) {
            m.shutOut++
            return
        }
        if (!wasHolder) {
            talker = m
            restamper.start(index, (now - startNanos) / frameNanos)
        }
        val out = restamper.map(index) ?: return
        m.relayedFrom++
        relay(m, PacketKind.AUDIO, (out * samplesPerFrame).toInt(), payload)
    }

    private fun onTalkEnd(m: Member, lastIndex: Long) {
        if (lastIndex > m.endedAt) m.endedAt = lastIndex
        m.lastAudioIndex = -1
        if (talker === m) {
            // Repeats of the marker are relayed too (the listeners ignore the extra ones).
            restamper.map(lastIndex)?.let { relay(m, PacketKind.TALK_END, 0, ByteBuffer.allocate(8).putLong(it).array()) }
        }
        floor.spurtEnded(m.id)
    }

    /** The holder's spurt ends without their TALK_END: tell the listeners where it ended. */
    private fun endSpurt(m: Member) {
        if (talker !== m || restamper.lastIndex < 0) return
        relay(m, PacketKind.TALK_END, 0, ByteBuffer.allocate(8).putLong(restamper.lastIndex).array())
        m.lastAudioIndex = -1
    }

    private fun relay(from: Member, kind: PacketKind, timestamp: Int, payload: ByteArray) {
        for (to in members.values) if (to !== from) send(to, kind, timestamp, payload)
    }

    private fun send(to: Member, kind: PacketKind, timestamp: Int, payload: ByteArray) {
        val bytes = to.sealer.seal(MediaHeader(kind, to.sequence++, timestamp), payload)
        try {
            socket.send(DatagramPacket(bytes, bytes.size, to.address))
            if (kind == PacketKind.AUDIO) to.sentTo++
        } catch (e: Exception) {
            if (running) log("Group send to ${to.id} failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    companion object {
        /** Same as the members' sessions: a jump of more than this many frames is a new spurt. */
        private const val SPURT_GAP_FRAMES = 50
    }
}
