package com.anupshrestha.pairloom.ui

import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.anupshrestha.pairloom.session.JoinCodeUi
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import com.anupshrestha.pairloom.net.ControlProtocol.LinkQuality
import com.anupshrestha.pairloom.session.GroupDetails
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import com.anupshrestha.pairloom.ui.theme.LocalThreads
import androidx.compose.foundation.background
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
    /** Join the group push-to-talk (Phase 7). */
    val onGroupTalk: () -> Unit,
    /** Debug builds: add or remove the simulated group member on the hosting phone. */
    val onToggleVirtualPhone: () -> Unit = {},
    /** "Start a group": this phone hosts a group others join with a code (Phase 7, G6). */
    val onStartCodeGroup: () -> Unit = {},
    /** "Join with a code"; false if the text is not a join code. */
    val onJoinWithCode: (String) -> Boolean = { false },
    /** "Back" from a failed code join. */
    val onDismissCodeProblem: () -> Unit = {},
    /** The talk button was pressed (true) or released (false). */
    val onTalk: (Boolean) -> Unit,
    val onToggleOpus: () -> Unit,
    /** Join a paired phone's group directly (by key fingerprint). */
    val onJoinPaired: (String) -> Unit,
    /** From the home screen: connect to a paired phone that is ready (by key fingerprint). */
    val onConnectPaired: (String) -> Unit,
    /** From the home screen: Connect on both phones; the roles are picked automatically. */
    val onConnectAuto: (String) -> Unit,
    val onRenameThisPhone: (String) -> Unit,
    /** A local nickname for a paired phone (key fingerprint, name; blank removes it). */
    val onRenamePaired: (String, String) -> Unit,
    val onAppearance: (com.anupshrestha.pairloom.session.Appearance) -> Unit,
)

/**
 * One layout for every state (docs/plans/phase6-ux.md): the two rings and the other phone's name
 * at the top, what is happening in one line under it, and the actions at the bottom, where a thumb
 * rests. Diagnostics live on a separate details screen.
 */
@Composable
fun ConnectionScreen(ui: LinkUiState, actions: ConnectionActions, modifier: Modifier = Modifier) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var pairingNew by rememberSaveable { mutableStateOf(false) }
    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(
            ui, actions,
            onClose = { showSettings = false },
            onPairNew = { showSettings = false; pairingNew = true },
            modifier = modifier,
        )
        return
    }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val link = ui.link
    LaunchedEffect(link.isActive) { if (link.isActive) pairingNew = false }
    if (pairingNew) BackHandler { pairingNew = false }

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        TopBar(onSettings = { showSettings = true })
        val reconnecting = ui.reconnectingTo?.takeIf { link !is LinkState.Linked }
        val device = ui.pairedDevices.firstOrNull { it.fingerprintHex == selected } ?: ui.pairedDevices.firstOrNull()
        // One key per screen; a change of screen cross-fades instead of cutting. The fading-out
        // screen only draws if the state still fits it, so it never shows half-switched data.
        val screen = when {
            reconnecting != null -> Screen.RECONNECTING
            link is LinkState.Linked -> when {
                link.details.incomingCall -> Screen.INCOMING
                link.details.mediaActive && link.details.mediaKind == MediaKind.VOICE -> Screen.CALL
                link.details.mediaActive && link.details.mediaKind == MediaKind.PTT -> Screen.PTT
                else -> Screen.LINKED
            }
            link is LinkState.Pairing -> Screen.PAIRING
            link is LinkState.Discovering && ui.waitingFor == null && ui.joinCode == null -> Screen.DISCOVERING
            link is LinkState.Discovering || link is LinkState.Hosting || link is LinkState.Connecting || link is LinkState.OpeningSocket -> Screen.CONNECTING
            ui.codeProblem && !link.isActive -> Screen.CODE_FAILED
            pairingNew || device == null -> Screen.NEW_PHONE
            else -> Screen.HOME
        }
        AnimatedContent(
            targetState = screen,
            transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(160)) },
            modifier = Modifier.weight(1f),
            label = "screen",
        ) { shown ->
            Column(Modifier.fillMaxSize()) {
                when (shown) {
                    Screen.RECONNECTING -> ui.reconnectingTo?.let { Reconnecting(it, ui.reconnectingGroup, actions) }
                    Screen.INCOMING, Screen.CALL, Screen.PTT, Screen.LINKED ->
                        (link as? LinkState.Linked)?.let { Linked(it.details, ui.micDenied, ui.joinCode, actions) }
                    Screen.PAIRING -> (link as? LinkState.Pairing)?.let { Pairing(it, actions) }
                    Screen.DISCOVERING -> (link as? LinkState.Discovering)?.let { Discovering(it, ui.waitingFor, actions) }
                    Screen.CONNECTING -> if (link.isActive) {
                        val code = ui.joinCode
                        if (code != null) CodeConnecting(link, code, actions) else Connecting(link, ui.waitingFor, actions)
                    }
                    Screen.CODE_FAILED -> CodeFailed(ui, actions)
                    Screen.NEW_PHONE -> NewPhone(firstTime = device == null, ui = ui, actions = actions, onCancel = { pairingNew = false })
                    Screen.HOME -> device?.let { Home(it, ui, actions, onSelect = { fp -> selected = fp }, onPairNew = { pairingNew = true }) }
                }
            }
        }
    }
}

