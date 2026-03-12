@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.trick.screens.messaging

import platform.Foundation.NSData

/**
 * Callback protocol for when an image is picked.
 * Implemented by Kotlin side, called by Swift when image selection completes.
 */
interface ImagePickerCallback {
    fun onImagePicked(data: NSData, filename: String, mimeType: String)
}

/**
 * Protocol for the native Swift image picker bridge.
 *
 * Defined in Kotlin so it's exported as an ObjC protocol in the ComposeApp framework.
 * The Swift `ImagePickerCoordinator` class conforms to this protocol.
 */
interface ImagePickerBridge {
    /**
     * Present the native photo picker UI.
     * When an image is selected, the callback's onImagePicked will be invoked.
     * 
     * @param callback Called when an image is successfully picked and processed
     */
    fun pickImage(callback: ImagePickerCallback)
    
    /**
     * Check if the image picker is available on this device.
     */
    fun isAvailable(): Boolean
}
