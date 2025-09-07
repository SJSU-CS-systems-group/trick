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
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeDiscoverySession
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.net.wifi.aware.AttachCallback
import android.provider.Settings
class AndroidWifiAwareManager(private val context: Context) {
    private var wifiAwareManager: WifiAwareManager? = null
    private var session: WifiAwareSession? = null
    private var publishSession: DiscoverySession? = null
    private var subscribeSession: DiscoverySession? = null
    private var peerHandle: PeerHandle? = null
    private var messageCallback: ((String) -> Unit)? = null

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
    
    private fun getDeviceName(): String {
        return try {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
                ?: Settings.Secure.getString(context.contentResolver, "bluetooth_name")
                ?: android.os.Build.MODEL
                ?: "Unknown Device"
        } catch (e: Exception) {
            Log.w("WifiAware", "Could not get device name: ${e.message}")
            android.os.Build.MODEL ?: "Unknown Device"
        }
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
                    val deviceName = getDeviceName()
                    Log.d("WifiAware", "Publishing service with device name: $deviceName")
                    session.publish(PublishConfig.Builder()
                        .setServiceName("KMPChat")
                        .setServiceSpecificInfo(deviceName.toByteArray())
                        .build(), 
                        object : DiscoverySessionCallback() {
                            override fun onPublishStarted(session: PublishDiscoverySession) {
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
                            }
                            
                            override fun onMessageSendFailed(messageId: Int) {
                                Log.e("WifiAware", "Message send failed via publish session, ID: $messageId")
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
                            override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                                subscribeSession = session
                                Log.d("WifiAware", "Subscribing started")
                            }
                            
                            override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray?, matchFilter: List<ByteArray>?) {
                                this@AndroidWifiAwareManager.peerHandle = peerHandle
                                Log.d("WifiAware", "Service discovered from peer: $peerHandle")
                                Log.d("WifiAware", "Peer handle stored for messaging")
                                serviceSpecificInfo?.let {
                                    val deviceName = String(it)
                                    Log.d("WifiAware", "Service info: $deviceName")
                                    handleIncomingMessage("Device discovered: $deviceName")
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
                            }
                            
                            override fun onMessageSendFailed(messageId: Int) {
                                Log.e("WifiAware", "Message send failed via subscribe session, ID: $messageId")
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
        
        val subscribeSession = subscribeSession as? SubscribeDiscoverySession
        if (subscribeSession == null) {
            Log.e("WifiAware", "No subscribe session available - cannot send message")
            return
        }
        
        Log.d("WifiAware", "Sending message: $message to peer: $peer")
        
        val messageBytes = message.toByteArray()
        val messageId = System.currentTimeMillis().toInt()
        
        try {
            subscribeSession.sendMessage(peer, messageId, messageBytes)
            Log.d("WifiAware", "Message sent: $message")
        } catch (e: Exception) {
            Log.e("WifiAware", "Failed to send message: ${e.message}")
        }
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
