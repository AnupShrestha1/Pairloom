package com.anupshrestha.pairloom.media

import com.anupshrestha.pairloom.net.ControlProtocol
import com.anupshrestha.pairloom.net.ControlProtocol.LeaveReason
import com.anupshrestha.pairloom.net.ControlProtocol.LinkQuality
import com.anupshrestha.pairloom.net.ControlProtocol.Message
import com.anupshrestha.pairloom.net.ControlProtocol.RosterEntry
import com.anupshrestha.pairloom.security.hexToBytesOrNull
import com.anupshrestha.pairloom.security.toHex
import java.io.Closeable
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private fun directionKey(keyHex: String, saltHex: String): DirectionKey? {
    val k = hexToBytesOrNull(keyHex, DirectionKey.KEY_BYTES) ?: return null
    val s = hexToBytesOrNull(saltHex, DirectionKey.SALT_BYTES) ?: return null
    return DirectionKey(k, s)
}

private fun serialExecutor(name: String): ExecutorService = Executors.newSingleThreadExecutor { Thread(it, name).apply { isDaemon = true } }

/**
 * The hosting phone's side of group push-to-talk (Phase 7). Every phone on the link is [link]ed
 * with a way to send it control messages, and each one joins or leaves the group talk on its own
 * (GROUP_JOIN / GROUP_LEAVE). The first join starts a [GroupHub] on [localAddress] and fixes the
 * group's codec (Opus if the host and that member both have it, otherwise PCM); a later member
 * that cannot use it is refused. The hub stops when the last member leaves.
 *
 * Every phone on the link gets the roster (who is linked, who is in the talk, who hosts, how well
 * the hub hears each member), and the members in the talk get FLOOR whenever it changes. A phone
 * whose link dropped stays in the roster as out of range for [departedKeepNanos], until it comes
 * back. Messages go out in order on one thread, so this class
 * never does network I/O while holding its lock, and the hub's floor callback never waits for it.
 */
