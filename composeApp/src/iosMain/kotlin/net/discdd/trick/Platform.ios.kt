package net.discdd.trick

import platform.UIKit.UIDevice

class IOSPlatform: Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion

    override fun testCustomLibSignal(): String {
        return testCustomLibSignalWrapper()
    }

    override fun testLibSignal(): String {
        // TODO: Implement iOS LibSignal test using Swift interop
        return "iOS LibSignal test not implemented yet"
    }
}

actual fun getPlatform(): Platform = IOSPlatform()