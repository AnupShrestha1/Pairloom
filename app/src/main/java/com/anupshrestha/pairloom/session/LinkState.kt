package com.anupshrestha.pairloom.session

/** Which side of the Wi-Fi Direct pairing the user chose on this phone. */
enum class Role { HOST, JOIN }

data class PeerInfo(
    val name: String,
    val address: String,
    val status: Int,
    val isPhone: Boolean,
)

enum class FailureKind {
    WIFI_OFF,
    UNSUPPORTED,
    OPERATION_FAILED,
    CONNECT_TIMEOUT,
    SOCKET_FAILED,
    VERSION_MISMATCH,

    /** Android made the joining phone the owner of its own group instead of joining the host's. */
    GROUP_ROLE_MISMATCH,

    /** The connection could not be proven to run over the Wi-Fi Direct link, so it was refused. */
    LINK_UNVERIFIED,

    /** This phone's Keystore identity could not be created or failed its checks. */
    IDENTITY_UNAVAILABLE,

    /** The mutual TLS handshake failed. */
    SECURE_HANDSHAKE_FAILED,

    /** A paired phone's key or install ID changed (or the peer reports that ours did): hard stop. */
    IDENTITY_CHANGED,

    /** One of the users said the pairing codes do not match. */
    PAIRING_REJECTED,

    /** The pairing codes were not confirmed in time. */
    PAIRING_TIMEOUT,

    /** The pairing exchange itself failed, for example a broken commitment. */
    PAIRING_FAILED,

    /** The paired-device store could not be read or written; sessions are refused. */
    TRUST_STORE_ERROR,
}

enum class DisconnectReason {
    LOCAL,
    PEER_CLOSED,
    LINK_TIMEOUT,
    GROUP_ENDED,
    PROTOCOL_ERROR,
    IO_ERROR,
}

data class PingStats(
    val sent: Int = 0,
    val received: Int = 0,
    val lost: Int = 0,
    val lastRttMs: Double? = null,
    val avgRttMs: Double? = null,
    val minRttMs: Double? = null,
    val maxRttMs: Double? = null,
)

/** What Android reports about the current Wi-Fi Direct group. Deliberately has no passphrase. */
data class GroupSnapshot(
    val networkName: String?,
    val isGroupOwner: Boolean,
    val ownerDeviceAddress: String?,
    val frequencyMhz: Int?,
    val clientCount: Int,
    /** The network interface the group runs on, such as "p2p0". */
    val interfaceName: String? = null,
)

/**
 * Decides when the joining phone may try the control link without Android's "group formed"
 * broadcast. Observed on a Galaxy A03s (Android 13): the phone joins the group at radio level,
 * then the system fails to configure the interface and never sends the broadcast, although the
 * link itself works.
 *
 * Only allowed when this phone is a client (not an owner) of exactly the peer the user chose, and
 * only after that has been continuously true for [graceMs], giving the broadcast a fair chance.
 */
object JoinFallback {
    /** The group owner address Android uses for Wi-Fi Direct groups (seen on both test phones). */
    const val GROUP_OWNER_ADDRESS = "192.168.49.1"
    const val DEFAULT_GRACE_MS = 3_000L

    fun isClientOfPeer(group: GroupSnapshot?, peerAddress: String): Boolean =
        group != null &&
            !group.isGroupOwner &&
            group.ownerDeviceAddress != null &&
            group.ownerDeviceAddress.equals(peerAddress, ignoreCase = true)

    fun shouldUse(
        group: GroupSnapshot?,
        peerAddress: String,
        clientSinceMs: Long?,
        nowMs: Long,
        graceMs: Long = DEFAULT_GRACE_MS,
    ): Boolean = isClientOfPeer(group, peerAddress) && clientSinceMs != null && nowMs - clientSinceMs >= graceMs
}

data class LinkDetails(
    val isGroupOwner: Boolean,
    val ownerAddress: String?,
    val peerName: String,
    /** Time from the connect request (or group creation) until Android reported a formed group. */
    val groupFormedMs: Long,
    /** Time from the formed group until the TCP control link finished its handshake. */
    val socketMs: Long,
    /** True if the link was opened via [JoinFallback] rather than Android's broadcast. */
    val viaFallback: Boolean = false,
    val uptimeMs: Long = 0,
    val stats: PingStats = PingStats(),
    /** Negotiated TLS protocol and cipher suite. */
    val tlsProtocol: String = "",
    val cipherSuite: String = "",
    /** Short form of the peer's key fingerprint. */
    val peerKey: String = "",
    /** True if the phones were paired in this session. */
    val newlyPaired: Boolean = false,
    /** Media (milestone A1 test tone): live statistics while a session runs. */
    val media: com.anupshrestha.pairloom.media.MediaStats? = null,
    val mediaActive: Boolean = false,
    val mediaOffering: Boolean = false,
    /** What the running session carries (tone or voice), if one runs. */
    val mediaKind: com.anupshrestha.pairloom.media.MediaKind? = null,
    /** The other phone is calling (voice or push-to-talk) and waits for the user. */
    val incomingCall: Boolean = false,
    val incomingKind: com.anupshrestha.pairloom.media.MediaKind? = null,
    val muted: Boolean = false,
    val speakerOn: Boolean = false,
    /** Where call audio plays while a call or push-to-talk runs. */
    val audioRoute: com.anupshrestha.pairloom.media.AudioRoute? = null,
    /** How the last call or push-to-talk session ended, until the next one starts. */
    val lastEndKind: com.anupshrestha.pairloom.media.MediaKind? = null,
    val lastEnd: com.anupshrestha.pairloom.media.MediaEnd? = null,
)