private enum class Screen { RECONNECTING, INCOMING, CALL, PTT, LINKED, PAIRING, DISCOVERING, CONNECTING, CODE_FAILED, NEW_PHONE, HOME }

@Composable
private fun TopBar(onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSettings, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onBackground)) {
            GlyphIcon(Glyph.SETTINGS, MaterialTheme.colorScheme.onBackground, size = 20.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * The rings, the big name and the status line, centred, with the state's own content under them;
 * [actions] go at the bottom of the screen.
 */
@Composable
private fun ColumnScope.Stage(
    mode: RingsMode,
    title: String,
    status: String?,
    statusColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    peerLevel: Float = 0f,
    selfTalking: Boolean = false,
    ringing: Boolean = false,
    /** How a screen reader announces status changes; null for a status that changes every second. */
    announce: LiveRegionMode? = LiveRegionMode.Polite,
    content: @Composable ColumnScope.() -> Unit = {},
    actions: @Composable ColumnScope.() -> Unit,
) {
    // The rings, name and status sit centred in the space above the actions; on a short screen (or
    // with large text) that space scrolls instead.
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val room = maxHeight
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = room),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
        Spacer(Modifier.height(16.dp))
        LinkRings(mode, peerLevel = peerLevel, selfTalking = selfTalking, ringing = ringing)
        Spacer(Modifier.height(28.dp))
        Text(title, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
        if (status != null) {
            Spacer(Modifier.height(6.dp))
            val spoken = "$title, $status" // "Galaxy, is talking", not just "is talking"
            Text(
                status,
                style = MaterialTheme.typography.titleLarge,
                color = statusColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics {
                    contentDescription = spoken
                    if (announce != null) liveRegion = announce
                },
            )
        }
        Spacer(Modifier.height(20.dp))
        content()
        Spacer(Modifier.height(16.dp))
        }
    }
    Column(
        Modifier.fillMaxWidth().padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { actions() }
}

@Composable
private fun Body(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(text, style = MaterialTheme.typography.bodyLarge, color = color, textAlign = TextAlign.Center)

/** The main action: full width, tall, in this phone's colour (or red to end something). */
@Composable
private fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    glyph: Glyph? = null,
) {
    val threads = LocalThreads.current
    val colors = if (danger) {
        ButtonDefaults.buttonColors(containerColor = threads.hangUp, contentColor = threads.onHangUp)
    } else {
        ButtonDefaults.buttonColors()
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = colors,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 60.dp),
    ) {
        if (glyph != null) {
            GlyphIcon(glyph, if (danger) threads.onHangUp else MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun outlinedOnBackground() = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground)

/** A secondary action: outlined, full width, plainly visible (Pair a new phone, Disconnect, Cancel). */
@Composable
private fun SecondButton(text: String, onClick: () -> Unit, enabled: Boolean = true) =
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(20.dp),
        colors = outlinedOnBackground(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }

@Composable
private fun QuietButton(text: String, onClick: () -> Unit, enabled: Boolean = true) =
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }

// ---- Not connected ------------------------------------------------------------------------------

@Composable
private fun ColumnScope.Home(
    device: PairedDeviceUi,
    ui: LinkUiState,
    actions: ConnectionActions,
    onSelect: (String) -> Unit,
    onPairNew: () -> Unit,
) {
    val canStart = !ui.cleaningUp
    val problem = ui.groupEndedBy?.let { stringResource(R.string.status_disconnected) to stringResource(R.string.group_ended_by, it) }
        ?: problemOf(ui.link)
    Stage(
        mode = RingsMode.APART,
        title = device.name,
        status = problem?.first ?: stringResource(R.string.status_not_connected),
        statusColor = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        content = {
            if (problem?.second != null) Body(problem.second!!)
            if (ui.cleaningUp) Body(stringResource(R.string.cleaning_up))
            LocationAndPermission(ui, actions)
            if (ui.pairedDevices.size > 1) {
                Spacer(Modifier.height(16.dp))
                OtherPhones(ui.pairedDevices.filter { it.fingerprintHex != device.fingerprintHex }, canStart, actions, onSelect)
            }
        },
    ) {
        Text(
            stringResource(if (device.canJoinDirectly) R.string.home_hint else R.string.home_hint_first, device.name),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        BigButton(
            stringResource(if (problem != null) R.string.btn_try_again else R.string.btn_connect),
            onClick = { actions.onConnectAuto(device.fingerprintHex) },
            enabled = canStart,
        )
        // Connect picks who hosts from the two keys. When the other phone already hosts a group
        // with other phones, this phone must join it, whatever the keys say.
        if (device.canJoinDirectly && android.os.Build.VERSION.SDK_INT >= 29) {
            QuietButton(stringResource(R.string.btn_join_group_of, device.name), { actions.onConnectPaired(device.fingerprintHex) }, enabled = canStart)
        }
        CodeGroupButtons(canStart, actions)
        SecondButton(stringResource(R.string.btn_pair_new), onPairNew, enabled = canStart)
    }
}

/** The other paired phones, each with its own Connect; tapping a name shows it at the top. */
@Composable
private fun OtherPhones(phones: List<PairedDeviceUi>, canStart: Boolean, actions: ConnectionActions, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.other_phones),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        phones.forEach { p ->
            Surface(
                onClick = { onSelect(p.fingerprintHex) },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(LocalThreads.current.peer, CircleShape))
                    Spacer(Modifier.width(14.dp))
                    Text(p.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onSelect(p.fingerprintHex); actions.onConnectAuto(p.fingerprintHex) }, enabled = canStart) {
                        Text(stringResource(R.string.btn_connect))
                    }
                }
            }
        }
    }
}

