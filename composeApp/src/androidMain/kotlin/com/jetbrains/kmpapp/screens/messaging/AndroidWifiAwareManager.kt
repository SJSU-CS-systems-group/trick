package com.jetbrains.kmpapp.screens.messaging

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.*
import android.net.wifi.aware.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.net.InetSocketAddress

/**
 * WiFi Aware Manager with full peer-to-peer capabilities
 * - Simultaneous publish & subscribe
 * - Deterministic role negotiation
 * - Connection-based networking (TCP sockets)
 * - Multi-peer support
 * - Automatic reconnection
 * - Unlimited message sizes
 */
class AndroidWifiAwareManager(private val context: Context) {
    private val TAG = "WifiAware"

    // WiFi Aware components
    private var wifiAwareManager: WifiAwareManager? = null
    private var session: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null

    // Device identity
    private val localDeviceId: String by lazy {
        DeviceIdentity.generateDeviceId(context)
    }

    // Connection management
    private val connectionPool = ConnectionPool()
    private val pendingHandshakes = mutableSetOf<PeerHandle>()
    private val peerDeviceIds = mutableMapOf<PeerHandle, String>()

    // Connectivity
    private val connectivityManager: ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    // Coroutines
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler { _, throwable ->
                    Log.e(TAG, "Coroutine error", throwable)
                }
    )

    // Callbacks
    private var messageCallback: ((String, String?) -> Unit)? = null // (message, peerId)
    private var connectionStatusCallback: ((String, ConnectionState) -> Unit)? = null

    // State
    private val isRunning = AtomicBoolean(false)

    init {
        wifiAwareManager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
    }

    /**
     * Start discovery and connection establishment
     */
    fun startDiscovery(
        onMessageReceived: (String, String?) -> Unit,
        onConnectionStatusChanged: ((String, ConnectionState) -> Unit)? = null
    ) {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "Discovery already running")
            return
        }

        messageCallback = onMessageReceived
        connectionStatusCallback = onConnectionStatusChanged

        if (!hasRequiredPermissions()) {
            Log.e(TAG, "Missing required permissions")
            notifyMessage("[Error] Missing required permissions")
            return
        }

        if (wifiAwareManager?.isAvailable != true) {
            Log.e(TAG, "WiFi Aware is not available")
            notifyMessage("[Error] WiFi Aware not available on this device")
            return
        }

        Log.d(TAG, "Starting WiFi Aware discovery with device ID: ${DeviceIdentity.getShortId(localDeviceId)}")

        try {
            wifiAwareManager?.attach(object : AttachCallback() {
                override fun onAttached(wifiSession: WifiAwareSession) {
                    session = wifiSession
                    Log.d(TAG, "Attached to WiFi Aware session")

                    // Start both publish and subscribe simultaneously
                    startPublishing(wifiSession)
                    startSubscribing(wifiSession)

                    // Start heartbeat monitor
                    startHeartbeatMonitor()
                }

                override fun onAttachFailed() {
                    Log.e(TAG, "WiFi Aware attach failed")
                    notifyMessage("[Error] Failed to attach to WiFi Aware")
                    isRunning.set(false)
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception: ${e.message}", e)
            notifyMessage("[Error] Permission denied")
            isRunning.set(false)
        }
    }

    /**
     * Start publishing service (advertising)
     */
    private fun startPublishing(wifiSession: WifiAwareSession) {
        if (!hasRequiredPermissions()) {
            Log.e(TAG, "Cannot start publishing: missing permissions")
            return
        }

        val publishConfig = PublishConfig.Builder()
            .setServiceName("KMPChat")
            .setServiceSpecificInfo(localDeviceId.toByteArray())
            .build()

        try {
            wifiSession.publish(
                publishConfig,
                object : DiscoverySessionCallback() {
                override fun onPublishStarted(session: PublishDiscoverySession) {
                    publishSession = session
                    Log.d(TAG, "Publishing started")
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    handleDiscoveryMessage(peerHandle, message, isFromPublish = true)
                }

                override fun onMessageSendSucceeded(messageId: Int) {
                    Log.d(TAG, "Publish message sent: ID $messageId")
                }

                override fun onMessageSendFailed(messageId: Int) {
                    Log.e(TAG, "Publish message send failed: ID $messageId")
                }

                override fun onSessionTerminated() {
                    Log.w(TAG, "Publish session terminated")
                }
            },
            Handler(Looper.getMainLooper())
        )
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception in publish: ${e.message}", e)
            notifyMessage("[Error] Permission denied for publishing")
        }
    }

    /**
     * Start subscribing to service (discovering)
     */
    private fun startSubscribing(wifiSession: WifiAwareSession) {
        if (!hasRequiredPermissions()) {
            Log.e(TAG, "Cannot start subscribing: missing permissions")
            return
        }

        val subscribeConfig = SubscribeConfig.Builder()
            .setServiceName("KMPChat")
            .build()

        try {
            wifiSession.subscribe(
                subscribeConfig,
                object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                    subscribeSession = session
                    Log.d(TAG, "Subscribing started")
                }

                override fun onServiceDiscovered(
                    peerHandle: PeerHandle,
                    serviceSpecificInfo: ByteArray?,
                    matchFilter: List<ByteArray>?
                ) {
                    handleServiceDiscovered(peerHandle, serviceSpecificInfo)
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    handleDiscoveryMessage(peerHandle, message, isFromPublish = false)
                }

                override fun onMessageSendSucceeded(messageId: Int) {
                    Log.d(TAG, "Subscribe message sent: ID $messageId")
                }

                override fun onMessageSendFailed(messageId: Int) {
                    Log.e(TAG, "Subscribe message send failed: ID $messageId")
                }

                override fun onSessionTerminated() {
                    Log.w(TAG, "Subscribe session terminated")
                }
            },
            Handler(Looper.getMainLooper())
        )
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception in subscribe: ${e.message}", e)
            notifyMessage("[Error] Permission denied for subscribing")
        }
    }

    /**
     * Handle service discovery event
     */
    private fun handleServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray?) {
        val remoteDeviceId = serviceSpecificInfo?.let { String(it) } ?: run {
            Log.w(TAG, "Service discovered but no device ID provided")
            return
        }

        Log.d(TAG, "Service discovered from peer: ${DeviceIdentity.getShortId(remoteDeviceId)}")

        // Store peer device ID
        peerDeviceIds[peerHandle] = remoteDeviceId

        // Check if already connected
        if (connectionPool.hasConnection(remoteDeviceId)) {
            Log.d(TAG, "Already connected to ${DeviceIdentity.getShortId(remoteDeviceId)}")
            return
        }

        // Negotiate role
        val role = DeviceIdentity.negotiateRole(localDeviceId, remoteDeviceId)
        Log.d(TAG, "Negotiated role: $role with ${DeviceIdentity.getShortId(remoteDeviceId)}")

        // Initiate connection based on role
        when (role) {
            Role.SERVER -> {
                // As server, wait for client's handshake
                Log.d(TAG, "Waiting for handshake from client ${DeviceIdentity.getShortId(remoteDeviceId)}")
            }
            Role.CLIENT -> {
                // As client, send handshake to initiate connection
                if (!pendingHandshakes.contains(peerHandle)) {
                    sendHandshake(peerHandle, remoteDeviceId)
                }
            }
            Role.NONE -> {
                Log.e(TAG, "Role negotiation failed")
            }
        }
    }

    /**
     * Handle messages received during discovery phase
     */
    private fun handleDiscoveryMessage(peerHandle: PeerHandle, message: ByteArray, isFromPublish: Boolean) {
        val messageStr = String(message)
        val source = if (isFromPublish) "publish" else "subscribe"
        Log.d(TAG, "Discovery message from $source: $messageStr")

        // Parse handshake message
        DeviceIdentity.parseHandshakeMessage(messageStr)?.let { remoteDeviceId ->
            handleHandshakeReceived(peerHandle, remoteDeviceId)
            return
        }

        // Parse port message
        DeviceIdentity.parsePortMessage(messageStr)?.let { port ->
            val remoteDeviceId = peerDeviceIds[peerHandle] ?: return
            handlePortReceived(peerHandle, remoteDeviceId, port)
            return
        }

        // Check if already connected - route to connection handler
        val peerId = peerDeviceIds[peerHandle]
        if (peerId != null && connectionPool.hasConnection(peerId)) {
            // Message will be handled via TCP socket, ignore here
            Log.d(TAG, "Message from connected peer, ignoring (will come via TCP)")
            return
        }

        // Handle other system messages
        if (DeviceIdentity.isSystemMessage(messageStr)) {
            Log.d(TAG, "System message: $messageStr")
        } else {
            Log.w(TAG, "Unexpected message during discovery: $messageStr")
        }
    }

    /**
     * Send handshake to peer (client role)
     */
    private fun sendHandshake(peerHandle: PeerHandle, remoteDeviceId: String) {
        if (pendingHandshakes.contains(peerHandle)) {
            Log.d(TAG, "Handshake already pending for ${DeviceIdentity.getShortId(remoteDeviceId)}")
            return
        }

        pendingHandshakes.add(peerHandle)
        val handshakeMessage = DeviceIdentity.createHandshakeMessage(localDeviceId)

        Log.d(TAG, "Sending handshake to ${DeviceIdentity.getShortId(remoteDeviceId)}")

        subscribeSession?.sendMessage(
            peerHandle,
            System.currentTimeMillis().toInt(),
            handshakeMessage.toByteArray()
        )

        notifyConnectionStatus(remoteDeviceId, ConnectionState.NEGOTIATING)
    }

    /**
     * Handle handshake received (server role)
     */
    private fun handleHandshakeReceived(peerHandle: PeerHandle, remoteDeviceId: String) {
        Log.d(TAG, "Handshake received from ${DeviceIdentity.getShortId(remoteDeviceId)}")

        peerDeviceIds[peerHandle] = remoteDeviceId

        // Check if already connected
        if (connectionPool.hasConnection(remoteDeviceId)) {
            Log.d(TAG, "Already connected to ${DeviceIdentity.getShortId(remoteDeviceId)}")
            return
        }

        // Verify we should be server
        val role = DeviceIdentity.negotiateRole(localDeviceId, remoteDeviceId)
        if (role != Role.SERVER) {
            Log.e(TAG, "Received handshake but we should be client! Ignoring.")
            return
        }

        notifyConnectionStatus(remoteDeviceId, ConnectionState.CONNECTING)

        // Setup as server
        scope.launch {
            setupServerConnection(peerHandle, remoteDeviceId)
        }
    }

    /**
     * Setup server-side connection
     */
    private suspend fun setupServerConnection(peerHandle: PeerHandle, remoteDeviceId: String) {
        try {
            Log.d(TAG, "[Server] Setting up connection for ${DeviceIdentity.getShortId(remoteDeviceId)}")

            // Create server socket (port 0 = auto-assign)
            val serverSocket = withContext(Dispatchers.IO) {
                ServerSocket(0)
            }
            val assignedPort = serverSocket.localPort

            Log.d(TAG, "[Server] ServerSocket created on port $assignedPort")

            // Send port to client
            val portMessage = DeviceIdentity.createPortMessage(assignedPort)
            publishSession?.sendMessage(
                peerHandle,
                System.currentTimeMillis().toInt(),
                portMessage.toByteArray()
            )

            Log.d(TAG, "[Server] Port message sent to client: $assignedPort")

            // Create network specifier with port
            val session = publishSession ?: throw Exception("Publish session is null")
            val networkSpecifier = WifiAwareNetworkSpecifier.Builder(session, peerHandle)
                .setPskPassphrase(DeviceIdentity.getPskPassphrase())
                .setPort(assignedPort)
                .build()

            // Create network request
            val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(networkSpecifier)
                .build()

            // Track network and callback
            var network: Network? = null
            var networkCallback: ConnectivityManager.NetworkCallback? = null

            // Request network
            val callbackDeferred = CompletableDeferred<Network>()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(net: Network) {
                    Log.d(TAG, "[Server] Network available")
                    network = net
                    callbackDeferred.complete(net)
                }

                override fun onCapabilitiesChanged(
                    net: Network,
                    capabilities: NetworkCapabilities
                ) {
                    Log.d(TAG, "[Server] Network capabilities changed")
                }

                override fun onLost(net: Network) {
                    Log.w(TAG, "[Server] Network lost")
                    handleConnectionLost(remoteDeviceId)
                }

                override fun onUnavailable() {
                    Log.e(TAG, "[Server] Network unavailable")
                    callbackDeferred.completeExceptionally(Exception("Network unavailable"))
                }
            }

            connectivityManager.requestNetwork(networkRequest, networkCallback)

            // Wait for network with timeout
            withTimeout(30000) {
                network = callbackDeferred.await()
            }

            Log.d(TAG, "[Server] Waiting for client connection on port $assignedPort")

            // Accept client connection
            val clientSocket = withContext(Dispatchers.IO) {
                serverSocket.accept()
            }

            Log.d(TAG, "[Server] Client connected: ${clientSocket.remoteSocketAddress}")

            // Setup IO streams
            val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
            val writer = BufferedWriter(OutputStreamWriter(clientSocket.getOutputStream()))

            // Create connection object
            val connection = PeerConnection(
                peerId = remoteDeviceId,
                peerHandle = peerHandle,
                role = Role.SERVER,
                socket = clientSocket,
                serverSocket = serverSocket,
                reader = reader,
                writer = writer,
                network = network,
                networkCallback = networkCallback
            )

            // Add to pool
            connectionPool.addConnection(remoteDeviceId, connection)
            pendingHandshakes.remove(peerHandle)

            Log.d(TAG, "[Server] Connection established with ${DeviceIdentity.getShortId(remoteDeviceId)}")
            notifyConnectionStatus(remoteDeviceId, ConnectionState.CONNECTED)
            notifyMessage("[System] Connected as SERVER to ${DeviceIdentity.getShortId(remoteDeviceId)}", remoteDeviceId)

            // Start message listener
            startMessageListener(remoteDeviceId)

        } catch (e: Exception) {
            Log.e(TAG, "[Server] Connection setup failed: ${e.message}", e)
            notifyConnectionStatus(remoteDeviceId, ConnectionState.DISCONNECTED)
            notifyMessage("[Error] Server connection failed: ${e.message}", remoteDeviceId)
            pendingHandshakes.remove(peerHandle)
        }
    }

    /**
     * Handle port received (client role)
     */
    private fun handlePortReceived(peerHandle: PeerHandle, remoteDeviceId: String, port: Int) {
        Log.d(TAG, "[Client] Port received from server: $port")

        notifyConnectionStatus(remoteDeviceId, ConnectionState.CONNECTING)

        scope.launch {
            setupClientConnection(peerHandle, remoteDeviceId, port)
        }
    }

    /**
     * Setup client-side connection
     */
    private suspend fun setupClientConnection(
        peerHandle: PeerHandle,
        remoteDeviceId: String,
        serverPort: Int
    ) {
        try {
            Log.d(TAG, "[Client] Setting up connection to ${DeviceIdentity.getShortId(remoteDeviceId)} on port $serverPort")

            // Create network specifier (client doesn't specify port)
            val session = subscribeSession ?: throw Exception("Subscribe session is null")
            val networkSpecifier = WifiAwareNetworkSpecifier.Builder(session, peerHandle)
                .setPskPassphrase(DeviceIdentity.getPskPassphrase())
                .build()

            // Create network request
            val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(networkSpecifier)
                .build()

            // Track network and callback
            var network: Network? = null
            var serverIpv6: String? = null
            var networkCallback: ConnectivityManager.NetworkCallback? = null

            val callbackDeferred = CompletableDeferred<Pair<Network, String>>()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(net: Network) {
                    Log.d(TAG, "[Client] Network available")
                    network = net
                }

                override fun onCapabilitiesChanged(
                    net: Network,
                    capabilities: NetworkCapabilities
                ) {
                    Log.d(TAG, "[Client] Network capabilities changed")

                    // Extract server IPv6 address
                    val wifiAwareInfo = capabilities.transportInfo as? WifiAwareNetworkInfo
                    val ipv6 = wifiAwareInfo?.peerIpv6Addr
                    val port = wifiAwareInfo?.port

                    if (ipv6 != null && network != null) {
                        Log.d(TAG, "[Client] Server IPv6: $ipv6, Port: $port")
                        serverIpv6 = ipv6.hostAddress
                        callbackDeferred.complete(Pair(network!!, serverIpv6!!))
                    }
                }

                override fun onLost(net: Network) {
                    Log.w(TAG, "[Client] Network lost")
                    handleConnectionLost(remoteDeviceId)
                }

                override fun onUnavailable() {
                    Log.e(TAG, "[Client] Network unavailable")
                    callbackDeferred.completeExceptionally(Exception("Network unavailable"))
                }
            }

            connectivityManager.requestNetwork(networkRequest, networkCallback)

            // Wait for network and IPv6 with timeout
            val (net, ipv6) = withTimeout(30000) {
                callbackDeferred.await()
            }

            Log.d(TAG, "[Client] Connecting to server at [$ipv6]:$serverPort")

            // Create socket connection
            val socket = withContext(Dispatchers.IO) {
                val sock = net.socketFactory.createSocket()
                sock.connect(InetSocketAddress(ipv6, serverPort), 10000)
                sock
            }

            Log.d(TAG, "[Client] Connected to server: ${socket.remoteSocketAddress}")

            // Setup IO streams
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))

            // Create connection object
            val connection = PeerConnection(
                peerId = remoteDeviceId,
                peerHandle = peerHandle,
                role = Role.CLIENT,
                socket = socket,
                serverSocket = null,
                reader = reader,
                writer = writer,
                network = network,
                networkCallback = networkCallback
            )

            // Add to pool
            connectionPool.addConnection(remoteDeviceId, connection)
            pendingHandshakes.remove(peerHandle)

            Log.d(TAG, "[Client] Connection established with ${DeviceIdentity.getShortId(remoteDeviceId)}")
            notifyConnectionStatus(remoteDeviceId, ConnectionState.CONNECTED)
            notifyMessage("[System] Connected as CLIENT to ${DeviceIdentity.getShortId(remoteDeviceId)}", remoteDeviceId)

            // Start message listener
            startMessageListener(remoteDeviceId)

        } catch (e: Exception) {
            Log.e(TAG, "[Client] Connection setup failed: ${e.message}", e)
            notifyConnectionStatus(remoteDeviceId, ConnectionState.DISCONNECTED)
            notifyMessage("[Error] Client connection failed: ${e.message}", remoteDeviceId)
            pendingHandshakes.remove(peerHandle)
        }
    }

    /**
     * Start listening for messages from a peer
     */
    private fun startMessageListener(peerId: String) {
        scope.launch(Dispatchers.IO) {
            val connection = connectionPool.getConnection(peerId) ?: run {
                Log.e(TAG, "Cannot start listener: connection not found for $peerId")
                return@launch
            }

            val reader = connection.reader ?: run {
                Log.e(TAG, "Cannot start listener: reader is null")
                return@launch
            }

            Log.d(TAG, "Message listener started for ${DeviceIdentity.getShortId(peerId)}")

            try {
                while (isActive && connection.socket?.isConnected == true) {
                    // Read message using framing protocol
                    val message = reader.readLine()

                    if (message == null) {
                        Log.w(TAG, "Connection closed by peer $peerId")
                        break
                    }

                    connection.updateLastMessageTime()

                    // Handle system messages
                    if (message.startsWith("HEARTBEAT")) {
                        Log.d(TAG, "Heartbeat received from ${DeviceIdentity.getShortId(peerId)}")
                        continue
                    }

                    Log.d(TAG, "Message received from ${DeviceIdentity.getShortId(peerId)}: $message")

                    // Notify on main thread
                    withContext(Dispatchers.Main) {
                        messageCallback?.invoke(message, peerId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Message listener error for $peerId: ${e.message}")
            } finally {
                Log.d(TAG, "Message listener stopped for ${DeviceIdentity.getShortId(peerId)}")
                handleConnectionLost(peerId)
            }
        }
    }

    /**
     * Send message to specific peer or broadcast to all
     */
    fun sendMessage(message: String, targetPeerId: String? = null) {
        if (targetPeerId != null) {
            sendMessageToPeer(message, targetPeerId)
        } else {
            broadcastMessage(message)
        }
    }

    /**
     * Send message to specific peer
     */
    fun sendMessageToPeer(message: String, peerId: String) {
        scope.launch(Dispatchers.IO) {
            val connection = connectionPool.getConnection(peerId)

            if (connection == null) {
                Log.e(TAG, "Cannot send: no connection to ${DeviceIdentity.getShortId(peerId)}")
                withContext(Dispatchers.Main) {
                    notifyMessage("[Error] Not connected to ${DeviceIdentity.getShortId(peerId)}", null)
                }
                return@launch
            }

            try {
                val writer = connection.writer ?: throw Exception("Writer is null")

                // Send with framing (newline-delimited for simplicity)
                writer.write(message)
                writer.write("\n")
                writer.flush()

                connection.updateLastMessageTime()

                Log.d(TAG, "Message sent to ${DeviceIdentity.getShortId(peerId)}: $message")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message to $peerId: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    notifyMessage("[Error] Failed to send message: ${e.message}", peerId)
                }
                handleConnectionLost(peerId)
            }
        }
    }

    /**
     * Broadcast message to all connected peers
     */
    fun broadcastMessage(message: String) {
        val peers = connectionPool.getAllConnections()
        Log.d(TAG, "Broadcasting message to ${peers.size} peers")

        peers.forEach { connection ->
            sendMessageToPeer(message, connection.peerId)
        }
    }

    /**
     * Handle connection loss
     */
    private fun handleConnectionLost(peerId: String) {
        val connection = connectionPool.removeConnection(peerId) ?: return

        Log.w(TAG, "Handling connection loss for ${DeviceIdentity.getShortId(peerId)}")

        // Cleanup resources
        scope.launch(Dispatchers.IO) {
            try {
                connection.socket?.close()
                connection.serverSocket?.close()
                connection.reader?.close()
                connection.writer?.close()
                connection.networkCallback?.let {
                    connectivityManager.unregisterNetworkCallback(it)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during connection cleanup: ${e.message}")
            }
        }

        notifyConnectionStatus(peerId, ConnectionState.DISCONNECTED)
        notifyMessage("[System] Connection lost to ${DeviceIdentity.getShortId(peerId)}", peerId)

        // Attempt reconnection
        scope.launch {
            delay(2000)
            Log.d(TAG, "Attempting reconnection to ${DeviceIdentity.getShortId(peerId)}")
            notifyConnectionStatus(peerId, ConnectionState.RECONNECTING)
            // Discovery is still running, peer will be re-discovered automatically
        }
    }

    /**
     * Start heartbeat monitor
     */
    private fun startHeartbeatMonitor() {
        scope.launch {
            while (isActive && isRunning.get()) {
                delay(30000) // 30 seconds

                val connections = connectionPool.getAllConnections()
                Log.d(TAG, "Heartbeat check: ${connections.size} connections")

                connections.forEach { connection ->
                    try {
                        sendMessageToPeer("HEARTBEAT", connection.peerId)

                        // Check health
                        if (!connection.isHealthy()) {
                            Log.w(TAG, "Unhealthy connection detected: ${DeviceIdentity.getShortId(connection.peerId)}")
                            handleConnectionLost(connection.peerId)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Heartbeat error for ${connection.peerId}: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Get connected peer IDs
     */
    fun getConnectedPeers(): List<String> {
        return connectionPool.getPeerIds()
    }

    /**
     * Check if specific peer is connected
     */
    fun isPeerConnected(peerId: String): Boolean {
        return connectionPool.hasConnection(peerId)
    }

    /**
     * Get connection status summary
     */
    fun getConnectionStatus(): String {
        val stats = connectionPool.getStatistics()
        return buildString {
            append("Connections: ${stats.totalConnections}")
            if (stats.totalConnections > 0) {
                append(" (${stats.serverConnections} server, ${stats.clientConnections} client)")
            }
        }
    }

    /**
     * Get local device ID
     */
    fun getDeviceId(): String = localDeviceId

    /**
     * Stop discovery and cleanup all connections
     */
    fun stopDiscovery() {
        if (!isRunning.getAndSet(false)) {
            return
        }

        Log.d(TAG, "Stopping discovery and cleaning up connections")

        // Close all connections
        connectionPool.getAllConnections().forEach { connection ->
            handleConnectionLost(connection.peerId)
        }

        // Cancel coroutines
        scope.cancel()

        // Close sessions
        publishSession?.close()
        subscribeSession?.close()
        session?.close()

        publishSession = null
        subscribeSession = null
        session = null

        connectionPool.clear()
        peerDeviceIds.clear()
        pendingHandshakes.clear()

        Log.d(TAG, "Cleanup complete")
    }

    /**
     * Helper to notify message callback
     */
    private fun notifyMessage(message: String, peerId: String? = null) {
        scope.launch(Dispatchers.Main) {
            messageCallback?.invoke(message, peerId)
        }
    }

    /**
     * Helper to notify connection status callback
     */
    private fun notifyConnectionStatus(peerId: String, state: ConnectionState) {
        scope.launch(Dispatchers.Main) {
            connectionStatusCallback?.invoke(peerId, state)
        }
    }

    /**
     * Check if required permissions are granted
     */
    private fun hasRequiredPermissions(): Boolean {
        val perms = listOf(
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES
        )
        return perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
