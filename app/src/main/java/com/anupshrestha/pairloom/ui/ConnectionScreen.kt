package com.anupshrestha.pairloom.ui

import android.net.wifi.p2p.WifiP2pDevice
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.heightIn
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anupshrestha.pairloom.R
import com.anupshrestha.pairloom.media.AudioRoute
import com.anupshrestha.pairloom.media.MediaEnd
import com.anupshrestha.pairloom.media.MediaKind
import com.anupshrestha.pairloom.session.DisconnectReason
import com.anupshrestha.pairloom.session.FailureKind
import com.anupshrestha.pairloom.session.LinkDetails
import com.anupshrestha.pairloom.session.LinkState
import com.anupshrestha.pairloom.session.LinkUiState
import com.anupshrestha.pairloom.session.PairedDeviceUi
import com.anupshrestha.pairloom.session.PeerInfo
import com.anupshrestha.pairloom.session.isActive
import java.util.Locale

/** What the screen can ask the activity to do. */
class ConnectionActions(
    val onHost: () -> Unit,
    val onJoin: () -> Unit,
    val onSelectPeer: (PeerInfo) -> Unit,
    val onRescan: () -> Unit,
    val onDisconnect: () -> Unit,
    val onOpenLocationSettings: () -> Unit,
    val onOpenAppSettings: () -> Unit,
    val onPairingAnswer: (Boolean) -> Unit,
    val onForget: (String) -> Unit,
    val onStartTone: () -> Unit,
    val onStopMedia: () -> Unit,
    val onCall: () -> Unit,
    val onAcceptCall: () -> Unit,
    val onDeclineCall: () -> Unit,
    val onToggleMute: () -> Unit,
    val onToggleSpeaker: () -> Unit,
    val onPushToTalk: () -> Unit,
    /** The talk button was pressed (true) or released (false). */
    val onTalk: (Boolean) -> Unit,
    val onToggleOpus: () -> Unit,
    /** Join a paired phone's group directly (by key fingerprint). */
    val onJoinPaired: (String) -> Unit,
    /** From the home screen: connect to a paired phone that is ready (by key fingerprint). */
    val onConnectPaired: (String) -> Unit,
    /** From the home screen: Connect on both phones; the roles are picked automatically. */
    val onConnectAuto: (String) -> Unit,
)

