package com.anupshrestha.pairloom.session

import android.app.Application
import android.content.Context
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
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
    private var clientSinceMs: Long? = null
    private var groupPollJob: Job? = null

    private var socketJob: Job? = null
    private var timeoutJob: Job? = null
    private var discoveryHintJob: Job? = null

    /** The adopted control channel. Only ever set on the main thread by [onLinked]. */
    @Volatile private var channel: ControlChannel? = null

    /** Every socket the current session opened; closed as a whole by [teardown]. */
    @Volatile private var sockets = SocketRegistry()

    // ---- Entry points called by the UI -------------------------------------------------------

    /** Starts a session in [role]. Ignored while a session is active or still cleaning up. */
    fun start(role: Role) {
        val state = _ui.value
        if (state.link.isActive || state.cleaningUp) {
            log("Ignored start request: a session is already active or being cleaned up")
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
        if (JoinFallback.isClientOfPeer(group, peer.address)) {
            if (clientSinceMs == null) {
                clientSinceMs = now
                log("Android lists this phone as a client of ${peer.name} (group ${group?.networkName}, ${group?.frequencyMhz ?: "?"} MHz) but has not reported the group as formed yet")
            }
            if (JoinFallback.shouldUse(group, peer.address, clientSinceMs, now)) useJoinFallback(gen)
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
                Role.HOST -> createGroup(gen, attempt = 1)
                Role.JOIN -> discover(gen, attempt = 1)
            }
        }
    }

    private fun createGroup(gen: Int, attempt: Int) {
        val c = controller ?: return
        requestedAtMs = SystemClock.elapsedRealtime()
        c.createGroup { result ->
            if (gen != generation) return@createGroup
            when {
                result.ok -> log("createGroup accepted by the system; waiting for the group to form")
                result.reason == WifiP2pManager.BUSY && attempt < MAX_BUSY_RETRIES ->
                    retryAfterBusy(gen) { createGroup(gen, attempt + 1) }

                else -> onOperationFailed("createGroup", result)
            }
        }
    }

    private fun discover(gen: Int, attempt: Int) {
        val c = controller ?: return
        c.discoverPeers { result ->
            if (gen != generation) return@discoverPeers
            when {
                result.ok -> {
                    log("Peer discovery started")
                    armDiscoveryHint(gen)
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
        log("Peers: ${sorted.joinToString { "${it.name}[${if (it.isPhone) "phone" else "other"},${statusName(it.status)}]" }.ifEmpty { "none" }}")
        _ui.update {
            it.copy(
                link = state.copy(peers = sorted, noneFoundHint = false),
                locationHint = if (sorted.isNotEmpty()) false else it.locationHint,
            )
        }
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
                    val peer = targetPeerAddress
                    if (peer != null && JoinFallback.isClientOfPeer(group, peer)) {
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
            val peer = targetPeerAddress
            val problem = when {
                group == null -> "Android reported no Wi-Fi Direct group"
                interfaceName.isNullOrEmpty() -> "Android did not report the group's network interface"
                group.isGroupOwner != isGroupOwner -> "Android's group info disagrees about which phone owns the group"
                !isGroupOwner && peer != null && !JoinFallback.isClientOfPeer(group, peer) ->
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
            try {
                val endpoint = LinkEndpoint.resolve(interfaceName)
                    ?: throw LinkUnverifiedException("$interfaceName has no IPv4 address")
                LinkEndpoint.checkEndpoint(endpoint)?.let { throw LinkUnverifiedException(it) }
                log("Wi-Fi Direct endpoint: $endpoint")
                val (opened, peerName) = if (isGroupOwner) {
                    LinkEndpoint.checkHostAddress(endpoint, ownerAddress)?.let { throw LinkUnverifiedException(it) }
                    acceptPeer(endpoint, registry)
                } else {
                    val target = LinkEndpoint.parseIpv4(ownerAddress)
                        ?: throw LinkUnverifiedException("the group owner address is unknown")
                    LinkEndpoint.checkPeer(endpoint, target)?.let { throw LinkUnverifiedException("group owner $it") }
                    connectToOwner(endpoint, target, registry)
                }
                ch = opened
                withContext(Dispatchers.Main) {
                    // Only a still-current session may adopt the channel; otherwise finally closes it.
                    if (gen == generation) {
                        onLinked(opened, peerName, isGroupOwner, ownerAddress)
                        handedOver = true
                    }
                }
                if (!handedOver) return@launch
                val exit = opened.run { stats -> onStats(stats, gen) }
                withContext(Dispatchers.Main) { if (gen == generation) onChannelEnded(exit) }
            } catch (e: CancellationException) {
                throw e
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
                if (!handedOver) ch?.close(sendBye = false)
            }
        }
    }

    /**
     * Group owner side: listen on the Wi-Fi Direct address only, and accept the first connection
     * that comes from inside the group's subnet and completes a valid handshake.
     */
    private suspend fun acceptPeer(endpoint: P2pEndpoint, registry: SocketRegistry): Pair<ControlChannel, String> {
        val server = ServerSocket()
        if (!registry.register(server)) throw IOException("the session ended")
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(endpoint.localAddress, PORT))
            server.soTimeout = 1000
            log("Listening on ${endpoint.localAddress.hostAddress}:$PORT (${endpoint.interfaceName} only)")
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
                val ch = try {
                    prepare(socket)
                } catch (e: IOException) {
                    log("Could not set up the connection from ${remote?.hostAddress}: ${e.message}")
                    discard(socket, registry)
                    continue
                }
                try {
                    val peerName = ch.handshake(HANDSHAKE_TIMEOUT_MS)
                    return ch to peerName
                } catch (e: ControlProtocol.VersionMismatchException) {
                    discard(ch, registry)
                    throw e
                } catch (e: IOException) {
                    log("Rejected connection: ${e.message}")
                    discard(ch, registry)
                }
            }
        } finally {
            discard(server, registry)
        }
    }

    /**
     * Client side: connect from this phone's Wi-Fi Direct address to the group owner, retrying
     * while the owner brings its listener up. Both ends are checked before the handshake.
     */
    private suspend fun connectToOwner(
        endpoint: P2pEndpoint,
        target: Inet4Address,
        registry: SocketRegistry,
    ): Pair<ControlChannel, String> {
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
                log("TCP connected to ${target.hostAddress}:$PORT from ${socket.localAddress?.hostAddress} (${endpoint.interfaceName})")
                val ch = prepare(socket)
                val peerName = try {
                    ch.handshake(HANDSHAKE_TIMEOUT_MS)
                } catch (e: IOException) {
                    discard(ch, registry)
                    throw e
                }
                return ch to peerName
            } catch (e: ControlProtocol.VersionMismatchException) {
                discard(socket, registry)
                throw e
            } catch (e: LinkUnverifiedException) {
                discard(socket, registry)
                throw e
            } catch (e: IOException) {
                lastError = e
                log("Connect attempt ${attempt + 1}/$CONNECT_ATTEMPTS failed: ${e.message}")
                discard(socket, registry)
                delay(CONNECT_RETRY_DELAY_MS)
            }
        }
        throw IOException("could not reach ${target.hostAddress}:$PORT (${lastError?.message})")
    }

    private fun discard(resource: Closeable, registry: SocketRegistry) {
        closeQuietly(resource)
        registry.release(resource)
    }

    private fun prepare(socket: Socket): ControlChannel {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        return ControlChannel(socket, Build.MODEL) { log(it) }
    }

    private fun onLinked(ch: ControlChannel, peerName: String, isGroupOwner: Boolean, ownerAddress: String?) {
        channel = ch
        linkedAtMs = SystemClock.elapsedRealtime()
        timeoutJob?.cancel()
        val details = LinkDetails(
            isGroupOwner = isGroupOwner,
            ownerAddress = ownerAddress,
            peerName = peerName,
            groupFormedMs = groupFormedAtMs - requestedAtMs,
            socketMs = linkedAtMs - groupFormedAtMs,
            viaFallback = fallbackUsed,
        )
        log("Linked with $peerName; group formed after ${details.groupFormedMs} ms, TCP handshake ${details.socketMs} ms later${if (details.viaFallback) " (via fallback)" else ""}")
        logStaState("linked")
        setLink(LinkState.Linked(details))
    }

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
        generation++
        timeoutJob?.cancel()
        discoveryHintJob?.cancel()
        groupPollJob?.cancel()
        socketJob?.cancel()
        socketJob = null

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
        clientSinceMs = null

        // Closing may write (the BYE), so it happens off the main thread. The registry is marked
        // closed at once, so any socket a background thread opens from now on is closed on the spot.
        thread(isDaemon = true, name = "pairloom-close") {
            ch?.close(sendBye)
            registry.closeAll()
        }
        if (finalState != null) setLink(finalState)
        if (c != null) {
            _ui.update { it.copy(cleaningUp = true) }
            c.shutdown {
                log("Wi-Fi Direct cleanup finished")
                _ui.update { it.copy(cleaningUp = false) }
            }
        }
    }

    // ---- Helpers -----------------------------------------------------------------------------

    private fun setLink(state: LinkState) {
        _ui.update { it.copy(link = state) }
    }

    private fun refreshLocation() {
        val on = isLocationOn()
        _ui.update { it.copy(locationServicesOn = on) }
    }

    private fun isLocationOn(): Boolean {
        val manager = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
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
    }
}
