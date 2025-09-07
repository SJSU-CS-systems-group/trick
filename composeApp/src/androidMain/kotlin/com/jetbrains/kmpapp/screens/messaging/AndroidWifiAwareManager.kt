package com.jetbrains.kmpapp.screens.messaging

import android.Manifest
import android.content.Context
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.WifiAwareSession
import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.net.wifi.aware.AttachCallback
import kotlinx.coroutines.*

class AndroidWifiAwareManager(private val context: Context) {
    private var wifiAwareManager: WifiAwareManager? = null
    private var session: WifiAwareSession? = null
    private var publishSession: DiscoverySession? = null
    private var subscribeSession: DiscoverySession? = null
    private var peerHandle: PeerHandle? = null
    private var messageCallback: ((String) -> Unit)? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val pendingMessages = mutableSetOf<Int>() // Track pending message IDs
    private val messageResults = mutableMapOf<Int, Boolean>() // Track message results

    init {
        wifiAwareManager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
    }

    private fun hasRequiredPermissions(): Boolean {
        val perms = listOf(
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        return perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    fun startDiscovery(onMessageReceived: (String) -> Unit) {
        messageCallback = onMessageReceived
        if (!hasRequiredPermissions()) {
            Log.e("WifiAware", "Missing required permissions")
            return
        }
        try {
            wifiAwareManager?.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    this@AndroidWifiAwareManager.session = session
                    Log.d("WifiAware", "Attached to WiFi Aware session")
                    
                    // Start publishing (advertising) the service
                    session.publish(PublishConfig.Builder()
                        .setServiceName("KMPChat")
                        .setServiceSpecificInfo("Hello from KMP Chat!".toByteArray())
                        .build(), 
                        object : DiscoverySessionCallback() {
                            override fun onPublishStarted(session: android.net.wifi.aware.PublishDiscoverySession) {
                                publishSession = session
                                Log.d("WifiAware", "Publishing started")
                            }
                            
                            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                                val receivedMessage = String(message)
                                Log.d("WifiAware", "Message received via publish session: $receivedMessage")
                                handleIncomingMessage(receivedMessage)
                            }
                            
                            override fun onMessageSendSucceeded(messageId: Int) {
                                Log.d("WifiAware", "Message sent successfully via publish session, ID: $messageId")
                                pendingMessages.remove(messageId)
                                messageResults[messageId] = true
                            }
                            
                            override fun onMessageSendFailed(messageId: Int) {
                                Log.e("WifiAware", "Message send failed via publish session, ID: $messageId")
                                pendingMessages.remove(messageId)
                                messageResults[messageId] = false
                            }
                            
                            override fun onSessionConfigUpdated() {
                                Log.d("WifiAware", "Session config updated")
                            }
                            
                            override fun onSessionConfigFailed() {
                                Log.e("WifiAware", "Session config failed")
                            }
                            
                            override fun onSessionTerminated() {
                                Log.d("WifiAware", "Publish session terminated")
                            }
                        }, Handler(Looper.getMainLooper()))
                    
                    // Start subscribing (discovering) the service
                    session.subscribe(SubscribeConfig.Builder()
                        .setServiceName("KMPChat")
                        .build(),
                        object : DiscoverySessionCallback() {
                            override fun onSubscribeStarted(session: android.net.wifi.aware.SubscribeDiscoverySession) {
                                subscribeSession = session
                                Log.d("WifiAware", "Subscribing started")
                            }
                            
                            override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray?, matchFilter: List<ByteArray>?) {
                                this@AndroidWifiAwareManager.peerHandle = peerHandle
                                Log.d("WifiAware", "Service discovered from peer: $peerHandle")
                                Log.d("WifiAware", "Peer handle stored for messaging")
                                serviceSpecificInfo?.let {
                                    val msg = String(it)
                                    Log.d("WifiAware", "Service info: $msg")
                                    handleIncomingMessage("Service discovered: $msg")
                                }
                            }
                            
                            override fun onServiceDiscoveredWithinRange(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray?, matchFilter: List<ByteArray>?, distanceMm: Int) {
                                this@AndroidWifiAwareManager.peerHandle = peerHandle
                                Log.d("WifiAware", "Service discovered within range: ${distanceMm}mm")
                            }
                            
                            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                                val receivedMessage = String(message)
                                Log.d("WifiAware", "Message received via subscribe session: $receivedMessage")
                                handleIncomingMessage(receivedMessage)
                            }
                            
                            override fun onMessageSendSucceeded(messageId: Int) {
                                Log.d("WifiAware", "Message sent successfully via subscribe session, ID: $messageId")
                                pendingMessages.remove(messageId)
                                messageResults[messageId] = true
                            }
                            
                            override fun onMessageSendFailed(messageId: Int) {
                                Log.e("WifiAware", "Message send failed via subscribe session, ID: $messageId")
                                pendingMessages.remove(messageId)
                                messageResults[messageId] = false
                            }
                            
                            override fun onSessionConfigUpdated() {
                                Log.d("WifiAware", "Subscribe session config updated")
                            }
                            
                            override fun onSessionConfigFailed() {
                                Log.e("WifiAware", "Subscribe session config failed")
                            }
                            
                            override fun onSessionTerminated() {
                                Log.d("WifiAware", "Subscribe session terminated")
                            }
                        }, Handler(Looper.getMainLooper()))
                }
                override fun onAttachFailed() {
                    Log.e("WifiAware", "Attach failed")
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            Log.e("WifiAware", "SecurityException: ${e.message}")
        }
    }

    fun sendMessage(message: String) {
        val peer = peerHandle ?: run {
            Log.e("WifiAware", "No peer handle available - cannot send message")
            return
        }
        
        Log.d("WifiAware", "Attempting to send message: $message to peer: $peer")
        Log.d("WifiAware", "Publish session available: ${publishSession != null}")
        Log.d("WifiAware", "Subscribe session available: ${subscribeSession != null}")
        
        // Use coroutine to add delay and retry logic
        coroutineScope.launch {
            sendMessageWithRetry(message, peer, maxRetries = 3)
        }
    }
    
    private suspend fun sendMessageWithRetry(message: String, peer: PeerHandle, maxRetries: Int) {
        val messageBytes = message.toByteArray()
        var retryCount = 0
        
        while (retryCount < maxRetries) {
            val messageId = System.currentTimeMillis().toInt() + retryCount
            
            Log.d("WifiAware", "Attempt $retryCount: Sending message: $message (ID: $messageId)")
            
            // Add small delay to ensure session is ready
            if (retryCount > 0) {
                delay(500) // 500ms delay between retries
            }
            
            // Try to send via subscribe session first (more reliable for messaging)
            val subscribeSession = subscribeSession as? android.net.wifi.aware.SubscribeDiscoverySession
            if (subscribeSession != null) {
                try {
                    Log.d("WifiAware", "Sending via subscribe session...")
                    pendingMessages.add(messageId) // Track this message
                    subscribeSession.sendMessage(peer, messageId, messageBytes)
                    Log.d("WifiAware", "Message sent via subscribe session: $message (ID: $messageId)")
                    
                    // Wait for callback result with timeout
                    val success = waitForMessageResult(messageId, timeoutMs = 2000)
                    if (success) {
                        Log.d("WifiAware", "Message $messageId confirmed successful via subscribe session")
                        return
                    } else {
                        Log.w("WifiAware", "Message $messageId failed via subscribe session")
                    }
                } catch (e: Exception) {
                    Log.e("WifiAware", "Failed to send via subscribe session: ${e.message}")
                    Log.e("WifiAware", "Exception type: ${e.javaClass.simpleName}")
                    pendingMessages.remove(messageId)
                }
            }
            
            // Fallback: Try to send via publish session
            val publishSession = publishSession as? android.net.wifi.aware.PublishDiscoverySession
            if (publishSession != null) {
                try {
                    Log.d("WifiAware", "Fallback: Sending via publish session...")
                    pendingMessages.add(messageId) // Track this message
                    publishSession.sendMessage(peer, messageId, messageBytes)
                    Log.d("WifiAware", "Message sent via publish session: $message (ID: $messageId)")
                    
                    // Wait for callback result with timeout
                    val success = waitForMessageResult(messageId, timeoutMs = 2000)
                    if (success) {
                        Log.d("WifiAware", "Message $messageId confirmed successful via publish session")
                        return
                    } else {
                        Log.w("WifiAware", "Message $messageId failed via publish session")
                    }
                } catch (e: Exception) {
                    Log.e("WifiAware", "Failed to send via publish session: ${e.message}")
                    Log.e("WifiAware", "Exception type: ${e.javaClass.simpleName}")
                    pendingMessages.remove(messageId)
                }
            }
            
            retryCount++
            Log.w("WifiAware", "Retry $retryCount/$maxRetries failed")
        }
        
        Log.e("WifiAware", "Failed to send message after $maxRetries attempts")
    }
    
    private suspend fun waitForMessageResult(messageId: Int, timeoutMs: Long): Boolean {
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            // Check if we have a result for this message
            messageResults[messageId]?.let { result ->
                messageResults.remove(messageId) // Clean up
                return result
            }
            
            // Check if message is no longer pending (callback arrived)
            if (!pendingMessages.contains(messageId)) {
                // Message was removed from pending, check if we have a result
                messageResults[messageId]?.let { result ->
                    messageResults.remove(messageId) // Clean up
                    return result
                }
                // If no result found, assume failure
                return false
            }
            
            delay(50) // Check every 50ms
        }
        
        // Timeout reached
        Log.w("WifiAware", "Timeout waiting for message result: $messageId")
        pendingMessages.remove(messageId)
        messageResults.remove(messageId)
        return false
    }
    
    private fun handleIncomingMessage(message: String) {
        Log.d("WifiAware", "Handling incoming message: $message")
        messageCallback?.invoke(message)
    }
    
    fun isPeerConnected(): Boolean {
        return peerHandle != null && (publishSession != null || subscribeSession != null)
    }
    
    fun getConnectionStatus(): String {
        return when {
            peerHandle == null -> "No peer discovered"
            publishSession == null && subscribeSession == null -> "No active sessions"
            else -> "Connected to peer: $peerHandle"
        }
    }
    
    fun stopDiscovery() {
        coroutineScope.cancel()
        pendingMessages.clear()
        messageResults.clear()
        publishSession?.close()
        subscribeSession?.close()
        session?.close()
        publishSession = null
        subscribeSession = null
        session = null
        peerHandle = null
        Log.d("WifiAware", "Discovery stopped")
    }
}
