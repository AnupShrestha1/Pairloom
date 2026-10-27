package com.anupshrestha.pairloom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anupshrestha.pairloom.R
import com.anupshrestha.pairloom.media.MediaKind
import com.anupshrestha.pairloom.session.LinkDetails
import com.anupshrestha.pairloom.session.LinkState
import com.anupshrestha.pairloom.session.LinkUiState
import com.anupshrestha.pairloom.session.PairedDeviceUi
import com.anupshrestha.pairloom.session.isActive
import com.anupshrestha.pairloom.ui.theme.LocalThreads
import java.util.Locale

/**
 * Everything that is not talking, in plain sections: this phone's name, your phones (rename,
 * forget, pair a new one), the voice codec, and troubleshooting information folded away at the end.
 */
@Composable
fun SettingsScreen(
    ui: LinkUiState,
    actions: ConnectionActions,
    onClose: () -> Unit,
    onPairNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val closeLabel = stringResource(R.string.btn_back)
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = closeLabel }) {
                GlyphIcon(Glyph.BACK, MaterialTheme.colorScheme.onBackground)
            }
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            val link = ui.link
            Section(stringResource(R.string.this_phone_title)) { ThisPhone(ui, actions) }
            Section(stringResource(R.string.your_phones_title)) {
                YourPhones(ui, actions)
                Spacer(Modifier.heightIn(min = 4.dp))
                OutlinedButton(
                    onClick = onPairNew,
                    enabled = !link.isActive && !ui.cleaningUp,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text(stringResource(R.string.btn_pair_new), style = MaterialTheme.typography.labelLarge) }
            }
            Section(stringResource(R.string.calls_title)) {
                SwitchRow(
                    title = stringResource(R.string.opus_title),
                    body = stringResource(R.string.opus_body),
                    checked = ui.opusEnabled,
                    onToggle = actions.onToggleOpus,
                )
            }
            Troubleshooting(ui, actions, link)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun Fact(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = { onToggle() }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ThisPhone(ui: LinkUiState, actions: ConnectionActions) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    val own = ui.ownName ?: return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(own, style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.this_phone_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = { renaming = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground)) {
            Text(stringResource(R.string.btn_rename))
        }
    }
    if (renaming) {
        RenameDialog(stringResource(R.string.rename_this_phone), own) { renaming = false; if (it != null) actions.onRenameThisPhone(it) }
    }
}

@Composable
private fun YourPhones(ui: LinkUiState, actions: ConnectionActions) {
    if (ui.pairedDevices.isEmpty()) Fact(stringResource(R.string.paired_none))
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var forgetting by rememberSaveable { mutableStateOf<String?>(null) }
    val canForget = !ui.link.isActive && !ui.cleaningUp
    ui.pairedDevices.forEach { device ->
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(device.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    stringResource(R.string.phone_key, device.shortKey),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { renaming = device.fingerprintHex }) { Text(stringResource(R.string.btn_rename)) }
                    TextButton(
                        onClick = { forgetting = device.fingerprintHex },
                        enabled = canForget,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(stringResource(R.string.btn_forget)) }
                }
            }
        }
    }
    if (!canForget && ui.pairedDevices.isNotEmpty()) Fact(stringResource(R.string.forget_while_linked))
    ui.pairedDevices.firstOrNull { it.fingerprintHex == renaming }?.let { target ->
        RenameDialog(stringResource(R.string.rename_paired, target.name), target.name, hint = stringResource(R.string.rename_paired_hint)) {
            renaming = null
            if (it != null) actions.onRenamePaired(target.fingerprintHex, it)
        }
    }
    ui.pairedDevices.firstOrNull { it.fingerprintHex == forgetting }?.let { target ->
        ForgetDialog(target, onDone = { confirmed ->
            forgetting = null
            if (confirmed) actions.onForget(target.fingerprintHex)
        })
    }
}

