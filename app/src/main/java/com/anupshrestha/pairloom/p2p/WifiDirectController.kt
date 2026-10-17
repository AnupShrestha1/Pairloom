package com.anupshrestha.pairloom.p2p

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.MacAddress
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.anupshrestha.pairloom.session.GroupSnapshot
import com.anupshrestha.pairloom.session.PeerInfo
import java.util.concurrent.atomic.AtomicBoolean

/** Outcome of a single [WifiP2pManager] request. */
class P2pResult(val ok: Boolean, val reason: Int)

sealed interface P2pEvent {
    data class P2pEnabled(val enabled: Boolean) : P2pEvent
    data class PeersChanged(val peers: List<PeerInfo>) : P2pEvent
    data class ConnectionChanged(
        val groupFormed: Boolean,
        val isGroupOwner: Boolean,
        val ownerAddress: String?,
    ) : P2pEvent

    data class ThisDeviceChanged(val name: String?) : P2pEvent
    data object ChannelLost : P2pEvent
}

/**
 * Thin wrapper around [WifiP2pManager]. Everything runs on the main thread: the channel is
 * initialised with the main looper, so every callback arrives there as well.
 *
 * Lifecycle: [start] registers the broadcast receiver, [shutdown] undoes everything. Neither the
 * receiver nor the channel outlives [shutdown], and calling it twice is harmless.
 *
 * Permissions are checked by the caller before use. Each request is wrapped so that a revoked
 * permission is reported as a failed [P2pResult] instead of crashing.
 */
