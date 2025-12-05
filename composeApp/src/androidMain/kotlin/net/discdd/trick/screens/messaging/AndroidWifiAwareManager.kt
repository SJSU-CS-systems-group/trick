package net.discdd.trick.screens.messaging

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.*
import android.net.wifi.aware.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import net.discdd.trick.messaging.ChatMessage
import net.discdd.trick.messaging.PhotoContent
import net.discdd.trick.messaging.TextContent
import net.discdd.trick.security.KeyManager
import net.discdd.trick.libsignal.createLibSignalManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import okio.ByteString.Companion.toByteString

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
    private val localDeviceId: String by lazy { DeviceIdentity.generateDeviceId(context) }

    // Encryption components
    private val keyManager = KeyManager(context)
    private val libSignalManager = createLibSignalManager()

    // Connection management
    private val connectionPool = ConnectionPool()
    private val pendingHandshakes = mutableSetOf<PeerHandle>()
    private val peerDeviceIds = mutableMapOf<PeerHandle, String>()

    // Connectivity
    private val connectivityManager: ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    // Coroutines
    private var scope =
            CoroutineScope(
                    SupervisorJob() +
                            Dispatchers.IO +
                            CoroutineExceptionHandler { _, throwable ->
                                Log.e(TAG, "Coroutine error", throwable)
                            }
            )

    // Callbacks
    private var messageCallback: ((ChatMessage, String?) -> Unit)? = null // (chatMessage, peerId)
    private var connectionStatusCallback: ((String, ConnectionState) -> Unit)? = null

    // State
    private val isRunning = AtomicBoolean(false)

    init {
        wifiAwareManager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
    }

    /** Start discovery and connection establishment */
    fun startDiscovery(
            onMessageReceived: (ChatMessage, String?) -> Unit,
            onConnectionStatusChanged: ((String, ConnectionState) -> Unit)? = null
    ) {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "Discovery already running")
            return
        }

        // Create a fresh coroutine scope
        scope =
                CoroutineScope(
                        SupervisorJob() +
                                Dispatchers.IO +
                                CoroutineExceptionHandler { _, throwable ->
                                    Log.e(TAG, "Coroutine error", throwable)
                                }
                )

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

        Log.d(
                TAG,
                "Starting WiFi Aware discovery with device ID: ${DeviceIdentity.getShortId(localDeviceId)}"
        )

        try {
            wifiAwareManager?.attach(
                    object : AttachCallback() {
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
                    },
                    Handler(Looper.getMainLooper())
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception: ${e.message}", e)
            notifyMessage("[Error] Permission denied")
            isRunning.set(false)
        }
    }

    /** Start publishing service (advertising) */
    private fun startPublishing(wifiSession: WifiAwareSession) {
        if (!hasRequiredPermissions()) {
            Log.e(TAG, "Cannot start publishing: missing permissions")
            return
        }

        val publishConfig =
                PublishConfig.Builder()
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

    /** Start subscribing to service (discovering) */
    private fun startSubscribing(wifiSession: WifiAwareSession) {
        if (!hasRequiredPermissions()) {
            Log.e(TAG, "Cannot start subscribing: missing permissions")
            return
        }

        val subscribeConfig = SubscribeConfig.Builder().setServiceName("KMPChat").build()

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

    /** Handle service discovery event */
    private fun handleServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray?) {
        val remoteDeviceId =
                serviceSpecificInfo?.let { String(it) }
                        ?: run {
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
                Log.d(
                        TAG,
                        "Waiting for handshake from client ${DeviceIdentity.getShortId(remoteDeviceId)}"
                )
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

    /** Handle messages received during discovery phase */
    private fun handleDiscoveryMessage(
            peerHandle: PeerHandle,
            message: ByteArray,
            isFromPublish: Boolean
    ) {
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

    /** Send handshake to peer (client role) */
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

    /** Handle handshake received (server role) */
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
        scope.launch { setupServerConnection(peerHandle, remoteDeviceId) }
    }

    /** Setup server-side connection */
    private suspend fun setupServerConnection(peerHandle: PeerHandle, remoteDeviceId: String) {
        try {
            Log.d(
                    TAG,
                    "[Server] Setting up connection for ${DeviceIdentity.getShortId(remoteDeviceId)}"
            )

            // Create server socket (port 0 = auto-assign)
            val serverSocket = withContext(Dispatchers.IO) { ServerSocket(0) }
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
            val networkSpecifier =
                    WifiAwareNetworkSpecifier.Builder(session, peerHandle)
                            .setPskPassphrase(DeviceIdentity.getPskPassphrase())
                            .setPort(assignedPort)
                            .build()

            // Create network request
            val networkRequest =
                    NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                            .setNetworkSpecifier(networkSpecifier)
                            .build()

            // Track network and callback
            var network: Network? = null
            var networkCallback: ConnectivityManager.NetworkCallback? = null

            // Request network
            val callbackDeferred = CompletableDeferred<Network>()

            networkCallback =
                    object : ConnectivityManager.NetworkCallback() {
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

            // Wait for network
            network = callbackDeferred.await()

            Log.d(TAG, "[Server] Waiting for client connection on port $assignedPort")

            // Accept client connection
            val clientSocket = withContext(Dispatchers.IO) { serverSocket.accept() }

            Log.d(TAG, "[Server] Client connected: ${clientSocket.remoteSocketAddress}")

            // Setup IO streams for binary protobuf data
            val inputStream = DataInputStream(clientSocket.getInputStream())
            val outputStream = DataOutputStream(clientSocket.getOutputStream())

            // Create connection object
            val connection =
                    PeerConnection(
                            peerId = remoteDeviceId,
                            peerHandle = peerHandle,
                            role = Role.SERVER,
                            socket = clientSocket,
                            serverSocket = serverSocket,
                            inputStream = inputStream,
                            outputStream = outputStream,
                            network = network,
                            networkCallback = networkCallback
                    )

            // Add to pool
            connectionPool.addConnection(remoteDeviceId, connection)
            pendingHandshakes.remove(peerHandle)

            Log.d(
                    TAG,
                    "[Server] Connection established with ${DeviceIdentity.getShortId(remoteDeviceId)}"
            )
            notifyConnectionStatus(remoteDeviceId, ConnectionState.CONNECTED)
            notifyMessage(
                    "[System] Connected to ${DeviceIdentity.getShortId(remoteDeviceId)}",
                    remoteDeviceId
            )

            // Start message listener
            startMessageListener(remoteDeviceId)
        } catch (e: Exception) {
            Log.e(TAG, "[Server] Connection setup failed: ${e.message}", e)
            notifyConnectionStatus(remoteDeviceId, ConnectionState.DISCONNECTED)
            notifyMessage("[Error] Server connection failed: ${e.message}", remoteDeviceId)
            pendingHandshakes.remove(peerHandle)
        }
    }

    /** Handle port received (client role) */
    private fun handlePortReceived(peerHandle: PeerHandle, remoteDeviceId: String, port: Int) {
        Log.d(TAG, "[Client] Port received from server: $port")

        // Check if already connected
        if (connectionPool.hasConnection(remoteDeviceId)) {
            Log.d(
                    TAG,
                    "[Client] Already connected to ${DeviceIdentity.getShortId(remoteDeviceId)}, ignoring port message"
            )
            return
        }

        // Check if subscribe session is ready
        if (subscribeSession == null) {
            Log.e(TAG, "[Client] Subscribe session not available - this should not happen")
            notifyMessage(
                    "[Error] Client connection failed: Subscribe session not available",
                    remoteDeviceId
            )
            return
        }

        notifyConnectionStatus(remoteDeviceId, ConnectionState.CONNECTING)

        scope.launch { setupClientConnection(peerHandle, remoteDeviceId, port) }
    }

    /** Setup client-side connection */
    private suspend fun setupClientConnection(
            peerHandle: PeerHandle,
            remoteDeviceId: String,
            serverPort: Int
    ) {
        try {
            // Double-check connection doesn't already exist
            if (connectionPool.hasConnection(remoteDeviceId)) {
                Log.d(
                        TAG,
                        "[Client] Connection already exists for ${DeviceIdentity.getShortId(remoteDeviceId)}"
                )
                return
            }

            Log.d(
                    TAG,
                    "[Client] Setting up connection to ${DeviceIdentity.getShortId(remoteDeviceId)} on port $serverPort"
            )

            // Create network specifier (client doesn't specify port)
            val session = subscribeSession ?: throw Exception("Subscribe session is null")
            val networkSpecifier =
                    WifiAwareNetworkSpecifier.Builder(session, peerHandle)
                            .setPskPassphrase(DeviceIdentity.getPskPassphrase())
                            .build()

            // Create network request
            val networkRequest =
                    NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                            .setNetworkSpecifier(networkSpecifier)
                            .build()

            // Track network and callback
            var network: Network? = null
            var serverIpv6: String? = null
            var networkCallback: ConnectivityManager.NetworkCallback? = null

            val callbackDeferred = CompletableDeferred<Pair<Network, String>>()

            networkCallback =
                    object : ConnectivityManager.NetworkCallback() {
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

                            if (ipv6 != null && network != null && !callbackDeferred.isCompleted) {
                                Log.d(TAG, "[Client] Server IPv6: $ipv6, Port: $port")
                                serverIpv6 = ipv6.hostAddress
                                callbackDeferred.complete(Pair(network!!, serverIpv6!!))
                            }
                        }

                        override fun onLost(net: Network) {
                            Log.w(TAG, "[Client] Network lost")
                            if (!callbackDeferred.isCompleted) {
                                callbackDeferred.completeExceptionally(Exception("Network lost"))
                            }
                            handleConnectionLost(remoteDeviceId)
                        }

                        override fun onUnavailable() {
                            Log.e(TAG, "[Client] Network unavailable")
                            if (!callbackDeferred.isCompleted) {
                                callbackDeferred.completeExceptionally(
                                        Exception("Network unavailable")
                                )
                            }
                        }
                    }

            connectivityManager.requestNetwork(networkRequest, networkCallback)

            // Wait for network and IPv6 with timeout
            val (net, ipv6) =
                    try {
                        withTimeoutOrNull(15000) { // 15 second timeout
                            callbackDeferred.await()
                        }
                                ?: run {
                                    connectivityManager.unregisterNetworkCallback(networkCallback)
                                    throw Exception("Network connection timeout")
                                }
                    } catch (e: Exception) {
                        connectivityManager.unregisterNetworkCallback(networkCallback)
                        throw e
                    }

            Log.d(TAG, "[Client] Connecting to server at [$ipv6]:$serverPort")

            // Create socket connection
            val socket =
                    try {
                        withContext(Dispatchers.IO) {
                            val sock = net.socketFactory.createSocket()
                            sock.connect(InetSocketAddress(ipv6, serverPort), 10000)
                            sock
                        }
                    } catch (e: Exception) {
                        connectivityManager.unregisterNetworkCallback(networkCallback)
                        throw e
                    }

            Log.d(TAG, "[Client] Connected to server: ${socket.remoteSocketAddress}")

            // Setup IO streams for binary protobuf data
            val inputStream = DataInputStream(socket.getInputStream())
            val outputStream = DataOutputStream(socket.getOutputStream())

            // Create connection object
            val connection =
                    PeerConnection(
                            peerId = remoteDeviceId,
                            peerHandle = peerHandle,
                            role = Role.CLIENT,
                            socket = socket,
                            serverSocket = null,
                            inputStream = inputStream,
                            outputStream = outputStream,
                            network = network,
                            networkCallback = networkCallback
                    )

            // Add to pool
            connectionPool.addConnection(remoteDeviceId, connection)
            pendingHandshakes.remove(peerHandle)

            Log.d(
                    TAG,
                    "[Client] Connection established with ${DeviceIdentity.getShortId(remoteDeviceId)}"
            )
            notifyConnectionStatus(remoteDeviceId, ConnectionState.CONNECTED)
            notifyMessage(
                    "[System] Connected to ${DeviceIdentity.getShortId(remoteDeviceId)}",
                    remoteDeviceId
            )

            // Start message listener
            startMessageListener(remoteDeviceId)
        } catch (e: Exception) {
            Log.e(TAG, "[Client] Connection setup failed: ${e.message}", e)
            notifyConnectionStatus(remoteDeviceId, ConnectionState.DISCONNECTED)
            notifyMessage("[Error] Client connection failed: ${e.message}", remoteDeviceId)
            pendingHandshakes.remove(peerHandle)
        }
    }

    /** Start listening for messages from a peer */
    private fun startMessageListener(peerId: String) {
        scope.launch(Dispatchers.IO) {
            val connection =
                    connectionPool.getConnection(peerId)
                            ?: run {
                                Log.e(
                                        TAG,
                                        "Cannot start listener: connection not found for $peerId"
                                )
                                return@launch
                            }

            val inputStream =
                    connection.inputStream
                            ?: run {
                                Log.e(TAG, "Cannot start listener: inputStream is null")
                                return@launch
                            }

            Log.d(TAG, "Message listener started for ${DeviceIdentity.getShortId(peerId)}")

            try {
                while (isActive && connection.socket?.isConnected == true) {
                    // Read length-prefixed protobuf message
                    val messageLength = inputStream.readInt()

                    if (messageLength <= 0 || messageLength > 10_000_000) {
                        Log.e(TAG, "Invalid message length: $messageLength")
                        break
                    }

                    val messageBytes = ByteArray(messageLength)
                    inputStream.readFully(messageBytes)

                    connection.updateLastMessageTime()

                    // Deserialize protobuf message
                    val chatMessage =
                            try {
                                ChatMessage.ADAPTER.decode(messageBytes)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to decode protobuf message: ${e.message}")
                                continue
                            }

                    // Check if encrypted and decrypt if necessary
                    val decryptedMessage = if (chatMessage.encrypted_content != null) {
                        try {
                            // DECRYPT: Get our private key and decrypt
                            val myKeyPair = keyManager.getIdentityKeyPair()
                            if (myKeyPair == null) {
                                Log.e(TAG, "Cannot decrypt: no identity key pair")
                                chatMessage.copy(text_content = TextContent(text = "[Decryption failed: No key pair]"))
                            } else {
                                val decryptedBytes = libSignalManager.decrypt(
                                    myKeyPair.privateKey,
                                    chatMessage.encrypted_content.toByteArray()
                                )

                                // Try to determine content type and deserialize
                                val decryptedContent = try {
                                    // Try as TextContent first
                                    val textContent = TextContent.ADAPTER.decode(decryptedBytes)
                                    chatMessage.copy(text_content = textContent, encrypted_content = null)
                                } catch (e: Exception) {
                                    // Try as PhotoContent
                                    try {
                                        val photoContent = PhotoContent.ADAPTER.decode(decryptedBytes)
                                        chatMessage.copy(photo_content = photoContent, encrypted_content = null)
                                    } catch (e2: Exception) {
                                        Log.e(TAG, "Failed to deserialize decrypted content: ${e2.message}")
                                        chatMessage.copy(text_content = TextContent(text = "[Decryption failed: Invalid content]"))
                                    }
                                }

                                Log.d(TAG, "Message decrypted from ${DeviceIdentity.getShortId(peerId)}")
                                decryptedContent
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Decryption failed: ${e.message}", e)
                            chatMessage.copy(text_content = TextContent(text = "[Decryption failed: ${e.message}]"))
                        }
                    } else {
                        // Plaintext message
                        chatMessage
                    }

                    // Handle heartbeat (special case with empty content)
                    if (decryptedMessage.text_content?.text == "HEARTBEAT") {
                        Log.d(TAG, "Heartbeat received from ${DeviceIdentity.getShortId(peerId)}")
                        continue
                    }

                    Log.d(TAG, "Message received from ${DeviceIdentity.getShortId(peerId)}")

                    // Notify on main thread with the decrypted ChatMessage
                    withContext(Dispatchers.Main) { messageCallback?.invoke(decryptedMessage, peerId) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Message listener error for $peerId: ${e.message}")
            } finally {
                Log.d(TAG, "Message listener stopped for ${DeviceIdentity.getShortId(peerId)}")
                handleConnectionLost(peerId)
            }
        }
    }

    /** Send message to specific peer or broadcast to all */
    fun sendMessage(message: String, targetPeerId: String? = null) {
        if (targetPeerId != null) {
            sendMessageToPeer(message, targetPeerId)
        } else {
            broadcastMessage(message)
        }
    }

    /** Send message to specific peer */
    fun sendMessageToPeer(message: String, peerId: String) {
        scope.launch(Dispatchers.IO) {
            val connection = connectionPool.getConnection(peerId)

            if (connection == null) {
                Log.e(TAG, "Cannot send: no connection to ${DeviceIdentity.getShortId(peerId)}")
                withContext(Dispatchers.Main) {
                    notifyMessage(
                            "[Error] Not connected to ${DeviceIdentity.getShortId(peerId)}",
                            null
                    )
                }
                return@launch
            }

            try {
                val outputStream =
                        connection.outputStream ?: throw Exception("OutputStream is null")

                // Create text content
                val textContent = TextContent(text = message)

                // Check if peer is trusted (key exchange completed)
                val peerPublicKey = keyManager.getPeerPublicKey(peerId)

                val chatMessage = if (peerPublicKey != null) {
                    // ENCRYPT: Serialize content, then encrypt with peer's public key
                    val contentBytes = textContent.encode()
                    val encryptedBytes = libSignalManager.encrypt(peerPublicKey, contentBytes)
                    val myPublicKey = keyManager.getIdentityKeyPair()?.publicKey

                    ChatMessage(
                        message_id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        sender_id = localDeviceId,
                        encrypted_content = encryptedBytes.toByteString(),
                        encryption_version = "hpke-v1",
                        sender_public_key = myPublicKey?.data?.toByteString()
                    )
                } else {
                    // PLAINTEXT FALLBACK: Peer not trusted, send unencrypted
                    Log.w(TAG, "Peer $peerId not trusted, sending plaintext")
                    ChatMessage(
                        message_id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        sender_id = localDeviceId,
                        text_content = textContent
                    )
                }

                // Serialize to bytes
                val messageBytes = chatMessage.encode()

                // Send with length-prefixed framing
                outputStream.writeInt(messageBytes.size)
                outputStream.write(messageBytes)
                outputStream.flush()

                connection.updateLastMessageTime()

                Log.d(TAG, "Message sent to ${DeviceIdentity.getShortId(peerId)} (encrypted=${peerPublicKey != null}): $message")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message to $peerId: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    notifyMessage("[Error] Failed to send message: ${e.message}", peerId)
                }
                handleConnectionLost(peerId)
            }
        }
    }

    /** Broadcast message to all connected peers */
    fun broadcastMessage(message: String) {
        val peers = connectionPool.getAllConnections()
        Log.d(TAG, "Broadcasting message to ${peers.size} peers")

        peers.forEach { connection -> sendMessageToPeer(message, connection.peerId) }
    }

    /** Send picture to specific peer or broadcast to all */
    fun sendPicture(
            imageData: ByteArray,
            filename: String?,
            mimeType: String?,
            targetPeerId: String? = null
    ) {
        if (targetPeerId != null) {
            sendPictureToPeer(imageData, filename, mimeType, targetPeerId)
        } else {
            broadcastPicture(imageData, filename, mimeType)
        }
    }

    /** Send picture to specific peer */
    fun sendPictureToPeer(
            imageData: ByteArray,
            filename: String?,
            mimeType: String?,
            peerId: String
    ) {
        scope.launch(Dispatchers.IO) {
            val connection = connectionPool.getConnection(peerId)

            if (connection == null) {
                Log.e(
                        TAG,
                        "Cannot send picture: no connection to ${DeviceIdentity.getShortId(peerId)}"
                )
                withContext(Dispatchers.Main) {
                    notifyMessage(
                            "[Error] Not connected to ${DeviceIdentity.getShortId(peerId)}",
                            null
                    )
                }
                return@launch
            }

            try {
                val outputStream =
                        connection.outputStream ?: throw Exception("OutputStream is null")

                // Create photo content
                val photoContent = PhotoContent(
                    data_ = imageData.toByteString(),
                    filename = filename,
                    mime_type = mimeType
                )

                // Check if peer is trusted (key exchange completed)
                val peerPublicKey = keyManager.getPeerPublicKey(peerId)

                val chatMessage = if (peerPublicKey != null) {
                    // ENCRYPT: Serialize content, then encrypt with peer's public key
                    val contentBytes = photoContent.encode()
                    val encryptedBytes = libSignalManager.encrypt(peerPublicKey, contentBytes)
                    val myPublicKey = keyManager.getIdentityKeyPair()?.publicKey

                    ChatMessage(
                        message_id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        sender_id = localDeviceId,
                        encrypted_content = encryptedBytes.toByteString(),
                        encryption_version = "hpke-v1",
                        sender_public_key = myPublicKey?.data?.toByteString()
                    )
                } else {
                    // PLAINTEXT FALLBACK: Peer not trusted, send unencrypted
                    Log.w(TAG, "Peer $peerId not trusted, sending plaintext image")
                    ChatMessage(
                        message_id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        sender_id = localDeviceId,
                        photo_content = photoContent
                    )
                }

                // Serialize to bytes
                val messageBytes = chatMessage.encode()

                Log.d(
                        TAG,
                        "Sending picture to ${DeviceIdentity.getShortId(peerId)} (encrypted=${peerPublicKey != null}): ${messageBytes.size} bytes"
                )

                // Send with length-prefixed framing
                outputStream.writeInt(messageBytes.size)
                outputStream.write(messageBytes)
                outputStream.flush()

                connection.updateLastMessageTime()

                Log.d(TAG, "Picture sent to ${DeviceIdentity.getShortId(peerId)}: $filename")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send picture to $peerId: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    notifyMessage("[Error] Failed to send picture: ${e.message}", peerId)
                }
                handleConnectionLost(peerId)
            }
        }
    }

    /** Broadcast picture to all connected peers */
    fun broadcastPicture(imageData: ByteArray, filename: String?, mimeType: String?) {
        val peers = connectionPool.getAllConnections()
        Log.d(TAG, "Broadcasting picture to ${peers.size} peers")

        peers.forEach { connection ->
            sendPictureToPeer(imageData, filename, mimeType, connection.peerId)
        }
    }

    /** Handle connection loss */
    private fun handleConnectionLost(peerId: String) {
        val connection = connectionPool.removeConnection(peerId) ?: return

        Log.w(TAG, "Handling connection loss for ${DeviceIdentity.getShortId(peerId)}")

        // Cleanup resources
        scope.launch(Dispatchers.IO) {
            try {
                connection.inputStream?.close()
                connection.outputStream?.close()
                connection.socket?.close()
                connection.serverSocket?.close()
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

    /** Start heartbeat monitor */
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
                            Log.w(
                                    TAG,
                                    "Unhealthy connection detected: ${DeviceIdentity.getShortId(connection.peerId)}"
                            )
                            handleConnectionLost(connection.peerId)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Heartbeat error for ${connection.peerId}: ${e.message}")
                    }
                }
            }
        }
    }

    /** Get connected peer IDs */
    fun getConnectedPeers(): List<String> {
        return connectionPool.getPeerIds()
    }

    /** Check if specific peer is connected */
    fun isPeerConnected(peerId: String): Boolean {
        return connectionPool.hasConnection(peerId)
    }

    /** Get connection status summary */
    fun getConnectionStatus(): String {
        val stats = connectionPool.getStatistics()
        return buildString {
            append("Connections: ${stats.totalConnections}")
            if (stats.totalConnections > 0) {
                append(" (${stats.serverConnections} server, ${stats.clientConnections} client)")
            }
        }
    }

    /** Get local device ID */
    fun getDeviceId(): String = localDeviceId

    /** Stop discovery and cleanup all connections */
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

    /** Helper to notify message callback */
    private fun notifyMessage(message: String, peerId: String? = null) {
        scope.launch(Dispatchers.Main) {
            // Create a ChatMessage for system messages
            val chatMessage =
                    ChatMessage(
                            message_id = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            sender_id = "system",
                            text_content = TextContent(text = message)
                    )
            messageCallback?.invoke(chatMessage, peerId)
        }
    }

    /** Helper to notify connection status callback */
    private fun notifyConnectionStatus(peerId: String, state: ConnectionState) {
        scope.launch(Dispatchers.Main) { connectionStatusCallback?.invoke(peerId, state) }
    }

    /** Check if required permissions are granted */
    private fun hasRequiredPermissions(): Boolean {
        val perms =
                listOf(
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
