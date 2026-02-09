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
import net.discdd.trick.signal.SignalError
import net.discdd.trick.signal.SignalSessionManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlin.coroutines.cancellation.CancellationException
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
class AndroidWifiAwareManager(
    private val context: Context,
    private val signalSessionManager: SignalSessionManager
) {
    private val TAG = "WifiAware"

    companion object {
        private const val CONTENT_TYPE_TEXT = 0
        private const val CONTENT_TYPE_PHOTO = 1
        private const val HEARTBEAT_FRAME_LENGTH = 0
    }

    // WiFi Aware components
    private var wifiAwareManager: WifiAwareManager? = null
    private var session: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null

    // Device identity
    private val localDeviceId: String by lazy { DeviceIdentity.generateDeviceId(context) }

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
    private val desiredPeerId = AtomicReference<String?>(null)

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
            isRunning.set(false)
            return
        }

        if (wifiAwareManager?.isAvailable != true) {
            Log.e(TAG, "WiFi Aware is not available")
            notifyMessage("[Error] WiFi Aware not available on this device")
            isRunning.set(false)
            return
        }

        try {
            wifiAwareManager?.attach(
                    object : AttachCallback() {
                        override fun onAttached(wifiSession: WifiAwareSession) {
                            session = wifiSession
                            startPublishing(wifiSession)
                            startSubscribing(wifiSession)
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
                        .setServiceName("_trick-msg._tcp")
                        .setServiceSpecificInfo(localDeviceId.toByteArray())
                        .build()

        try {
            wifiSession.publish(
                    publishConfig,
                    object : DiscoverySessionCallback() {
                        override fun onPublishStarted(session: PublishDiscoverySession) {
                            publishSession = session
                        }

                        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                            handleDiscoveryMessage(peerHandle, message, isFromPublish = true)
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

        val subscribeConfig = SubscribeConfig.Builder().setServiceName("_trick-msg._tcp").build()

        try {
            wifiSession.subscribe(
                    subscribeConfig,
                    object : DiscoverySessionCallback() {
                        override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                            subscribeSession = session
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

        peerDeviceIds[peerHandle] = remoteDeviceId

        if (connectionPool.hasConnection(remoteDeviceId)) return

        val desired = desiredPeerId.get()
        if (desired != remoteDeviceId) return

        runConnectionFlowForPeer(peerHandle, remoteDeviceId)
    }

    /** Run role negotiation and initiate connection (handshake or wait). */
    private fun runConnectionFlowForPeer(peerHandle: PeerHandle, remoteDeviceId: String) {
        val role = DeviceIdentity.negotiateRole(localDeviceId, remoteDeviceId)

        when (role) {
            Role.SERVER -> { /* Wait for handshake from client */ }
            Role.CLIENT -> {
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
            @Suppress("UNUSED_PARAMETER") isFromPublish: Boolean
    ) {
        val messageStr = String(message)

        DeviceIdentity.parseHandshakeMessage(messageStr)?.let { remoteDeviceId ->
            handleHandshakeReceived(peerHandle, remoteDeviceId)
            return
        }

        DeviceIdentity.parsePortMessage(messageStr)?.let { port ->
            val remoteDeviceId = peerDeviceIds[peerHandle] ?: return
            handlePortReceived(peerHandle, remoteDeviceId, port)
            return
        }

        val peerId = peerDeviceIds[peerHandle]
        if (peerId != null && connectionPool.hasConnection(peerId)) {
            return
        }

        if (!DeviceIdentity.isSystemMessage(messageStr)) {
            Log.w(TAG, "Unexpected message during discovery: $messageStr")
        }
    }

    /** Send handshake to peer (client role) */
    private fun sendHandshake(peerHandle: PeerHandle, remoteDeviceId: String) {
        pendingHandshakes.add(peerHandle)
        val handshakeMessage = DeviceIdentity.createHandshakeMessage(localDeviceId)

        subscribeSession?.sendMessage(
                peerHandle,
                System.currentTimeMillis().toInt(),
                handshakeMessage.toByteArray()
        )

        notifyConnectionStatus(remoteDeviceId, ConnectionState.NEGOTIATING)
    }

    /** Handle handshake received (server role) */
    private fun handleHandshakeReceived(peerHandle: PeerHandle, remoteDeviceId: String) {
        peerDeviceIds[peerHandle] = remoteDeviceId

        if (connectionPool.hasConnection(remoteDeviceId)) return

        val role = DeviceIdentity.negotiateRole(localDeviceId, remoteDeviceId)
        if (role != Role.SERVER) {
            Log.e(TAG, "Received handshake but we should be client! Ignoring.")
            return
        }

        notifyConnectionStatus(remoteDeviceId, ConnectionState.CONNECTING)

        scope.launch { setupServerConnection(peerHandle, remoteDeviceId) }
    }

    /** Setup server-side connection */
    private suspend fun setupServerConnection(peerHandle: PeerHandle, remoteDeviceId: String) {
        try {
            val serverSocket = withContext(Dispatchers.IO) {
                ServerSocket(0).apply {
                    soTimeout = 30000
                }
            }
            val assignedPort = serverSocket.localPort

            // Start accepting connections before sending port to avoid race condition
            val acceptDeferred = scope.async(Dispatchers.IO) {
                serverSocket.accept()
            }

            val session = publishSession ?: throw Exception("Publish session is null")
            val networkSpecifier =
                    WifiAwareNetworkSpecifier.Builder(session, peerHandle)
                            .setPskPassphrase(DeviceIdentity.getPskPassphrase())
                            .setPort(assignedPort)
                            .build()

            val networkRequest =
                    NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                            .setNetworkSpecifier(networkSpecifier)
                            .build()

            @Suppress("VARIABLE_WITH_REDUNDANT_INITIALIZER")
            var network: Network? = null

            val callbackDeferred = CompletableDeferred<Network>()

            val networkCallback =
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(net: Network) {
                            network = net
                            callbackDeferred.complete(net)
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

            // Send port to client so they can make their network request
            val portMessage = DeviceIdentity.createPortMessage(assignedPort)
            publishSession?.sendMessage(
                    peerHandle,
                    System.currentTimeMillis().toInt(),
                    portMessage.toByteArray()
            )

            network = callbackDeferred.await()

            val clientSocket = try {
                acceptDeferred.await()
            } catch (e: Exception) {
                serverSocket.close()
                connectivityManager.unregisterNetworkCallback(networkCallback)
                throw Exception("Accept failed: ${e.message}")
            }

            val inputStream = DataInputStream(clientSocket.getInputStream())
            val outputStream = DataOutputStream(clientSocket.getOutputStream())

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

            connectionPool.addConnection(remoteDeviceId, connection)
            pendingHandshakes.remove(peerHandle)

            Log.d(TAG, "[Server] Connection established with ${DeviceIdentity.getShortId(remoteDeviceId)}")
            notifyConnectionStatus(remoteDeviceId, ConnectionState.CONNECTED)
            notifyMessage(
                    "You're now connected to ${DeviceIdentity.getShortId(remoteDeviceId)}!",
                    remoteDeviceId
            )

            startMessageListener(remoteDeviceId)
        } catch (e: CancellationException) {
            pendingHandshakes.remove(peerHandle)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "[Server] Connection setup failed: ${e.message}", e)
            notifyConnectionStatus(remoteDeviceId, ConnectionState.DISCONNECTED)
            notifyMessage("[Error] Server connection failed: ${e.message}", remoteDeviceId)
            pendingHandshakes.remove(peerHandle)
        }
    }

    /** Handle port received (client role) */
    private fun handlePortReceived(peerHandle: PeerHandle, remoteDeviceId: String, port: Int) {
        if (connectionPool.hasConnection(remoteDeviceId)) return

        if (subscribeSession == null) {
            Log.e(TAG, "[Client] Subscribe session not available")
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
            val session = subscribeSession ?: throw Exception("Subscribe session is null")
            val networkSpecifier =
                    WifiAwareNetworkSpecifier.Builder(session, peerHandle)
                            .setPskPassphrase(DeviceIdentity.getPskPassphrase())
                            .build()

            val networkRequest =
                    NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                            .setNetworkSpecifier(networkSpecifier)
                            .build()

            @Suppress("VARIABLE_WITH_REDUNDANT_INITIALIZER")
            var network: Network? = null
            @Suppress("VARIABLE_WITH_REDUNDANT_INITIALIZER")
            var serverIpv6: String? = null

            val callbackDeferred = CompletableDeferred<Pair<Network, String>>()

            val networkCallback =
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(net: Network) {
                            network = net
                        }

                        override fun onCapabilitiesChanged(
                                net: Network,
                                capabilities: NetworkCapabilities
                        ) {
                            val wifiAwareInfo = capabilities.transportInfo as? WifiAwareNetworkInfo
                            val ipv6 = wifiAwareInfo?.peerIpv6Addr

                            if (ipv6 != null && network != null && !callbackDeferred.isCompleted) {
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

            val (net, ipv6) =
                    try {
                        withTimeoutOrNull(15000) {
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

            val socket =
                    try {
                        withContext(Dispatchers.IO) {
                            val sock = net.socketFactory.createSocket()
                            sock.connect(InetSocketAddress(ipv6, serverPort), 15000)
                            sock
                        }
                    } catch (e: Exception) {
                        connectivityManager.unregisterNetworkCallback(networkCallback)
                        throw e
                    }

            val inputStream = DataInputStream(socket.getInputStream())
            val outputStream = DataOutputStream(socket.getOutputStream())

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

            connectionPool.addConnection(remoteDeviceId, connection)
            pendingHandshakes.remove(peerHandle)

            Log.d(TAG, "[Client] Connection established with ${DeviceIdentity.getShortId(remoteDeviceId)}")
            notifyConnectionStatus(remoteDeviceId, ConnectionState.CONNECTED)
            notifyMessage(
                    "You're now connected to ${DeviceIdentity.getShortId(remoteDeviceId)}!",
                    remoteDeviceId
            )

            startMessageListener(remoteDeviceId)
        } catch (e: CancellationException) {
            pendingHandshakes.remove(peerHandle)
            throw e
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
                                Log.e(TAG, "Cannot start listener: connection not found for $peerId")
                                return@launch
                            }

            val inputStream =
                    connection.inputStream
                            ?: run {
                                Log.e(TAG, "Cannot start listener: inputStream is null")
                                return@launch
                            }

            try {
                while (isActive && connection.socket?.isConnected == true) {
                    val messageLength = inputStream.readInt()

                    // Heartbeat frame: length == 0 means lightweight ping
                    if (messageLength == HEARTBEAT_FRAME_LENGTH) {
                        connection.updateLastMessageTime()
                        continue
                    }

                    if (messageLength < 0 || messageLength > 10_000_000) {
                        Log.e(TAG, "Invalid message length: $messageLength")
                        break
                    }

                    val messageBytes = ByteArray(messageLength)
                    inputStream.readFully(messageBytes)

                    connection.updateLastMessageTime()

                    val chatMessage =
                            try {
                                ChatMessage.ADAPTER.decode(messageBytes)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to decode protobuf message: ${e.message}")
                                continue
                            }

                    val decryptedMessage = handleReceivedMessage(chatMessage, peerId)

                    withContext(Dispatchers.Main) { messageCallback?.invoke(decryptedMessage, peerId) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Message listener error for $peerId: ${e.message}")
            } finally {
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
        val textContent = TextContent(text = message)
        val contentBytes = byteArrayOf(CONTENT_TYPE_TEXT.toByte()) + textContent.encode()
        sendEncryptedContent(contentBytes, peerId)
    }

    /** Broadcast message to all connected peers */
    fun broadcastMessage(message: String) {
        connectionPool.getAllConnections().forEach { connection ->
            sendMessageToPeer(message, connection.peerId)
        }
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
        val photoContent = PhotoContent(
            data_ = imageData.toByteString(),
            filename = filename,
            mime_type = mimeType
        )
        val contentBytes = byteArrayOf(CONTENT_TYPE_PHOTO.toByte()) + photoContent.encode()
        sendEncryptedContent(contentBytes, peerId)
    }

    /** Broadcast picture to all connected peers */
    fun broadcastPicture(imageData: ByteArray, filename: String?, mimeType: String?) {
        connectionPool.getAllConnections().forEach { connection ->
            sendPictureToPeer(imageData, filename, mimeType, connection.peerId)
        }
    }

    /**
     * Encrypt content bytes with Signal and send over the TCP connection.
     * Handles session check, encryption, framing, and error reporting.
     */
    private fun sendEncryptedContent(contentBytes: ByteArray, peerId: String) {
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

                if (!signalSessionManager.hasSession(peerId)) {
                    Log.e(TAG, "No Signal session for $peerId")
                    withContext(Dispatchers.Main) {
                        notifyMessage("[Error] Secure session not established. Exchange QR codes first.", peerId)
                    }
                    return@launch
                }

                val result = signalSessionManager.encryptMessage(peerId, 1, contentBytes)

                val chatMessage = ChatMessage(
                    message_id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    sender_id = localDeviceId,
                    encrypted_content = result.ciphertext.toByteString(),
                    encryption_version = "signal-v1"
                )

                val messageBytes = chatMessage.encode()

                outputStream.writeInt(messageBytes.size)
                outputStream.write(messageBytes)
                outputStream.flush()

                connection.updateLastMessageTime()
            } catch (e: SignalError.NoSession) {
                Log.e(TAG, "No Signal session for $peerId")
                withContext(Dispatchers.Main) {
                    notifyMessage("[Error] Secure session not established. Exchange QR codes first.", peerId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send to $peerId: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    notifyMessage("[Error] Failed to send message: ${e.message}", peerId)
                }
                handleConnectionLost(peerId)
            }
        }
    }

    /**
     * Handle received message with proper encryption handling.
     * - Signal-v1: Decrypt using SignalSessionManager
     * - HPKE-v1: Reject as downgrade attempt (only allowed for local history)
     * - Plaintext: Reject - encryption is required
     */
    private suspend fun handleReceivedMessage(chatMessage: ChatMessage, peerId: String): ChatMessage {
        return when {
            chatMessage.encrypted_content == null -> {
                Log.e(TAG, "REJECTED: Plaintext message from $peerId - encryption required")
                chatMessage.copy(text_content = TextContent(text = "[Rejected: unencrypted message]"))
            }

            chatMessage.encryption_version == "signal-v1" -> {
                try {
                    val encryptedContent = chatMessage.encrypted_content
                        ?: return chatMessage.copy(text_content = TextContent(text = "[Decryption failed: No encrypted content]"))

                    val result = signalSessionManager.decryptMessage(
                        senderId = peerId,
                        deviceId = 1,
                        ciphertext = encryptedContent.toByteArray()
                    )

                    val decryptedBytes = result.plaintext
                    when {
                        decryptedBytes.isEmpty() -> {
                            Log.e(TAG, "Decrypted content is empty")
                            chatMessage.copy(text_content = TextContent(text = "[Decryption failed: Invalid content]"))
                        }
                        decryptedBytes[0] == CONTENT_TYPE_TEXT.toByte() -> {
                            val payload = decryptedBytes.copyOfRange(1, decryptedBytes.size)
                            val textContent = TextContent.ADAPTER.decode(payload.toByteString())
                            chatMessage.copy(text_content = textContent, encrypted_content = null)
                        }
                        decryptedBytes[0] == CONTENT_TYPE_PHOTO.toByte() -> {
                            val payload = decryptedBytes.copyOfRange(1, decryptedBytes.size)
                            val photoContent = PhotoContent.ADAPTER.decode(payload.toByteString())
                            chatMessage.copy(photo_content = photoContent, encrypted_content = null)
                        }
                        else -> {
                            Log.e(TAG, "Unknown content type: ${decryptedBytes[0]}")
                            chatMessage.copy(text_content = TextContent(text = "[Decryption failed: Unknown content type]"))
                        }
                    }
                } catch (e: SignalError.UntrustedIdentity) {
                    Log.e(TAG, "Identity changed for $peerId")
                    chatMessage.copy(text_content = TextContent(text = "[Security: Identity changed - verify contact]"))
                } catch (e: SignalError.InvalidMessage) {
                    Log.e(TAG, "Signal decryption failed: ${e.reason}")
                    chatMessage.copy(text_content = TextContent(text = "[Decryption failed]"))
                } catch (e: SignalError.NoSession) {
                    Log.e(TAG, "No Signal session for $peerId")
                    chatMessage.copy(text_content = TextContent(text = "[No secure session]"))
                } catch (e: Exception) {
                    Log.e(TAG, "Signal decryption error: ${e.message}")
                    chatMessage.copy(text_content = TextContent(text = "[Decryption failed: ${e.message}]"))
                }
            }

            chatMessage.encryption_version == "hpke-v1" -> {
                Log.e(TAG, "DOWNGRADE REJECTED: hpke-v1 from $peerId over network")
                chatMessage.copy(text_content = TextContent(text = "[Rejected: encryption downgrade]"))
            }

            else -> {
                Log.e(TAG, "Unknown encryption version: ${chatMessage.encryption_version}")
                chatMessage.copy(text_content = TextContent(text = "[Unknown encryption]"))
            }
        }
    }

    /** Close a connection's resources without triggering reconnection logic. */
    private fun closeConnection(connection: PeerConnection) {
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
    }

    /** Handle connection loss with reconnection attempt */
    private fun handleConnectionLost(peerId: String) {
        val connection = connectionPool.removeConnection(peerId) ?: return

        Log.w(TAG, "Connection lost to ${DeviceIdentity.getShortId(peerId)}")

        closeConnection(connection)

        notifyConnectionStatus(peerId, ConnectionState.DISCONNECTED)
        notifyMessage("Connection lost to ${DeviceIdentity.getShortId(peerId)}", peerId)

        // Attempt reconnection if discovery is still running
        if (isRunning.get()) {
            scope.launch {
                delay(2000)
                notifyConnectionStatus(peerId, ConnectionState.RECONNECTING)
                tryConnectToDesiredPeer()
            }
        }
    }

    /** Send a lightweight heartbeat ping (raw 4-byte zero-length frame) directly on the socket. */
    private fun sendHeartbeatPing(connection: PeerConnection): Boolean {
        val outputStream = connection.outputStream ?: return false
        return try {
            outputStream.writeInt(HEARTBEAT_FRAME_LENGTH)
            outputStream.flush()
            connection.updateLastMessageTime()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Start heartbeat monitor using lightweight raw socket pings */
    private fun startHeartbeatMonitor() {
        scope.launch {
            while (isActive && isRunning.get()) {
                delay(30000)

                val connections = connectionPool.getAllConnections()

                connections.forEach { connection ->
                    if (!sendHeartbeatPing(connection)) {
                        Log.w(TAG, "Heartbeat failed for ${DeviceIdentity.getShortId(connection.peerId)}")
                        handleConnectionLost(connection.peerId)
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

    /**
     * Set the peer to connect to when discovered. Only this peer will get connection establishment.
     * If the peer was already discovered, connection flow is triggered immediately.
     */
    fun setDesiredPeerId(peerId: String?) {
        desiredPeerId.set(peerId)
        if (peerId != null) {
            tryConnectToDesiredPeer()
        }
    }

    /** If desired peer is set and already discovered (but not connected), run connection flow. */
    private fun tryConnectToDesiredPeer() {
        val desired = desiredPeerId.get() ?: return
        if (connectionPool.hasConnection(desired)) return

        val snapshot = peerDeviceIds.entries.toList()
        for ((peerHandle, deviceId) in snapshot) {
            if (deviceId == desired && !connectionPool.hasConnection(deviceId)) {
                runConnectionFlowForPeer(peerHandle, deviceId)
                return
            }
        }
    }

    /** Stop discovery and cleanup all connections */
    fun stopDiscovery() {
        if (!isRunning.getAndSet(false)) {
            return
        }

        // Close all connections directly without triggering reconnection
        connectionPool.getAllConnections().forEach { connection ->
            connectionPool.removeConnection(connection.peerId)
            closeConnection(connection)
        }

        scope.cancel()

        publishSession?.close()
        subscribeSession?.close()
        session?.close()

        publishSession = null
        subscribeSession = null
        session = null

        connectionPool.clear()
        peerDeviceIds.clear()
        pendingHandshakes.clear()
    }

    /** Helper to notify message callback */
    private fun notifyMessage(message: String, peerId: String? = null) {
        scope.launch(Dispatchers.Main) {
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
