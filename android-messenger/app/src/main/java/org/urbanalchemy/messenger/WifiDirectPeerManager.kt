package org.urbanalchemy.messenger

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Discovers and connects nearby Android peers with Wi-Fi Direct, then exposes the
 * resulting TCP socket through UA's transport-neutral [SocketFrameTransport].
 *
 * This class deliberately does not perform identity exchange, encryption, or message
 * delivery. Those remain UA protocol responsibilities. The caller must request the
 * runtime permission before invoking discovery or connection methods.
 */
class WifiDirectPeerManager(
    private val activity: Activity,
    private val listener: Listener
) : AutoCloseable {

    interface Listener {
        fun onPeersAvailable(peers: List<WifiP2pDevice>)
        fun onConnectionStateChanged(connected: Boolean)
        fun onFrame(frame: ByteArray)
        fun onTransportReady(transport: SocketFrameTransport)
        fun onError(message: String)
    }

    private val manager = activity.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel = manager.initialize(activity, Looper.getMainLooper(), null)
        ?: throw IllegalStateException("Wi-Fi Direct is not supported on this device.")
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val receiverRegistered = AtomicBoolean(false)
    private val socketPublished = AtomicBoolean(false)
    private val socketEstablishing = AtomicBoolean(false)
    @Volatile private var closed = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var peerSocket: Socket? = null
    @Volatile private var frameTransport: SocketFrameTransport? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (!enabled) listener.onConnectionStateChanged(false)
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
            }
        }
    }

    fun start() {
        check(!closed) { "Peer manager is closed." }
        if (receiverRegistered.compareAndSet(false, true)) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                activity.registerReceiver(receiver, filter)
            }
        }
    }

    fun discoverPeers() {
        requirePermission()
        check(!closed) { "Peer manager is closed." }
        manager.discoverPeers(channel, actionListener("Peer discovery"))
    }

    fun connect(device: WifiP2pDevice) {
        requirePermission()
        check(!closed) { "Peer manager is closed." }
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        manager.connect(channel, config, actionListener("Peer connection"))
    }

    private fun requestPeers() {
        if (closed || !hasPermission()) return
        manager.requestPeers(channel) { peers: WifiP2pDeviceList ->
            if (!closed) listener.onPeersAvailable(peers.deviceList.toList())
        }
    }

    private fun requestConnectionInfo() {
        if (closed || !hasPermission()) return
        manager.requestConnectionInfo(channel) { info: WifiP2pInfo ->
            if (closed) return@requestConnectionInfo
            val connected = info.groupFormed && info.groupOwnerAddress != null
            listener.onConnectionStateChanged(connected)
            if (connected) establishSocket(info)
            else closePeerSocket()
        }
    }

    private fun establishSocket(info: WifiP2pInfo) {
        if (socketPublished.get() || closed || !socketEstablishing.compareAndSet(false, true)) return
        if (info.isGroupOwner) {
            executor.execute {
                try {
                    val server = ServerSocket()
                    server.reuseAddress = true
                    server.bind(InetSocketAddress(SOCKET_PORT), 1)
                    serverSocket = server
                    if (closed) {
                        server.close()
                        return@execute
                    }
                    val accepted = server.accept()
                    server.close()
                    serverSocket = null
                    publishSocket(accepted)
                } catch (error: IOException) {
                    socketEstablishing.set(false)
                    if (!closed) listener.onError("Wi-Fi Direct socket accept failed: ${error.message}")
                }
            }
        } else {
            val address = info.groupOwnerAddress
            executor.execute {
                var socket: Socket? = null
                try {
                    socket = Socket()
                    socket.connect(InetSocketAddress(address, SOCKET_PORT), CONNECT_TIMEOUT_MS)
                    publishSocket(socket)
                } catch (error: IOException) {
                    try { socket?.close() } catch (_: IOException) { }
                    socketEstablishing.set(false)
                    if (!closed) listener.onError("Wi-Fi Direct socket connect failed: ${error.message}")
                }
            }
        }
    }

    private fun publishSocket(socket: Socket) {
        if (closed || !socketPublished.compareAndSet(false, true)) {
            try { socket.close() } catch (_: IOException) { }
            return
        }
        peerSocket = socket
        val transport = SocketFrameTransport(socket, object : SocketFrameTransport.Listener {
            override fun onFrame(frame: ByteArray) = listener.onFrame(frame)
            override fun onDisconnected() {
                socketPublished.set(false)
                if (!closed) listener.onConnectionStateChanged(false)
            }
            override fun onError(error: IOException) {
                if (!closed) listener.onError("Peer socket failed: ${error.message}")
            }
        })
        frameTransport = transport
        try {
            transport.start()
            listener.onTransportReady(transport)
        } catch (error: Exception) {
            socketPublished.set(false)
            socketEstablishing.set(false)
            transport.close()
            if (!closed) listener.onError("Could not start peer frame transport: ${error.message}")
        }
    }

    private fun closePeerSocket() {
        frameTransport?.close()
        frameTransport = null
        peerSocket = null
        socketPublished.set(false)
        socketEstablishing.set(false)
        try { serverSocket?.close() } catch (_: IOException) { }
        serverSocket = null
    }

    private fun actionListener(operation: String) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = Unit
        override fun onFailure(reason: Int) {
            if (!closed) listener.onError("$operation failed (reason $reason).")
        }
    }

    private fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requirePermission() {
        check(hasPermission()) {
            if (Build.VERSION.SDK_INT >= 33)
                "NEARBY_WIFI_DEVICES runtime permission is required."
            else
                "Location runtime permission is required for Wi-Fi Direct discovery on this Android version."
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        closePeerSocket()
        if (receiverRegistered.compareAndSet(true, false)) {
            try { activity.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) { }
        }
        executor.shutdownNow()
    }

    companion object {
        const val SOCKET_PORT = 47821
        private const val CONNECT_TIMEOUT_MS = 10_000
    }
}
