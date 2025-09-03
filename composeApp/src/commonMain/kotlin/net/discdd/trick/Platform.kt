package net.discdd.trick

import net.discdd.trick.libsignal.createLibSignalManager

interface Platform {
    val name: String
    fun testLibSignal(): String
    fun testCustomLibSignal(): String
}

expect fun getPlatform(): Platform

// Helper function to test our custom wrapper
fun testCustomLibSignalWrapper(): String {
    return try {
        val manager = createLibSignalManager()
        manager.test()
    } catch (e: Exception) {
        "❌ Custom LibSignal Error: ${e.message}"
    }
}