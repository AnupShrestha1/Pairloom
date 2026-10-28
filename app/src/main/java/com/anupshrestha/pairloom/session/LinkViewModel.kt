package com.anupshrestha.pairloom.session

import com.anupshrestha.pairloom.p2p.GroupCredentialStore
import com.anupshrestha.pairloom.p2p.GroupCredentials
import android.app.Application
import android.content.Context
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anupshrestha.pairloom.media.CallAudio
import com.anupshrestha.pairloom.media.DirectionKey
import com.anupshrestha.pairloom.media.AudioCodec
import com.anupshrestha.pairloom.media.CodecPair
import com.anupshrestha.pairloom.media.MediaEnd
import com.anupshrestha.pairloom.media.MediaKind
import com.anupshrestha.pairloom.media.OpusSupport
import com.anupshrestha.pairloom.media.MediaNegotiation
import com.anupshrestha.pairloom.media.MicrophoneSource
import com.anupshrestha.pairloom.media.SpeakerSink
import com.anupshrestha.pairloom.media.MediaSession
import com.anupshrestha.pairloom.media.GroupHost
import com.anupshrestha.pairloom.media.GroupMemberLink
import com.anupshrestha.pairloom.media.InProcessLink
import com.anupshrestha.pairloom.debugtools.VirtualPhone
import com.anupshrestha.pairloom.net.ControlChannel
import com.anupshrestha.pairloom.net.ControlProtocol
import com.anupshrestha.pairloom.net.LinkEndpoint
import com.anupshrestha.pairloom.net.LinkUnverifiedException
import com.anupshrestha.pairloom.net.P2pEndpoint
import com.anupshrestha.pairloom.net.SocketRegistry
import com.anupshrestha.pairloom.p2p.P2pEvent
import com.anupshrestha.pairloom.p2p.P2pPermissions
import com.anupshrestha.pairloom.p2p.P2pResult
import com.anupshrestha.pairloom.p2p.WifiDirectController
import com.anupshrestha.pairloom.security.DeviceIdentity
import com.anupshrestha.pairloom.security.Fingerprints
import com.anupshrestha.pairloom.security.Identity
import com.anupshrestha.pairloom.security.InstallId
import com.anupshrestha.pairloom.security.PairedDeviceStore
import com.anupshrestha.pairloom.security.SecureHandshakeException
import com.anupshrestha.pairloom.security.SecureTransport
import com.anupshrestha.pairloom.security.toHex
import com.anupshrestha.pairloom.security.JoinCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import com.anupshrestha.pairloom.LinkService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/**
 * Owns one Wi-Fi Direct session at a time and turns its events into [LinkUiState].
 *
 * Threading: all state touched by this class is only read and written on the main thread, except
 * the volatile [channel] and [sockets] handles. Socket work runs on [Dispatchers.IO], registers
 * every socket it opens in the session's [SocketRegistry] (so [teardown] can close them while a
 * coroutine is blocked on them), and reports back to the main thread.
 *
 * Every asynchronous callback captures the [generation] it was started in and ignores itself if
 * the generation has moved on, so a stale callback can never act on a newer session.
 */
class LinkViewModel(application: Application) : AndroidViewModel(application) {
    private val app: Application = application
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private val _ui = MutableStateFlow(LinkUiState(locationServicesOn = isLocationOn()))
    val ui: StateFlow<LinkUiState> = _ui.asStateFlow()

    /**
     * Whether the activity is on screen: an incoming call alerts with a notification only when it is
     * not. Declared before init, which collects it.
     */
    private val appVisible = MutableStateFlow(true)

    /** Chosen names (this phone's, nicknames for paired phones). Declared before init, which reads it. */
    private val names = NameStore(application)

    // Written on the main thread; generation and linkedAtMs are also read from IO threads.
    @Volatile private var generation = 0

    @Volatile private var linkedAtMs = 0L
    private var controller: WifiDirectController? = null
    private var eventsAccepted = false
    private var groupFormed = false
    private var socketsStarted = false
    private var requestedAtMs = 0L
    private var groupFormedAtMs = 0L

    // Join-side fallback bookkeeping (see JoinFallback).
    private var fallbackUsed = false
    private var targetPeerAddress: String? = null

    /** For a direct join of a paired phone: the group name to expect instead of an owner address. */
    private var targetGroupName: String? = null

    /** Joining with a code: the device address of the phone whose group is being tried. */
    private var codeTargetAddress: String? = null

    /** Key fingerprint of the phone on the current link (for storing its group). */
    @Volatile private var linkedPeerFingerprint: String? = null
    private var clientSinceMs: Long? = null
    private var groupPollJob: Job? = null

    private var socketJob: Job? = null
    private var timeoutJob: Job? = null
    private var discoveryHintJob: Job? = null

    /** The adopted control channel. Only ever set on the main thread by [onLinked]. */
    @Volatile private var channel: ControlChannel? = null

    /** Every socket the current session opened; closed as a whole by [teardown]. */
    @Volatile private var sockets = SocketRegistry()

    /** This phone's identity, install ID and paired phones; loaded once off the main thread. */
    private class Security(
        val identity: Identity,
        val installId: String,
        val store: PairedDeviceStore,
        val groupStore: GroupCredentialStore,
        /** This phone's own group credentials, used when hosting and sent to paired phones. */
        @Volatile var ownGroup: GroupCredentials,
    )

    @Volatile private var security: Security? = null
    private var securityError: String? = null

    /** The pairing prompt waiting for the user's answer. Main thread only. */
    private var pendingPairing: CompletableDeferred<Boolean>? = null