/** First run, or "Pair a new phone": one phone hosts, the other joins it. */
@Composable
private fun ColumnScope.NewPhone(firstTime: Boolean, ui: LinkUiState, actions: ConnectionActions, onCancel: () -> Unit) {
    val problem = problemOf(ui.link)
    Stage(
        mode = RingsMode.APART,
        title = stringResource(if (firstTime) R.string.new_title_first else R.string.btn_pair_new),
        status = problem?.first ?: stringResource(R.string.new_status),
        statusColor = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        content = {
            Body(problem?.second ?: stringResource(R.string.new_body))
            LocationAndPermission(ui, actions)
        },
    ) {
        ChoiceButton(stringResource(R.string.btn_host), stringResource(R.string.host_explain), primary = true, enabled = !ui.cleaningUp, onClick = actions.onHost)
        ChoiceButton(stringResource(R.string.btn_join), stringResource(R.string.join_explain), primary = false, enabled = !ui.cleaningUp, onClick = actions.onJoin)
        // A group by code needs no pairing at all, so it is offered on the first screen too.
        if (firstTime) CodeGroupButtons(!ui.cleaningUp, actions)
        if (!firstTime) SecondButton(stringResource(R.string.btn_cancel), onCancel)
        ui.ownName?.let { own ->
            var renaming by rememberSaveable { mutableStateOf(false) }
            val called = stringResource(R.string.this_phone_is_called, own)
            val rename = stringResource(R.string.btn_rename)
            val accent = MaterialTheme.colorScheme.primary
            TextButton(onClick = { renaming = true }) {
                Text(
                    buildAnnotatedString {
                        append(called)
                        append(" ")
                        withStyle(SpanStyle(color = accent)) { append(rename) }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (renaming) {
                RenameDialog(
                    title = stringResource(R.string.rename_this_phone),
                    initial = own,
                    onDone = { renaming = false; if (it != null) actions.onRenameThisPhone(it) },
                )
            }
        }
    }
}

/** A big choice: a short title and one line saying what it does. */
@Composable
private fun ChoiceButton(title: String, explain: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val threads = LocalThreads.current
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(20.dp),
        color = if (primary) scheme.primary else Color.Transparent,
        border = if (primary) null else BorderStroke(1.5.dp, threads.self),
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (primary) scheme.onPrimary else scheme.onBackground)
            Text(explain, style = MaterialTheme.typography.bodyMedium, color = if (primary) scheme.onPrimary else scheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun LocationAndPermission(ui: LinkUiState, actions: ConnectionActions) {
    val link = ui.link
    if (link is LinkState.NeedsPermissions && link.permanentlyDenied) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = actions.onOpenAppSettings) { Text(stringResource(R.string.btn_open_app_settings)) }
    }
    if (link == LinkState.LocationServicesOff || ui.locationHint) {
        Spacer(Modifier.height(8.dp))
        if (ui.locationHint) Body(stringResource(R.string.location_hint))
        OutlinedButton(onClick = actions.onOpenLocationSettings) { Text(stringResource(R.string.btn_open_location_settings)) }
    }
}

/** A failed or ended link as (what happened, what to do), or null if nothing went wrong. */
@Composable
private fun problemOf(link: LinkState): Pair<String, String?>? = when (link) {
    is LinkState.Disconnected -> when (link.reason) {
        DisconnectReason.LOCAL -> null
        else -> stringResource(R.string.status_disconnected) to stringResource(
            when (link.reason) {
                DisconnectReason.PEER_CLOSED -> R.string.disconnected_peer_closed
                DisconnectReason.LINK_TIMEOUT -> R.string.disconnected_link_timeout
                DisconnectReason.GROUP_ENDED -> R.string.disconnected_group_ended
                DisconnectReason.PROTOCOL_ERROR -> R.string.disconnected_protocol_error
                else -> R.string.disconnected_io_error
            },
        )
    }
    is LinkState.Failed -> stringResource(R.string.status_failed) to stringResource(
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
            FailureKind.JOIN_CODE_REJECTED -> R.string.failed_join_code
            FailureKind.TRUST_STORE_ERROR -> R.string.failed_trust_store
        },
    )
    is LinkState.NeedsPermissions -> stringResource(R.string.status_needs_permission) to stringResource(
        if (link.permanentlyDenied) R.string.needs_permission_permanent_body else R.string.needs_permission_body,
    )
    LinkState.LocationServicesOff -> stringResource(R.string.status_location_off) to stringResource(R.string.location_off_body)
    else -> null
}

// ---- Connecting -----------------------------------------------------------------------------------

@Composable
private fun ColumnScope.Connecting(link: LinkState, waitingFor: String?, actions: ConnectionActions) {
    val title = waitingFor ?: when (link) {
        is LinkState.Connecting -> link.peerName
        else -> stringResource(R.string.hosting_title)
    }
    val body = when {
        waitingFor != null -> stringResource(R.string.waiting_for_body, waitingFor)
        link is LinkState.Hosting -> stringResource(R.string.hosting_ready_body)
        link is LinkState.Connecting -> stringResource(R.string.connecting_body)
        else -> null
    }
    Stage(
        mode = RingsMode.SEEKING,
        title = title,
        status = stringResource(if (link is LinkState.Hosting && waitingFor == null) R.string.status_waiting else R.string.status_connecting_short),
        content = { if (body != null) Body(body) },
    ) {
        OutlinedButton(onClick = actions.onDisconnect, shape = RoundedCornerShape(20.dp), colors = outlinedOnBackground(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.btn_cancel), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ColumnScope.Discovering(state: LinkState.Discovering, waitingFor: String?, actions: ConnectionActions) {
    if (waitingFor != null) {
        Connecting(state, waitingFor, actions)
        return
    }
    Stage(
        mode = RingsMode.SEEKING,
        title = stringResource(R.string.discover_title),
        status = stringResource(if (state.peers.isEmpty()) R.string.discover_looking else R.string.discover_pick),
        content = {
            if (state.noneFoundHint) Body(stringResource(R.string.discovering_none))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.peers.forEach { peer ->
                    Surface(
                        onClick = { actions.onSelectPeer(peer) },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                    ) {
                        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).background(LocalThreads.current.peer, CircleShape))
                            Spacer(Modifier.width(14.dp))
                            Text(peer.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
    ) {
        SecondButton(stringResource(R.string.btn_rescan), actions.onRescan)
        OutlinedButton(onClick = actions.onDisconnect, shape = RoundedCornerShape(20.dp), colors = outlinedOnBackground(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.btn_cancel), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ColumnScope.Reconnecting(peer: String, inGroup: Boolean, actions: ConnectionActions) {
    Stage(
        mode = RingsMode.SEEKING,
        title = peer,
        status = stringResource(R.string.status_reconnecting),
        content = { Body(if (inGroup) stringResource(R.string.reconnecting_group_body, peer) else stringResource(R.string.reconnecting_body)) },
    ) {
        OutlinedButton(onClick = actions.onDisconnect, shape = RoundedCornerShape(20.dp), colors = outlinedOnBackground(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.btn_cancel), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ColumnScope.Pairing(state: LinkState.Pairing, actions: ConnectionActions) {
    val spoken = state.code.filter { it.isDigit() }.toList().joinToString(" ")
    Stage(
        mode = RingsMode.JOINED,
        title = stringResource(R.string.pairing_check_title),
        status = stringResource(if (state.waitingForPeer) R.string.pairing_waiting else R.string.pairing_check_status, state.peerName),
        content = {
            Text(
                state.code,
                style = MaterialTheme.typography.displayMedium,
                // TalkBack reads "4 8 2 ..." rather than "four hundred eighty-two thousand ...".
                modifier = Modifier.semantics { contentDescription = spoken },
            )
        },
    ) {
        if (!state.waitingForPeer) {
            BigButton(stringResource(R.string.btn_codes_match), onClick = { actions.onPairingAnswer(true) })
            SecondButton(stringResource(R.string.btn_codes_differ), { actions.onPairingAnswer(false) })
        }
    }
}

// ---- Linked ---------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.Linked(details: LinkDetails, micDenied: Boolean, joinCode: JoinCodeUi?, actions: ConnectionActions) {
    val threads = LocalThreads.current
    val inCall = details.mediaActive && details.mediaKind == MediaKind.VOICE
    val inPtt = details.mediaActive && details.mediaKind == MediaKind.PTT
    when {
        details.incomingCall -> Stage(
            mode = RingsMode.JOINED,
            title = details.peerName,
            status = stringResource(if (details.incomingKind == MediaKind.PTT) R.string.incoming_ptt_status else R.string.incoming_call_status),
            statusColor = threads.peerText,
            ringing = true,
            announce = LiveRegionMode.Assertive,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton(stringResource(R.string.btn_decline_call), actions.onDeclineCall, Modifier.weight(1f), danger = true, glyph = Glyph.HANG_UP)
                BigButton(stringResource(R.string.btn_accept_call), actions.onAcceptCall, Modifier.weight(1f), glyph = Glyph.CALL)
            }
        }
        inCall -> InCall(details, actions)
        inPtt -> PushToTalk(details, actions)
        details.mediaOffering -> Stage(
            mode = RingsMode.JOINED,
            title = details.peerName,
            status = stringResource(R.string.calling_status),
        ) {
            BigButton(stringResource(R.string.btn_cancel), actions.onStopMedia, danger = true, glyph = Glyph.HANG_UP)
        }
        else -> Stage(
            mode = RingsMode.JOINED,
            title = details.peerName,
            status = endMessage(details) ?: stringResource(R.string.status_connected),
            content = {
                if (micDenied) Body(stringResource(R.string.call_mic_denied), MaterialTheme.colorScheme.error)
                // More than two phones on the link: say who else is here.
                details.group?.takeIf { it.members.size > 2 || joinCode != null }?.let { GroupMembers(it) }
                joinCode?.let { JoinCodeLine(it.words) }
            },
        ) {
            // A group joined by code is for group talk only: no private calls with its members.
            if (joinCode == null) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton(stringResource(R.string.btn_call), actions.onCall, Modifier.weight(1f), glyph = Glyph.CALL)
                OutlinedButton(
                    onClick = actions.onPushToTalk,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(2.dp, threads.peer),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
                    modifier = Modifier.weight(1f).heightIn(min = 60.dp),
                ) { Text(stringResource(R.string.btn_push_to_talk), style = MaterialTheme.typography.labelLarge) }
            }
            details.group?.let { group ->
                val others = group.members.count { it.inTalk && !it.isThisPhone }
                SecondButton(
                    if (others > 0) pluralStringResource(R.plurals.btn_join_group_talk, others, others) else stringResource(R.string.btn_group_talk),
                    actions.onGroupTalk,
                    enabled = !group.joining,
                )
            }
            DisconnectButton(details, joinCode != null, actions)
        }
    }
}

/** "Join code: maple lotus otter", so someone can still be let in later. */
@Composable
private fun JoinCodeLine(words: String) {
    Spacer(Modifier.height(10.dp))
    Text(
        stringResource(R.string.join_code_line, words),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** Starting or joining a group by code: the host shows the code; a joining phone says what it does. */
@Composable
private fun ColumnScope.CodeConnecting(link: LinkState, code: JoinCodeUi, actions: ConnectionActions) {
    Stage(
        mode = RingsMode.SEEKING,
        title = if (code.isHost) code.words else stringResource(R.string.code_joining_title),
        status = stringResource(
            when {
                code.isHost -> R.string.code_host_status
                link is LinkState.Discovering -> R.string.code_looking_status
                else -> R.string.code_found_status
            },
        ),
        content = {
            Body(
                stringResource(
                    when {
                        code.isHost -> R.string.code_host_body
                        link is LinkState.Discovering -> R.string.code_join_body
                        else -> R.string.code_found_body
                    },
                ),
            )
        },
    ) {
        OutlinedButton(onClick = actions.onDisconnect, shape = RoundedCornerShape(20.dp), colors = outlinedOnBackground(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.btn_cancel), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Joining or starting a group by code went wrong: say what happened and offer another code. */
@Composable
private fun ColumnScope.CodeFailed(ui: LinkUiState, actions: ConnectionActions) {
    val problem = problemOf(ui.link)
    var asking by rememberSaveable { mutableStateOf(false) }
    Stage(
        mode = RingsMode.APART,
        title = stringResource(R.string.code_failed_title),
        status = problem?.first ?: stringResource(R.string.status_failed),
        statusColor = MaterialTheme.colorScheme.error,
        content = {
            problem?.second?.let { Body(it) }
            LocationAndPermission(ui, actions)
        },
    ) {
        BigButton(stringResource(R.string.btn_try_another_code), onClick = { asking = true }, enabled = !ui.cleaningUp)
        SecondButton(stringResource(R.string.btn_back), actions.onDismissCodeProblem)
    }
    if (asking) JoinCodeDialog(actions, onClose = { asking = false })
}

/** "Start a group" and "Join with a code", side by side, with the dialog that asks for the code. */
@Composable
private fun CodeGroupButtons(enabled: Boolean, actions: ConnectionActions) {
    var asking by rememberSaveable { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = actions.onStartCodeGroup,
            enabled = enabled,
            shape = RoundedCornerShape(20.dp),
            colors = outlinedOnBackground(),
            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
        ) { Text(stringResource(R.string.btn_start_group), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
        OutlinedButton(
            onClick = { asking = true },
            enabled = enabled,
            shape = RoundedCornerShape(20.dp),
            colors = outlinedOnBackground(),
            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
        ) { Text(stringResource(R.string.btn_join_with_code), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
    }
    if (asking) JoinCodeDialog(actions, onClose = { asking = false })
}

/** Asks for the three words. Android 9 and older cannot join a group by code, so it says so instead. */
@Composable
private fun JoinCodeDialog(actions: ConnectionActions, onClose: () -> Unit) {
    if (android.os.Build.VERSION.SDK_INT < 29) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text(stringResource(R.string.join_code_title)) },
            text = { Text(stringResource(R.string.join_code_old_android), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.btn_ok)) } },
        )
        return
    }
    run {
        var text by rememberSaveable { mutableStateOf("") }
        var wrong by rememberSaveable { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text(stringResource(R.string.join_code_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.join_code_body), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it; wrong = false },
                        singleLine = true,
                        isError = wrong,
                        placeholder = { Text(stringResource(R.string.join_code_hint)) },
                        supportingText = if (wrong) ({ Text(stringResource(R.string.join_code_invalid)) }) else null,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { if (actions.onJoinWithCode(text)) onClose() else wrong = true }) {
                    Text(stringResource(R.string.btn_join))
                }
            },
            dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.btn_cancel)) } },
        )
    }
}

/**
 * Disconnect. On the hosting phone with other phones depending on it (beyond the one on the main
 * link), it asks first: the group ends for everyone.
 */
@Composable
private fun DisconnectButton(details: LinkDetails, codeGroup: Boolean, actions: ConnectionActions) {
    val others = details.group?.members.orEmpty()
        .filter { !it.isThisPhone && it.quality != LinkQuality.LOST }
        .map { it.name }
    var asking by rememberSaveable { mutableStateOf(false) }
    SecondButton(stringResource(if (codeGroup) R.string.btn_leave_group else R.string.btn_disconnect), {
        if (details.isGroupOwner && others.size > 1) asking = true else actions.onDisconnect()
    })
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.end_group_title)) },
            text = { Text(stringResource(R.string.end_group_body, others.joinToString(", "))) },
            confirmButton = {
                TextButton(onClick = { asking = false; actions.onDisconnect() }) {
                    Text(stringResource(R.string.btn_end_group), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.btn_keep_group)) } },
        )
    }
}

