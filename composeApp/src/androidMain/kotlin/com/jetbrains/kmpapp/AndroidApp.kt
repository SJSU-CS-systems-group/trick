package com.jetbrains.kmpapp

import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.jetbrains.kmpapp.screens.UnsupportedDeviceScreen
import com.jetbrains.kmpapp.screens.messaging.Message
import com.jetbrains.kmpapp.screens.messaging.MessageType
import com.jetbrains.kmpapp.screens.messaging.MessagingScreen
import com.jetbrains.kmpapp.screens.messaging.WifiAwareService
import com.jetbrains.kmpapp.screens.messaging.rememberImagePickerLauncher
import kotlinx.coroutines.delay

/** 
 * Android-specific wrapper for App that provides image picker functionality 
 * */
@Composable
fun AndroidApp(
    wifiAwareService: WifiAwareService,
    permissionsGranted: Boolean = false,
    wifiAwareSupported: Boolean = true
) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    ) {
        Surface {
            // Show unsupported screen if WiFi Aware is not available
            if (!wifiAwareSupported) {
                UnsupportedDeviceScreen()
                return@Surface
            }

            // Continue with normal app flow
            val messages = remember { mutableStateListOf<Message>() }
            val debugLogs = remember { mutableStateListOf<String>() }
            val discoveryStatus = remember { mutableStateOf("Waiting for permissions...") }
            val lastReceivedMessage = remember { mutableStateOf("") }
            val lastSentMessage = remember { mutableStateOf("") }
            val localDeviceId = remember { mutableStateOf("") }
            val connectedPeerIds = remember { mutableStateListOf<String>() }

            // Initialize device ID
            LaunchedEffect(Unit) { localDeviceId.value = wifiAwareService.getDeviceId() }

            // Poll for connected peers periodically
            LaunchedEffect(permissionsGranted) {
                if (permissionsGranted) {
                    while (true) {
                        connectedPeerIds.clear()
                        connectedPeerIds.addAll(wifiAwareService.getConnectedPeers())
                        delay(1000) // Update every second
                    }
                }
            }

            // Image picker launcher
            val imagePickerLauncher = rememberImagePickerLauncher { imageResult ->
                debugLogs.add(
                        "[App] Image picked: ${imageResult.filename} (${imageResult.data.size} bytes)"
                )
                println("[App] Image picked: ${imageResult.filename}")

                // Send the image
                wifiAwareService.sendPicture(
                        imageResult.data,
                        imageResult.filename,
                        imageResult.mimeType
                )

                // Add to messages
                val message =
                        Message(
                                content = "[Image]",
                                isSent = true,
                                isServiceMessage = false,
                                type = MessageType.IMAGE,
                                imageData = imageResult.data,
                                filename = imageResult.filename
                        )
                messages.add(message)
                lastSentMessage.value = imageResult.filename
            }

            // Start discovery only when permissions are granted
            LaunchedEffect(permissionsGranted) {
                if (permissionsGranted) {
                    discoveryStatus.value = "Starting..."
                    debugLogs.add("[UI] Starting discovery...")
                    wifiAwareService.startDiscovery { chatMessage ->
                        // Handle text content
                        val textContent = chatMessage.text_content
                        if (textContent != null) {
                            val msg = textContent.text
                            debugLogs.add("[App] Message received: $msg")
                            println("[App] Message received: $msg")
                            val message =
                                    Message(
                                            content = msg,
                                            isSent = false,
                                            isServiceMessage = msg.startsWith("Service discovered:")
                                    )
                            messages.add(message)
                            lastReceivedMessage.value = msg
                        }
                        // Handle photo content
                        val photoContent = chatMessage.photo_content
                        if (photoContent != null) {
                            val imageData = photoContent!!.data_.toByteArray()
                            val filename = photoContent!!.filename ?: "image"
                            debugLogs.add(
                                    "[App] Image received: $filename (${imageData.size} bytes)"
                            )
                            println("[App] Image received: $filename")
                            val message =
                                    Message(
                                            content = "[Image]",
                                            isSent = false,
                                            isServiceMessage = false,
                                            type = MessageType.IMAGE,
                                            imageData = imageData,
                                            filename = filename
                                    )
                            messages.add(message)
                            lastReceivedMessage.value = filename
                        }
                    }
                    discoveryStatus.value = "Running"
                    debugLogs.add("[UI] Discovery running.")
                } else {
                    discoveryStatus.value = "Waiting for permissions..."
                    debugLogs.add("[UI] Waiting for user to grant permissions...")
                }
            }

            fun refreshDiscovery() {
                debugLogs.add("[UI] Manual refresh triggered - stopping discovery...")
                discoveryStatus.value = "Stopping..."

                // Stop existing discovery first
                wifiAwareService.stopDiscovery()
                debugLogs.add("[UI] Discovery stopped. Restarting...")

                discoveryStatus.value = "Restarting..."
                wifiAwareService.startDiscovery { chatMessage ->
                    // Handle text content
                    val textContent = chatMessage.text_content
                    if (textContent != null) {
                        val msg = textContent.text
                        debugLogs.add("[App] Message received (refresh): $msg")
                        println("[App] Message received (refresh): $msg")
                        val message =
                                Message(
                                        content = msg,
                                        isSent = false,
                                        isServiceMessage = msg.startsWith("Service discovered:")
                                )
                        messages.add(message)
                        lastReceivedMessage.value = msg
                    }
                    // Handle photo content
                    val photoContent = chatMessage.photo_content
                    if (photoContent != null) {
                        val imageData = photoContent!!.data_.toByteArray()
                        val filename = photoContent!!.filename ?: "image"
                        debugLogs.add(
                                "[App] Image received (refresh): $filename (${imageData.size} bytes)"
                        )
                        println("[App] Image received (refresh): $filename")
                        val message =
                                Message(
                                        content = "[Image]",
                                        isSent = false,
                                        isServiceMessage = false,
                                        type = MessageType.IMAGE,
                                        imageData = imageData,
                                        filename = filename
                                )
                        messages.add(message)
                        lastReceivedMessage.value = filename
                    }
                }
                discoveryStatus.value = "Running (refreshed)"
                debugLogs.add("[UI] Discovery restarted successfully.")
            }

            MessagingScreen(
                    messages = messages,
                    onSend = { msg ->
                        debugLogs.add("[App] Sending message: $msg")
                        println("[App] Sending message: $msg")
                        wifiAwareService.sendMessage(msg)
                        val message =
                                Message(
                                        content = msg,
                                        isSent = true,
                                        isServiceMessage = false,
                                        type = MessageType.TEXT
                                )
                        messages.add(message)
                        lastSentMessage.value = msg
                    },
                    onSendPicture = { imageData, filename, mimeType ->
                        debugLogs.add("[App] Sending picture: $filename (${imageData.size} bytes)")
                        println("[App] Sending picture: $filename")
                        wifiAwareService.sendPicture(imageData, filename, mimeType)
                        val message =
                                Message(
                                        content = "[Image]",
                                        isSent = true,
                                        isServiceMessage = false,
                                        type = MessageType.IMAGE,
                                        imageData = imageData,
                                        filename = filename
                                )
                        messages.add(message)
                        lastSentMessage.value = filename ?: "[Image]"
                    },
                    debugLogs = debugLogs,
                    discoveryStatus = discoveryStatus.value,
                    lastReceivedMessage = lastReceivedMessage.value,
                    lastSentMessage = lastSentMessage.value,
                    onRefresh = { refreshDiscovery() },
                    localDeviceId = localDeviceId.value,
                    connectedPeerIds = connectedPeerIds.toList(),
                    onPickImage = {
                        imagePickerLauncher.launch(
                                PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                        )
                    }
            )
        }
    }
}
