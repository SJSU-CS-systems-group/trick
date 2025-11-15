package com.jetbrains.kmpapp.screens.messaging

import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.aware.PeerHandle
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.net.ServerSocket

/**
 * Enum representing the role of the device in a peer connection
 */
enum class Role {
    NONE,    // No role assigned yet
    SERVER,  // Device acts as server (listening for connections)
    CLIENT   // Device acts as client (initiating connection)
}

/**
 * Data class representing a connection to a peer device
 */
data class PeerConnection(
    val peerId: String,
    val peerHandle: PeerHandle,
    val role: Role,
    val socket: Socket?,
    val serverSocket: ServerSocket?,
    val inputStream: DataInputStream?,
    val outputStream: DataOutputStream?,
    val network: Network?,
    val networkCallback: ConnectivityManager.NetworkCallback?,
    val connectionTime: Long = System.currentTimeMillis(),
    var lastMessageTime: Long = System.currentTimeMillis(),
    var isActive: Boolean = true
) {
    fun updateLastMessageTime() {
        lastMessageTime = System.currentTimeMillis()
    }

    fun isHealthy(): Boolean {
        val now = System.currentTimeMillis()
        val timeSinceLastMessage = now - lastMessageTime
        return isActive && socket?.isConnected == true && timeSinceLastMessage < 60_000
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerConnection) return false
        return peerId == other.peerId
    }

    override fun hashCode(): Int {
        return peerId.hashCode()
    }
}

/**
 * Connection state enum
 */
enum class ConnectionState {
    DISCOVERING,    // Looking for peers
    NEGOTIATING,    // Determining roles
    CONNECTING,     // Establishing TCP connection
    CONNECTED,      // Fully connected
    DISCONNECTED,   // Connection lost
    RECONNECTING    // Attempting to reconnect
}