@Composable
private fun ColumnScope.InCall(details: LinkDetails, actions: ConnectionActions) {
    val s = details.media
    val seconds = (s?.uptimeMs ?: 0L) / 1000
    Stage(
        mode = RingsMode.JOINED,
        title = details.peerName,
        status = String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60),
        peerLevel = s?.peerLevel ?: 0f,
        announce = null, // the timer
        content = {
            routeName(details.audioRoute)?.let { Body(it) }
            if (s != null && s.signalLost) {
                Spacer(Modifier.height(8.dp))
                Body(stringResource(R.string.call_signal_lost, details.peerName, (s.silentMs / 1000).toInt()), MaterialTheme.colorScheme.error)
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            RoundToggle(
                glyph = if (details.muted) Glyph.MIC_OFF else Glyph.MIC,
                label = stringResource(if (details.muted) R.string.btn_unmute else R.string.btn_mute),
                on = details.muted,
                onClick = actions.onToggleMute,
            )
            RoundToggle(
                glyph = if (details.speakerOn) Glyph.SPEAKER else Glyph.EARPIECE,
                label = stringResource(R.string.btn_speaker_short),
                on = details.speakerOn,
                onClick = actions.onToggleSpeaker,
            )
        }
        Spacer(Modifier.height(6.dp))
        BigButton(stringResource(R.string.btn_end_call), actions.onStopMedia, danger = true, glyph = Glyph.HANG_UP)
    }
}

