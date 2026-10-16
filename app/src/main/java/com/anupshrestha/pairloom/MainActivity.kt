package com.anupshrestha.pairloom

import android.content.ActivityNotFoundException
import android.content.Intent
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
