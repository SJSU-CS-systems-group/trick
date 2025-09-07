package com.jetbrains.kmpapp.screens.messaging

import android.content.Context
import android.util.Log

class WifiAwareServiceImpl(private val context: Context) : WifiAwareService {
    private val manager = AndroidWifiAwareManager(context)

    override fun startDiscovery(onMessageReceived: (String) -> Unit) {
        Log.d("WifiAwareServiceImpl", "Starting discovery")
        manager.startDiscovery { message ->
            Log.d("WifiAwareServiceImpl", "Message received: $message")
            onMessageReceived(message)
        }
    }

    override fun sendMessage(message: String) {
        Log.d("WifiAwareServiceImpl", "Sending message: $message")
        Log.d("WifiAwareServiceImpl", "Connection status: ${manager.getConnectionStatus()}")
        manager.sendMessage(message)
    }
    
    override fun isPeerConnected(): Boolean {
        return manager.isPeerConnected()
    }
    
    override fun getConnectionStatus(): String {
        return manager.getConnectionStatus()
    }
}
