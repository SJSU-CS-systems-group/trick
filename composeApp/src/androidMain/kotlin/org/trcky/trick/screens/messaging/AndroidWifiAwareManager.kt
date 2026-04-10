package org.trcky.trick.screens.messaging

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.*
import android.net.wifi.aware.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import org.trcky.trick.messaging.ChatMessage
import org.trcky.trick.messaging.PhotoContent
import org.trcky.trick.messaging.TextContent
import org.trcky.trick.signal.SignalError
import org.trcky.trick.signal.SignalSessionManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlin.coroutines.cancellation.CancellationException
import org.trcky.trick.metrics.PerformanceTracker
import org.trcky.trick.security.QRKeyDistribution
import org.trcky.trick.signal.PreKeyBundleSerialization
import org.trcky.trick.util.sha256
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
    private val signalSessionManager: SignalSessionManager,
    private val trickDDDManager: TrickDDDManager? = null
) {
    private val TAG = "WifiAware"

    companion object {
        private const val CONTENT_TYPE_TEXT = 0
        private const val CONTENT_TYPE_PHOTO = 1
        private const val HEARTBEAT_FRAME_LENGTH = 0
    }

    private val peerDddClientIds = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val db by lazy { org.trcky.trick.data.DatabaseProvider.getDatabase() }

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
    
    // Bidirectional stress test mode
    private val bidirectionalStressTestPeerId = AtomicReference<String?>(null)

    // ── Performance metrics timers ───────────────────────────────────────
    private var attachTimerToken: Long = -1
    private var discoveryTimerToken: Long = -1
    private var totalConnectTimerToken: Long = -1
    private val connectionTimerTokens = java.util.concurrent.ConcurrentHashMap<String, Long>() // peerId → token
    private val reconnectionTimerTokens = java.util.concurrent.ConcurrentHashMap<String, Long>() // peerId → token

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
            // ── Metrics: start attach + total connect timers ─────────
            attachTimerToken = PerformanceTracker.startTimer("wifi_aware", "attach_time")
            totalConnectTimerToken = PerformanceTracker.startTimer("wifi_aware", "total_connect_time")

            wifiAwareManager?.attach(
                    object : AttachCallback() {
                        override fun onAttached(wifiSession: WifiAwareSession) {
                            // ── Metrics: stop attach timer, start discovery timer ──
                            PerformanceTracker.stopTimer(attachTimerToken, "wifi_aware", "attach_time")
                            discoveryTimerToken = PerformanceTracker.startTimer("wifi_aware", "discovery_time")

                            session = wifiSession
                            startPublishing(wifiSession)
                            startSubscribing(wifiSession)
                            startHeartbeatMonitor()
                        }

                        override fun onAttachFailed() {
                            PerformanceTracker.cancelTimer(attachTimerToken)
                            PerformanceTracker.cancelTimer(totalConnectTimerToken)
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

        // ── Metrics: stop discovery timer on first peer found ────────
        if (discoveryTimerToken >= 0) {
            PerformanceTracker.stopTimer(discoveryTimerToken, "wifi_aware", "discovery_time",
                mapOf("peer_id" to DeviceIdentity.getShortId(remoteDeviceId)))
            discoveryTimerToken = -1
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

        // ── Metrics: start server connection timer ───────────────────
        connectionTimerTokens[remoteDeviceId] =
            PerformanceTracker.startTimer("wifi_aware", "connection_server_time")

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

            // Bundle exchange: verify hash commitment before starting message loop
            if (!exchangeAndVerifyBundle(remoteDeviceId, inputStream, outputStream)) {
                clientSocket.close()
                serverSocket.close()
                connectivityManager.unregisterNetworkCallback(networkCallback)
                notifyConnectionStatus(remoteDeviceId, ConnectionState.DISCONNECTED)
                notifyMessage("[Error] Pairing verification failed — possible MITM attack!", remoteDeviceId)
                pendingHandshakes.remove(peerHandle)
                return
            }

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

            // ── Metrics: stop server connection timer + total connect ─
            val peerShortId = DeviceIdentity.getShortId(remoteDeviceId)
            connectionTimerTokens.remove(remoteDeviceId)?.let { token ->
                PerformanceTracker.stopTimer(token, "wifi_aware", "connection_server_time",
                    mapOf("peer_id" to peerShortId, "role" to "server"))
            }
            if (totalConnectTimerToken >= 0) {
                PerformanceTracker.stopTimer(totalConnectTimerToken, "wifi_aware", "total_connect_time",
                    mapOf("peer_id" to peerShortId, "role" to "server"))
                totalConnectTimerToken = -1
            }
            reconnectionTimerTokens.remove(remoteDeviceId)?.let { token ->
                PerformanceTracker.stopTimer(token, "wifi_aware", "reconnection_time",
                    mapOf("peer_id" to peerShortId))
            }
            PerformanceTracker.recordMemorySnapshot(connectionPool.size())

            notifyConnectionStatus(remoteDeviceId, ConnectionState.CONNECTED)
            notifyMessage(
                    "You're now connected to ${DeviceIdentity.getShortId(remoteDeviceId)}!",
                    remoteDeviceId
            )

            startMessageListener(remoteDeviceId)
        } catch (e: CancellationException) {
            connectionTimerTokens.remove(remoteDeviceId)?.let { PerformanceTracker.cancelTimer(it) }
            pendingHandshakes.remove(peerHandle)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "[Server] Connection setup failed: ${e.message}", e)
            connectionTimerTokens.remove(remoteDeviceId)?.let { PerformanceTracker.cancelTimer(it) }
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

        // ── Metrics: start client connection timer ───────────────────
        connectionTimerTokens[remoteDeviceId] =
            PerformanceTracker.startTimer("wifi_aware", "connection_client_time")

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

            // Bundle exchange: verify hash commitment before starting message loop
            if (!exchangeAndVerifyBundle(remoteDeviceId, inputStream, outputStream)) {
                socket.close()
                connectivityManager.unregisterNetworkCallback(networkCallback)
                notifyConnectionStatus(remoteDeviceId, ConnectionState.DISCONNECTED)
                notifyMessage("[Error] Pairing verification failed — possible MITM attack!", remoteDeviceId)
                pendingHandshakes.remove(peerHandle)
                return
            }

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

            // ── Metrics: stop client connection timer + total connect ─
            val peerShortId = DeviceIdentity.getShortId(remoteDeviceId)
            connectionTimerTokens.remove(remoteDeviceId)?.let { token ->
                PerformanceTracker.stopTimer(token, "wifi_aware", "connection_client_time",
                    mapOf("peer_id" to peerShortId, "role" to "client"))
            }
            if (totalConnectTimerToken >= 0) {
                PerformanceTracker.stopTimer(totalConnectTimerToken, "wifi_aware", "total_connect_time",
                    mapOf("peer_id" to peerShortId, "role" to "client"))
                totalConnectTimerToken = -1
            }
            reconnectionTimerTokens.remove(remoteDeviceId)?.let { token ->
                PerformanceTracker.stopTimer(token, "wifi_aware", "reconnection_time",
                    mapOf("peer_id" to peerShortId))
            }
            PerformanceTracker.recordMemorySnapshot(connectionPool.size())

            notifyConnectionStatus(remoteDeviceId, ConnectionState.CONNECTED)
            notifyMessage(
                    "You're now connected to ${DeviceIdentity.getShortId(remoteDeviceId)}!",
                    remoteDeviceId
            )

            startMessageListener(remoteDeviceId)
        } catch (e: CancellationException) {
            connectionTimerTokens.remove(remoteDeviceId)?.let { PerformanceTracker.cancelTimer(it) }
            pendingHandshakes.remove(peerHandle)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "[Client] Connection setup failed: ${e.message}", e)
            connectionTimerTokens.remove(remoteDeviceId)?.let { PerformanceTracker.cancelTimer(it) }
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
                val peerShortId = DeviceIdentity.getShortId(peerId)

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

                    // ── Metrics: socket read time ────────────────────────
                    val messageBytes = PerformanceTracker.measure("transport", "socket_read_time",
                        mapOf("wire_size" to (4 + messageLength).toString(), "peer_id" to peerShortId)) {
                        val buf = ByteArray(messageLength)
                        inputStream.readFully(buf)
                        buf
                    }

                    connection.updateLastMessageTime()

                    // ── Metrics: E2E receive timer ───────────────────────
                    val receiveE2eStart = System.nanoTime()

                    // ── Metrics: deserialize time ────────────────────────
                    val chatMessage =
                            try {
                                PerformanceTracker.measure("transport", "deserialize_time",
                                    mapOf("size" to messageBytes.size.toString(), "peer_id" to peerShortId)) {
                                    ChatMessage.ADAPTER.decode(messageBytes)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to decode protobuf message: ${e.message}")
                                continue
                            }

                    // ── Metrics: decrypt + process (also timed inside SignalSessionManager) ──
                    val decryptedMessage = handleReceivedMessage(chatMessage, peerId)

                    // ── Metrics: determine content type from decrypted result ──
                    val receivedContentType = when {
                        decryptedMessage.photo_content != null -> "photo"
                        decryptedMessage.text_content != null -> "text"
                        else -> "unknown"
                    }

                    // ── Metrics: receive E2E total ───────────────────────
                    val receiveE2eMs = (System.nanoTime() - receiveE2eStart) / 1_000_000.0
                    PerformanceTracker.recordDuration("transport", "receive_e2e_time", receiveE2eMs,
                        mapOf("content_type" to receivedContentType,
                            "wire_size" to (4 + messageLength).toString(),
                            "peer_id" to peerShortId))

                    // ── Metrics: receive-side message size ───────────────
                    val ciphertextSize = chatMessage.encrypted_content?.size ?: 0
                    PerformanceTracker.recordValue("transport", "message_size", mapOf(
                        "content_type" to receivedContentType,
                        "ciphertext_size" to ciphertextSize.toString(),
                        "wire_size" to (4 + messageLength).toString(),
                        "peer_id" to peerShortId,
                        "direction" to "receive"
                    ))

                    withContext(Dispatchers.Main) { messageCallback?.invoke(decryptedMessage, peerId) }
                    
                    // Auto-detect stress test messages and enable bidirectional mode + auto-reply
                    val messageText = decryptedMessage.text_content?.text ?: ""
                    val isStressTestMessage = messageText.startsWith("stress_test_") || 
                                             messageText.startsWith("ramp_") || 
                                             messageText.startsWith("benchmark_") ||
                                             messageText.startsWith("reply_")
                    
                    if (isStressTestMessage) {
                        // Enable bidirectional mode for this peer if not already enabled
                        val currentStressPeer = bidirectionalStressTestPeerId.get()
                        if (currentStressPeer != peerId) {
                            bidirectionalStressTestPeerId.set(peerId)
                            Log.i(TAG, "Auto-detected stress test, enabling bidirectional mode for peer ${peerId.take(8)}")
                        }
                        
                        // Auto-reply to keep the session ratcheted
                        scope.launch(Dispatchers.IO) {
                            try {
                                // Small delay to avoid overwhelming the connection
                                delay(10)
                                // Send a simple reply message
                                sendMessageToPeer("reply_${System.currentTimeMillis()}", peerId)
                            } catch (e: Exception) {
                                Log.w(TAG, "Auto-reply failed during stress test: ${e.message}")
                            }
                        }
                    } else {
                        // Auto-reply if bidirectional mode is already enabled for this peer
                        val stressTestPeerId = bidirectionalStressTestPeerId.get()
                        if (stressTestPeerId != null && peerId == stressTestPeerId) {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    delay(10)
                                    sendMessageToPeer("reply_${System.currentTimeMillis()}", peerId)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Auto-reply failed during stress test: ${e.message}")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Message listener error for $peerId: ${e.message}")
            } finally {
                handleConnectionLost(peerId)
            }
        }
    }

    /**
     * TCP bundle exchange: send own prekey bundle and verify peer's bundle against the stored
     * QR hash commitment. Called immediately after TCP socket is established, before the message loop.
     *
     * If no commitment is stored for [peerId] (e.g. reconnection after prior pairing), the exchange
     * is skipped and the function returns true, allowing the existing Signal session to handle things.
     *
     * Returns true if the connection should proceed, false if it must be dropped.
     */
    private suspend fun exchangeAndVerifyBundle(
        peerId: String,
        inputStream: DataInputStream,
        outputStream: DataOutputStream
    ): Boolean {
        val storedCommitment = QRKeyDistribution.getCommitment(peerId)
            ?: return true  // No pending commitment — reconnection or already-paired device, allow through

        return try {
            // Send our bundle
            val localBundle = signalSessionManager.generatePreKeyBundle()
            val localBundleJson = PreKeyBundleSerialization.serialize(
                localBundle,
                System.currentTimeMillis(),
                dddClientId = trickDDDManager?.getLocalDddClientId()
            )
            val localBundleBytes = localBundleJson.encodeToByteArray()
            outputStream.writeInt(localBundleBytes.size)
            outputStream.write(localBundleBytes)
            outputStream.flush()

            // Read peer's bundle
            val peerBundleLength = inputStream.readInt()
            if (peerBundleLength <= 0 || peerBundleLength > 100_000) {
                Log.e(TAG, "Invalid bundle length from $peerId: $peerBundleLength")
                return false
            }
            val peerBundleBytes = ByteArray(peerBundleLength)
            inputStream.readFully(peerBundleBytes)

            val peerBundle = PreKeyBundleSerialization.deserialize(String(peerBundleBytes, Charsets.UTF_8))

            // Verify SHA-256(identityKey || signedPreKeyPublic || kyberPreKeyPublic) == stored commitment
            val kyberPublic = peerBundle.kyberPreKeyPublic ?: run {
                Log.e(TAG, "Peer bundle missing Kyber prekey for ${peerId.take(8)}")
                return false
            }
            val combinedBytes = peerBundle.identityKey + peerBundle.signedPreKeyPublic + kyberPublic
            val computedHash = sha256(combinedBytes)

            if (!computedHash.contentEquals(storedCommitment)) {
                Log.e(TAG, "Bundle hash MISMATCH for ${peerId.take(8)} — possible MITM attack!")
                return false
            }

            // Build Signal session from verified bundle
            signalSessionManager.buildSessionFromPreKeyBundle(peerId, 1, peerBundle)
            QRKeyDistribution.clearCommitment(peerId)
            Log.d(TAG, "Bundle verified and Signal session built with ${DeviceIdentity.getShortId(peerId)}")

            // Cache and persist peer's DDD routing address
            peerBundle.dddClientId?.let { dddClientId ->
                peerDddClientIds[peerId] = dddClientId
                try {
                    db.trickDatabaseQueries.upsertDddClientId(dddClientId, peerId)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to persist DDD client ID for ${peerId.take(8)}: ${e.message}")
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Bundle exchange failed for ${peerId.take(8)}: ${e.message}", e)
            false
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
        sendEncryptedContent(contentBytes, peerId, contentType = "text")
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
        sendEncryptedContent(contentBytes, peerId, contentType = "photo")
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
     *
     * @param contentType "text" or "photo" — used for metrics tagging.
     */
    private fun sendEncryptedContent(contentBytes: ByteArray, peerId: String, contentType: String = "unknown") {
        scope.launch(Dispatchers.IO) {
            val connection = connectionPool.getConnection(peerId)
            val dddClientId = if (connection == null) peerDddClientIds[peerId] else null

            if (connection == null && dddClientId == null) {
                Log.e(TAG, "Cannot send: no connection or DDD address for ${DeviceIdentity.getShortId(peerId)}")
                withContext(Dispatchers.Main) {
                    notifyMessage(
                        "Peer unreachable — no DDD address known for ${DeviceIdentity.getShortId(peerId)}",
                        null
                    )
                }
                return@launch
            }

            // ── Metrics: E2E send timer covers encrypt + serialize + write ──
            val sendE2eStart = System.nanoTime()
            val peerShortId = DeviceIdentity.getShortId(peerId)
            val plaintextSize = contentBytes.size

            try {
                if (!signalSessionManager.hasSession(peerId)) {
                    Log.e(TAG, "No Signal session for $peerId")
                    withContext(Dispatchers.Main) {
                        notifyMessage("[Error] Secure session not established. Exchange QR codes first.", peerId)
                    }
                    return@launch
                }

                // ── Metrics: encrypt (also timed inside SignalSessionManager) ──
                val result = signalSessionManager.encryptMessage(peerId, 1, contentBytes)
                val ciphertextSize = result.ciphertext.size

                // ── Metrics: record sizes ────────────────────────────────
                PerformanceTracker.recordValue("transport", "message_size", mapOf(
                    "content_type" to contentType,
                    "plaintext_size" to plaintextSize.toString(),
                    "ciphertext_size" to ciphertextSize.toString(),
                    "overhead_bytes" to (ciphertextSize - plaintextSize).toString(),
                    "peer_id" to peerShortId,
                    "direction" to "send"
                ))

                val chatMessage = ChatMessage(
                    message_id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    sender_id = localDeviceId,
                    encrypted_content = result.ciphertext.toByteString(),
                    encryption_version = "signal-v1"
                )

                // ── DDD fallback: send via bundle network if no direct connection ──
                if (connection == null && dddClientId != null) {
                    trickDDDManager?.sendViaDDD(dddClientId, chatMessage)
                    return@launch
                }

                val outputStream = connection!!.outputStream ?: throw Exception("OutputStream is null")

                // ── Metrics: serialize ───────────────────────────────────
                val messageBytes = PerformanceTracker.measure("transport", "serialize_time",
                    mapOf("content_type" to contentType, "peer_id" to peerShortId)) {
                    chatMessage.encode()
                }

                val wireSize = 4 + messageBytes.size  // 4-byte length prefix + protobuf

                // ── Metrics: socket write ────────────────────────────────
                PerformanceTracker.measure("transport", "socket_write_time",
                    mapOf("wire_size" to wireSize.toString(), "content_type" to contentType, "peer_id" to peerShortId)) {
                    outputStream.writeInt(messageBytes.size)
                    outputStream.write(messageBytes)
                    outputStream.flush()
                }

                // ── Metrics: E2E send total ──────────────────────────────
                val sendE2eMs = (System.nanoTime() - sendE2eStart) / 1_000_000.0
                PerformanceTracker.recordDuration("transport", "send_e2e_time", sendE2eMs,
                    mapOf("content_type" to contentType, "plaintext_size" to plaintextSize.toString(),
                        "wire_size" to wireSize.toString(), "peer_id" to peerShortId))

                PerformanceTracker.recordMemorySnapshot(connectionPool.size())

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
            // ── Metrics: start reconnection timer ────────────────────
            reconnectionTimerTokens[peerId] =
                PerformanceTracker.startTimer("wifi_aware", "reconnection_time")

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

    /** Get connected peer IDs (full-length device IDs) */
    fun getConnectedPeers(): List<String> {
        return connectionPool.getPeerIds()
    }

    /**
     * Resolve a short peer ID (first 8 chars) to the full device ID from the connection pool.
     * Returns null if no connected peer matches.
     */
    fun resolveShortPeerId(shortId: String): String? {
        return connectionPool.getPeerIds().firstOrNull { fullId ->
            DeviceIdentity.getShortId(fullId) == shortId || fullId == shortId
        }
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
     * Enable bidirectional stress test mode: automatically reply to messages from [peerId].
     * Set to null to disable.
     */
    fun setBidirectionalStressTestMode(peerId: String?) {
        bidirectionalStressTestPeerId.set(peerId)
        if (peerId != null) {
            Log.i(TAG, "Bidirectional stress test mode enabled for peer ${peerId.take(8)}")
        } else {
            Log.i(TAG, "Bidirectional stress test mode disabled")
        }
    }

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
