package org.urbanalchemy.messenger

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.UUID
import java.util.concurrent.Executors

private data class PeerChatLine(val id: String, val text: String, val outgoing: Boolean, val status: String)

/** Wi-Fi Direct, explicit peer pinning, authenticated session, and encrypted text chat.
 * Delivery status is driven by the verified signed-receipt pipeline.
 */
@Composable
fun WifiDirectPanel() {
    val activity = LocalContext.current as Activity
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val handshakeExecutor = remember { Executors.newSingleThreadExecutor() }
    val pinStore = remember(activity) { PeerIdentityPinStore(activity) }
    val localSigningPair = remember { DeviceIdentity.getSigningKeyPair() }
    val localBundle = remember { PeerIdentityBundle.fromPublicKey(localSigningPair.public).encode() }
    val clipboard = remember(activity) { activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    var peers by remember { mutableStateOf(emptyList<WifiP2pDevice>()) }
    var status by remember { mutableStateOf("Wi-Fi Direct is idle.") }
    var connected by remember { mutableStateOf(false) }
    var managerError by remember { mutableStateOf<String?>(null) }
    var transportReady by remember { mutableStateOf(false) }
    var activeTransport by remember { mutableStateOf<SocketFrameTransport?>(null) }
    var handshake by remember { mutableStateOf<PeerSessionHandshakeController?>(null) }
    var messageProtocol by remember { mutableStateOf<PeerMessageProtocol?>(null) }
    var pinnedPeer by remember { mutableStateOf(pinStore.listFingerprints().firstOrNull()?.let(pinStore::get)) }
    var peerBundleText by remember { mutableStateOf("") }
    var sessionReady by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var chatLines by remember { mutableStateOf(emptyList<PeerChatLine>()) }

    fun prepareHandshake(transport: SocketFrameTransport, peer: PeerIdentityBundle) {
        messageProtocol?.close()
        messageProtocol = null
        handshake?.close()
        lateinit var controller: PeerSessionHandshakeController
        controller = PeerSessionHandshakeController(
            localSigningKeyPair = localSigningPair,
            pinnedPeerSigningKey = peer.signingPublicKey,
            replayGuard = SessionReplayGuard(activity.applicationContext),
            sendFrame = { frame -> transport.send(frame) },
            onEstablished = {
                val session = requireNotNull(controller.establishedSession())
                val protocol = PeerMessageProtocol(
                    context = activity.applicationContext,
                    session = session,
                    localSigningKeyPair = localSigningPair,
                    pinnedPeerSigningPublicKey = peer.signingPublicKey,
                    sendFrame = { frame -> transport.send(frame) },
                    onMessageReceived = { message ->
                        mainHandler.post {
                            chatLines = chatLines + PeerChatLine(
                                message.messageId, message.text, false, "received"
                            )
                            status = "Received an authenticated message."
                        }
                    },
                    onDeliveryUpdated = { record ->
                        mainHandler.post {
                            chatLines = chatLines.map {
                                if (it.id == record.messageId) it.copy(status = record.state.name.lowercase())
                                else it
                            }
                        }
                    }
                )
                mainHandler.post {
                    messageProtocol = protocol
                    handshakeExecutor.execute {
                        runCatching {
                            val history = protocol.loadHistory()
                            protocol.flushQueued()
                            history
                        }.onSuccess { history ->
                            mainHandler.post {
                                chatLines = history.map { item ->
                                    PeerChatLine(item.messageId, item.text, item.outgoing, item.status)
                                }
                            }
                        }.onFailure { error ->
                            mainHandler.post { status = "Could not restore conversation: ${error.message}" }
                        }
                    }
                    sessionReady = true
                    status = "Peer identity authenticated. Encrypted messaging is ready."
                }
            },
            onError = { message ->
                mainHandler.post {
                    sessionReady = false
                    status = "Secure handshake rejected: $message"
                }
            }
        )
        handshake = controller
        status = "Pinned peer loaded. Start the secure handshake when the other device is ready."
    }

    val managerResult = remember(activity) {
        runCatching {
            WifiDirectPeerManager(activity, object : WifiDirectPeerManager.Listener {
                override fun onPeersAvailable(devices: List<WifiP2pDevice>) {
                    mainHandler.post {
                        peers = devices
                        status = if (devices.isEmpty()) "No peers found yet. Keep the other device's discovery screen open and scan again."
                        else "Found ${devices.size} nearby peer(s)."
                    }
                }
                override fun onConnectionStateChanged(isConnected: Boolean) {
                    mainHandler.post {
                        connected = isConnected
                        if (!isConnected) {
                            transportReady = false
                            sessionReady = false
                            activeTransport = null
                            messageProtocol?.close()
                            messageProtocol = null
                            handshake?.close()
                            handshake = null
                            status = "Not connected."
                        } else status = "Wi-Fi Direct group formed. Establishing peer socket..."
                    }
                }
                override fun onFrame(frame: ByteArray) {
                    mainHandler.post {
                        val controller = handshake
                        if (controller != null && controller.state() != PeerSessionHandshakeController.State.ESTABLISHED) {
                            handshakeExecutor.execute { controller.receiveFrame(frame) }
                        } else {
                            val protocol = messageProtocol
                            if (protocol != null) {
                                handshakeExecutor.execute {
                                    runCatching { protocol.receiveFrame(frame) }
                                        .onFailure { error ->
                                            mainHandler.post { status = "Message rejected: ${error.message}" }
                                        }
                                }
                            } else status = "Received a frame before the secure session was ready."
                        }
                    }
                }
                override fun onTransportReady(transport: SocketFrameTransport) {
                    mainHandler.post {
                        activeTransport = transport
                        transportReady = true
                        val peer = pinnedPeer
                        if (peer != null) prepareHandshake(transport, peer)
                        else status = "Peer socket ready. Import the other device's identity bundle to authenticate it."
                    }
                }
                override fun onError(message: String) { mainHandler.post { status = message } }
            })
        }
    }
    val manager = managerResult.getOrNull()
    val managerUnavailable = managerResult.exceptionOrNull()?.message
        ?: if (manager == null) "Wi-Fi Direct is unavailable." else null

    DisposableEffect(manager) {
        manager?.let { runCatching { it.start() }.onFailure { managerError = it.message ?: "Could not start Wi-Fi Direct." } }
        onDispose {
            messageProtocol?.close()
            handshake?.close()
            manager?.close()
            handshakeExecutor.shutdownNow()
        }
    }

    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            runCatching { manager?.discoverPeers() }
                .onSuccess { status = "Searching for nearby devices..." }
                .onFailure { status = it.message ?: "Peer discovery failed." }
        } else status = if (Build.VERSION.SDK_INT >= 33)
            "Nearby devices permission is required to discover and connect."
        else "Location permission is required for Wi-Fi Direct discovery."
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Direct offline connection", style = MaterialTheme.typography.titleSmall)
            Text(status, style = MaterialTheme.typography.bodySmall)
            (managerError ?: managerUnavailable)?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Text("Your public identity bundle", style = MaterialTheme.typography.labelLarge)
            Text("Share this with the other person through a trusted out-of-band channel.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                clipboard.setPrimaryClip(ClipData.newPlainText("Urban Alchemy identity", localBundle))
                status = "Public identity bundle copied."
            }) { Text("Copy my identity bundle") }

            OutlinedTextField(
                value = peerBundleText, onValueChange = { peerBundleText = it },
                modifier = Modifier.fillMaxWidth(), label = { Text("Paste the other device's identity bundle") }, minLines = 2
            )
            Button(onClick = {
                runCatching {
                    val peer = PeerIdentityBundle.decode(peerBundleText)
                    require(peer.fingerprint != SignedSessionHandshake.fingerprint(localSigningPair.public)) {
                        "You cannot pin your own identity."
                    }
                    pinStore.pin(peerBundleText)
                    peer
                }.onSuccess { peer ->
                    pinnedPeer = peer
                    peerBundleText = ""
                    sessionReady = false
                    activeTransport?.let { prepareHandshake(it, peer) }
                        ?: run { status = "Peer identity pinned. Connect to the other device to begin." }
                }.onFailure { status = it.message ?: "Could not pin peer identity." }
            }, enabled = peerBundleText.isNotBlank()) { Text("Verify and pin peer identity") }

            pinnedPeer?.let { Text("Pinned peer: ${it.fingerprint}", style = MaterialTheme.typography.bodySmall) }
                ?: Text("No peer identity pinned yet.", style = MaterialTheme.typography.bodySmall)

            Button(onClick = {
                if (activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                    runCatching { manager?.discoverPeers() }
                        .onSuccess { status = "Searching for nearby devices..." }
                        .onFailure { status = it.message ?: "Peer discovery failed." }
                } else permissionLauncher.launch(permission)
            }, enabled = manager != null && !connected) { Text("Find nearby devices") }

            if (peers.isNotEmpty()) {
                Text("Nearby devices", style = MaterialTheme.typography.labelLarge)
                peers.forEach { peer ->
                    TextButton(onClick = {
                        runCatching { manager?.connect(peer) }
                            .onSuccess { status = "Connecting to ${peer.deviceName}..." }
                            .onFailure { status = it.message ?: "Connection failed." }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(peer.deviceName.ifBlank { "Unnamed Android device" })
                            Text(peer.deviceAddress, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                TextButton(onClick = {
                    if (activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                        runCatching { manager?.discoverPeers() }.onSuccess { status = "Refreshing nearby devices..." }
                            .onFailure { status = it.message ?: "Peer discovery failed." }
                    } else permissionLauncher.launch(permission)
                }) { Text("Scan again") }
            }

            if (transportReady) Text("Wi-Fi Direct socket connected · UA frame channel ready", style = MaterialTheme.typography.bodySmall)
            if (handshake != null && !sessionReady) {
                Button(onClick = {
                    runCatching { handshake?.let { controller -> handshakeExecutor.execute { controller.begin() } } }
                        .onSuccess { status = "Sending signed session offer..." }
                        .onFailure { status = it.message ?: "Could not start secure handshake." }
                }, enabled = transportReady && pinnedPeer != null &&
                    handshake?.state() == PeerSessionHandshakeController.State.NEW) {
                    Text("Start secure session")
                }
            }

            if (sessionReady) {
                Text("Authenticated encrypted session", color = MaterialTheme.colorScheme.primary)
                LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(chatLines, key = { it.id }) { line ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(if (line.outgoing) "You: ${line.text}" else "Peer: ${line.text}")
                                Text(line.status, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it },
                        modifier = Modifier.weight(1f), label = { Text("Encrypted message") }
                    )
                    Button(onClick = {
                        val text = draft.trim()
                        val protocol = messageProtocol
                        if (text.isNotEmpty() && protocol != null) {
                            draft = ""
                            val id = UUID.randomUUID().toString()
                            handshakeExecutor.execute {
                                runCatching {
                                    protocol.queueMessage(text, id)
                                    mainHandler.post {
                                        chatLines = chatLines + PeerChatLine(id, text, true, "queued")
                                    }
                                    val count = protocol.flushQueued()
                                    mainHandler.post {
                                        status = if (count > 0) "Message sent over the peer socket; waiting for signed receipt."
                                        else "Message remains queued for retry."
                                    }
                                }.onFailure { error ->
                                    mainHandler.post { status = "Message send failed: ${error.message}" }
                                }
                            }
                        }
                    }, enabled = draft.isNotBlank() && messageProtocol != null) { Text("Send") }
                }
            }

            Text(
                "No internet or LXMF is required. Peer identity must be exchanged and verified out of band; an ordinary socket connection is not trusted by itself.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