@SuppressLint("MissingPermission")
class WifiDirectController(
    private val appContext: Context,
    private val log: (String) -> Unit,
) {
    var onEvent: ((P2pEvent) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    onEvent?.invoke(P2pEvent.P2pEnabled(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED))
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnection()
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = IntentCompat.getParcelableExtra(
                        intent,
                        WifiP2pManager.EXTRA_WIFI_P2P_DEVICE,
                        WifiP2pDevice::class.java,
                    )
                    onEvent?.invoke(P2pEvent.ThisDeviceChanged(device?.deviceName))
                }
            }
        }
    }

    /** Creates the channel and registers the receiver. Returns false if Wi-Fi Direct is unavailable. */
    fun start(): Boolean {
        if (channel != null) return true
        val m = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (m == null) {
            log("WifiP2pManager is not available")
            return false
        }
        val ch = m.initialize(appContext, Looper.getMainLooper()) {
            log("Wi-Fi Direct channel lost")
            onEvent?.invoke(P2pEvent.ChannelLost)
        }
        if (ch == null) {
            log("WifiP2pManager.initialize returned no channel")
            return false
        }
        manager = m
        channel = ch
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        log("Wi-Fi Direct channel opened, receiver registered")
        return true
    }

    /**
     * Cancels discovery and any pending connect, then removes any existing group. Failures are
     * expected (nothing to cancel or remove) and ignored. [done] is always called exactly once.
     */
    fun clearStaleState(done: () -> Unit) {
        releaseAll {
            log("Stale Wi-Fi Direct state cleared")
            done()
        }
    }

    /** Full teardown: clears the group, unregisters the receiver, closes the channel. */
    fun shutdown(done: () -> Unit) {
        releaseAll {
            if (receiverRegistered) {
                receiverRegistered = false
                runCatching { appContext.unregisterReceiver(receiver) }
            }
            val ch = channel
            channel = null
            manager = null
            if (ch != null && Build.VERSION.SDK_INT >= 27) runCatching { ch.close() }
            done()
        }
    }

    /**
     * Creates a group with this phone as owner.
     *
     * On Android 10+ the group is named and non-persistent, so Android does not save it and cannot
     * later restart it in the wrong role. It uses this phone's own [credentials]
     * ([GroupCredentialStore]): never shown or logged, and known only to paired phones, which can
     * then join directly. Any other phone receives them through Android's own Wi-Fi Direct handshake
     * after the user accepts. If Android rejects the named group (for a reason other than being
     * busy), this falls back to the platform's default group.
     */
    fun createGroup(credentials: GroupCredentials, preferredFrequencyMhz: Int? = null, done: (P2pResult) -> Unit) {
        if (Build.VERSION.SDK_INT < 29) {
            createDefaultGroup(done)
            return
        }
        // Running the group on the same channel as this phone's normal Wi-Fi connection avoids
        // multi-channel time sharing, which delivers packets in bursts (measured in milestone A1).
        val frequency = preferredFrequencyMhz?.takeIf { it in 2412..2484 || it in 5160..5885 }
        val config = try {
            WifiP2pConfig.Builder()
                .setNetworkName(credentials.networkName)
                .setPassphrase(credentials.passphrase)
                .enablePersistentMode(false)
                .apply { if (frequency != null) setGroupOperatingFrequency(frequency) }
                .build()
                .also { if (frequency != null) log("Asking for the group on $frequency MHz (this phone's Wi-Fi channel)") }
        } catch (e: IllegalArgumentException) {
            if (frequency != null) {
                log("Group config for $frequency MHz rejected (${e.message}); trying without a fixed channel")
                createGroup(credentials, null, done)
                return
            }
            log("Named group config rejected (${e.message}); using the default group")
            null
        } catch (e: IllegalStateException) {
            log("Named group config rejected (${e.message}); using the default group")
            null
        }
        if (config == null) {
            createDefaultGroup(done)
            return
        }
        request("createGroup(named, non-persistent${if (frequency != null) ", $frequency MHz" else ""})", { result ->
            if (result.ok || result.reason == WifiP2pManager.BUSY) {
                done(result)
            } else if (frequency != null) {
                log("The group on $frequency MHz was refused; trying without a fixed channel")
                createGroup(credentials, null, done)
            } else {
                log("Named group was refused; falling back to the default group")
                createDefaultGroup(done)
            }
        }) { m, ch, l -> m.createGroup(ch, config, l) }
    }

    private fun createDefaultGroup(done: (P2pResult) -> Unit) =
        request("createGroup(default)", done) { m, ch, l -> m.createGroup(ch, l) }

    fun discoverPeers(done: (P2pResult) -> Unit) = request("discoverPeers", done) { m, ch, l ->
        m.discoverPeers(ch, l)
    }

    fun stopDiscovery(done: (P2pResult) -> Unit) = request("stopPeerDiscovery", done) { m, ch, l ->
        m.stopPeerDiscovery(ch, l)
    }

    fun cancelConnect(done: (P2pResult) -> Unit) = request("cancelConnect", done) { m, ch, l ->
        m.cancelConnect(ch, l)
    }

    /**
     * Asks to join the group of [address], preferring to be the client (group owner intent 0).
     *
     * On Android 10+ the request is non-persistent. The plain [WifiP2pConfig] constructor defaults
     * to a persistent group, which makes Android save the group and later restart it, possibly with
     * the roles reversed. Older versions keep the plain constructor.
     */
    fun connect(address: String, done: (P2pResult) -> Unit) = request("connect", done) { m, ch, l ->
        m.connect(ch, connectConfig(address), l)
    }

    /**
     * Joins a paired phone's group directly with its name and passphrase (Android 10+). The host
     * shows no prompt: knowing the passphrase is the permission. Which phone answers is still
     * checked by TLS against the paired key.
     */
    fun connectToGroup(credentials: GroupCredentials, done: (P2pResult) -> Unit) {
        if (Build.VERSION.SDK_INT < 29) {
            done(P2pResult(false, WifiP2pManager.ERROR))
            return
        }
        val config = try {
            WifiP2pConfig.Builder()
                .setNetworkName(credentials.networkName)
                .setPassphrase(credentials.passphrase)
                .enablePersistentMode(false)
                .build()
        } catch (e: IllegalArgumentException) {
            log("Group join config rejected (${e.message})")
            done(P2pResult(false, WifiP2pManager.ERROR))
            return
        }
        request("connect(group ${credentials.networkName})", done) { m, ch, l -> m.connect(ch, config, l) }
    }

    private fun connectConfig(address: String): WifiP2pConfig {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return WifiP2pConfig.Builder()
                    .setDeviceAddress(MacAddress.fromString(address))
                    .enablePersistentMode(false)
                    .build()
                    .apply {
                        wps.setup = WpsInfo.PBC
                        groupOwnerIntent = 0
                    }
            } catch (e: IllegalArgumentException) {
                log("Non-persistent connect config rejected (${e.message}); using the default config")
            }
        }
        return WifiP2pConfig().apply {
            deviceAddress = address
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0
        }
    }

    /** Reports the current group, or null if there is none. Never includes the passphrase. */
    fun requestGroupInfo(done: (GroupSnapshot?) -> Unit) {
        val m = manager ?: return
        val ch = channel ?: return
        try {
            m.requestGroupInfo(ch) { group ->
                done(
                    group?.let {
                        GroupSnapshot(
                            networkName = it.networkName,
                            isGroupOwner = it.isGroupOwner,
                            ownerDeviceAddress = it.owner?.deviceAddress,
                            frequencyMhz = if (Build.VERSION.SDK_INT >= 29) it.frequency else null,
                            clientCount = it.clientList?.size ?: 0,
                            interfaceName = it.`interface`,
                        )
                    },
                )
            }
        } catch (e: SecurityException) {
            log("requestGroupInfo denied: ${e.message}")
        }
    }

    private fun requestPeers() {
        val m = manager ?: return
        val ch = channel ?: return
        try {
            m.requestPeers(ch) { list ->
                val peers = list.deviceList.map { d ->
                    PeerInfo(
                        name = d.deviceName.orEmpty().ifBlank { d.deviceAddress },
                        address = d.deviceAddress,
                        status = d.status,
                        isPhone = d.primaryDeviceType?.startsWith("10-") == true,
                    )
                }
                onEvent?.invoke(P2pEvent.PeersChanged(peers))
            }
        } catch (e: SecurityException) {
            log("requestPeers denied: ${e.message}")
        }
    }

    private fun requestConnection() {
        val m = manager ?: return
        val ch = channel ?: return
        m.requestConnectionInfo(ch) { info ->
            onEvent?.invoke(
                P2pEvent.ConnectionChanged(
                    groupFormed = info.groupFormed,
                    isGroupOwner = info.isGroupOwner,
                    ownerAddress = info.groupOwnerAddress?.hostAddress,
                ),
            )
        }
    }

    private fun request(
        name: String,
        done: (P2pResult) -> Unit,
        block: (WifiP2pManager, WifiP2pManager.Channel, WifiP2pManager.ActionListener) -> Unit,
    ) {
        val m = manager
        val ch = channel
        if (m == null || ch == null) {
            done(P2pResult(false, REASON_NOT_STARTED))
            return
        }
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                done(P2pResult(true, 0))
            }

            override fun onFailure(reason: Int) {
                log("$name failed: ${reasonText(reason)}")
                done(P2pResult(false, reason))
            }
        }
        try {
            block(m, ch, listener)
        } catch (e: SecurityException) {
            log("$name denied: ${e.message}")
            done(P2pResult(false, REASON_PERMISSION))
        }
    }

    /**
     * stopPeerDiscovery, then cancelConnect, then remove any group that exists. Failures of the
     * first two are expected (nothing to stop or cancel) and ignored. A group left behind by an
     * earlier session must really be gone before a new session starts: otherwise Android may
     * keep this phone as the owner of the old group and the two phones end up in separate groups.
     */
    private fun releaseAll(done: () -> Unit) {
        val finished = AtomicBoolean(false)
        val finish = { if (finished.compareAndSet(false, true)) done() }
        if (manager == null || channel == null) {
            finish()
            return
        }
        // Safety net: some devices never call back when there is nothing to remove.
        handler.postDelayed({
            if (!finished.get()) log("Wi-Fi Direct cleanup timed out; continuing")
            finish()
        }, RELEASE_TIMEOUT_MS)
        stopDiscovery {
            cancelConnect {
                removeGroupIfPresent { finish() }
            }
        }
    }

    private fun removeGroupIfPresent(done: () -> Unit) {
        val m = manager
        val ch = channel
        if (m == null || ch == null) {
            done()
            return
        }
        try {
            m.requestGroupInfo(ch) { group ->
                if (group == null) {
                    done()
                } else {
                    log("Removing an existing group (${group.networkName})")
                    removeGroupWithRetry(1, done)
                }
            }
        } catch (e: SecurityException) {
            log("requestGroupInfo denied during cleanup: ${e.message}")
            done()
        }
    }

    private fun removeGroupWithRetry(attempt: Int, done: () -> Unit) {
        request("removeGroup", { result ->
            when {
                result.ok -> awaitGroupGone(0, done)
                result.reason == WifiP2pManager.BUSY && attempt < MAX_REMOVE_ATTEMPTS ->
                    handler.postDelayed({ removeGroupWithRetry(attempt + 1, done) }, REMOVE_RETRY_MS)

                else -> done()
            }
        }) { m, ch, l -> m.removeGroup(ch, l) }
    }

    /** removeGroup is asynchronous: poll until the group is really gone. */
    private fun awaitGroupGone(poll: Int, done: () -> Unit) {
        val m = manager
        val ch = channel
        if (m == null || ch == null) {
            done()
            return
        }
        try {
            m.requestGroupInfo(ch) { group ->
                if (group == null) {
                    log("Group removed")
                    done()
                } else if (poll >= MAX_GONE_POLLS) {
                    log("Group still present after ${MAX_GONE_POLLS * GONE_POLL_MS} ms; continuing")
                    done()
                } else {
                    handler.postDelayed({ awaitGroupGone(poll + 1, done) }, GONE_POLL_MS)
                }
            }
        } catch (e: SecurityException) {
            done()
        }
    }

    companion object {
        const val REASON_NOT_STARTED = -2
        const val REASON_PERMISSION = -1
        private const val RELEASE_TIMEOUT_MS = 10_000L
        private const val MAX_REMOVE_ATTEMPTS = 4
        private const val REMOVE_RETRY_MS = 600L
        private const val MAX_GONE_POLLS = 10
        private const val GONE_POLL_MS = 300L

        fun reasonText(reason: Int): String = when (reason) {
            WifiP2pManager.ERROR -> "internal error"
            WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported"
            WifiP2pManager.BUSY -> "system is busy"
            WifiP2pManager.NO_SERVICE_REQUESTS -> "no service requests"
            REASON_PERMISSION -> "permission denied"
            REASON_NOT_STARTED -> "controller not started"
            else -> "code $reason"
        }
    }
}
