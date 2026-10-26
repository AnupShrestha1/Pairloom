package com.anupshrestha.pairloom

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
    /** The process-wide link ([PairloomApp]): it outlives this activity, so calls go on in the background. */
    private val viewModel: LinkViewModel get() = (application as PairloomApp).link

    /** What the user tapped, run once the permission dialog has been answered with a grant. */
    private var pendingStart: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val action = pendingStart
            pendingStart = null
            val missing = P2pPermissions.missing(this)
            if (missing.isEmpty()) {
                action?.invoke()
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
            onPushToTalk = { withMicrophone(viewModel::startPushToTalk) },
            onTalk = viewModel::setTalking,
            onToggleOpus = viewModel::toggleOpus,
            onJoinPaired = viewModel::joinPaired,
            onConnectPaired = { fp -> requestThen { viewModel.connectPaired(fp) } },
            onConnectAuto = { fp -> requestThen { viewModel.connectAuto(fp) } },
        )
        handleAnswer(intent)
        setContent {
            PairloomTheme {
                val ui by viewModel.ui.collectAsState()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ConnectionScreen(ui = ui, actions = actions, modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAnswer(intent)
    }

    /** "Answer" on the incoming-call notification opens the app and accepts here, in the foreground. */
    private fun handleAnswer(intent: Intent?) {
        if (intent?.action != LinkService.ACTION_ANSWER) return
        intent.action = null
        withMicrophone(viewModel::acceptCall)
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppShown()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    override fun onStop() {
        super.onStop()
        // Calls go on in the background (the link lives in PairloomApp, LinkService keeps the process
        // in the foreground). A configuration change is not the app being hidden.
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
    private fun requestAndStart(role: Role) = requestThen { viewModel.start(role) }

    private fun requestThen(action: () -> Unit) {
        val missing = P2pPermissions.missing(this)
        // The notification permission is asked for once, together with the others, but never
        // required: without it a call still goes on in the background, just without a notification.
        val notifications = if (Build.VERSION.SDK_INT >= 33 && !askedNotifications &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        if (missing.isEmpty() && notifications.isEmpty()) {
            action()
        } else {
            askedNotifications = true
            pendingStart = action
            permissionLauncher.launch((missing + notifications).toTypedArray())
        }
    }

    private var askedNotifications = false

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // No settings screen to show on this device; the on-screen explanation still applies.
        }
    }
}
