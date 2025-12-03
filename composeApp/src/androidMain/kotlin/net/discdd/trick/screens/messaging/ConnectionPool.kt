package net.discdd.trick.screens.messaging

import android.net.wifi.aware.PeerHandle
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe connection pool for managing multiple peer connections
 */
class ConnectionPool {
    private val TAG = "ConnectionPool"
    private val connections = ConcurrentHashMap<String, PeerConnection>()
    private val maxConnections = 10

    /**
     * Add a new connection to the pool
     */
    fun addConnection(peerId: String, connection: PeerConnection): Boolean {
        if (connections.size >= maxConnections) {
            Log.w(TAG, "Maximum connections reached ($maxConnections)")
            return false
        }

        connections[peerId] = connection
        Log.d(TAG, "Connection added: $peerId (Total: ${connections.size})")
        return true
    }

    /**
     * Remove a connection from the pool
     */
    fun removeConnection(peerId: String): PeerConnection? {
        val removed = connections.remove(peerId)
        if (removed != null) {
            Log.d(TAG, "Connection removed: $peerId (Remaining: ${connections.size})")
        }
        return removed
    }

    /**
     * Get a specific connection
     */
    fun getConnection(peerId: String): PeerConnection? {
        return connections[peerId]
    }

    /**
     * Get connection by peer handle
     */
    fun getConnectionByPeerHandle(peerHandle: PeerHandle): PeerConnection? {
        return connections.values.find { it.peerHandle == peerHandle }
    }

    /**
     * Get all active connections
     */
    fun getAllConnections(): List<PeerConnection> {
        return connections.values.toList()
    }

    /**
     * Check if a peer is connected
     */
    fun hasConnection(peerId: String): Boolean {
        return connections.containsKey(peerId)
    }

    /**
     * Get total number of connections
     */
    fun size(): Int {
        return connections.size
    }

    /**
     * Clear all connections
     */
    fun clear() {
        connections.clear()
        Log.d(TAG, "All connections cleared")
    }

    /**
     * Get all peer IDs
     */
    fun getPeerIds(): List<String> {
        return connections.keys.toList()
    }

    /**
     * Update connection state
     */
    fun updateConnection(peerId: String, updater: (PeerConnection) -> PeerConnection): Boolean {
        val current = connections[peerId] ?: return false
        connections[peerId] = updater(current)
        return true
    }

    /**
     * Get unhealthy connections
     */
    fun getUnhealthyConnections(): List<PeerConnection> {
        return connections.values.filter { !it.isHealthy() }
    }

    /**
     * Get connection count by role
     */
    fun getConnectionCountByRole(role: Role): Int {
        return connections.values.count { it.role == role }
    }

    /**
     * Get statistics
     */
    fun getStatistics(): ConnectionStatistics {
        return ConnectionStatistics(
            totalConnections = connections.size,
            serverConnections = getConnectionCountByRole(Role.SERVER),
            clientConnections = getConnectionCountByRole(Role.CLIENT),
            unhealthyConnections = getUnhealthyConnections().size,
            averageConnectionTime = if (connections.isEmpty()) 0L else {
                val now = System.currentTimeMillis()
                connections.values.map { now - it.connectionTime }.average().toLong()
            }
        )
    }
}

/**
 * Statistics about connections
 */
data class ConnectionStatistics(
    val totalConnections: Int,
    val serverConnections: Int,
    val clientConnections: Int,
    val unhealthyConnections: Int,
    val averageConnectionTime: Long
)
