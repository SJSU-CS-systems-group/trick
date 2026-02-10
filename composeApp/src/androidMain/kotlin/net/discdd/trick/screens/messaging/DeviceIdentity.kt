package net.discdd.trick.screens.messaging

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.security.MessageDigest

/**
 * Utility class for device identity and role negotiation
 */
object DeviceIdentity {
    private const val TAG = "DeviceIdentity"
    private const val PSK_PASSPHRASE = "KMPChatSecure2024"

    /**
     * Generate a unique, deterministic device ID
     * Uses Android ID + Build info to create SHA-256 hash
     */
    fun generateDeviceId(context: Context): String {
        try {
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: "unknown"

            // Combine multiple identifiers for uniqueness
            val combined = buildString {
                append(androidId)
                append(":")
                append(Build.MANUFACTURER)
                append(":")
                append(Build.MODEL)
                append(":")
                append(Build.DEVICE)
            }

            // Create SHA-256 hash
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(combined.toByteArray())

            // Convert to hex string
            val hexString = hashBytes.joinToString("") { "%02x".format(it) }

            Log.d(TAG, "Generated Device ID: ${hexString.take(16)}...")
            return hexString
        } catch (e: Exception) {
            Log.e(TAG, "Error generating device ID: ${e.message}", e)
            // Fallback to a combination of available info
            return "${Build.MODEL}_${System.currentTimeMillis()}".hashCode().toString(16)
        }
    }

    /**
     * Negotiate role between two devices deterministically
     * Both devices will compute the same result
     *
     * @param localDeviceId This device's ID
     * @param remoteDeviceId Remote peer's device ID
     * @return Role.SERVER if this device should be server, Role.CLIENT if client
     */
    fun negotiateRole(localDeviceId: String, remoteDeviceId: String): Role {
        // Use direct lexicographic comparison for deterministic, cross-platform role negotiation.
        // hashCode() differs across platforms (JVM vs Swift vs native), so string comparison
        // is the only reliable approach for consistent results on both Android and iOS.
        return when {
            localDeviceId > remoteDeviceId -> {
                Log.d(TAG, "Negotiated role: SERVER (lexicographic: local > remote)")
                Role.SERVER
            }
            localDeviceId < remoteDeviceId -> {
                Log.d(TAG, "Negotiated role: CLIENT (lexicographic: local < remote)")
                Role.CLIENT
            }
            else -> {
                Log.e(TAG, "Negotiated role: NONE (identical device IDs)")
                Role.NONE
            }
        }
    }

    /**
     * Get the PSK (Pre-Shared Key) passphrase for WiFi Aware connections
     * In production, this should be user-configurable or generated securely
     */
    fun getPskPassphrase(): String {
        return PSK_PASSPHRASE
    }

    /**
     * Validate device ID format
     */
    fun isValidDeviceId(deviceId: String): Boolean {
        // Check if it's a valid hex string with reasonable length
        return deviceId.matches(Regex("^[0-9a-f]{8,}$")) || deviceId.isNotEmpty()
    }

    /**
     * Extract short ID for display purposes (first 8 characters)
     */
    fun getShortId(deviceId: String): String {
        return if (deviceId.length >= 8) {
            deviceId.substring(0, 8)
        } else {
            deviceId
        }
    }

    /**
     * Create a handshake message containing device ID
     */
    fun createHandshakeMessage(deviceId: String): String {
        return "HANDSHAKE:$deviceId"
    }

    /**
     * Parse handshake message to extract device ID
     */
    fun parseHandshakeMessage(message: String): String? {
        return if (message.startsWith("HANDSHAKE:")) {
            message.substringAfter("HANDSHAKE:")
        } else {
            null
        }
    }

    /**
     * Create a port announcement message
     */
    fun createPortMessage(port: Int): String {
        return "PORT:$port"
    }

    /**
     * Parse port message
     */
    fun parsePortMessage(message: String): Int? {
        return if (message.startsWith("PORT:")) {
            message.substringAfter("PORT:").toIntOrNull()
        } else {
            null
        }
    }

    /**
     * Check if a message is a system control message
     */
    fun isSystemMessage(message: String): Boolean {
        return message.startsWith("HANDSHAKE:") ||
                message.startsWith("PORT:") ||
                message.startsWith("HEARTBEAT") ||
                message.startsWith("ACK:")
    }
}