@Composable
fun ConnectionScreen(
    ui: LinkUiState,
    actions: ConnectionActions,
    modifier: Modifier = Modifier,
) {
    val link = ui.link
    var showDetails by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Text(text = stringResource(R.string.home_tagline), style = MaterialTheme.typography.bodyMedium)

        if (link !is LinkState.Idle) StatusCard(link, ui.waitingFor)

        if (ui.cleaningUp) {
            Text(text = stringResource(R.string.cleaning_up), style = MaterialTheme.typography.bodySmall)
        }

        val canStart = !link.isActive && !ui.cleaningUp
        if (!link.isActive) {
            PairedPhonesHome(ui, canStart, actions)
            NewPhoneCard(hasPaired = ui.pairedDevices.isNotEmpty(), canStart = canStart, actions = actions)
        }

        when (link) {
            is LinkState.NeedsPermissions -> if (link.permanentlyDenied) {
                OutlinedButton(onClick = actions.onOpenAppSettings) {
                    Text(stringResource(R.string.btn_open_app_settings))
                }
            }

            LinkState.LocationServicesOff -> OutlinedButton(onClick = actions.onOpenLocationSettings) {
                Text(stringResource(R.string.btn_open_location_settings))
            }

            is LinkState.Discovering -> DiscoveryList(link, ui.pairedDevices, actions)
            is LinkState.Pairing -> PairingCard(link, actions)
            is LinkState.Linked -> {
                CallCard(link.details, ui.micDenied, actions)
                if (showDetails) {
                    LinkedCard(link.details)
                    MediaCard(link.details, ui.opusEnabled, actions)
                }
            }
            else -> Unit
        }

        if (showDetails && !link.isActive && !ui.cleaningUp) PairedDevicesCard(ui, actions)

        if (ui.locationHint) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = stringResource(R.string.location_hint), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = actions.onOpenLocationSettings) {
                        Text(stringResource(R.string.btn_open_location_settings))
                    }
                }
            }
        }

        if (link.isActive) {
            val label = if (link is LinkState.Linked) R.string.btn_disconnect else R.string.btn_cancel
            OutlinedButton(onClick = actions.onDisconnect) { Text(stringResource(label)) }
        }

        if (showDetails) {
            Text(
                text = stringResource(
                    if (ui.locationServicesOn) R.string.location_status_on else R.string.location_status_off,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // Link statistics, the media test and the event log are for diagnosis, and redrawing them
        // twice a second during a call cost noticeable CPU on the A03s: hidden unless asked for.
        TextButton(onClick = { showDetails = !showDetails }) {
            Text(stringResource(if (showDetails) R.string.btn_hide_details else R.string.btn_show_details))
        }
        if (showDetails) LogCard(ui.log)
    }
}

/** Home: one card per paired phone, with "Connect" and "Be ready". */
@Composable
private fun PairedPhonesHome(ui: LinkUiState, canStart: Boolean, actions: ConnectionActions) {
    if (ui.pairedDevices.isEmpty()) return
    Text(text = stringResource(R.string.home_paired_title), style = MaterialTheme.typography.titleMedium)
    ui.pairedDevices.forEach { d ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text = d.name, style = MaterialTheme.typography.titleLarge)
                Button(
                    onClick = { actions.onConnectAuto(d.fingerprintHex) },
                    enabled = canStart,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) { Text(stringResource(R.string.btn_connect), style = MaterialTheme.typography.titleMedium) }
                Text(
                    text = stringResource(if (d.canJoinDirectly) R.string.home_paired_hint else R.string.home_paired_hint_first, d.name),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** How to pair a new phone: Host on one, Join on the other, compare the code. */
@Composable
private fun NewPhoneCard(hasPaired: Boolean, canStart: Boolean, actions: ConnectionActions) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(if (hasPaired) R.string.home_new_title_more else R.string.home_new_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(text = stringResource(R.string.home_new_steps), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = actions.onHost, enabled = canStart) { Text(stringResource(R.string.btn_host)) }
                Button(onClick = actions.onJoin, enabled = canStart) { Text(stringResource(R.string.btn_join)) }
            }
        }
    }
}

