package com.jetbrains.kmpapp.screens.messaging

import android.content.Context
import android.util.Log

class WifiAwareServiceImpl(private val context: Context) : WifiAwareService {
    // Connection-based WiFi Aware manager
    private val manager = AndroidWifiAwareManager(context)

    override fun startDiscovery(onMessageReceived: (String) -> Unit) {
        Log.d("WifiAwareServiceImpl", "Starting discovery with connection-based networking")

        manager.startDiscovery(
            onMessageReceived = { message, peerId ->
                Log.d("WifiAwareServiceImpl", "Message received from ${peerId?.take(8) ?: "unknown"}: $message")
                onMessageReceived(message)
            },
            onConnectionStatusChanged = { peerId, state ->
                Log.d("WifiAwareServiceImpl", "Connection status: ${peerId.take(8)} -> $state")
            }
        )
    }

    override fun sendMessage(message: String) {
        Log.d("WifiAwareServiceImpl", "Broadcasting message: $message")
        Log.d("WifiAwareServiceImpl", "Connection status: ${manager.getConnectionStatus()}")

        // Broadcast to all connected peers
        manager.broadcastMessage(message)
    }

    override fun isPeerConnected(): Boolean {
        return manager.getConnectedPeers().isNotEmpty()
    }

    override fun getConnectionStatus(): String {
        return manager.getConnectionStatus()
    }

    fun stopDiscovery() {
        Log.d("WifiAwareServiceImpl", "Stopping discovery")
        manager.stopDiscovery()
    }

    // Additional methods for enhanced functionality
    fun sendMessageToPeer(message: String, peerId: String) {
        Log.d("WifiAwareServiceImpl", "Sending message to specific peer: ${peerId.take(8)}")
        manager.sendMessageToPeer(message, peerId)
    }

    fun getConnectedPeers(): List<String> {
        return manager.getConnectedPeers()
    }
}
