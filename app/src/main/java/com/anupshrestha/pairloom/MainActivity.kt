package com.anupshrestha.pairloom

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import com.anupshrestha.pairloom.p2p.P2pPermissions
import com.anupshrestha.pairloom.session.LinkViewModel
import com.anupshrestha.pairloom.session.Role
import com.anupshrestha.pairloom.ui.ConnectionActions
import com.anupshrestha.pairloom.ui.ConnectionScreen
import com.anupshrestha.pairloom.ui.theme.PairloomTheme

class MainActivity : ComponentActivity() {
    private val viewModel: LinkViewModel by viewModels()

    /** The role the user tapped, remembered while the permission dialog is showing. */
    private var pendingRole: Role? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val role = pendingRole
            pendingRole = null
            val missing = P2pPermissions.missing(this)
            if (missing.isEmpty()) {
                if (role != null) viewModel.start(role)
            } else {
                // After a request, a permission that is still missing and that Android will not
                // explain again has been permanently denied ("don't ask again").
                val permanent = missing.any { !shouldShowRequestPermissionRationale(it) }
                viewModel.onPermissionsDenied(permanent)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Version 1 only works while the app is open, so keep the screen from sleeping mid-session.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val actions = ConnectionActions(
            onHost = { requestAndStart(Role.HOST) },
            onJoin = { requestAndStart(Role.JOIN) },
            onSelectPeer = viewModel::connectTo,
            onRescan = viewModel::rescan,
            onDisconnect = viewModel::disconnect,
            onOpenLocationSettings = { openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
            onOpenAppSettings = {
                openSettings(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                )
            },
            onPairingAnswer = viewModel::answerPairing,
            onForget = viewModel::forgetDevice,
            onStartTone = viewModel::startTestTone,
            onStopMedia = viewModel::stopMedia,
            onCall = { withMicrophone(viewModel::startCall) },
            onAcceptCall = { withMicrophone(viewModel::acceptCall) },
            onDeclineCall = viewModel::declineCall,
            onToggleMute = viewModel::toggleMute,
            onToggleSpeaker = viewModel::toggleSpeaker,
        )
        setContent {
            PairloomTheme {
                val ui by viewModel.ui.collectAsState()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ConnectionScreen(ui = ui, actions = actions, modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    override fun onStop() {
        super.onStop()
        // Version 1 is foreground-only: no call continues while the app is not visible. A rotation
        // or other configuration change also stops the activity, but the call (held by the view
        // model) must survive it.
        if (!isChangingConfigurations) viewModel.onAppHidden()
    }

    /** What to do once the microphone permission is granted (call or accept). */
    private var pendingMicAction: (() -> Unit)? = null

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingMicAction
            pendingMicAction = null
            if (granted) action?.invoke() else viewModel.onMicrophoneDenied()
        }

    /** The microphone permission is asked for only when the user starts or accepts a call. */
    private fun withMicrophone(action: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingMicAction = action
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** Permissions are requested only when the user starts a session, never at launch. */
    private fun requestAndStart(role: Role) {
        val missing = P2pPermissions.missing(this)
        if (missing.isEmpty()) {
            viewModel.start(role)
        } else {
            pendingRole = role
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // No settings screen to show on this device; the on-screen explanation still applies.
        }
    }
}