@Composable
private fun StatusCard(link: LinkState, waitingFor: String? = null) {
    val (title, body) = if (waitingFor != null && (link is LinkState.Hosting || link is LinkState.Connecting || link is LinkState.Discovering)) {
        stringResource(R.string.status_waiting_for, waitingFor) to stringResource(R.string.waiting_for_body, waitingFor)
    } else {
        describe(link)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (body.isNotEmpty()) Text(text = body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun describe(link: LinkState): Pair<String, String> = when (link) {
    LinkState.Idle -> stringResource(R.string.status_idle_title) to ""
    is LinkState.NeedsPermissions -> stringResource(R.string.status_needs_permission) to stringResource(
        if (link.permanentlyDenied) R.string.needs_permission_permanent_body else R.string.needs_permission_body,
    )

    LinkState.LocationServicesOff ->
        stringResource(R.string.status_location_off) to stringResource(R.string.location_off_body)

    is LinkState.Hosting -> if (link.groupReady) {
        val name = link.networkName?.let { stringResource(R.string.hosting_network_name, it) }.orEmpty()
        stringResource(R.string.status_hosting_ready) to
            (stringResource(R.string.hosting_ready_body) + if (name.isEmpty()) "" else "\n$name")
    } else {
        stringResource(R.string.status_hosting_starting) to ""
    }

    is LinkState.Discovering -> stringResource(R.string.status_discovering) to stringResource(
        if (link.noneFoundHint) R.string.discovering_none else R.string.discovering_body,
    )

    is LinkState.Connecting ->
        stringResource(R.string.status_connecting, link.peerName) to stringResource(R.string.connecting_body)

    is LinkState.OpeningSocket ->
        stringResource(R.string.status_opening) to stringResource(R.string.opening_body)

    is LinkState.Pairing ->
        stringResource(R.string.status_pairing, link.peerName) to stringResource(R.string.pairing_body)

    is LinkState.Linked -> stringResource(R.string.status_linked) to
        stringResource(R.string.linked_peer, link.details.peerName)

    is LinkState.Disconnected -> stringResource(R.string.status_disconnected) to withDetail(
        stringResource(
            when (link.reason) {
                DisconnectReason.LOCAL -> R.string.disconnected_local
                DisconnectReason.PEER_CLOSED -> R.string.disconnected_peer_closed
                DisconnectReason.LINK_TIMEOUT -> R.string.disconnected_link_timeout
                DisconnectReason.GROUP_ENDED -> R.string.disconnected_group_ended
                DisconnectReason.PROTOCOL_ERROR -> R.string.disconnected_protocol_error
                DisconnectReason.IO_ERROR -> R.string.disconnected_io_error
            },
        ),
        link.detail,
    )

    is LinkState.Failed -> stringResource(R.string.status_failed) to withDetail(
        stringResource(
            when (link.kind) {
                FailureKind.WIFI_OFF -> R.string.failed_wifi_off
                FailureKind.UNSUPPORTED -> R.string.failed_unsupported
                FailureKind.OPERATION_FAILED -> R.string.failed_operation
                FailureKind.CONNECT_TIMEOUT -> R.string.failed_connect_timeout
                FailureKind.SOCKET_FAILED -> R.string.failed_socket
                FailureKind.GROUP_ROLE_MISMATCH -> R.string.failed_group_role_mismatch
                FailureKind.LINK_UNVERIFIED -> R.string.failed_link_unverified
                FailureKind.VERSION_MISMATCH -> R.string.failed_version_mismatch
                FailureKind.IDENTITY_UNAVAILABLE -> R.string.failed_identity_unavailable
                FailureKind.SECURE_HANDSHAKE_FAILED -> R.string.failed_secure_handshake
                FailureKind.IDENTITY_CHANGED -> R.string.failed_identity_changed
                FailureKind.PAIRING_REJECTED -> R.string.failed_pairing_rejected
                FailureKind.PAIRING_TIMEOUT -> R.string.failed_pairing_timeout
                FailureKind.PAIRING_FAILED -> R.string.failed_pairing_failed
                FailureKind.TRUST_STORE_ERROR -> R.string.failed_trust_store
            },
        ),
        link.detail,
    )
}

@Composable
private fun withDetail(message: String, detail: String?): String =
    if (detail.isNullOrBlank()) message else message + "\n" + stringResource(R.string.detail_format, detail)

@Composable
private fun DiscoveryList(state: LinkState.Discovering, paired: List<PairedDeviceUi>, actions: ConnectionActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val direct = paired.filter { it.canJoinDirectly }
        if (direct.isNotEmpty()) {
            Text(text = stringResource(R.string.join_direct_title), style = MaterialTheme.typography.titleSmall)
            direct.forEach { d ->
                Button(onClick = { actions.onJoinPaired(d.fingerprintHex) }) { Text(stringResource(R.string.btn_join_direct, d.name)) }
            }
            Text(text = stringResource(R.string.join_direct_hint), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
        }
        state.peers.forEach { peer ->
            Card(modifier = Modifier.fillMaxWidth().clickable { actions.onSelectPeer(peer) }) {
                Column(Modifier.padding(12.dp)) {
                    Text(text = peer.name, style = MaterialTheme.typography.titleSmall)
                    val type = stringResource(if (peer.isPhone) R.string.peer_type_phone else R.string.peer_type_other)
                    Text(
                        text = "$type · ${stringResource(statusRes(peer.status))}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        OutlinedButton(onClick = actions.onRescan) { Text(stringResource(R.string.btn_rescan)) }
    }
}

private fun statusRes(status: Int): Int = when (status) {
    WifiP2pDevice.CONNECTED -> R.string.peer_status_connected
    WifiP2pDevice.INVITED -> R.string.peer_status_invited
    WifiP2pDevice.FAILED -> R.string.peer_status_failed
    WifiP2pDevice.UNAVAILABLE -> R.string.peer_status_unavailable
    else -> R.string.peer_status_available
}

@Composable
private fun LinkedCard(details: LinkDetails) {
    val stats = details.stats
    val none = stringResource(R.string.rtt_none)
    fun ms(value: Double?) = value?.let { String.format(Locale.US, "%.1f ms", it) } ?: none
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (details.isGroupOwner) {
                    stringResource(R.string.linked_group_owner_yes)
                } else {
                    stringResource(R.string.linked_group_owner_no, details.ownerAddress.orEmpty())
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.linked_timings, details.groupFormedMs, details.socketMs),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (details.viaFallback) {
                Text(text = stringResource(R.string.linked_via_fallback), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = stringResource(
                    if (details.newlyPaired) R.string.linked_secure_new else R.string.linked_secure_known,
                    details.tlsProtocol,
                    details.peerKey,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.linked_uptime, details.uptimeMs / 1000),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.linked_pings, stats.sent, stats.received, stats.lost),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    R.string.linked_rtt,
                    ms(stats.lastRttMs),
                    ms(stats.avgRttMs),
                    ms(stats.minRttMs),
                    ms(stats.maxRttMs),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun CallCard(details: LinkDetails, micDenied: Boolean, actions: ConnectionActions) {
    val inCall = details.mediaActive && details.mediaKind == MediaKind.VOICE
    val inPtt = details.mediaActive && details.mediaKind == MediaKind.PTT
    val busy = details.mediaActive || details.mediaOffering
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                details.incomingCall -> {
                    val prompt = if (details.incomingKind == MediaKind.PTT) R.string.ptt_incoming else R.string.call_incoming
                    Text(
                        text = stringResource(prompt, details.peerName),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = actions.onAcceptCall) { Text(stringResource(R.string.btn_accept_call)) }
                        OutlinedButton(onClick = actions.onDeclineCall) { Text(stringResource(R.string.btn_decline_call)) }
                    }
                }
                inCall -> {
                    Text(text = stringResource(R.string.call_active, details.peerName), style = MaterialTheme.typography.titleLarge)
                    CallStatusLines(details)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = actions.onToggleMute) {
                            Text(stringResource(if (details.muted) R.string.btn_unmute else R.string.btn_mute))
                        }
                        OutlinedButton(onClick = actions.onToggleSpeaker) {
                            Text(stringResource(if (details.speakerOn) R.string.btn_earpiece else R.string.btn_speaker))
                        }
                    }
                    Button(onClick = actions.onStopMedia) { Text(stringResource(R.string.btn_end_call)) }
                    details.media?.let { s ->
                        Text(
                            text = stringResource(
                                R.string.call_quality,
                                String.format(Locale.US, "%.1f", s.lossPercent + if (s.played + s.concealed > 0) 100.0 * s.concealed / (s.played + s.concealed) else 0.0),
                                s.estimatedOneWayMs?.let { String.format(Locale.US, "%.0f", it) } ?: "–",
                                s.codec.name,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                inPtt -> PushToTalkPanel(details, actions)
                details.mediaOffering -> {
                    Text(text = stringResource(R.string.call_calling, details.peerName), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = actions.onStopMedia) { Text(stringResource(R.string.btn_cancel)) }
                }
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = actions.onCall, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 64.dp)) {
                            Text(stringResource(R.string.btn_call), style = MaterialTheme.typography.titleMedium)
                        }
                        FilledTonalButton(onClick = actions.onPushToTalk, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 64.dp)) {
                            Text(stringResource(R.string.btn_push_to_talk), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    endMessage(details)?.let {
                        Text(text = it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    if (micDenied) Text(text = stringResource(R.string.call_mic_denied), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** The route, and a warning while nothing arrives from the other phone. */
@Composable
private fun CallStatusLines(details: LinkDetails) {
    details.audioRoute?.let { r ->
        val route = stringResource(
            when (r) {
                AudioRoute.EARPIECE -> R.string.route_earpiece
                AudioRoute.SPEAKER -> R.string.route_speaker
                AudioRoute.HEADSET -> R.string.route_headset
            },
        )
        Text(text = stringResource(R.string.call_route, route), style = MaterialTheme.typography.bodySmall)
    }
    val s = details.media
    if (s != null && s.signalLost) {
        Text(
            text = stringResource(R.string.call_signal_lost, details.peerName, (s.silentMs / 1000).toInt()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun endMessage(details: LinkDetails): String? {
    val end = details.lastEnd ?: return null
    val what = stringResource(if (details.lastEndKind == MediaKind.PTT) R.string.end_what_ptt else R.string.end_what_call)
    val whatTitle = what.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    return when (end) {
        MediaEnd.ENDED_HERE -> stringResource(R.string.end_here, whatTitle)
        MediaEnd.ENDED_BY_PEER -> stringResource(R.string.end_by_peer, whatTitle, details.peerName)
        MediaEnd.MISSED -> stringResource(R.string.end_missed, what, details.peerName)
        MediaEnd.DECLINED_HERE -> stringResource(R.string.end_declined_here, whatTitle)
        MediaEnd.DECLINED_BY_PEER -> stringResource(R.string.end_declined_by_peer, details.peerName, what)
        MediaEnd.FAILED -> stringResource(R.string.end_failed, whatTitle)
        MediaEnd.AUDIO_TAKEN -> stringResource(R.string.end_audio_taken, whatTitle)
    }
}

/** Push-to-talk: hold the big button to talk; shows who is talking. */
@Composable
private fun PushToTalkPanel(details: LinkDetails, actions: ConnectionActions) {
    val s = details.media
    Text(text = stringResource(R.string.ptt_active, details.peerName), style = MaterialTheme.typography.titleLarge)
    CallStatusLines(details)
    val status = when {
        s?.talking == true && s.peerTalking -> stringResource(R.string.ptt_both_talking, details.peerName)
        s?.talking == true -> stringResource(R.string.ptt_you_talking)
        s?.peerTalking == true -> stringResource(R.string.ptt_peer_talking, details.peerName)
        else -> stringResource(R.string.ptt_quiet)
    }
    Text(
        text = status,
        style = MaterialTheme.typography.titleMedium,
        color = if (s?.peerTalking == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )

    // Holding is hard with TalkBack (and for some hands), so tap-to-talk is used automatically while
    // TalkBack's touch exploration is on, and can be chosen by hand.
    val touchExploration = rememberTouchExploration()
    var tapChosen by rememberSaveable { mutableStateOf(false) }
    val tapMode = tapChosen || touchExploration
    var talking by remember { mutableStateOf(false) }
    val onTalk by rememberUpdatedState(actions.onTalk)
    fun setTalking(on: Boolean) {
        if (talking == on) return
        talking = on
        onTalk(on)
    }
    // Never leave the microphone sending if the panel goes away or the mode changes mid-press.
    DisposableEffect(tapMode) { onDispose { if (talking) setTalking(false) } }
    val idleText = stringResource(if (tapMode) R.string.ptt_tap else R.string.ptt_hold)
    val activeText = stringResource(if (tapMode) R.string.ptt_tap_stop else R.string.ptt_release)
    val stateText = stringResource(if (talking) R.string.ptt_state_talking else R.string.ptt_state_listening)
    val surfaceModifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 140.dp)
        .semantics {
            role = Role.Button
            stateDescription = stateText
        }
    val gestures = if (tapMode) {
        Modifier.clickable(onClickLabel = if (talking) activeText else idleText) { setTalking(!talking) }
    } else {
        Modifier.pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    setTalking(true)
                    try {
                        tryAwaitRelease()
                    } finally {
                        setTalking(false)
                    }
                },
            )
        }
    }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = if (talking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
        modifier = surfaceModifier.then(gestures),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (talking) activeText else idleText,
                style = MaterialTheme.typography.headlineSmall,
                color = if (talking) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().toggleable(
        value = tapMode,
        enabled = !touchExploration,
        role = Role.Switch,
        onValueChange = { tapChosen = it },
    )) {
        Text(
            text = stringResource(if (touchExploration) R.string.ptt_tap_mode_talkback else R.string.ptt_tap_mode),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = tapMode, onCheckedChange = null, enabled = !touchExploration)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = actions.onToggleSpeaker) {
            Text(stringResource(if (details.speakerOn) R.string.btn_earpiece else R.string.btn_speaker))
        }
        Button(onClick = actions.onStopMedia) { Text(stringResource(R.string.btn_end_ptt)) }
    }
}

@Composable
private fun MediaCard(details: LinkDetails, opusEnabled: Boolean, actions: ConnectionActions) {
    val toneActive = details.mediaActive && details.mediaKind == MediaKind.TONE
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = stringResource(R.string.media_title), style = MaterialTheme.typography.titleSmall)
            when {
                toneActive -> OutlinedButton(onClick = actions.onStopMedia) { Text(stringResource(R.string.btn_stop_tone)) }
                details.mediaActive || details.mediaOffering || details.incomingCall -> Unit
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = actions.onStartTone) { Text(stringResource(R.string.btn_start_tone)) }
                    OutlinedButton(onClick = actions.onToggleOpus) {
                        Text(stringResource(if (opusEnabled) R.string.btn_opus_on else R.string.btn_opus_off))
                    }
                }
            }
            val s = details.media ?: return@Column
            fun f(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "–"
            Text(
                text = stringResource(R.string.media_packets, s.sent, s.received, s.lost, String.format(Locale.US, "%.2f", s.lossPercent), s.codec.name),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.media_frames, s.played, s.intact, s.corrupted, s.concealed, s.late),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.media_timing, f(s.rttAvgMs), f(s.rttMinMs), f(s.rttMaxMs), f(s.jitterMs), f(s.bufferMs), f(s.targetMs), f(s.spreadMs)),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.media_security, s.authFailures, s.wrongSource, s.duplicates, f(s.estimatedOneWayMs)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PairingCard(state: LinkState.Pairing, actions: ConnectionActions) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = stringResource(R.string.pairing_code_label), style = MaterialTheme.typography.titleSmall)
            val spoken = state.code.filter { it.isDigit() }.toList().joinToString(" ")
            Text(
                text = state.code,
                style = MaterialTheme.typography.displayMedium,
                fontFamily = FontFamily.Monospace,
                // TalkBack reads "4 8 2 ..." rather than "four hundred eighty-two thousand ...".
                modifier = Modifier.semantics { contentDescription = spoken },
            )
            if (state.waitingForPeer) {
                Text(text = stringResource(R.string.pairing_waiting), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(text = stringResource(R.string.pairing_question), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { actions.onPairingAnswer(true) }) { Text(stringResource(R.string.btn_codes_match)) }
                    OutlinedButton(onClick = { actions.onPairingAnswer(false) }) { Text(stringResource(R.string.btn_codes_differ)) }
                }
            }
        }
    }
}

@Composable
private fun PairedDevicesCard(ui: LinkUiState, actions: ConnectionActions) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = stringResource(R.string.paired_title), style = MaterialTheme.typography.titleSmall)
            ui.ownKey?.let {
                Text(text = stringResource(R.string.own_key, it), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            if (ui.pairedDevices.isEmpty()) {
                Text(text = stringResource(R.string.paired_none), style = MaterialTheme.typography.bodySmall)
            }
            ui.pairedDevices.forEach { device ->
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(text = device.name, style = MaterialTheme.typography.bodyMedium)
                        Text(text = device.shortKey, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    OutlinedButton(onClick = { actions.onForget(device.fingerprintHex) }) { Text(stringResource(R.string.btn_forget)) }
                }
            }
        }
    }
}

@Composable
private fun LogCard(lines: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = stringResource(R.string.log_title), style = MaterialTheme.typography.titleSmall)
            HorizontalDivider()
            if (lines.isEmpty()) {
                Text(text = stringResource(R.string.log_empty), style = MaterialTheme.typography.bodySmall)
            } else {
                lines.takeLast(MAX_VISIBLE_LOG_LINES).forEach { line ->
                    Text(text = line, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp)
                }
            }
        }
    }
}

private const val MAX_VISIBLE_LOG_LINES = 40

/** Whether TalkBack (or another touch-exploration service) is on, kept up to date. */
@Composable
private fun rememberTouchExploration(): Boolean {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(AccessibilityManager::class.java) }
    var on by remember { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return on
}