@Composable
private fun routeName(route: AudioRoute?): String? = when (route) {
    AudioRoute.EARPIECE -> stringResource(R.string.route_earpiece_title)
    AudioRoute.SPEAKER -> stringResource(R.string.route_speaker_title)
    AudioRoute.HEADSET -> stringResource(R.string.route_headset_title)
    null -> null
}

/** A round on/off control with its label under it (mute, speaker). */
@Composable
private fun RoundToggle(glyph: Glyph, label: String, on: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.toggleable(value = on, role = Role.Switch, onValueChange = { onClick() }),
    ) {
        Surface(
            shape = CircleShape,
            color = if (on) scheme.onBackground else Color.Transparent,
            border = BorderStroke(1.5.dp, if (on) scheme.onBackground else scheme.outline),
            modifier = Modifier.size(64.dp),
        ) {
            Box(contentAlignment = Alignment.Center) { GlyphIcon(glyph, if (on) scheme.background else scheme.onBackground, size = 28.dp) }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
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
        MediaEnd.LINK_LOST -> stringResource(R.string.end_link_lost, whatTitle)
    }
}

// ---- Push-to-talk ---------------------------------------------------------------------------------

/** The talk pad fills the bottom of the screen; who is talking shows in the rings and the status. */
@Composable
private fun ColumnScope.PushToTalk(details: LinkDetails, actions: ConnectionActions) {
    val threads = LocalThreads.current
    val s = details.media
    // Holding is hard with TalkBack (and for some hands), so tap-to-talk is used automatically while
    // TalkBack's touch exploration is on, and can be chosen by hand.
    val touchExploration = rememberTouchExploration()
    var tapChosen by rememberSaveable { mutableStateOf(false) }
    val tapMode = tapChosen || touchExploration
    var talking by remember { mutableStateOf(false) }
    val onTalk by rememberUpdatedState(actions.onTalk)
    val haptics = LocalHapticFeedback.current
    fun setTalking(on: Boolean) {
        if (talking == on) return
        talking = on
        // A firm tick when the microphone goes live, a light one when it stops: no need to look.
        haptics.performHapticFeedback(if (on) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
        onTalk(on)
    }
    // Never leave the microphone sending if the panel goes away or the mode changes mid-press.
    DisposableEffect(tapMode) { onDispose { if (talking) setTalking(false) } }
    val peerTalking = s?.peerTalking == true
    // In the group talk the talker is whoever has the floor, and pressing while someone else has
    // it is not heard (the hub drops it), so say so.
    val group = details.group?.takeIf { it.inTalk }
    val talker = group?.floorHolder?.takeUnless { group.floorIsMine } ?: details.peerName
    val busy = talking && group != null && group.floorHolder != null && !group.floorIsMine
    // A press that started while someone else had the floor stays unheard until it is let go,
    // even once the floor is free (the hub's rule), so the screen keeps saying so.
    var shutOutThisPress by remember { mutableStateOf(false) }
    LaunchedEffect(talking, busy) { if (!talking) shutOutThisPress = false else if (busy) shutOutThisPress = true }
    val unheard = busy || (talking && shutOutThisPress)
    val status = when {
        busy -> stringResource(R.string.group_busy, talker)
        unheard -> stringResource(R.string.group_press_again)
        group != null && talking -> stringResource(R.string.ptt_you_talking)
        group != null && peerTalking -> stringResource(R.string.group_peer_talking, talker)
        talking && peerTalking -> stringResource(R.string.ptt_both_talking)
        talking -> stringResource(R.string.ptt_you_talking)
        peerTalking -> stringResource(R.string.ptt_peer_talking)
        else -> stringResource(R.string.ptt_quiet)
    }
    Stage(
        mode = RingsMode.JOINED,
        title = if (group != null) stringResource(R.string.group_talk_title) else details.peerName,
        status = status,
        statusColor = if (peerTalking) threads.peerText else MaterialTheme.colorScheme.onSurfaceVariant,
        peerLevel = if (peerTalking) maxOf(s?.peerLevel ?: 0f, 0.35f) else 0f,
        selfTalking = talking && !unheard,
        content = {
            routeName(details.audioRoute)?.let { Body(it) }
            group?.let { GroupMembers(it) }
        },
    ) {
        val idleText = stringResource(if (tapMode) R.string.ptt_tap else R.string.ptt_hold)
        val activeText = stringResource(if (tapMode) R.string.ptt_tap_stop else R.string.ptt_release)
        val stateText = stringResource(if (talking) R.string.ptt_state_talking else R.string.ptt_state_listening)
        val gestures = if (tapMode) {
            Modifier.clickable(onClickLabel = if (talking) activeText else idleText) { setTalking(!talking) }
        } else {
            Modifier.pointerInput(Unit) {
                detectTapGestures(onPress = {
                    setTalking(true)
                    try {
                        tryAwaitRelease()
                    } finally {
                        setTalking(false)
                    }
                })
            }
        }
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = if (talking) threads.self else MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(2.dp, threads.self),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 150.dp)
                .semantics {
                    role = Role.Button
                    stateDescription = stateText
                }
                .then(gestures),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(20.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GlyphIcon(Glyph.MIC, if (talking) MaterialTheme.colorScheme.onPrimary else threads.self, size = 34.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (talking) activeText else idleText,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (talking) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().toggleable(
                value = tapMode,
                enabled = !touchExploration,
                role = Role.Switch,
                onValueChange = { tapChosen = it },
            ),
        ) {
            Text(
                stringResource(if (touchExploration) R.string.ptt_tap_mode_talkback else R.string.ptt_tap_mode),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = tapMode, onCheckedChange = null, enabled = !touchExploration)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundToggle(
                glyph = if (details.speakerOn) Glyph.SPEAKER else Glyph.EARPIECE,
                label = stringResource(R.string.btn_speaker_short),
                on = details.speakerOn,
                onClick = actions.onToggleSpeaker,
            )
            BigButton(stringResource(R.string.btn_end_ptt_short), actions.onStopMedia, Modifier.weight(1f), danger = true, glyph = Glyph.HANG_UP)
        }
    }
}

/**
 * Who is on the link, in one line: whoever has the floor in the other phone's colour, phones not
 * in the group talk dimmed, and this phone marked.
 */
@Composable
private fun GroupMembers(group: GroupDetails) {
    val threads = LocalThreads.current
    val normal = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val you = stringResource(R.string.group_you)
    val notIn = stringResource(R.string.group_not_in_talk)
    val hostNote = stringResource(R.string.group_host)
    val weak = stringResource(R.string.group_weak_link)
    val away = stringResource(R.string.group_out_of_range)
    val text = buildAnnotatedString {
        group.members.forEachIndexed { i, m ->
            if (i > 0) withStyle(SpanStyle(color = dim)) { append("  ·  ") }
            val talking = m.hasFloor
            val color = when {
                talking -> threads.peerText
                m.quality == LinkQuality.LOST -> dim
                m.quality == LinkQuality.WEAK -> MaterialTheme.colorScheme.error
                m.inTalk -> normal
                else -> dim
            }
            withStyle(SpanStyle(color = color, fontWeight = if (talking) FontWeight.SemiBold else null)) {
                append(m.name)
                val notes = listOfNotNull(
                    you.takeIf { m.isThisPhone },
                    hostNote.takeIf { m.isHost },
                    when (m.quality) {
                        LinkQuality.LOST -> away
                        LinkQuality.WEAK -> weak
                        else -> notIn.takeIf { !m.inTalk }
                    },
                )
                if (notes.isNotEmpty()) append(notes.joinToString(", ", " (", ")"))
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
}

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