    init {
        // The saved look at once (a small preferences read), so a chosen theme does not flash.
        _ui.update { it.copy(appearance = names.appearance, ownName = names.ownName) }
        // The foreground service and its notification follow the link (main thread).
        viewModelScope.launch {
            combine(ui, appVisible) { state, visible -> linkNotice(state, visible) }
                .distinctUntilChanged()
                .collect { LinkService.sync(app, it) }
        }
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = app.noBackupFilesDir
                    val groupStore = GroupCredentialStore(File(dir, "group-credentials"))
                    Security(
                        identity = DeviceIdentity.getOrCreate(),
                        installId = InstallId.getOrCreate(File(dir, "install-id")),
                        store = PairedDeviceStore(File(dir, "paired-devices")),
                        groupStore = groupStore,
                        ownGroup = groupStore.get(),
                    )
                }
            }
            loaded.onSuccess {
                security = it
                _ui.update { s -> s.copy(ownKey = Fingerprints.short(it.identity.fingerprintHex), ownName = names.ownName, appearance = names.appearance) }
                log("Identity ready: key ${Fingerprints.short(it.identity.fingerprintHex)}")
                refreshPaired()
            }.onFailure {
                securityError = it.message ?: it.javaClass.simpleName
                log("Identity unavailable: $securityError")
            }
        }
    }

    // ---- Entry points called by the UI -------------------------------------------------------

    /** Starts a session in [role]. Ignored while a session is active or still cleaning up. */
    fun start(role: Role) {
        // A paired session (Host, Join, Connect) is not a code group.
        if (!_ui.value.link.isActive && !_ui.value.cleaningUp && codeGroup != null) setCodeGroup(null)
        dismissCodeProblem()
        startSession(role)
    }

    private fun startSession(role: Role) {
        val state = _ui.value
        if (state.link.isActive || state.cleaningUp) {
            log("Ignored start request: a session is already active or being cleaned up")
            return
        }
        if (security == null) {
            setLink(LinkState.Failed(FailureKind.IDENTITY_UNAVAILABLE, securityError ?: "the identity is still loading"))
            return
        }
        if (P2pPermissions.missing(app).isNotEmpty()) {
            setLink(LinkState.NeedsPermissions(permanentlyDenied = false))
            return
        }
        refreshLocation()
        if (P2pPermissions.locationServicesRequired() && !_ui.value.locationServicesOn) {
            log("Location services are off; Android ${Build.VERSION.SDK_INT} needs them for Wi-Fi Direct discovery")
            setLink(LinkState.LocationServicesOff)
            return
        }
        begin(role)
    }

    fun onPermissionsDenied(permanentlyDenied: Boolean) {
        log("Permission denied (permanent=$permanentlyDenied)")
        setLink(LinkState.NeedsPermissions(permanentlyDenied))
    }

    /** Re-reads the Location services state, for example after returning from system settings. */
    fun onResume() {
        refreshLocation()
        if (_ui.value.link is LinkState.LocationServicesOff && _ui.value.locationServicesOn) {
            setLink(LinkState.Idle)
        }
    }

    fun rescan() {
        val c = controller ?: return
        if (_ui.value.link !is LinkState.Discovering) return
        val gen = generation
        log("Rescanning")
        c.discoverPeers { result -> if (gen == generation && !result.ok) onOperationFailed("discoverPeers", result) }
        armDiscoveryHint(gen)
    }

    fun connectTo(peer: PeerInfo) {
        val c = controller ?: return
        if (_ui.value.link !is LinkState.Discovering) {
            log("Ignored connect request: not discovering")
            return
        }
        val gen = generation
        discoveryHintJob?.cancel()
        requestedAtMs = SystemClock.elapsedRealtime()
        targetPeerAddress = peer.address
        targetGroupName = null
        codeTargetAddress = null
        setLink(LinkState.Connecting(peer.name))
        log("Connecting to ${peer.name} (${peer.address}); the other phone may show an accept prompt")
        // Discovery is deliberately not stopped first: on some phones (observed on the Galaxy A03s)
        // stopping it makes the system forget the peer, and the connect request is then dropped.
        // Android stops discovery by itself once the connection starts.
        connect(peer, gen, attempt = 1)
        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (gen == generation && _ui.value.link is LinkState.Connecting) {
                log("Connect timed out after ${CONNECT_TIMEOUT_MS / 1000} s")
                fail(FailureKind.CONNECT_TIMEOUT, null)
            }
        }
    }

    /**
     * Joins a paired phone's group directly with the credentials it sent on an earlier link, so the
     * host shows no connection prompt. The host must already be hosting. TLS still checks the key.
     */
    fun joinPaired(fingerprintHex: String) {
        val c = controller ?: return
        val sec = security ?: return
        if (_ui.value.link !is LinkState.Discovering) {
            log("Ignored direct join: not in the join screen")
            return
        }
        val gen = generation
        viewModelScope.launch {
            val device = withContext(Dispatchers.IO) { runCatching { sec.store.load() }.getOrNull() }
                ?.firstOrNull { it.fingerprintHex == fingerprintHex }
            val name = device?.groupName
            val pass = device?.groupPassphrase
            if (gen != generation || _ui.value.link !is LinkState.Discovering) return@launch
            if (device == null || name == null || pass == null) {
                log("No group known for that phone; pick it from the list instead")
                return@launch
            }
            discoveryHintJob?.cancel()
            requestedAtMs = SystemClock.elapsedRealtime()
            targetPeerAddress = null
            targetGroupName = name
            setLink(LinkState.Connecting(device.name))
            log("Joining ${device.name} directly (group $name); no prompt on the other phone")
            val target = PeerInfo(device.name, "", WifiP2pDevice.AVAILABLE, isPhone = true)
            directJoinAttempt(GroupCredentials(name, pass), target, gen, attempt = 1)
            timeoutJob?.cancel()
            timeoutJob = viewModelScope.launch {
                delay(CONNECT_TIMEOUT_MS)
                if (gen == generation && _ui.value.link is LinkState.Connecting) {
                    log("Direct join timed out after ${CONNECT_TIMEOUT_MS / 1000} s (is the other phone hosting?)")
                    fail(FailureKind.CONNECT_TIMEOUT, null)
                }
            }
        }
    }

    private var directRetryJob: Job? = null

    /** For a host started by Connect: the other phone's Wi-Fi channel on the last link, if 2.4 GHz. */
    @Volatile private var peerChannelHint: Int? = null

    /**
     * The channel to ask for when hosting. This phone's own Wi-Fi channel if it is a 2.4 GHz one
     * (so this phone does not time-share two channels). Otherwise the other phone's last 2.4 GHz
     * channel, so the other phone does not. Seen: with the Redmi hosting on 2462 MHz and the A03s's
     * Wi-Fi on 2437 MHz, the A03s's single radio time-shared and the call delay rose from about
     * 200 to 370 ms. Otherwise null (any 2.4 GHz channel).
     */
    private fun groupChannel(): Int? {
        val own = staFrequencyMhz()
        val hint = peerChannelHint
        return when {
            own != null && own in 2412..2484 -> own
            hint != null -> hint.also { log("Using ${it} MHz, the other phone's Wi-Fi channel on the last link") }
            else -> own
        }
    }

    /**
     * One direct-join request. A request made before the host's group exists never finds it later
     * (seen on the Redmi: it waited the full minute although the host was up after 15 s), so while
     * no group has formed the request is cancelled and made again every [DIRECT_RETRY_MS].
     */
    private fun directJoinAttempt(credentials: GroupCredentials, target: PeerInfo, gen: Int, attempt: Int, ownerAddress: String? = null) {
        val c = controller ?: return
        c.connectToGroup(credentials, ownerAddress) { result ->
            if (gen != generation) return@connectToGroup
            when {
                result.ok && attempt == 1 -> {
                    log("Direct join accepted by the system; waiting for the group to form")
                    startGroupPoll(target, gen)
                }
                result.ok -> Unit
                result.reason == WifiP2pManager.BUSY -> Unit // the next round asks again
                else -> onOperationFailed("connect(group)", result)
            }
        }
        directRetryJob?.cancel()
        directRetryJob = viewModelScope.launch {
            delay(DIRECT_RETRY_MS)
            if (gen != generation || groupFormed || socketsStarted || _ui.value.link !is LinkState.Connecting) return@launch
            // Android already lists this phone in the group, without the "formed" broadcast (seen on
            // the A03s): cancelling now would undo a join that worked. The join fallback takes over.
            if (clientSinceMs != null) return@launch
            if (attempt == 1) log("${target.name}'s group is not there yet; asking again every ${DIRECT_RETRY_MS / 1000} s")
            c.cancelConnect {
                if (gen == generation && !groupFormed && clientSinceMs == null && _ui.value.link is LinkState.Connecting) {
                    directJoinAttempt(credentials, target, gen, attempt + 1, ownerAddress)
                }
            }
        }
    }

    /**
     * Home screen "Connect" for a paired phone, tapped on both phones in any order. The roles follow
     * from the two key fingerprints, which both phones know, so no message is needed: the phone with
     * the lower fingerprint hosts, the other joins its group directly (retrying until it is up).
     * Without the other phone's group, this phone joins through the normal list instead.
     */
    fun connectAuto(peerFingerprintHex: String) = connect(peerFingerprintHex, asHost = null)

    /** Like [connectAuto], but with the role given ([asHost]) instead of taken from the keys. */
    private fun connect(peerFingerprintHex: String, asHost: Boolean?) {
        val sec = security ?: return
        viewModelScope.launch {
            val device = withContext(Dispatchers.IO) { runCatching { sec.store.load() }.getOrNull() }
                ?.firstOrNull { it.fingerprintHex == peerFingerprintHex } ?: return@launch
            val host = asHost ?: hostsFor(sec.identity.fingerprintHex, peerFingerprintHex)
            when {
                host -> {
                    log("Connect: this phone hosts for ${device.name} (${if (asHost == null) "lower key" else "as before"})")
                    peerChannelHint = device.staFrequencyMhz.takeIf { it in 2412..2484 }
                    start(Role.HOST)
                }
                device.groupName != null && Build.VERSION.SDK_INT >= 29 -> {
                    log("Connect: this phone joins ${device.name} directly (${if (asHost == null) "higher key" else "as before"})")
                    connectPaired(peerFingerprintHex)
                }
                else -> {
                    log("Connect: no group known for ${device.name}; pick it from the list")
                    start(Role.JOIN)
                }
            }
            // After starting: a start clears this while the link is not yet active.
            if (_ui.value.link.isActive) _ui.update { it.copy(waitingFor = device.name) }
        }
    }

    private var reconnectJob: Job? = null

    /**
     * After a dropped link: runs Connect again (same roles, from the keys) until the link is back
     * or [RECONNECT_WINDOW_MS] has passed. The other phone does the same, since it saw the drop too.
     */
    private fun startReconnecting(peerFingerprintHex: String, inGroup: Boolean, wasHost: Boolean) {
        reconnectJob?.cancel()
        // A group keeps trying much longer, and in the same role: a member that joined
        // the host's group must not start hosting because of the keys.
        val window = if (inGroup) GROUP_RECONNECT_WINDOW_MS else RECONNECT_WINDOW_MS
        log("The link dropped; reconnecting for up to ${window / 1000} s${if (inGroup) " (a group)" else ""}")
        reconnectJob = viewModelScope.launch {
            val deadline = SystemClock.elapsedRealtime() + window
            while (SystemClock.elapsedRealtime() < deadline) {
                // Wait until the previous attempt has finished and Wi-Fi Direct is cleaned up.
                while (_ui.value.cleaningUp || _ui.value.link.isActive) {
                    if (_ui.value.link is LinkState.Linked) {
                        log("Reconnected")
                        _ui.update { it.copy(reconnectingTo = null, reconnectingGroup = false) }
                        return@launch
                    }
                    if (SystemClock.elapsedRealtime() >= deadline) break
                    delay(500)
                }
                if (SystemClock.elapsedRealtime() >= deadline) break
                val cg = codeGroup
                if (cg != null) startSession(if (cg.isHost) Role.HOST else Role.JOIN) else connect(peerFingerprintHex, asHost = if (inGroup) wasHost else null)
                delay(2_000)
            }
            log("Could not reconnect within ${window / 1000} s")
            rejoinTalk = false
            setCodeGroup(null)
            _ui.update { it.copy(reconnectingTo = null, reconnectingGroup = false) }
            if (_ui.value.link.isActive && _ui.value.link !is LinkState.Linked) {
                teardown(LinkState.Disconnected(DisconnectReason.LINK_TIMEOUT, null), sendBye = false)
            }
        }
    }

    private fun stopReconnecting() {
        reconnectJob?.cancel()
        reconnectJob = null
        rejoinTalk = false
        if (_ui.value.reconnectingTo != null) _ui.update { it.copy(reconnectingTo = null, reconnectingGroup = false) }
    }

    /** After a dropped group: join the group talk again once the link is back, as before the drop. */
    @Volatile private var rejoinTalk = false

    /** A direct join waiting for the join screen to open ([connectPaired]). */
    @Volatile private var pendingDirectJoin: String? = null

    /** Home screen "Connect": opens the join side, then joins that paired phone directly. */
    fun connectPaired(fingerprintHex: String) {
        pendingDirectJoin = fingerprintHex
        start(Role.JOIN) // the join runs once discovery has started; setLink drops it if starting fails
    }

    /** Whether [group] is the one the user chose: by owner address, or by name for a direct join. */
    private fun inChosenGroup(group: GroupSnapshot?): Boolean {
        targetGroupName?.let { name -> return group != null && !group.isGroupOwner && group.networkName == name }
        val peer = targetPeerAddress ?: return false
        return JoinFallback.isClientOfPeer(group, peer)
    }

    private fun connect(peer: PeerInfo, gen: Int, attempt: Int) {
        val c = controller ?: return
        c.connect(peer.address) { result ->
            if (gen != generation) return@connect
            when {
                result.ok -> {
                    log("Connect request accepted by the system; waiting for the group to form")
                    startGroupPoll(peer, gen)
                }

                result.reason == WifiP2pManager.BUSY && attempt < MAX_BUSY_RETRIES ->
                    retryAfterBusy(gen) { connect(peer, gen, attempt + 1) }

                else -> onOperationFailed("connect", result)
            }
        }
    }

    /**
     * While connecting, asks Android once a second what group this phone is in. Normally the
     * "group formed" broadcast arrives first and the poll stops. If instead Android shows this
     * phone as a client of the chosen peer for [JoinFallback.DEFAULT_GRACE_MS] without the
     * broadcast, the control link is attempted anyway. The link only counts as connected once its
     * TCP handshake succeeds, and the usual deadlines and cleanup still apply.
     */
    private fun startGroupPoll(peer: PeerInfo, gen: Int) {
        groupPollJob?.cancel()
        clientSinceMs = null
        groupPollJob = viewModelScope.launch {
            while (gen == generation && !socketsStarted && _ui.value.link is LinkState.Connecting) {
                delay(GROUP_POLL_MS)
                controller?.requestGroupInfo { group -> onGroupPolled(group, peer, gen) }
            }
        }
    }

    private fun onGroupPolled(group: GroupSnapshot?, peer: PeerInfo, gen: Int) {
        if (gen != generation || socketsStarted || _ui.value.link !is LinkState.Connecting) return
        val now = SystemClock.elapsedRealtime()
        if (inChosenGroup(group)) {
            if (clientSinceMs == null) {
                clientSinceMs = now
                log("Android lists this phone as a client of ${peer.name} (group ${group?.networkName}, ${group?.frequencyMhz ?: "?"} MHz) but has not reported the group as formed yet")
            }
            val since = clientSinceMs
            if (since != null && now - since >= JoinFallback.DEFAULT_GRACE_MS) useJoinFallback(gen)
        } else {
            if (clientSinceMs != null) log("This phone is no longer listed as a client of ${peer.name}")
            clientSinceMs = null
            if (group != null && group.isGroupOwner) {
                log("A group exists but this phone owns it (${group.networkName}); not using the fallback")
            }
        }
    }

    private fun useJoinFallback(gen: Int) {
        fallbackUsed = true
        groupFormed = true
        groupFormedAtMs = SystemClock.elapsedRealtime()
        socketsStarted = true
        groupPollJob?.cancel()
        timeoutJob?.cancel()
        log("Fallback: no group-formed broadcast after ${JoinFallback.DEFAULT_GRACE_MS / 1000} s; trying the control link to ${JoinFallback.GROUP_OWNER_ADDRESS}")
        setLink(LinkState.OpeningSocket(isGroupOwner = false))
        armOpeningDeadline(gen, joinedAsOwner = false)
        startSockets(isGroupOwner = false, ownerAddress = JoinFallback.GROUP_OWNER_ADDRESS, gen = gen)
    }

    /** Cancels a session in progress, or disconnects an established link. */
    fun disconnect() {
        stopReconnecting()
        if (_ui.value.link !is LinkState.Linked && codeGroup != null) setCodeGroup(null)
        val wasLinked = _ui.value.link is LinkState.Linked
        log(if (wasLinked) "Disconnecting" else "Cancelling")
        teardown(
            finalState = if (wasLinked) LinkState.Disconnected(DisconnectReason.LOCAL, null) else LinkState.Idle,
            sendBye = true,
        )
    }

    override fun onCleared() {
        teardown(finalState = null, sendBye = false)
    }

    /** Media for the current link (milestone A1: test tone). Created by [onLinked]. */
    @Volatile private var media: MediaNegotiation? = null
    private var mediaPollJob: Job? = null

    // Group talk (Phase 7). On the hosting phone: the host side and its own user's in-process
    // membership; on a member phone: its membership of the host's talk.
    @Volatile private var groupHost: GroupHost? = null
    @Volatile private var ownGroupLink: InProcessLink? = null
    @Volatile private var group: GroupMemberLink? = null
    @Volatile private var primaryMemberId: Int? = null
    @Volatile private var groupLocal: InetAddress? = null

    /**
     * A group joined by code (Phase 7, G6): the code, this phone's role, and on the host the
     * Wi-Fi Direct group (a random name and a public password; see [GroupCredentials.codeGroup]).
     * Kept across drops, so reconnecting uses the same code.
     */
    private class CodeGroup(val code: JoinCode, val isHost: Boolean, val wifi: GroupCredentials?) {
        val guard = CodeGuard()

        /** Wi-Fi groups that turned out to hold a different code: not tried again. */
        val rejected: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    }

    @Volatile private var codeGroup: CodeGroup? = null

    private fun setCodeGroup(g: CodeGroup?) {
        codeGroup = g
        _ui.update { it.copy(joinCode = g?.let { c -> JoinCodeUi(c.code.spoken, c.isHost) }, codeProblem = false) }
    }

    /** "Back" from a failed code join: the normal start screen again. */
    fun dismissCodeProblem() {
        _ui.update { it.copy(codeProblem = false) }
    }

    /** "Start a group": a new join code, and this phone hosts. RECORD_AUDIO is already granted. */
    fun startCodeGroup() {
        if (_ui.value.link.isActive || _ui.value.cleaningUp) return
        setCodeGroup(CodeGroup(JoinCode.generate(), isHost = true, wifi = GroupCredentials.codeGroup()))
        log("Started a group with a join code")
        startSession(Role.HOST)
    }

    /** "Join with a code". Returns false if [text] is not a join code. */
    fun joinWithCode(text: String): Boolean {
        val code = JoinCode.parse(text) ?: return false
        if (_ui.value.link.isActive || _ui.value.cleaningUp) return true
        if (Build.VERSION.SDK_INT < 29) {
            setLink(LinkState.Failed(FailureKind.UNSUPPORTED, "joining with a code needs Android 10 or later"))
            return true
        }
        setCodeGroup(CodeGroup(code, isHost = false, wifi = null))
        log("Joining a group with a join code")
        startSession(Role.JOIN)
        return true
    }

    /** Debug builds (G3): a simulated member on the hosting phone, see [VirtualPhone]. */
    @Volatile private var virtualPhone: Closeable? = null

    /** Hosting phone: a phone linked after the first one. */
    private class ExtraMember(val channel: ControlChannel, val name: String, val fingerprintHex: String, val address: InetAddress)

    /** Hosting phone: the phones that joined after the first, by member id. */
    private val extraMembers = java.util.concurrent.ConcurrentHashMap<Int, ExtraMember>()

    /** Joins the group talk. The activity has already made sure RECORD_AUDIO is granted. */
    fun startGroupTalk() {
        val g = group ?: return
        if (_ui.value.link !is LinkState.Linked || media?.isBusy == true) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { g.join() }.onFailure { log("Could not join the group talk: ${it.message}") }
        }
    }

    /** Debug builds, hosting phone: adds or removes the simulated group member. */
    fun toggleVirtualPhone() {
        if (!VirtualPhone.AVAILABLE) return
        val host = groupHost ?: return
        val local = groupLocal ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val running = virtualPhone
            if (running != null) {
                virtualPhone = null
                runCatching { running.close() }
            } else {
                virtualPhone = runCatching { VirtualPhone.start(host, local, opusEnabled && opusAvailable) { log(it) } }
                    .onFailure { log("Could not add the virtual phone: ${it.message}") }
                    .getOrNull()
            }
            _ui.update { it.copy(virtualPhoneOn = virtualPhone != null) }
        }
    }

    /** Starts the test-tone media session with the linked phone. */
    fun startTestTone() = startMedia(MediaKind.TONE)

    /** Starts a voice call. The activity has already made sure RECORD_AUDIO is granted. */
    fun startCall() = startMedia(MediaKind.VOICE)

    /** Starts a push-to-talk session. The activity has already made sure RECORD_AUDIO is granted. */
    fun startPushToTalk() = startMedia(MediaKind.PTT)

    /** Push-to-talk: the user pressed (true) or released (false) the talk button. */
    fun setTalking(talking: Boolean) {
        group?.takeIf { it.session != null }?.let { g ->
            if (g.talking != talking) log(if (talking) "Talk button pressed (group)" else "Talk button released (group)")
            g.talking = talking
            return
        }
        val s = media?.session ?: return
        if (media?.kind != MediaKind.PTT) return
        if (s.talking != talking) log(if (talking) "Talk button pressed" else "Talk button released")
        s.talking = talking
    }

    private fun startMedia(kind: MediaKind) {
        val m = media ?: return
        if (_ui.value.link !is LinkState.Linked || group?.isBusy == true) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { m.start(kind) }.onFailure { log("Could not start ($kind): ${it.message}") }
        }
    }

    /** Ends the tone or the call, or cancels an outgoing call. */
    fun stopMedia() = stopMedia(MediaEnd.ENDED_HERE)

    private fun stopMedia(reason: MediaEnd) {
        group?.takeIf { it.isBusy }?.let { g ->
            viewModelScope.launch(Dispatchers.IO) { runCatching { g.leave(reason) } }
            return
        }
        val m = media ?: return
        viewModelScope.launch(Dispatchers.IO) { runCatching { m.stop(reason) } }
    }

    /** How the last call or push-to-talk session ended, shown until the next one starts. */
    @Volatile private var lastEnd: Pair<MediaKind, MediaEnd>? = null

    /** Accepts the incoming call. The activity has already made sure RECORD_AUDIO is granted. */
    fun acceptCall() {
        val m = media ?: return
        viewModelScope.launch(Dispatchers.IO) { runCatching { m.accept() }.onFailure { log("Could not accept the call: ${it.message}") } }
    }

    fun declineCall() {
        val m = media ?: return
        viewModelScope.launch(Dispatchers.IO) { runCatching { m.decline() } }
    }

    fun onMicrophoneDenied() {
        log("Microphone permission denied; calls need it")
        _ui.update { it.copy(micDenied = true) }
    }

    fun toggleMute() {
        callMuted = !callMuted
        activeMic?.muted = callMuted
        log(if (callMuted) "Microphone muted" else "Microphone unmuted")
    }

    fun toggleSpeaker() {
        callSpeaker = !callSpeaker
        activeCallAudio?.setSpeaker(callSpeaker)
    }

    /** Version 1 works only while the app is visible: leaving the app ends the call. */
    fun onAppHidden() {
        appVisible.value = false
        if (media?.session != null) log("The app left the screen; the session goes on in the background")
    }

    fun onAppShown() {
        appVisible.value = true
    }

    // Live call audio, so mute and route can change during the call. Set on the IO thread that
    // starts the session; read on the main thread.
    @Volatile private var activeMic: MicrophoneSource? = null
    @Volatile private var activeCallAudio: CallAudio? = null
    @Volatile private var callMuted = false
    @Volatile private var callSpeaker = false

    /** Whether this phone has a platform Opus encoder and decoder (checked once). */
    private val opusAvailable: Boolean by lazy { OpusSupport.available().also { log(if (it) "Opus available" else "Opus not available; voice uses PCM") } }

    /**
     * Diagnostics switch: offer Opus for voice. On by default since Phase 5: with a phone's screen
     * off, PCM lost 6-8.6 % of frames to Wi-Fi power saving and Opus (half the packets, 1/24 of the
     * data) 1.3-1.7 %. With the screen on, Opus costs about 15 ms more than PCM (A4). PCM is used
     * when either phone has it off or has no platform Opus encoder.
     */
    @Volatile private var opusEnabled = true

    fun toggleOpus() {
        opusEnabled = !opusEnabled
        log(if (opusEnabled) "Opus enabled for new calls" else "Opus disabled for new calls (PCM)")
        _ui.update { it.copy(opusEnabled = opusEnabled) }
    }

    private fun newMediaSession(kind: MediaKind, codec: AudioCodec, socket: DatagramSocket, peer: InetSocketAddress, out: DirectionKey, input: DirectionKey): MediaSession {
        val priority = { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        if (kind == MediaKind.TONE) return MediaSession(socket, peer, out, input, threadSetup = priority, log = { log(it) })
        callMuted = false
        // Calls start on the earpiece; push-to-talk on the speaker, like a walkie-talkie.
        callSpeaker = kind == MediaKind.PTT
        val audio = CallAudio(app, { log(it) }) { stopMedia(MediaEnd.AUDIO_TAKEN) }
        audio.begin(speaker = callSpeaker)
        val mic = try {
            MicrophoneSource { log(it) }
        } catch (e: Exception) {
            audio.end()
            throw e
        }
        val speaker = try {
            val burst = (app.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager)
                ?.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 480
            SpeakerSink({ log(it) }, framesPerBurst = burst)
        } catch (e: Exception) {
            mic.close()
            audio.end()
            throw e
        }
        activeMic = mic
        activeCallAudio = audio
        val session = try {
            MediaSession(
                socket, peer, out, input, source = mic, sink = speaker, verify = null, threadSetup = priority, log = { log(it) },
                pushToTalk = kind == MediaKind.PTT,
                codecs = if (codec == AudioCodec.OPUS) OpusSupport.pair { log(it) } else CodecPair.PCM,
                onStopped = {
                    audio.end()
                    if (activeCallAudio === audio) activeCallAudio = null
                    if (activeMic === mic) activeMic = null
                },
            )
        } catch (e: Exception) {
            if (activeMic === mic) activeMic = null
            if (activeCallAudio === audio) activeCallAudio = null
            mic.close()
            speaker.close()
            audio.end()
            throw e
        }
        return session
    }

    private fun groupCodecs() = if (opusEnabled && opusAvailable) AudioCodec.OPUS.bit else 0

    private fun newGroupMember(local: InetAddress, hostAddress: InetAddress, send: (ControlProtocol.Message) -> Unit) = GroupMemberLink(
        local, hostAddress, send,
        newSession = { codec, s, p, out, input -> newMediaSession(MediaKind.PTT, codec, s, p, out, input) },
        localCodecs = ::groupCodecs,
        log = { log(it) },
        onEnded = { reason ->
            log("Group talk ended: $reason")
            lastEnd = MediaKind.PTT to reason
        },
    )

    /**
     * Group talk for this link. The hosting phone runs the host side and joins it in-process like
     * any other member; the phone it linked with first becomes a member, and so does every paired
     * phone that connects later ([acceptMembers]).
     */
    private fun setUpGroup(isGroupOwner: Boolean, local: InetAddress, remote: InetAddress, ch: ControlChannel, peerName: String, peerKey: String) {
        setUpGroupLinks(isGroupOwner, local, remote, ch, peerName, peerKey)
        val g = group ?: return
        if (rejoinTalk || codeGroup != null) {
            log(if (rejoinTalk) "Back after a drop: rejoining the group talk" else "Joining the group talk (join code)")
            rejoinTalk = false
            viewModelScope.launch(Dispatchers.IO) {
                delay(REJOIN_TALK_DELAY_MS) // the host sets up its side of the link first
                if (group === g && media?.isBusy != true) runCatching { g.join() }.onFailure { log("Could not rejoin the group talk: ${it.message}") }
            }
        }
    }

    private fun setUpGroupLinks(isGroupOwner: Boolean, local: InetAddress, remote: InetAddress, ch: ControlChannel, peerName: String, peerKey: String) {
        if (!isGroupOwner) {
            group = newGroupMember(local, remote) { ch.sendMessage(it) }
            return
        }
        val host = GroupHost(
            local,
            localCodecs = ::groupCodecs,
            threadSetup = { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) },
            log = { log(it) },
        )
        groupHost = host
        groupLocal = local
        val own = InProcessLink(host, names.ownName, local, isHost = true) { send -> newGroupMember(local, local, send) }
        ownGroupLink = own
        group = own.member
        primaryMemberId = host.link(peerName, remote, key = peerKey) { ch.sendMessage(it) }
    }

    private fun groupDetails(g: GroupMemberLink): GroupDetails? {
        val roster = g.roster
        if (roster.isEmpty()) return null
        val me = g.ownId
        val holder = g.floorHolder
        return GroupDetails(
            members = roster.map { GroupMemberUi(it.name, it.inTalk, it.id == me, holder != null && it.id == holder, it.isHost, it.quality) },
            inTalk = g.isBusy,
            joining = g.isJoining,
            floorHolder = holder?.let { h -> roster.firstOrNull { it.id == h }?.name },
            floorIsMine = holder != null && holder == me,
        )
    }

    /** Media-control messages arrive on the control channel's read thread. */
    private fun onMediaMessage(message: ControlProtocol.Message, gen: Int) {
        if (gen != generation) return
        if (message is ControlProtocol.Message.GroupInfo) {
            onGroupInfo(message)
            return
        }
        if (message is ControlProtocol.Message.GroupJoin || message is ControlProtocol.Message.GroupWelcome ||
            message is ControlProtocol.Message.GroupLeave || message is ControlProtocol.Message.GroupRoster ||
            message is ControlProtocol.Message.Floor
        ) {
            val host = groupHost
            val id = primaryMemberId
            runCatching { if (host != null) id?.let { host.onMessage(it, message) } else group?.onMessage(message) }
                .onFailure { log("Group message failed: ${it.message}") }
            return
        }
        if (message is ControlProtocol.Message.MediaOffer && (group?.isBusy == true || codeGroup != null)) {
            // One microphone: a call cannot start while this phone is in the group talk.
            log("Declined the other phone's call: this phone is in the group talk")
            runCatching { channel?.sendMessage(ControlProtocol.Message.MediaStop) }
            return
        }
        runCatching { media?.onMessage(message) }.onFailure { log("Media message failed: ${it.message}") }
    }

    /** The authenticated peer's group, for joining it directly next time (control read thread). */
    private fun onGroupInfo(info: ControlProtocol.Message.GroupInfo) {
        val sec = security ?: return
        val peer = linkedPeerFingerprint ?: return
        val stored = runCatching { sec.store.updateGroup(peer, info.networkName, info.passphrase, info.staFrequencyMhz) }
        stored.onSuccess { if (it) log("Stored the other phone's group (${info.networkName}) for joining directly") }
            .onFailure { log("Could not store the other phone's group: ${it.message}") }
        refreshPaired()
    }

    private fun startMediaPoll(gen: Int) {
        mediaPollJob?.cancel()
        mediaPollJob = viewModelScope.launch {
            while (gen == generation) {
                val m = media
                val g = group
                val groupStats = g?.session?.stats()
                val stats = m?.session?.stats() ?: groupStats
                val kind = when {
                    m?.session != null -> m.kind
                    groupStats != null -> MediaKind.PTT
                    else -> null
                }
                val offering = m?.isOffering == true
                val incoming = m?.incomingKind
                val busy = m?.isBusy == true || g?.isBusy == true
                holdWifiLock(busy)
                if (busy) lastEnd = null
                val end = lastEnd
                val groupNow = g?.let(::groupDetails)
                _ui.update { s ->
                    val l = s.link
                    if (l is LinkState.Linked) {
                        s.copy(
                            link = l.copy(
                                details = l.details.copy(
                                    media = stats,
                                    mediaActive = stats != null,
                                    mediaOffering = offering,
                                    mediaKind = if (stats != null) kind else null,
                                    incomingCall = incoming?.needsAccept == true,
                                    incomingKind = incoming,
                                    muted = callMuted,
                                    speakerOn = callSpeaker,
                                    audioRoute = if (stats != null) activeCallAudio?.route else null,
                                    lastEndKind = end?.first,
                                    lastEnd = end?.second,
                                    group = groupNow,
                                ),
                            ),
                        )
                    } else {
                        s
                    }
                }
                // Faster while push-to-talk runs, so the "talking" indicator follows the voice.
                delay(
                    when (kind ?: m?.kind) {
                        MediaKind.PTT -> PTT_POLL_MS
                        MediaKind.VOICE -> CALL_POLL_MS // the voice pulse and the timer
                        else -> MEDIA_POLL_MS
                    },
                )
            }
        }
    }

    /** The user's answer to the pairing prompt: true if both phones show the same code. */
    fun answerPairing(codesMatch: Boolean) {
        val pending = pendingPairing ?: return
        pendingPairing = null
        log(if (codesMatch) "You confirmed the codes match" else "You said the codes do not match")
        pending.complete(codesMatch)
    }

    fun setAppearance(appearance: Appearance) {
        names.appearance = appearance
        _ui.update { it.copy(appearance = appearance) }
    }

    /** This phone's name, as the other phone will see it. */
    fun renameThisPhone(name: String) {
        names.ownName = name
        log("This phone is now called ${names.ownName}")
        _ui.update { it.copy(ownName = names.ownName) }
    }

    /** A local nickname for a paired phone; blank goes back to the name it sends. */
    fun renamePaired(fingerprintHex: String, name: String) {
        names.setNickname(fingerprintHex, name)
        refreshPaired()
        // Also on the screen of a running link.
        val nick = names.nickname(fingerprintHex)
        if (linkedPeerFingerprint == fingerprintHex) {
            _ui.update { s ->
                val l = s.link
                if (l is LinkState.Linked && nick != null) s.copy(link = l.copy(details = l.details.copy(peerName = nick))) else s
            }
        }
    }

    /** Removes a paired phone. Only allowed while no session is running. */
    fun forgetDevice(fingerprintHex: String) {
        val sec = security ?: return
        if (_ui.value.link.isActive) {
            log("Ignored forget request: a session is active")
            return
        }
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) {
                runCatching {
                    sec.store.remove(fingerprintHex).also { removed ->
                        // The forgotten phone knew this phone's group: replace it, so that phone can no
                        // longer join directly. Remaining paired phones get the new one on their next link.
                        if (removed) sec.ownGroup = sec.groupStore.rotate()
                    }
                }
            }
            removed.onSuccess { if (it) log("Forgot the phone with key ${Fingerprints.short(fingerprintHex)}; this phone's group credentials were replaced") }
                .onFailure { log("Could not forget the phone: ${it.message}") }
            refreshPaired()
        }
    }

    private fun refreshPaired() {
        val sec = security ?: return
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { sec.store.load() } }
            list.onSuccess { devices ->
                _ui.update { s ->
                    s.copy(
                        pairedDevices = devices.sortedByDescending { it.pairedAtMs }
                            .map {
                                PairedDeviceUi(
                                    names.nickname(it.fingerprintHex) ?: it.name, it.fingerprintHex, Fingerprints.short(it.fingerprintHex), it.pairedAtMs,
                                    canJoinDirectly = it.groupName != null && Build.VERSION.SDK_INT >= 29,
                                )
                            },
                    )
                }
            }.onFailure { log("Paired phones unreadable: ${it.message}") }
        }
    }

    // ---- Session start -----------------------------------------------------------------------

    private fun begin(role: Role) {
        val gen = ++generation
        sockets = SocketRegistry()
        groupFormed = false
        socketsStarted = false
        eventsAccepted = false
        groupFormedAtMs = 0
        linkedAtMs = 0
        fallbackUsed = false
        targetPeerAddress = null
        targetGroupName = null
        codeTargetAddress = null
        linkedPeerFingerprint = null
        clientSinceMs = null
        requestedAtMs = SystemClock.elapsedRealtime()
        _ui.update { it.copy(role = role, locationHint = false) }
        log("Starting as $role on Android ${Build.VERSION.SDK_INT} (${Build.MODEL}); location services ${if (_ui.value.locationServicesOn) "on" else "off"}")
        logStaState("before")

        val c = WifiDirectController(app) { log(it) }
        c.onEvent = { event -> if (controller === c) onP2pEvent(event, gen) }
        controller = c
        if (!c.start()) {
            fail(FailureKind.UNSUPPORTED, null)
            return
        }
        setLink(
            when (role) {
                Role.HOST -> LinkState.Hosting(groupReady = false, networkName = null)
                Role.JOIN -> LinkState.Discovering(emptyList(), noneFoundHint = false)
            },
        )
        c.clearStaleState {
            if (gen != generation) return@clearStaleState
            eventsAccepted = true
            when (role) {
                // A code group is advertised before the group forms: a service added while
                // the group is being created is not answered (seen on a Redmi, Android 16).
                Role.HOST -> codeGroup?.takeIf { it.isHost }?.wifi?.let { wifi ->
                    c.advertiseCodeGroup(wifi.networkName) { r ->
                        if (gen != generation) return@advertiseCodeGroup
                        log(if (r.ok) "Advertising the group" else "Could not advertise the group")
                        createGroup(gen, attempt = 1)
                    }
                } ?: createGroup(gen, attempt = 1)
                Role.JOIN -> discover(gen, attempt = 1)
            }
        }
    }

    private fun createGroup(gen: Int, attempt: Int) {
        val c = controller ?: return
        requestedAtMs = SystemClock.elapsedRealtime()
        val cg = codeGroup?.takeIf { it.isHost }
        val own = cg?.wifi ?: security?.ownGroup ?: GroupCredentials.generate()
        c.createGroup(own, groupChannel()) { result ->
            if (gen != generation) return@createGroup
            when {
                result.ok -> {
                    log("createGroup accepted by the system; waiting for the group to form")
                    if (cg != null) keepFindable(gen)
                }
                result.reason == WifiP2pManager.BUSY && attempt < MAX_BUSY_RETRIES ->
                    retryAfterBusy(gen) { createGroup(gen, attempt + 1) }

                else -> onOperationFailed("createGroup", result)
            }
        }
    }

    /**
     * Code-group host: keeps this phone findable (peer discovery puts it in listen mode, which
     * service discovery needs on some phones).
     */
    private fun keepFindable(gen: Int) {
        val c = controller ?: return
        viewModelScope.launch {
            while (gen == generation) {
                c.discoverPeers { }
                delay(CODE_LISTEN_MS)
            }
        }
    }

    /** Joining with a code: look for advertised groups and try each one (the code decides). */
    private fun findCodeGroup(gen: Int) {
        val c = controller ?: return
        val g = codeGroup ?: return
        val search = {
            c.findCodeGroups(onFound = { address -> tryCodeHost(address, gen) }) { r -> if (gen == generation && !r.ok) log("Searching for the group failed: ${WifiDirectController.reasonText(r.reason)}") }
        }
        log("Looking for the group that uses this join code")
        search()
        discoveryHintJob?.cancel()
        discoveryHintJob = viewModelScope.launch {
            while (true) {
                delay(CODE_SEARCH_MS)
                if (gen != generation || _ui.value.link !is LinkState.Discovering) break
                search()
            }
        }
    }

    /**
     * Joining with a code: a phone that may host a code group, heard by service discovery or seen
     * hosting a group in the peer list. Service discovery is not answered between some phones
     * (a Redmi host and an A03s), so the peer list is the main way in.
     */
    private fun tryCodeHost(address: String, gen: Int) {
        val g = codeGroup ?: return
        if (gen != generation || g.isHost || _ui.value.link !is LinkState.Discovering || address in g.rejected) return
        joinCodeWifi(address, gen)
    }

    private fun joinCodeWifi(ownerAddress: String, gen: Int) {
        val networkName = GroupCredentials.CODE_GROUP_NAME_FIXED
        discoveryHintJob?.cancel()
        requestedAtMs = SystemClock.elapsedRealtime()
        targetPeerAddress = null
        targetGroupName = networkName
        codeTargetAddress = ownerAddress
        setLink(LinkState.Connecting(""))
        log("Found a phone hosting a group; trying the code with it")
        val target = PeerInfo("The host", ownerAddress, WifiP2pDevice.AVAILABLE, isPhone = true)
        directJoinAttempt(GroupCredentials(networkName, GroupCredentials.CODE_GROUP_PASSPHRASE), target, gen, attempt = 1, ownerAddress = ownerAddress)
        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (gen == generation && _ui.value.link is LinkState.Connecting) {
                log("Joining the group timed out after ${CONNECT_TIMEOUT_MS / 1000} s")
                fail(FailureKind.CONNECT_TIMEOUT, null)
            }
        }
    }

    private fun discover(gen: Int, attempt: Int) {
        if (codeGroup?.isHost == false) {
            findCodeGroup(gen)
            return
        }
        val c = controller ?: return
        c.discoverPeers { result ->
            if (gen != generation) return@discoverPeers
            when {
                result.ok -> {
                    log("Peer discovery started")
                    armDiscoveryHint(gen)
                    // A home-screen Connect joins only now: the stale-state cleanup before discovery
                    // cancels connections, and it once cancelled a join that started too early.
                    pendingDirectJoin?.let { fp ->
                        pendingDirectJoin = null
                        joinPaired(fp)
                    }
                }

                result.reason == WifiP2pManager.BUSY && attempt < MAX_BUSY_RETRIES ->
                    retryAfterBusy(gen) { discover(gen, attempt + 1) }

                else -> onOperationFailed("discoverPeers", result)
            }
        }
    }

    private fun retryAfterBusy(gen: Int, action: () -> Unit) {
        log("System busy, retrying in 1 s")
        viewModelScope.launch {
            delay(1000)
            if (gen == generation) action()
        }
    }

    /** After a while with no peers, tell the user what to check. */
    private fun armDiscoveryHint(gen: Int) {
        discoveryHintJob?.cancel()
        discoveryHintJob = viewModelScope.launch {
            delay(DISCOVERY_HINT_MS)
            if (gen != generation) return@launch
            val state = _ui.value.link
            if (state is LinkState.Discovering && state.peers.isEmpty()) {
                refreshLocation()
                log("No devices found after ${DISCOVERY_HINT_MS / 1000} s")
                _ui.update {
                    it.copy(
                        link = state.copy(noneFoundHint = true),
                        locationHint = !it.locationServicesOn,
                    )
                }
            }
        }
    }

    // ---- Wi-Fi Direct events -----------------------------------------------------------------

    private fun onP2pEvent(event: P2pEvent, gen: Int) {
        if (gen != generation) return
        when (event) {
            is P2pEvent.P2pEnabled -> {
                log("Wi-Fi Direct ${if (event.enabled) "enabled" else "disabled"}")
                if (!event.enabled && _ui.value.link.isActive) fail(FailureKind.WIFI_OFF, null)
            }

            is P2pEvent.ThisDeviceChanged -> _ui.update { it.copy(thisDeviceName = event.name) }
            P2pEvent.ChannelLost -> fail(FailureKind.OPERATION_FAILED, "Wi-Fi Direct channel lost")
            is P2pEvent.PeersChanged -> onPeers(event.peers)
            is P2pEvent.ConnectionChanged -> if (eventsAccepted) onConnectionChanged(event, gen)
        }
    }

    private fun onPeers(peers: List<PeerInfo>) {
        val state = _ui.value.link as? LinkState.Discovering ?: return
        val sorted = peers.sortedWith(compareByDescending<PeerInfo> { it.isPhone }.thenBy { it.name })
        log("Peers: ${sorted.joinToString { "${it.name}[${if (it.isPhone) "phone" else "other"},${statusName(it.status)}${if (it.isGroupOwner) ",hosting" else ""}]" }.ifEmpty { "none" }}")
        _ui.update {
            it.copy(
                link = state.copy(peers = sorted, noneFoundHint = false),
                locationHint = if (sorted.isNotEmpty()) false else it.locationHint,
            )
        }
        // Joining with a code: try a phone that hosts a group (the code decides if it is the one).
        val g = codeGroup
        if (g != null && !g.isHost) sorted.firstOrNull { it.isGroupOwner && it.address !in g.rejected }?.let { tryCodeHost(it.address, generation) }
    }

    private fun onConnectionChanged(event: P2pEvent.ConnectionChanged, gen: Int) {
        log("Connection changed: formed=${event.groupFormed} owner=${event.isGroupOwner} ownerAddress=${event.ownerAddress}")
        if (event.groupFormed) {
            if (!groupFormed) {
                groupFormed = true
                groupFormedAtMs = SystemClock.elapsedRealtime()
                log("Group formed ${groupFormedAtMs - requestedAtMs} ms after the request")
                logStaState("group formed")
                controller?.requestGroupInfo { group ->
                    if (gen != generation) return@requestGroupInfo
                    log("Group: name=${group?.networkName} owner=${group?.isGroupOwner} frequency=${group?.frequencyMhz ?: "n/a"} MHz clients=${group?.clientCount ?: 0}")
                    _ui.update { s ->
                        val l = s.link
                        if (l is LinkState.Hosting) s.copy(link = l.copy(networkName = group?.networkName)) else s
                    }
                }
            }
            if (!socketsStarted) {
                socketsStarted = true
                groupPollJob?.cancel()
                timeoutJob?.cancel()
                val state = _ui.value.link
                if (state is LinkState.Hosting) {
                    setLink(state.copy(groupReady = true))
                } else {
                    setLink(LinkState.OpeningSocket(event.isGroupOwner))
                    armOpeningDeadline(gen, joinedAsOwner = _ui.value.role == Role.JOIN && event.isGroupOwner)
                }
                startSockets(event.isGroupOwner, event.ownerAddress, gen)
            }
        } else if (groupFormed) {
            if (fallbackUsed) {
                // Android never reported this group as formed, so its "not formed" reports are not
                // trustworthy either. Only end the session if the group is really gone.
                controller?.requestGroupInfo { group ->
                    if (gen != generation) return@requestGroupInfo
                    if (inChosenGroup(group)) {
                        log("Ignoring a 'not formed' report: this phone is still in group ${group?.networkName}")
                    } else {
                        log("The Wi-Fi Direct group ended")
                        teardown(LinkState.Disconnected(DisconnectReason.GROUP_ENDED, null), sendBye = false)
                    }
                }
            } else {
                log("The Wi-Fi Direct group ended")
                teardown(LinkState.Disconnected(DisconnectReason.GROUP_ENDED, null), sendBye = false)
            }
        }
    }

    /**
     * The group exists but the control link has not opened. Without a deadline a joining phone that
     * Android made a separate group owner would wait here forever, because nobody connects to it.
     */
    private fun armOpeningDeadline(gen: Int, joinedAsOwner: Boolean) {
        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch {
            delay(OPENING_TIMEOUT_MS)
            if (gen == generation && _ui.value.link is LinkState.OpeningSocket) {
                log("No control link ${OPENING_TIMEOUT_MS / 1000} s after the group formed (owner=$joinedAsOwner)")
                if (joinedAsOwner) {
                    fail(FailureKind.GROUP_ROLE_MISMATCH, null)
                } else {
                    fail(FailureKind.SOCKET_FAILED, "no control connection within ${OPENING_TIMEOUT_MS / 1000} s")
                }
            }
        }
    }

    // ---- Sockets -----------------------------------------------------------------------------

    /**
     * Opens the control link, but only over the Wi-Fi Direct group. First Android's group info must
     * name the group's interface and confirm this phone's role (and, when joining, that the owner is
     * the phone the user picked). The socket work then binds to that interface's address and checks
     * both ends of every connection. Anything that cannot be verified fails closed.
     */
    private fun startSockets(isGroupOwner: Boolean, ownerAddress: String?, gen: Int) {
        val c = controller ?: return
        c.requestGroupInfo { group ->
            if (gen != generation) return@requestGroupInfo
            val interfaceName = group?.interfaceName
            val chosen = targetPeerAddress != null || targetGroupName != null
            val problem = when {
                group == null -> "Android reported no Wi-Fi Direct group"
                interfaceName.isNullOrEmpty() -> "Android did not report the group's network interface"
                group.isGroupOwner != isGroupOwner -> "Android's group info disagrees about which phone owns the group"
                !isGroupOwner && chosen && !inChosenGroup(group) ->
                    "this phone is not in the group of the phone that was chosen"

                else -> null
            }
            if (problem != null || interfaceName == null) {
                fail(FailureKind.LINK_UNVERIFIED, problem)
                return@requestGroupInfo
            }
            launchSockets(isGroupOwner, ownerAddress, interfaceName, gen)
        }
    }

    private fun launchSockets(isGroupOwner: Boolean, ownerAddress: String?, interfaceName: String, gen: Int) {
        val registry = sockets
        socketJob = viewModelScope.launch(Dispatchers.IO) {
            var ch: ControlChannel? = null
            var handedOver = false
            var server: ServerSocket? = null
            try {
                val endpoint = awaitEndpoint(interfaceName)
                    ?: throw LinkUnverifiedException("$interfaceName has no IPv4 address after ${ADDRESS_WAIT_MS / 1000} s")
                LinkEndpoint.checkEndpoint(endpoint)?.let { throw LinkUnverifiedException(it) }
                log("Wi-Fi Direct endpoint: $endpoint")
                val established = if (isGroupOwner) {
                    LinkEndpoint.checkHostAddress(endpoint, ownerAddress)?.let { throw LinkUnverifiedException(it) }
                    val listening = openServer(endpoint, registry).also { server = it }
                    acceptPeer(listening, endpoint, registry, gen, allowPairing = true)
                } else {
                    val target = LinkEndpoint.parseIpv4(ownerAddress)
                        ?: throw LinkUnverifiedException("the group owner address is unknown")
                    LinkEndpoint.checkPeer(endpoint, target)?.let { throw LinkUnverifiedException("group owner $it") }
                    connectToOwner(endpoint, target, registry, gen)
                }
                val opened = established.channel
                ch = opened
                withContext(Dispatchers.Main) {
                    // Only a still-current session may adopt the channel; otherwise finally closes it.
                    if (gen == generation) {
                        onLinked(established, isGroupOwner, ownerAddress)
                        handedOver = true
                    }
                }
                if (!handedOver) return@launch
                // The hosting phone keeps listening: more paired phones may join the group talk.
                server?.let { s -> launch { acceptMembers(s, endpoint, registry, gen) } }
                val exit = opened.run(onStats = { stats -> onStats(stats, gen) }, onMedia = { m -> onMediaMessage(m, gen) })
                withContext(Dispatchers.Main) { if (gen == generation) onChannelEnded(exit) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityFailure) {
                withContext(Dispatchers.Main) { if (gen == generation) fail(e.kind, e.message) }
            } catch (e: LinkUnverifiedException) {
                withContext(Dispatchers.Main) { if (gen == generation) fail(FailureKind.LINK_UNVERIFIED, e.message) }
            } catch (e: ControlProtocol.VersionMismatchException) {
                withContext(Dispatchers.Main) { if (gen == generation) fail(FailureKind.VERSION_MISMATCH, e.message) }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    if (gen == generation) fail(FailureKind.SOCKET_FAILED, e.message ?: e.javaClass.simpleName)
                }
            } finally {
                // Covers failure and cancellation alike: a channel no session adopted is closed here.
                if (!handedOver) {
                    ch?.close(sendBye = false)
                    server?.let { discard(it, registry) }
                }
            }
        }
    }

    /** Group owner side: listens on the Wi-Fi Direct address only. The caller closes it. */
    private fun openServer(endpoint: P2pEndpoint, registry: SocketRegistry): ServerSocket {
        val server = ServerSocket()
        if (!registry.register(server)) throw IOException("the session ended")
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(endpoint.localAddress, PORT))
            server.soTimeout = 1000
        } catch (e: IOException) {
            discard(server, registry)
            throw e
        }
        log("Listening on ${endpoint.localAddress.hostAddress}:$PORT (${endpoint.interfaceName} only)")
        return server
    }

    /**
     * Group owner side: accept the next connection that comes from inside the group's subnet and
     * completes mutual TLS. A connection whose TLS handshake fails is dropped and listening
     * continues; any later security failure ends the attempt. With [allowPairing] false only a
     * phone that is already paired gets through.
     */
    private suspend fun acceptPeer(
        server: ServerSocket,
        endpoint: P2pEndpoint,
        registry: SocketRegistry,
        gen: Int,
        allowPairing: Boolean,
    ): Established {
        while (true) {
            currentCoroutineContext().ensureActive()
            val socket = try {
                server.accept()
            } catch (e: SocketTimeoutException) {
                continue
            }
            if (!registry.register(socket)) throw IOException("the session ended")
            val remote = socket.inetAddress
            val problem = LinkEndpoint.checkPeer(endpoint, remote)
                ?: if (LinkEndpoint.toIpv4(socket.localAddress) != endpoint.localAddress) {
                    "it arrived on ${socket.localAddress?.hostAddress}, not ${endpoint.localAddress.hostAddress}"
                } else {
                    null
                }
            if (problem != null) {
                log("Rejected connection from ${remote?.hostAddress}: $problem")
                discard(socket, registry)
                continue
            }
            log("Incoming connection from ${remote?.hostAddress}")
            try {
                return secure(socket, isServer = true, registry = registry, gen = gen, allowPairing = allowPairing)
            } catch (e: SecurityFailure) {
                discard(socket, registry)
                if (e.kind != FailureKind.SECURE_HANDSHAKE_FAILED && e.kind != FailureKind.JOIN_CODE_REJECTED) throw e
                log("Rejected connection from ${remote?.hostAddress}: ${e.message}")
            } catch (e: ControlProtocol.VersionMismatchException) {
                discard(socket, registry)
                throw e
            } catch (e: CancellationException) {
                discard(socket, registry)
                throw e
            } catch (e: IOException) {
                log("Rejected connection from ${remote?.hostAddress}: ${e.message}")
                discard(socket, registry)
            }
        }
    }

    /**
     * Hosting phone, once the first phone is linked: keeps accepting phones that are already
     * paired with this one, each on its own control link, as members of the group talk (Phase 7).
     * A phone that is not paired, or fails any check, is turned away without disturbing the phones
     * already linked. The session's end closes the server, which ends this loop.
     */
    private suspend fun acceptMembers(server: ServerSocket, endpoint: P2pEndpoint, registry: SocketRegistry, gen: Int) = coroutineScope {
        while (gen == generation && !server.isClosed) {
            val established = try {
                acceptPeer(server, endpoint, registry, gen, allowPairing = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (server.isClosed) break
                log("Turned away a phone: ${e.message}")
                continue
            }
            val ch = established.channel
            val name = established.peer.name
            val id = groupHost?.link(name, established.remoteAddress, key = established.peer.fingerprintHex) { ch.sendMessage(it) }
            if (id == null) {
                log("Turned away $name: the group is full")
                ch.close(sendBye = true)
                continue
            }
            extraMembers[id] = ExtraMember(ch, name, established.peer.fingerprintHex, established.remoteAddress)
            log("$name joined the link as member $id")
            launch {
                val exit = try {
                    ch.run(
                        // Once promoted to the main link ([promoteMember]) it feeds the screen and 1:1 calls too.
                        onStats = { st -> if (primaryMemberId == id) onStats(st, gen) },
                        onMedia = { m ->
                            if (gen == generation) {
                                if (primaryMemberId == id) onMediaMessage(m, gen) else runCatching { groupHost?.onMessage(id, m) }
                            }
                        },
                    )
                } finally {
                    extraMembers.remove(id)
                    ch.close(sendBye = false)
                }
                log("$name left the link: $exit")
                withContext(Dispatchers.Main) {
                    if (gen != generation) return@withContext
                    if (primaryMemberId == id) onChannelEnded(exit) else groupHost?.unlink(id, dropped = exit.isDrop)
                }
            }
        }
    }

    /**
     * Client side: connect from this phone's Wi-Fi Direct address to the group owner, retrying
     * while the owner brings its listener up. Both ends are checked before TLS. Only the TCP
     * connect is retried; a TLS or session failure ends the attempt.
     */
    private suspend fun connectToOwner(
        endpoint: P2pEndpoint,
        target: Inet4Address,
        registry: SocketRegistry,
        gen: Int,
    ): Established {
        var lastError: IOException? = null
        repeat(CONNECT_ATTEMPTS) { attempt ->
            currentCoroutineContext().ensureActive()
            val socket = Socket()
            if (!registry.register(socket)) throw IOException("the session ended")
            try {
                socket.bind(InetSocketAddress(endpoint.localAddress, 0))
                socket.connect(InetSocketAddress(target, PORT), CONNECT_TIMEOUT_SOCKET_MS)
                LinkEndpoint.checkConnected(endpoint, socket.localAddress, socket.inetAddress, target)
                    ?.let { throw LinkUnverifiedException(it) }
            } catch (e: LinkUnverifiedException) {
                discard(socket, registry)
                throw e
            } catch (e: IOException) {
                lastError = e
                log("Connect attempt ${attempt + 1}/$CONNECT_ATTEMPTS failed: ${e.message}")
                discard(socket, registry)
                delay(CONNECT_RETRY_DELAY_MS)
                return@repeat
            }
            log("TCP connected to ${target.hostAddress}:$PORT from ${socket.localAddress?.hostAddress} (${endpoint.interfaceName})")
            try {
                return secure(socket, isServer = false, registry = registry, gen = gen)
            } catch (e: Throwable) {
                discard(socket, registry)
                throw e
            }
        }
        throw IOException("could not reach ${target.hostAddress}:$PORT (${lastError?.message})")
    }

    /**
     * Android can report the group as formed before the interface has its IPv4 address (seen on
     * the Redmi: group formed 110 ms after the request, address not yet assigned). Wait briefly
     * for it; if it never appears the caller still fails closed.
     */
    private suspend fun awaitEndpoint(interfaceName: String): P2pEndpoint? {
        val deadline = SystemClock.elapsedRealtime() + ADDRESS_WAIT_MS
        while (true) {
            LinkEndpoint.resolve(interfaceName)?.let { return it }
            if (SystemClock.elapsedRealtime() >= deadline) return null
            delay(ADDRESS_POLL_MS)
        }
    }

    /** A connection that passed mutual TLS and session negotiation. */
    private class Established(
        val channel: ControlChannel,
        val peer: SessionPeer,
        val protocol: String,
        val cipherSuite: String,
        /** Both ends of the verified Wi-Fi Direct TCP connection; media uses the same addresses. */
        val localAddress: java.net.InetAddress,
        val remoteAddress: java.net.InetAddress,
    )

    /** Mutual TLS, then HELLO / STATUS and, if needed, pairing. */
    private suspend fun secure(socket: Socket, isServer: Boolean, registry: SocketRegistry, gen: Int, allowPairing: Boolean = true): Established {
        val sec = security ?: throw SecurityFailure(FailureKind.IDENTITY_UNAVAILABLE, securityError ?: "identity not loaded")
        socket.tcpNoDelay = true
        socket.keepAlive = true
        val tls = try {
            SecureTransport.establish(socket, isServer, sec.identity, HANDSHAKE_TIMEOUT_MS)
        } catch (e: SecureHandshakeException) {
            throw SecurityFailure(FailureKind.SECURE_HANDSHAKE_FAILED, e.message ?: "TLS handshake failed", e)
        }
        if (!registry.register(tls.socket)) throw IOException("the session ended")
        log("TLS ${tls.protocol} ${tls.cipherSuite}; peer key ${Fingerprints.short(tls.peerFingerprint.toHex())}")
        val ch = ControlChannel(tls.socket) { log(it) }
        try {
            val cg = codeGroup
            val peer = if (cg != null) {
                codeNegotiate(ch, isServer, tls.peerFingerprint.toHex(), cg)
            } else {
                SessionNegotiator(sec.identity.fingerprint, sec.installId, names.ownName, sec.store, { log(it) })
                    .negotiate(ch, isServer, tls.peerFingerprint, PromptFor(gen), allowPairing)
            }
            return Established(ch, peer, tls.protocol, tls.cipherSuite, socket.localAddress, socket.inetAddress)
        } catch (e: Throwable) {
            ch.close(sendBye = false)
            throw e
        }
    }

    /**
     * A code group's session start, instead of pairing: HELLO both ways (names), then the J-PAKE
     * join ([CodeJoin]) bound to both TLS keys. Nothing is stored: the peer is a member for as long
     * as the group lasts. The host refuses joins for a minute after repeated wrong codes.
     */
    private fun codeNegotiate(ch: ControlChannel, isServer: Boolean, peerFingerprintHex: String, cg: CodeGroup): SessionPeer {
        val sec = security ?: throw SecurityFailure(FailureKind.IDENTITY_UNAVAILABLE, "identity not loaded")
        val own = ControlProtocol.Message.Hello(ControlProtocol.VERSION, sec.installId, names.ownName)
        val hello = if (isServer) {
            ch.sendMessage(own)
            ch.expect<ControlProtocol.Message.Hello>(HANDSHAKE_TIMEOUT_MS * 2)
        } else {
            ch.expect<ControlProtocol.Message.Hello>(HANDSHAKE_TIMEOUT_MS * 2).also { ch.sendMessage(own) }
        }
        if (isServer && !cg.guard.mayTry()) {
            throw SecurityFailure(FailureKind.JOIN_CODE_REJECTED, "too many wrong codes; waiting a minute before the next try")
        }
        try {
            CodeJoin(cg.code, sec.identity.fingerprintHex, peerFingerprintHex).run(ch, isServer)
        } catch (e: SecurityFailure) {
            if (isServer) cg.guard.failed() else codeTargetAddress?.let { cg.rejected += it }
            throw e
        }
        log("${hello.deviceName} holds the same join code")
        return SessionPeer(hello.deviceName, peerFingerprintHex, hello.installIdHex, newlyPaired = false)
    }

    /** Shows the pairing code through [LinkState.Pairing] and waits for [answerPairing]. */
    private inner class PromptFor(private val gen: Int) : PairingPrompt {
        override suspend fun confirm(peerName: String, code: String): Boolean {
            val answer = CompletableDeferred<Boolean>()
            val shown = withContext(Dispatchers.Main) {
                if (gen != generation) return@withContext false
                pendingPairing?.cancel()
                pendingPairing = answer
                timeoutJob?.cancel()
                log("Pairing code $code; compare it with the other phone")
                setLink(LinkState.Pairing(peerName, code, waitingForPeer = false))
                true
            }
            if (!shown) return false
            return try {
                answer.await()
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { if (pendingPairing === answer) pendingPairing = null }
            }
        }

        override suspend fun waitingForPeer() {
            withContext(Dispatchers.Main) {
                val state = _ui.value.link
                if (gen == generation && state is LinkState.Pairing) setLink(state.copy(waitingForPeer = true))
            }
        }
    }

    private fun discard(resource: Closeable, registry: SocketRegistry) {
        closeQuietly(resource)
        registry.release(resource)
    }

    private fun onLinked(established: Established, isGroupOwner: Boolean, ownerAddress: String?) {
        val ch = established.channel
        // A nickname the user gave this phone wins over the name it sends.
        val peerName = names.nickname(established.peer.fingerprintHex) ?: established.peer.name
        channel = ch
        // Keep the stored name current if the other phone was renamed (shown when not linked).
        if (codeGroup == null) security?.let { sec ->
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { if (sec.store.updateName(established.peer.fingerprintHex, established.peer.name)) refreshPaired() }
            }
        }
        linkedAtMs = SystemClock.elapsedRealtime()
        timeoutJob?.cancel()
        val details = LinkDetails(
            isGroupOwner = isGroupOwner,
            ownerAddress = ownerAddress,
            peerName = peerName,
            groupFormedMs = groupFormedAtMs - requestedAtMs,
            socketMs = linkedAtMs - groupFormedAtMs,
            viaFallback = fallbackUsed,
            tlsProtocol = established.protocol,
            cipherSuite = established.cipherSuite,
            peerKey = Fingerprints.short(established.peer.fingerprintHex),
            newlyPaired = established.peer.newlyPaired,
        )
        log("Linked securely with $peerName (${details.tlsProtocol}, ${if (details.newlyPaired) "newly paired" else "paired earlier"}); group formed after ${details.groupFormedMs} ms, secure link ${details.socketMs} ms later${if (details.viaFallback) " (via fallback)" else ""}")
        logStaState("linked")
        setLink(LinkState.Linked(details))
        if (details.newlyPaired) refreshPaired()
        linkedPeerFingerprint = established.peer.fingerprintHex
        // Tell the (authenticated, paired) peer this phone's group, so it can join directly later.
        // Only paired phones get this phone's own group (a code member is not trusted that far).
        if (codeGroup == null) security?.ownGroup?.let { own ->
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { ch.sendMessage(ControlProtocol.Message.GroupInfo(own.networkName, own.passphrase, staFrequencyMhz() ?: 0)) }
                    .onFailure { log("Could not send this phone's group: ${it.message}") }
            }
        }
        val local = LinkEndpoint.toIpv4(established.localAddress)
        val remote = LinkEndpoint.toIpv4(established.remoteAddress)
        if (local != null && remote != null) {
            media = newNegotiation(isGroupOwner, local, remote, ch)
            setUpGroup(isGroupOwner, local, remote, ch, established.peer.name, established.peer.fingerprintHex)
            startMediaPoll(generation)
        } else {
            log("Media unavailable: the link addresses are not IPv4")
        }
    }

    /** 1:1 calls with the phone on the main link. */
    private fun newNegotiation(isServer: Boolean, local: InetAddress, remote: InetAddress, ch: ControlChannel) = MediaNegotiation(
        isServer = isServer,
        localAddress = local,
        peerAddress = remote,
        send = { ch.sendMessage(it) },
        newSession = { kind, codec, s, p, out, input -> newMediaSession(kind, codec, s, p, out, input) },
        log = { log(it) },
        onEnded = { kind, reason ->
            log("Media ended: $kind, $reason")
            if (kind != MediaKind.TONE) lastEnd = kind to reason
        },
        localCodecs = { if (opusEnabled && opusAvailable) AudioCodec.OPUS.bit else 0 },
    )

    /**
     * Hosting phone: the phone on the main link left, but others are still linked. The next one
     * becomes the main link (its name at the top, 1:1 calls, link statistics), and the group talk
     * goes on. Returns false when nobody else is linked.
     */
    private fun promoteMember(exit: ControlChannel.Exit): Boolean {
        val host = groupHost ?: return false
        val local = groupLocal ?: return false
        val (id, next) = extraMembers.entries.firstOrNull()?.toPair() ?: return false
        val linked = _ui.value.link as? LinkState.Linked ?: return false
        extraMembers.remove(id)
        primaryMemberId?.let { host.unlink(it, dropped = exit.isDrop) }
        primaryMemberId = id
        channel = next.channel
        linkedPeerFingerprint = next.fingerprintHex
        val old = media
        old?.kind?.takeIf { old.session != null && it != MediaKind.TONE }?.let { lastEnd = it to MediaEnd.ENDED_BY_PEER }
        media = newNegotiation(true, local, next.address, next.channel)
        if (old != null) thread(isDaemon = true, name = "pairloom-close") { runCatching { old.close() } }
        val name = names.nickname(next.fingerprintHex) ?: next.name
        log("${linked.details.peerName} left ($exit); staying linked with $name and the group")
        linkedAtMs = SystemClock.elapsedRealtime()
        setLink(
            LinkState.Linked(
                linked.details.copy(
                    peerName = name,
                    peerKey = Fingerprints.short(next.fingerprintHex),
                    newlyPaired = false,
                    stats = PingStats(),
                    uptimeMs = 0,
                ),
            ),
        )
        return true
    }

    /** The link went without a goodbye (out of range, most likely), not because someone ended it. */
    private val ControlChannel.Exit.isDrop: Boolean
        get() = this == ControlChannel.Exit.LinkTimeout || this is ControlChannel.Exit.IoError

    private fun onStats(stats: PingStats, gen: Int) {
        _ui.update { s ->
            val l = s.link
            if (gen == generation && l is LinkState.Linked) {
                val uptime = SystemClock.elapsedRealtime() - linkedAtMs
                s.copy(link = l.copy(details = l.details.copy(stats = stats, uptimeMs = uptime)))
            } else {
                s
            }
        }
    }

    private fun onChannelEnded(exit: ControlChannel.Exit) {
        log("Control link ended: $exit")
        if (exit != ControlChannel.Exit.LocalClose && promoteMember(exit)) return
        val state = when (exit) {
            ControlChannel.Exit.LocalClose -> LinkState.Disconnected(DisconnectReason.LOCAL, null)
            ControlChannel.Exit.PeerClosed -> LinkState.Disconnected(DisconnectReason.PEER_CLOSED, null)
            ControlChannel.Exit.LinkTimeout -> LinkState.Disconnected(DisconnectReason.LINK_TIMEOUT, null)
            is ControlChannel.Exit.ProtocolError -> LinkState.Disconnected(DisconnectReason.PROTOCOL_ERROR, exit.detail)
            is ControlChannel.Exit.IoError -> LinkState.Disconnected(DisconnectReason.IO_ERROR, exit.detail)
        }
        teardown(state, sendBye = false)
    }

    // ---- Failure and teardown ----------------------------------------------------------------

    private fun onOperationFailed(operation: String, result: P2pResult) {
        refreshLocation()
        val reason = WifiDirectController.reasonText(result.reason)
        log("$operation failed: $reason")
        _ui.update { it.copy(locationHint = !it.locationServicesOn) }
        fail(FailureKind.OPERATION_FAILED, "$operation: $reason")
    }

    private fun fail(kind: FailureKind, detail: String?) {
        log("Failed: $kind ${detail.orEmpty()}")
        teardown(LinkState.Failed(kind, detail), sendBye = false)
    }

    /**
     * Stops everything this session started, in order: invalidate callbacks, stop timers, close
     * sockets (on a background thread, since closing may write), then remove the group and
     * unregister the receiver. [LinkUiState.cleaningUp] stays set until that last step finishes,
     * so a new session cannot overlap the old one's cleanup.
     */
    private fun teardown(finalState: LinkState?, sendBye: Boolean) {
        // A link to a paired phone that dropped (not one a user ended) is brought back by itself.
        val linked = _ui.value.link as? LinkState.Linked
        val droppedPeer = linkedPeerFingerprint.takeIf {
            linked != null && finalState is LinkState.Disconnected && finalState.reason in DROP_REASONS
        }
        // More than two phones, or a group talk, counts as a group.
        val inGroup = (group?.roster?.size ?: 0) > 2 || group?.isBusy == true || codeGroup != null
        val wasHost = linked?.details?.isGroupOwner == true
        if (droppedPeer != null) {
            media?.kind?.takeIf { media?.session != null && it != MediaKind.TONE }?.let { lastEnd = it to MediaEnd.LINK_LOST }
            if (group?.session != null) lastEnd = MediaKind.PTT to MediaEnd.LINK_LOST
            rejoinTalk = inGroup && group?.isBusy == true
            // Before the link state changes, so the notification (and the foreground service) stays.
            _ui.update { it.copy(reconnectingTo = linked?.details?.peerName, reconnectingGroup = inGroup) }
        }
        // A member told the group is over (the host's goodbye) says so, rather than "disconnected".
        if (linked != null && !wasHost && inGroup && finalState is LinkState.Disconnected && finalState.reason == DisconnectReason.PEER_CLOSED) {
            _ui.update { it.copy(groupEndedBy = linked.details.peerName) }
        }
        generation++
        timeoutJob?.cancel()
        discoveryHintJob?.cancel()
        groupPollJob?.cancel()
        socketJob?.cancel()
        socketJob = null
        pendingPairing?.cancel()
        pendingPairing = null
        mediaPollJob?.cancel()
        mediaPollJob = null
        directRetryJob?.cancel()
        directRetryJob = null
        peerChannelHint = null
        holdWifiLock(false)
        val m = media
        media = null
        val gh = groupHost
        val ownLink = ownGroupLink
        val g = group
        groupHost = null
        ownGroupLink = null
        group = null
        primaryMemberId = null
        groupLocal = null
        val virtual = virtualPhone
        virtualPhone = null
        if (_ui.value.virtualPhoneOn) _ui.update { it.copy(virtualPhoneOn = false) }
        val extras = extraMembers.values.map { it.channel }
        extraMembers.clear()

        val ch = channel
        val registry = sockets
        val c = controller
        channel = null
        controller = null
        groupFormed = false
        socketsStarted = false
        eventsAccepted = false
        fallbackUsed = false
        targetPeerAddress = null
        targetGroupName = null
        codeTargetAddress = null
        linkedPeerFingerprint = null
        clientSinceMs = null

        // Closing may write (the BYE), so it happens off the main thread. The registry is marked
        // closed at once, so any socket a background thread opens from now on is closed on the spot.
        // The goodbyes go first and the Wi-Fi Direct group is removed only after all of this, so the
        // other phones see "disconnected" rather than a dropped link (which they would try to
        // bring back).
        if (c != null) _ui.update { it.copy(cleaningUp = true) }
        thread(isDaemon = true, name = "pairloom-close") {
            // The host side first: it sends nothing more, so no roster goes to phones already closing.
            gh?.let { runCatching { it.close() } }
            extras.forEach { it.close(sendBye) }
            ch?.close(sendBye)
            val byeSentAt = SystemClock.elapsedRealtime()
            // Closing audio and codecs can take seconds; it waits until the goodbyes are out.
            virtual?.let { runCatching { it.close() } }
            m?.let { runCatching { it.close() } }
            if (ownLink != null) runCatching { ownLink.close() } else g?.let { runCatching { it.close() } }
            registry.closeAll()
            // A goodbye still in flight is lost if the group goes at once (seen on the A03s).
            if (sendBye) {
                val wait = BYE_GRACE_MS - (SystemClock.elapsedRealtime() - byeSentAt)
                if (wait > 0) runCatching { Thread.sleep(wait) }
            }
            if (c != null) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    c.shutdown {
                        log("Wi-Fi Direct cleanup finished")
                        _ui.update { it.copy(cleaningUp = false) }
                    }
                }
            }
        }
        if (finalState != null) setLink(finalState)
        if (droppedPeer != null) {
            startReconnecting(droppedPeer, inGroup, wasHost)
        } else if (finalState != null && reconnectJob?.isActive != true && codeGroup != null) {
            val cg = codeGroup
            if (cg != null && cg.isHost && finalState is LinkState.Disconnected && finalState.reason != DisconnectReason.LOCAL) {
                // The last member left: the group goes on, waiting for others with the same code.
                log("Everyone left; waiting with the same join code")
                viewModelScope.launch {
                    while (_ui.value.cleaningUp) delay(200)
                    if (codeGroup === cg && !_ui.value.link.isActive) startSession(Role.HOST)
                }
            } else {
                // Ended, cancelled, or failed outside reconnecting: the code group is over.
                val badly = finalState is LinkState.Failed || (finalState is LinkState.Disconnected && finalState.reason != DisconnectReason.LOCAL)
                setCodeGroup(null)
                if (badly) _ui.update { it.copy(codeProblem = true) }
            }
        }
    }

    // ---- Helpers -----------------------------------------------------------------------------

    private fun setLink(state: LinkState) {
        _ui.update { it.copy(link = state, groupEndedBy = if (state.isActive) null else it.groupEndedBy) }
        if (!state.isActive) {
            pendingDirectJoin = null
            if (_ui.value.waitingFor != null) _ui.update { it.copy(waitingFor = null) }
        }
    }

    private fun refreshLocation() {
        val on = isLocationOn()
        _ui.update { it.copy(locationServicesOn = on) }
    }

    private fun isLocationOn(): Boolean {
        val manager = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    /** The channel of this phone's normal Wi-Fi connection, or null if it is not connected. */
    @Suppress("DEPRECATION")
    private fun staFrequencyMhz(): Int? = try {
        val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        // networkId is hidden from apps without location access on Android 10+, but the
        // supplicant state and frequency are still reported.
        wifi?.connectionInfo?.takeIf { it.supplicantState == android.net.wifi.SupplicantState.COMPLETED }?.frequency?.takeIf { it > 0 }
    } catch (e: SecurityException) {
        null
    }

    /**
     * Keeps the Wi-Fi radio out of power saving while media runs. Android 10+ has the low-latency
     * lock, but it only acts while the app is in front with the screen on. Up to Android 13 the
     * older high-performance lock also works in the background (it does nothing from Android 14),
     * so both are held where available. Released when the media session ends.
     */
    private val wifiLocks = mutableListOf<WifiManager.WifiLock>()
    private var cpuLock: android.os.PowerManager.WakeLock? = null

    @Suppress("DEPRECATION")
    private fun holdWifiLock(hold: Boolean) {
        if (hold == wifiLocks.isNotEmpty()) return
        if (!hold) {
            wifiLocks.forEach { runCatching { it.release() } }
            wifiLocks.clear()
            cpuLock?.let { runCatching { it.release() } }
            cpuLock = null
            log("Wi-Fi and CPU locks released")
            return
        }
        // With the screen off (seen on the Redmi) the audio threads fell behind: a speaker underrun
        // right after the screen went off and ~10 % concealed on the other phone. A partial wake
        // lock keeps the CPU running normally while media runs, as voice apps usually do.
        runCatching {
            val pm = app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            cpuLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "pairloom:media").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L) // a safety limit; released when the session ends
            }
        }.onFailure { log("Could not hold a CPU wake lock: ${it.message}") }
        val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val modes = buildList {
            if (Build.VERSION.SDK_INT >= 29) add(WifiManager.WIFI_MODE_FULL_LOW_LATENCY to "low-latency")
            if (Build.VERSION.SDK_INT < 34) add(WifiManager.WIFI_MODE_FULL_HIGH_PERF to "high-performance")
        }
        for ((mode, name) in modes) {
            runCatching {
                wifiLocks += wifi.createWifiLock(mode, "pairloom:media-$name").apply { setReferenceCounted(false); acquire() }
            }.onFailure { log("Could not hold the Wi-Fi $name lock: ${it.message}") }
        }
        log("Wi-Fi locks held for media: ${modes.joinToString { it.second }}")
    }

    @Suppress("DEPRECATION")
    private fun logStaState(label: String) {
        try {
            val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val info = wifi.connectionInfo
            log("Normal Wi-Fi ($label): enabled=${wifi.isWifiEnabled} state=${info?.supplicantState} frequency=${info?.frequency} MHz")
        } catch (e: SecurityException) {
            log("Normal Wi-Fi ($label): not readable (${e.message})")
        }
    }

    private fun statusName(status: Int): String = when (status) {
        WifiP2pDevice.CONNECTED -> "connected"
        WifiP2pDevice.INVITED -> "invited"
        WifiP2pDevice.FAILED -> "failed"
        WifiP2pDevice.AVAILABLE -> "available"
        WifiP2pDevice.UNAVAILABLE -> "unavailable"
        else -> "status $status"
    }

    private fun closeQuietly(closeable: Closeable?) {
        runCatching { closeable?.close() }
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        val line = "${LocalTime.now().format(timeFormat)}  $message"
        _ui.update { it.copy(log = (it.log + line).takeLast(MAX_LOG_LINES)) }
    }

    private companion object {
        const val TAG = "Pairloom"
        const val PORT = 47600
        const val MAX_LOG_LINES = 60
        const val MAX_BUSY_RETRIES = 3
        const val CONNECT_TIMEOUT_MS = 60_000L
        const val OPENING_TIMEOUT_MS = 15_000L
        const val GROUP_POLL_MS = 1_000L
        const val DISCOVERY_HINT_MS = 20_000L
        const val HANDSHAKE_TIMEOUT_MS = 5_000
        const val CONNECT_TIMEOUT_SOCKET_MS = 2_000
        const val CONNECT_ATTEMPTS = 10
        const val CONNECT_RETRY_DELAY_MS = 500L
        const val ADDRESS_WAIT_MS = 5_000L
        const val ADDRESS_POLL_MS = 100L
        const val MEDIA_POLL_MS = 500L
        const val PTT_POLL_MS = 100L
        const val CALL_POLL_MS = 200L
        const val DIRECT_RETRY_MS = 5_000L
        const val RECONNECT_WINDOW_MS = 90_000L
        const val BYE_GRACE_MS = 300L

        /** How long a dropped group keeps trying to come back (people can be out of range for a while). */
        const val GROUP_RECONNECT_WINDOW_MS = 30 * 60_000L
        const val REJOIN_TALK_DELAY_MS = 1_000L

        /** Joining with a code: how often to search for the group again. */
        const val CODE_SEARCH_MS = 10_000L

        /** A code group's host: how often to stay in listen mode for searching phones. */
        const val CODE_LISTEN_MS = 25_000L

        /** How a link to a paired phone ends when nobody chose to end it. */
        val DROP_REASONS = setOf(DisconnectReason.LINK_TIMEOUT, DisconnectReason.GROUP_ENDED, DisconnectReason.IO_ERROR)
    }
}