/** A paired phone as shown in the list. */
data class PairedDeviceUi(
    val name: String,
    val fingerprintHex: String,
    val shortKey: String,
    val pairedAtMs: Long,
    /** Its group is known, so this phone can join it without the system prompt. */
    val canJoinDirectly: Boolean = false,
)

sealed interface LinkState {
    data object Idle : LinkState
    data class NeedsPermissions(val permanentlyDenied: Boolean) : LinkState
    data object LocationServicesOff : LinkState
    data class Hosting(val groupReady: Boolean, val networkName: String?) : LinkState
    data class Discovering(val peers: List<PeerInfo>, val noneFoundHint: Boolean) : LinkState
    data class Connecting(val peerName: String) : LinkState
    data class OpeningSocket(val isGroupOwner: Boolean) : LinkState

    /** Both phones show [code]; the user must say whether they match. */
    data class Pairing(val peerName: String, val code: String, val waitingForPeer: Boolean) : LinkState
    data class Linked(val details: LinkDetails) : LinkState
    data class Disconnected(val reason: DisconnectReason, val detail: String?) : LinkState
    data class Failed(val kind: FailureKind, val detail: String?) : LinkState
}

/** True while a session is in progress and a new one must not be started. */
val LinkState.isActive: Boolean
    get() = this is LinkState.Hosting ||
        this is LinkState.Discovering ||
        this is LinkState.Connecting ||
        this is LinkState.OpeningSocket ||
        this is LinkState.Pairing ||
        this is LinkState.Linked

data class LinkUiState(
    val link: LinkState = LinkState.Idle,
    val role: Role? = null,
    val locationServicesOn: Boolean = true,
    /** Set when a Wi-Fi Direct step failed or found nothing while Location services are off. */
    val locationHint: Boolean = false,
    /** True while the previous Wi-Fi Direct group is still being torn down. */
    val cleaningUp: Boolean = false,
    val thisDeviceName: String? = null,
    val log: List<String> = emptyList(),
    /** Short form of this phone's key fingerprint, once the identity is loaded. */
    val ownKey: String? = null,
    val pairedDevices: List<PairedDeviceUi> = emptyList(),
    /** The user refused the microphone permission; calls cannot start. */
    val micDenied: Boolean = false,
    /** The paired phone being reconnected to after a dropped link, while that runs. */
    val reconnectingTo: String? = null,
    /** The paired phone a home-screen Connect is waiting for (until the link ends). */
    val waitingFor: String? = null,
    /** Diagnostics: whether this phone offers Opus for voice. */
    val opusEnabled: Boolean = true,
)

/**
 * Round-trip bookkeeping for the ping/pong exchange. Thread-safe because pings are sent from one
 * coroutine and pongs are received on another.
 */
class PingTracker(private val lostAfterNanos: Long = 3_000_000_000L) {
    private val pending = LinkedHashMap<Int, Long>()
    private var sent = 0
    private var received = 0
    private var lost = 0
    private var last: Double? = null
    private var sum = 0.0
    private var min: Double? = null
    private var max: Double? = null

    @Synchronized
    fun onPingSent(seq: Int, nowNanos: Long) {
        pending[seq] = nowNanos
        sent++
    }

    /** Returns false for a pong that matches no outstanding ping (late, duplicate or unknown). */
    @Synchronized
    fun onPong(seq: Int, nowNanos: Long): Boolean {
        val sentAt = pending.remove(seq) ?: return false
        val rttMs = (nowNanos - sentAt) / 1_000_000.0
        received++
        last = rttMs
        sum += rttMs
        min = minOf(min ?: rttMs, rttMs)
        max = maxOf(max ?: rttMs, rttMs)
        return true
    }

    /** Counts pings that have gone unanswered for too long as lost. */
    @Synchronized
    fun expire(nowNanos: Long) {
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowNanos - entry.value > lostAfterNanos) {
                iterator.remove()
                lost++
            }
        }
    }

    @Synchronized
    fun snapshot(): PingStats = PingStats(
        sent = sent,
        received = received,
        lost = lost,
        lastRttMs = last,
        avgRttMs = if (received > 0) sum / received else null,
        minRttMs = min,
        maxRttMs = max,
    )
}