@Composable
private fun ForgetDialog(device: PairedDeviceUi, onDone: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onDone(false) },
        title = { Text(stringResource(R.string.forget_title, device.name)) },
        text = { Text(stringResource(R.string.forget_body)) },
        confirmButton = {
            TextButton(onClick = { onDone(true) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(R.string.btn_forget))
            }
        },
        dismissButton = { TextButton(onClick = { onDone(false) }) { Text(stringResource(R.string.btn_cancel)) } },
    )
}

/** Statistics, the test tone and the event log: folded away, for when something does not work. */
@Composable
private fun Troubleshooting(ui: LinkUiState, actions: ConnectionActions, link: LinkState) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = { open = !open },
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) { Text(stringResource(if (open) R.string.btn_hide_troubleshooting else R.string.btn_show_troubleshooting)) }
        if (!open) return@Column
        if (link is LinkState.Linked) {
            Text(stringResource(R.string.details_link), style = MaterialTheme.typography.titleMedium)
            LinkFacts(link.details)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(stringResource(R.string.media_title), style = MaterialTheme.typography.titleMedium)
            MediaFacts(link.details, actions)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Fact(stringResource(if (ui.locationServicesOn) R.string.location_status_on else R.string.location_status_off))
        ui.ownKey?.let { Fact(stringResource(R.string.own_key, it)) }
        Text(stringResource(R.string.log_title), style = MaterialTheme.typography.titleMedium)
        Log(ui.log)
    }
}

@Composable
private fun LinkFacts(details: LinkDetails) {
    val stats = details.stats
    val none = stringResource(R.string.rtt_none)
    fun ms(value: Double?) = value?.let { String.format(Locale.US, "%.1f ms", it) } ?: none
    Fact(
        if (details.isGroupOwner) stringResource(R.string.linked_group_owner_yes)
        else stringResource(R.string.linked_group_owner_no, details.ownerAddress.orEmpty()),
    )
    Fact(stringResource(R.string.linked_timings, details.groupFormedMs, details.socketMs))
    if (details.viaFallback) Fact(stringResource(R.string.linked_via_fallback))
    Fact(stringResource(if (details.newlyPaired) R.string.linked_secure_new else R.string.linked_secure_known, details.tlsProtocol, details.peerKey))
    Fact(stringResource(R.string.linked_uptime, details.uptimeMs / 1000))
    Fact(stringResource(R.string.linked_pings, stats.sent, stats.received, stats.lost))
    Fact(stringResource(R.string.linked_rtt, ms(stats.lastRttMs), ms(stats.avgRttMs), ms(stats.minRttMs), ms(stats.maxRttMs)))
}

@Composable
private fun MediaFacts(details: LinkDetails, actions: ConnectionActions) {
    val toneActive = details.mediaActive && details.mediaKind == MediaKind.TONE
    when {
        toneActive -> OutlinedButton(onClick = actions.onStopMedia) { Text(stringResource(R.string.btn_stop_tone)) }
        details.mediaActive || details.mediaOffering || details.incomingCall -> Unit
        else -> OutlinedButton(onClick = actions.onStartTone) { Text(stringResource(R.string.btn_start_tone)) }
    }
    val s = details.media ?: return
    fun f(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "–"
    Fact(stringResource(R.string.media_packets, s.sent, s.received, s.lost, String.format(Locale.US, "%.2f", s.lossPercent), s.codec.name))
    Fact(stringResource(R.string.media_frames, s.played, s.intact, s.corrupted, s.concealed, s.late))
    Fact(stringResource(R.string.media_timing, f(s.rttAvgMs), f(s.rttMinMs), f(s.rttMaxMs), f(s.jitterMs), f(s.bufferMs), f(s.targetMs), f(s.spreadMs)))
    Fact(stringResource(R.string.media_security, s.authFailures, s.wrongSource, s.duplicates, f(s.estimatedOneWayMs)))
}

@Composable
private fun Log(lines: List<String>) {
    if (lines.isEmpty()) {
        Fact(stringResource(R.string.log_empty))
        return
    }
    lines.takeLast(40).forEach { line ->
        Text(line, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
