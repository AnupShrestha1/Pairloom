package com.anupshrestha.pairloom.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anupshrestha.pairloom.R
import com.anupshrestha.pairloom.media.MediaKind
import com.anupshrestha.pairloom.session.LinkDetails
import com.anupshrestha.pairloom.session.LinkState
import com.anupshrestha.pairloom.session.LinkUiState
import com.anupshrestha.pairloom.session.isActive
import java.util.Locale

/**
 * Everything that is for diagnosis rather than for talking: link statistics, the test tone and
 * codec switch, the paired phones' keys (and Forget), location status and the event log.
 */
@Composable
fun DetailsScreen(ui: LinkUiState, actions: ConnectionActions, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val closeLabel = stringResource(R.string.btn_back)
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = closeLabel }) {
                GlyphIcon(Glyph.BACK, MaterialTheme.colorScheme.onBackground)
            }
            Text(stringResource(R.string.details_title), style = MaterialTheme.typography.titleMedium)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val link = ui.link
            if (link is LinkState.Linked) {
                Section(stringResource(R.string.details_link)) { LinkFacts(link.details) }
                Section(stringResource(R.string.media_title)) { MediaFacts(link.details, ui.opusEnabled, actions) }
            }
            Section(stringResource(R.string.this_phone_title)) { ThisPhone(ui, actions) }
            if (!link.isActive && !ui.cleaningUp) {
                Section(stringResource(R.string.paired_title)) { PairedPhones(ui, actions) }
            }
            Text(
                text = stringResource(if (ui.locationServicesOn) R.string.location_status_on else R.string.location_status_off),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Section(stringResource(R.string.log_title)) { Log(ui.log) }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        content()
    }
}

@Composable
private fun Fact(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

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
private fun MediaFacts(details: LinkDetails, opusEnabled: Boolean, actions: ConnectionActions) {
    val toneActive = details.mediaActive && details.mediaKind == MediaKind.TONE
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
    val s = details.media ?: return
    fun f(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "–"
    Fact(stringResource(R.string.media_packets, s.sent, s.received, s.lost, String.format(Locale.US, "%.2f", s.lossPercent), s.codec.name))
    Fact(stringResource(R.string.media_frames, s.played, s.intact, s.corrupted, s.concealed, s.late))
    Fact(stringResource(R.string.media_timing, f(s.rttAvgMs), f(s.rttMinMs), f(s.rttMaxMs), f(s.jitterMs), f(s.bufferMs), f(s.targetMs), f(s.spreadMs)))
    Fact(stringResource(R.string.media_security, s.authFailures, s.wrongSource, s.duplicates, f(s.estimatedOneWayMs)))
}

@Composable
private fun ThisPhone(ui: LinkUiState, actions: ConnectionActions) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    val own = ui.ownName ?: return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(own, style = MaterialTheme.typography.bodyLarge)
            ui.ownKey?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        TextButton(onClick = { renaming = true }) { Text(stringResource(R.string.btn_rename)) }
    }
    if (renaming) {
        RenameDialog(stringResource(R.string.rename_this_phone), own) { renaming = false; if (it != null) actions.onRenameThisPhone(it) }
    }
}

@Composable
private fun PairedPhones(ui: LinkUiState, actions: ConnectionActions) {
    if (ui.pairedDevices.isEmpty()) Fact(stringResource(R.string.paired_none))
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    ui.pairedDevices.forEach { device ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.bodyLarge)
                Text(device.shortKey, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { renaming = device.fingerprintHex }) { Text(stringResource(R.string.btn_rename)) }
            OutlinedButton(onClick = { actions.onForget(device.fingerprintHex) }) { Text(stringResource(R.string.btn_forget)) }
        }
    }
    val target = ui.pairedDevices.firstOrNull { it.fingerprintHex == renaming }
    if (target != null) {
        RenameDialog(stringResource(R.string.rename_paired, target.name), target.name, hint = stringResource(R.string.rename_paired_hint)) {
            renaming = null
            if (it != null) actions.onRenamePaired(target.fingerprintHex, it)
        }
    }
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
