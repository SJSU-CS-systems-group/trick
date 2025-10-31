package net.discdd.trick

import android.os.Build

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
    
    override fun testCustomLibSignal(): String {
        return testCustomLibSignalWrapper()
    }
    
    override fun testLibSignal(): String {
        return try {
            // Test hybrid approach: Modern Android bindings + Stable protocol implementation
            val modernClasses = listOf(
                "org.signal.libsignal.protocol.logging.AndroidSignalProtocolLogger"
            )
            
            val legacyClasses = listOf(
                "org.whispersystems.libsignal.IdentityKey",
                "org.whispersystems.libsignal.IdentityKeyPair", 
                "org.whispersystems.libsignal.state.IdentityKeyStore",
                "org.whispersystems.libsignal.SessionBuilder",
                "org.whispersystems.libsignal.SessionCipher"
            )

            val foundModern = mutableListOf<String>()
            val foundLegacy = mutableListOf<String>()
            
            // Test modern 0.79.0 classes
            for (className in modernClasses) {
                try {
                    Class.forName(className)
                    foundModern.add(className.substringAfterLast('.'))
                } catch (e: ClassNotFoundException) {
                    // Expected for some classes
                }
            }
            
            // Test legacy stable classes
            for (className in legacyClasses) {
                try {
                    Class.forName(className)
                    foundLegacy.add(className.substringAfterLast('.'))
                } catch (e: ClassNotFoundException) {
                    // Expected for some classes
                }
            }

            val totalFound = foundModern.size + foundLegacy.size
            if (totalFound > 0) {
                "✅ LibSignal Hybrid (0.79.0 + Stable)!\n" +
                "Modern: ${foundModern.joinToString(", ").ifEmpty { "None" }}\n" +
                "Legacy: ${foundLegacy.joinToString(", ").ifEmpty { "None" }}\n" +
                "Total: $totalFound classes ready"
            } else {
                "❌ LibSignal Error: No classes found in hybrid setup"
            }
        } catch (e: Exception) {
            "❌ LibSignal Error: ${e.message}"
        }
    }
}

actual fun getPlatform(): Platform = AndroidPlatform()