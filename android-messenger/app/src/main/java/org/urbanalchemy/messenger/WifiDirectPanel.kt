package org.urbanalchemy.messenger

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Minimal Wi-Fi Direct controls for exercising discovery and peer connection.
 * A connected socket is ready for UA protocol frames, but this panel does not
 * exchange identities or send chat messages yet.
 */
@Composable
fun WifiDirectPanel() {
    val activity = LocalContext.current as Activity
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var peers by remember { mutableStateOf(emptyList<WifiP2pDevice>()) }
    var status by remember { mutableStateOf("Wi-Fi Direct is idle.") }
    var connected by remember { mutableStateOf(false) }
    var managerError by remember { mutableStateOf<String?>(null) }
    var transportReady by remember { mutableStateOf(false) }

    val managerResult = remember(activity) {
        runCatching {
            WifiDirectPeerManager(activity, object : WifiDirectPeerManager.Listener {
                override fun onPeersAvailable(devices: List<WifiP2pDevice>) {
                    peers = devices
                    status = if (devices.isEmpty()) "No peers found yet. Keep the other device's discovery screen open and scan again."
                    else "Found ${devices.size} nearby peer(s)."
                }

                override fun onConnectionStateChanged(isConnected: Boolean) {
                    mainHandler.post {
                        connected = isConnected
                        if (!isConnected) {
                            transportReady = false
                            status = "Not connected."
                        } else {
                            status = "Wi-Fi Direct group formed. Establishing the peer socket..."
                        }
                    }
                }

                override fun onFrame(frame: ByteArray) {
                    mainHandler.post {
                        status = "Received a ${frame.size}-byte UA frame. Protocol handling is not connected yet."
                    }
                }

                override fun onTransportReady(transport: SocketFrameTransport) {
                    mainHandler.post {
                        transportReady = true
                        status = "Peer socket ready. Secure UA session handshake is the next layer."
                    }
                }

                override fun onError(message: String) {
                    mainHandler.post { status = message }
                }
            })
        }
    }
    val manager = managerResult.getOrNull()
    if (manager == null) managerError = managerResult.exceptionOrNull()?.message ?: "Wi-Fi Direct is unavailable."

    DisposableEffect(manager) {
        manager?.let {
            runCatching { it.start() }
                .onFailure { error -> managerError = error.message ?: "Could not start Wi-Fi Direct." }
        }
        onDispose { manager?.close() }
    }

    val permission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.NEARBY_WIFI_DEVICES
    } else {
        Manifest.permission.ACCESS_FINE_LOCATION
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            runCatching { manager?.discoverPeers() }
                .onSuccess { status = "Searching for nearby devices..." }
                .onFailure { status = it.message ?: "Peer discovery failed." }
        } else {
            status = if (Build.VERSION.SDK_INT >= 33)
                "Nearby devices permission is required to discover and connect."
            else
                "Location permission is required for Wi-Fi Direct discovery on this Android version."
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Direct offline connection", style = MaterialTheme.typography.titleSmall)
            Text(status, style = MaterialTheme.typography.bodySmall)
            if (managerError != null) {
                Text(managerError!!, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = {
                    if (activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                        runCatching { manager?.discoverPeers() }
                            .onSuccess { status = "Searching for nearby devices..." }
                            .onFailure { status = it.message ?: "Peer discovery failed." }
                    } else {
                        permissionLauncher.launch(permission)
                    }
                },
                enabled = manager != null && !connected
            ) { Text("Find nearby devices") }
            if (peers.isNotEmpty()) {
                Text("Nearby devices", style = MaterialTheme.typography.labelLarge)
                peers.forEach { peer ->
                    TextButton(
                        onClick = {
                            runCatching { manager?.connect(peer) }
                                .onSuccess { status = "Connecting to ${peer.deviceName}..." }
                                .onFailure { status = it.message ?: "Connection failed." }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(peer.deviceName.ifBlank { "Unnamed Android device" })
                            Text(peer.deviceAddress, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                TextButton(
                    onClick = {
                        if (activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                            runCatching { manager?.discoverPeers() }
                                .onSuccess { status = "Refreshing nearby devices..." }
                                .onFailure { status = it.message ?: "Peer discovery failed." }
                        } else permissionLauncher.launch(permission)
                    }
                ) { Text("Scan again") }
            }
            if (transportReady) {
                Text("TCP socket connected · UA frame channel ready", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "No internet or LXMF is required for this direct link. This connection does not yet exchange identities or deliver chat messages.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