class GroupHost(
    private val localAddress: InetAddress,
    /** Codecs this phone can use for voice now, as an [AudioCodec] bit mask (PCM is always added). */
    private val localCodecs: () -> Int = { AudioCodec.PCM.bit },
    private val threadSetup: () -> Unit = {},
    private val log: (String) -> Unit = {},
    /** The roster or the floor changed (any thread). */
    private val onChanged: () -> Unit = {},
    private val random: SecureRandom = SecureRandom(),
    /** How often link quality is measured; 0 for never (tests call [refreshLinks]). */
    qualityEveryMs: Long = 1_000,
    private val departedKeepNanos: Long = 10 * 60 * 1_000_000_000L,
) : Closeable {
    private class Linked(
        val id: Int,
        val name: String,
        val address: InetAddress,
        /** Who the phone is (its key), so a phone that comes back replaces its out-of-range entry. */
        val key: String,
        val isHost: Boolean,
        val send: (Message) -> Unit,
    ) {
        var inTalk = false
        var quality = LinkQuality.UNKNOWN

        /** (time, packets received by the hub) samples over the last [QUALITY_WINDOW_NANOS]. */
        val samples = ArrayDeque<Pair<Long, Long>>()
    }

    /** A phone whose link dropped: shown as out of range until it comes back or [departedKeepNanos] pass. */
    private class Departed(val id: Int, val name: String, val since: Long)

    private val departed = LinkedHashMap<String, Departed>()
    private val ticker = if (qualityEveryMs > 0) {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { Thread(it, "pl-group-tick").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({ runCatching { refreshLinks() } }, qualityEveryMs, qualityEveryMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    } else {
        null
    }

    private val lock = Any()
    private val linked = LinkedHashMap<Int, Linked>()
    private var nextId = 0
    private var hub: GroupHub? = null
    private var codec: AudioCodec? = null
    private val outbox = serialExecutor("pl-group-out")

    /** Who has the floor (a member id), or null. */
    @Volatile var floorHolder: Int? = null
        private set

    val roster: List<RosterEntry> get() = synchronized(lock) { rosterLocked() }

    /** The hub's per-member statistics, by member id (empty while nobody is in the talk). */
    fun hubStats(): Map<Int, MemberStats> = synchronized(lock) { hub?.stats()?.mapKeys { it.key.toInt() } ?: emptyMap() }

    /**
     * A phone on the link; returns its member id, or null if the roster is full. [key] says who it
     * is (a phone that comes back after dropping out has the same key); [isHost] marks the hosting
     * phone's own user.
     */
    fun link(name: String, address: InetAddress, key: String = "", isHost: Boolean = false, send: (Message) -> Unit): Int? = synchronized(lock) {
        if (key.isNotEmpty() && departed.remove(key) != null) log("Group: $name is back")
        if (linked.size >= ControlProtocol.MAX_ROSTER) return null
        val id = nextId++
        linked[id] = Linked(id, name, address, key, isHost, send)
        log("Group: $name linked as member $id")
        broadcastRosterLocked()
        id
    }

    /** The phone's link ended; with [dropped], it went without a goodbye and is shown as out of range. */
    fun unlink(id: Int, dropped: Boolean = false) = synchronized(lock) {
        val m = linked.remove(id) ?: return
        if (m.inTalk) leaveLocked(m)
        if (dropped && m.key.isNotEmpty()) departed[m.key] = Departed(nextId++, m.name, System.nanoTime())
        log("Group: ${m.name} (member $id) ${if (dropped) "dropped out" else "unlinked"}")
        broadcastRosterLocked()
    }

    /**
     * Measures how well the hub hears each member in the talk (from the packets it received over
     * the last two seconds: probes alone bring about ten a second) and forgets phones that have
     * been out of range too long. Sends the roster again if anything changed.
     */
    fun refreshLinks(now: Long = System.nanoTime()) = synchronized(lock) {
        var changed = false
        val stats = hub?.stats()
        for (m in linked.values) {
            val q = if (!m.inTalk || stats == null) {
                m.samples.clear()
                LinkQuality.UNKNOWN
            } else {
                val received = stats[m.id.toString()]?.received ?: 0
                m.samples.addLast(now to received)
                while (m.samples.size > 1 && now - m.samples.first().first > QUALITY_WINDOW_NANOS) m.samples.removeFirst()
                val (t0, r0) = m.samples.first()
                if (now - t0 < QUALITY_WINDOW_NANOS / 2) m.quality else linkQuality((received - r0) * 1e9 / (now - t0))
            }
            if (q != m.quality) {
                m.quality = q
                changed = true
            }
        }
        if (departed.values.removeAll { now - it.since > departedKeepNanos }) changed = true
        if (changed) broadcastRosterLocked()
    }

    /** A group-talk message from member [id] (any thread). */
    fun onMessage(id: Int, message: Message) = synchronized(lock) {
        val m = linked[id] ?: return
        when (message) {
            is Message.GroupJoin -> join(m, message)
            is Message.GroupLeave -> if (m.inTalk) {
                leaveLocked(m)
                broadcastRosterLocked()
            }
            else -> Unit
        }
    }

    private fun join(m: Linked, j: Message.GroupJoin) {
        val fromMember = directionKey(j.keyHex, j.saltHex) ?: return log("Group: ignored a join with a malformed key from ${m.name}")
        if (m.inTalk) leaveLocked(m) // joining again: start over with the new keys
        val running = hub ?: run {
            val c = AudioCodec.choose(j.codecs, localCodecs() or AudioCodec.PCM.bit)
            val h = try {
                GroupHub(
                    DatagramSocket(InetSocketAddress(localAddress, 0)),
                    threadSetup = threadSetup,
                    log = log,
                    onFloor = { holder -> onFloor(holder?.toIntOrNull()) },
                )
            } catch (e: Exception) {
                log("Group: could not open the hub: ${e.message}")
                post(m, Message.GroupLeave(LeaveReason.REFUSED))
                return
            }
            h.start()
            hub = h
            codec = c
            log("Group talk started with $c")
            h
        }
        val c = codec ?: AudioCodec.PCM
        if (c.bit and j.codecs == 0) {
            log("Group: ${m.name} cannot use $c; not admitted")
            post(m, Message.GroupLeave(LeaveReason.REFUSED))
            stopHubIfEmpty()
            return
        }
        val toMember = DirectionKey.random(random)
        running.addMember(m.id.toString(), InetSocketAddress(m.address, j.port), fromMember = fromMember, toMember = toMember)
        m.inTalk = true
        post(m, Message.GroupWelcome(c.code, m.id, running.port, toMember.key.toHex(), toMember.salt.toHex()))
        post(m, Message.Floor(floorHolder))
        broadcastRosterLocked()
    }

    private fun leaveLocked(m: Linked) {
        m.inTalk = false
        hub?.removeMember(m.id.toString())
        stopHubIfEmpty()
    }

    private fun stopHubIfEmpty() {
        if (linked.values.any { it.inTalk }) return
        val h = hub ?: return
        hub = null
        codec = null
        h.stop()
        floorHolder = null
        log("Group talk ended: nobody is in it")
    }

    // Hub thread, with the hub's lock held: only hand the news on.
    private fun onFloor(holder: Int?) {
        floorHolder = holder
        outbox.execute {
            val members = synchronized(lock) { linked.values.filter { it.inTalk } }
            members.forEach { sendQuietly(it, Message.Floor(holder)) }
        }
        onChanged()
    }

    private fun rosterLocked(): List<RosterEntry> {
        val here = linked.values.map { RosterEntry(it.id, it.name, it.inTalk, it.isHost, it.quality) }
        val gone = departed.values.map { RosterEntry(it.id, it.name, inTalk = false, quality = LinkQuality.LOST) }
        return (here + gone).take(ControlProtocol.MAX_ROSTER)
    }

    private fun broadcastRosterLocked() {
        val roster = rosterLocked()
        linked.values.forEach { post(it, Message.GroupRoster(it.id, roster)) }
        onChanged()
    }

    private fun post(to: Linked, message: Message) {
        runCatching { outbox.execute { sendQuietly(to, message) } }
    }

    private fun sendQuietly(to: Linked, message: Message) {
        runCatching { to.send(message) }.onFailure { log("Group: could not send to ${to.name}: ${it.message}") }
    }

    override fun close() {
        ticker?.shutdownNow()
        synchronized(lock) {
            linked.values.forEach { it.inTalk = false }
            departed.clear()
            hub?.stop()
            hub = null
            codec = null
            floorHolder = null
            linked.clear()
        }
        outbox.shutdown()
    }

    companion object {
        const val QUALITY_WINDOW_NANOS = 2_000_000_000L

        /** Packets a second the hub receives from a member (about ten when the link is fine). */
        fun linkQuality(packetsPerSecond: Double): Int = when {
            packetsPerSecond >= 7.0 -> LinkQuality.GOOD
            packetsPerSecond >= 3.0 -> LinkQuality.FAIR
            packetsPerSecond > 0.0 -> LinkQuality.WEAK
            else -> LinkQuality.LOST
        }
    }
}

/**
 * One user's side of group push-to-talk: joins the host's group talk with a UDP socket on
 * [localAddress] and a fresh key, and on GROUP_WELCOME runs an ordinary push-to-talk
 * [MediaSession] towards the hub. Keeps the roster and the floor holder the host sends.
 * Synchronized; [join] and [leave] may block on network I/O, so they run off the main thread.
 */
class GroupMemberLink(
    private val localAddress: InetAddress,
    private val hostAddress: InetAddress,
    private val send: (Message) -> Unit,
    /** Opens the push-to-talk session (microphone and speaker on a phone; anything in tests). */
    private val newSession: (AudioCodec, DatagramSocket, InetSocketAddress, DirectionKey, DirectionKey) -> MediaSession =
        { c, s, p, out, `in` -> MediaSession(s, p, out, `in`, pushToTalk = true, codecs = if (c == AudioCodec.PCM) CodecPair.PCM else error("no $c here")) },
    private val localCodecs: () -> Int = { AudioCodec.PCM.bit },
    private val log: (String) -> Unit = {},
    /** The talk ended for this member (with the lock held: keep it short). Not on [close]. */
    private val onEnded: (MediaEnd) -> Unit = {},
    private val random: SecureRandom = SecureRandom(),
) : Closeable {
    private class Pending(val socket: DatagramSocket, val key: DirectionKey, val codecs: Int)

    private var pending: Pending? = null

    @Volatile var session: MediaSession? = null
        private set

    /** This phone's id in the roster (from the host's first roster). */
    @Volatile var ownId: Int? = null
        private set

    @Volatile var codec: AudioCodec? = null
        private set

    @Volatile var roster: List<RosterEntry> = emptyList()
        private set

    @Volatile var floorHolder: Int? = null
        private set

    @Volatile private var joining = false

    val isJoining: Boolean get() = joining

    /** From [join] until the talk has ended. */
    val isBusy: Boolean get() = joining || session != null

    /** Push-to-talk: the user holds the talk button. */
    var talking: Boolean
        get() = session?.talking == true
        set(value) {
            session?.talking = value
        }

    @Synchronized
    fun join() {
        if (isBusy) return
        val socket = DatagramSocket(InetSocketAddress(localAddress, 0))
        val key = DirectionKey.random(random)
        val codecs = localCodecs() or AudioCodec.PCM.bit
        pending = Pending(socket, key, codecs)
        joining = true
        log("Group: joining the talk from port ${socket.localPort}")
        try {
            send(Message.GroupJoin(codecs, socket.localPort, key.key.toHex(), key.salt.toHex()))
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    /** The user leaves the talk (or stops joining); tells the host. */
    @Synchronized
    fun leave(reason: MediaEnd = MediaEnd.ENDED_HERE) {
        if (!isBusy) return
        close()
        runCatching { send(Message.GroupLeave(LeaveReason.LEFT)) }
        onEnded(reason)
    }

    /** A group-talk message from the host. */
    @Synchronized
    fun onMessage(message: Message) {
        when (message) {
            is Message.GroupWelcome -> onWelcome(message)
            is Message.GroupLeave -> if (isBusy) {
                log("Group: the host ${if (message.reason == LeaveReason.REFUSED) "did not admit this phone" else "ended the talk"}")
                close()
                onEnded(if (message.reason == LeaveReason.REFUSED) MediaEnd.FAILED else MediaEnd.ENDED_BY_PEER)
            }
            is Message.GroupRoster -> {
                roster = message.members
                ownId = message.yourId
            }
            is Message.Floor -> if (session != null) floorHolder = message.holderId
            else -> Unit
        }
    }

    private fun onWelcome(w: Message.GroupWelcome) {
        val p = pending ?: return log("Group: ignored a welcome without a join")
        val hubKey = directionKey(w.keyHex, w.saltHex)
        val c = AudioCodec.fromCode(w.codec)?.takeIf { it.bit and p.codecs != 0 }
        if (hubKey == null || c == null) {
            log("Group: the host's welcome is not usable (codec ${w.codec})")
            fail()
            return
        }
        pending = null
        val s = try {
            newSession(c, p.socket, InetSocketAddress(hostAddress, w.port), p.key, hubKey)
        } catch (e: Exception) {
            p.socket.close()
            log("Group: could not start ($c): ${e.message}")
            fail()
            return
        }
        session = s
        if (ownId != w.memberId) log("Group: the welcome says member ${w.memberId}, the roster said $ownId")
        ownId = w.memberId
        codec = c
        joining = false
        log("Group: in the talk as member ${w.memberId} with $c")
        s.start()
    }

    private fun fail() {
        close()
        runCatching { send(Message.GroupLeave(LeaveReason.LEFT)) }
        onEnded(MediaEnd.FAILED)
    }

    /** Ends the talk without telling the host (link teardown). */
    @Synchronized
    override fun close() {
        pending?.socket?.close()
        pending = null
        joining = false
        session?.stop()
        session = null
        codec = null
        floorHolder = null
    }
}

/**
 * A member on the hosting phone itself (its own user, or a simulated phone), linked to the
 * [GroupHost] without a network control channel. Each direction is delivered in order on its own
 * thread, so neither side ever calls the other while holding its own lock.
 */
class InProcessLink(
    private val host: GroupHost,
    name: String,
    address: InetAddress,
    isHost: Boolean = false,
    makeMember: (send: (Message) -> Unit) -> GroupMemberLink,
) : Closeable {
    private val up = serialExecutor("pl-group-up")
    private val down = serialExecutor("pl-group-down")

    @Volatile private var id = -1

    val member: GroupMemberLink = makeMember { message -> up.execute { host.onMessage(id, message) } }

    init {
        id = host.link(name, address, isHost = isHost) { message -> runCatching { down.execute { member.onMessage(message) } } }
            ?: throw IllegalStateException("the group is full")
    }

    val memberId: Int get() = id

    override fun close() {
        member.close()
        host.unlink(id)
        up.shutdown()
        down.shutdown()
    }
}
