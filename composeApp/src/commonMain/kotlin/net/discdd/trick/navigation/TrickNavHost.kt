package net.discdd.trick.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import kotlinx.coroutines.delay
import net.discdd.trick.screens.ContactsLandingPage
import net.discdd.trick.screens.messaging.Message
import net.discdd.trick.screens.messaging.MessageType
import net.discdd.trick.screens.messaging.MessagingScreen
import net.discdd.trick.screens.messaging.WifiAwareService

/**
 * Optional: when non-null, platform provides a composable for the Key Exchange screen
 * (e.g. AndroidKeyExchangeScreen). When null, a simple placeholder is shown.
 */
typealias KeyExchangeContent = @Composable (deviceId: String, onNavigateBack: () -> Unit) -> Unit

/**
 * Optional: when user taps "pick image", TrickNavHost calls this with a callback.
 * Platform launches the picker; on result it invokes the callback with (data, filename, mimeType).
 * TrickNavHost implements the callback by adding the message and calling wifiAwareService.sendPicture.
 */
typealias OnPickImageRequest = (callback: (ByteArray, String, String) -> Unit) -> Unit

@Composable
fun TrickNavHost(
    navController: NavHostController,
    wifiAwareService: WifiAwareService,
    permissionsGranted: Boolean,
    onPickImage: OnPickImageRequest? = null,
    keyExchangeContent: KeyExchangeContent? = null
) {
    val messages = remember { mutableStateListOf<Message>() }
    val debugLogs = remember { mutableStateListOf<String>() }
    val discoveryStatus = remember { mutableStateOf("Waiting for permissions...") }
    val lastReceivedMessage = remember { mutableStateOf("") }
    val lastSentMessage = remember { mutableStateOf("") }
    val localDeviceId = remember { mutableStateOf("") }
    val connectedPeerIds = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) {
        localDeviceId.value = wifiAwareService.getDeviceId()
    }

    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted) {
            while (true) {
                connectedPeerIds.clear()
                connectedPeerIds.addAll(wifiAwareService.getConnectedPeers())
                delay(1000)
            }
        }
    }

    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted) {
            discoveryStatus.value = "Starting..."
            debugLogs.add("[UI] Starting discovery...")
            wifiAwareService.startDiscovery { chatMessage ->
                val wasEncrypted = chatMessage.encryption_version != null
                val textContent = chatMessage.text_content
                if (textContent != null) {
                    val msg = textContent.text
                    debugLogs.add(
                        "[App] Message received${if (wasEncrypted) " (encrypted)" else ""}: $msg"
                    )
                    println("[App] Message received: $msg")
                    messages.add(
                        Message(
                            content = msg,
                            isSent = false,
                            isServiceMessage = msg.startsWith("Service discovered:"),
                            isEncrypted = wasEncrypted
                        )
                    )
                    lastReceivedMessage.value = msg
                }
                val photoContent = chatMessage.photo_content
                if (photoContent != null) {
                    val imageData = photoContent.data_.toByteArray()
                    val filename = photoContent.filename ?: "image"
                    debugLogs.add(
                        "[App] Image received${if (wasEncrypted) " (encrypted)" else ""}: $filename (${imageData.size} bytes)"
                    )
                    println("[App] Image received: $filename")
                    messages.add(
                        Message(
                            content = "[Image]",
                            isSent = false,
                            isServiceMessage = false,
                            type = MessageType.IMAGE,
                            imageData = imageData,
                            filename = filename,
                            isEncrypted = wasEncrypted
                        )
                    )
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
        wifiAwareService.stopDiscovery()
        debugLogs.add("[UI] Discovery stopped. Restarting...")
        discoveryStatus.value = "Restarting..."
        wifiAwareService.startDiscovery { chatMessage ->
            val textContent = chatMessage.text_content
            if (textContent != null) {
                val msg = textContent.text
                debugLogs.add("[App] Message received (refresh): $msg")
                println("[App] Message received (refresh): $msg")
                messages.add(
                    Message(
                        content = msg,
                        isSent = false,
                        isServiceMessage = msg.startsWith("Service discovered:")
                    )
                )
                lastReceivedMessage.value = msg
            }
            val photoContent = chatMessage.photo_content
            if (photoContent != null) {
                val imageData = photoContent.data_.toByteArray()
                val filename = photoContent.filename ?: "image"
                debugLogs.add("[App] Image received (refresh): $filename (${imageData.size} bytes)")
                println("[App] Image received (refresh): $filename")
                messages.add(
                    Message(
                        content = "[Image]",
                        isSent = false,
                        isServiceMessage = false,
                        type = MessageType.IMAGE,
                        imageData = imageData,
                        filename = filename
                    )
                )
                lastReceivedMessage.value = filename
            }
        }
        discoveryStatus.value = "Running (refreshed)"
        debugLogs.add("[UI] Discovery restarted successfully.")
    }

    NavHost(
        navController = navController,
        startDestination = Screen.ContactsList.route
    ) {
        composable(Screen.ContactsList.route) {
            ContactsLandingPage(
                onNavigateToMessaging = { navController.navigate(Screen.Chat.route) }
            )
        }

        composable(Screen.Chat.route) {
            val onPickImageForScreen: (() -> Unit)? = if (onPickImage != null) {
                {
                    onPickImage { data, filename, mimeType ->
                        debugLogs.add("[App] Image picked: $filename (${data.size} bytes)")
                        println("[App] Image picked: $filename")
                        wifiAwareService.sendPicture(data, filename, mimeType)
                        messages.add(
                            Message(
                                content = "[Image]",
                                isSent = true,
                                isServiceMessage = false,
                                type = MessageType.IMAGE,
                                imageData = data,
                                filename = filename
                            )
                        )
                        lastSentMessage.value = filename
                    }
                }
            } else null

            MessagingScreen(
                messages = messages,
                onSend = { msg ->
                    debugLogs.add("[App] Sending message: $msg")
                    println("[App] Sending message: $msg")
                    wifiAwareService.sendMessage(msg)
                    messages.add(
                        Message(
                            content = msg,
                            isSent = true,
                            isServiceMessage = false,
                            type = MessageType.TEXT
                        )
                    )
                    lastSentMessage.value = msg
                },
                onSendPicture = { imageData, filename, mimeType ->
                    debugLogs.add("[App] Sending picture: $filename (${imageData.size} bytes)")
                    println("[App] Sending picture: $filename")
                    wifiAwareService.sendPicture(imageData, filename, mimeType)
                    messages.add(
                        Message(
                            content = "[Image]",
                            isSent = true,
                            isServiceMessage = false,
                            type = MessageType.IMAGE,
                            imageData = imageData,
                            filename = filename
                        )
                    )
                    lastSentMessage.value = filename ?: "[Image]"
                },
                debugLogs = debugLogs,
                discoveryStatus = discoveryStatus.value,
                lastReceivedMessage = lastReceivedMessage.value,
                lastSentMessage = lastSentMessage.value,
                onRefresh = { refreshDiscovery() },
                localDeviceId = localDeviceId.value,
                connectedPeerIds = connectedPeerIds.toList(),
                onPickImage = onPickImageForScreen,
                onNavigateToKeyExchange = { navController.navigate(Screen.KeyExchange.route) },
                onNavigateToContacts = { navController.popBackStack() }
            )
        }

        composable(Screen.KeyExchange.route) {
            if (keyExchangeContent != null) {
                keyExchangeContent(localDeviceId.value) { navController.popBackStack() }
            } else {
                KeyExchangePlaceholder(onNavigateBack = { navController.popBackStack() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyExchangePlaceholder(onNavigateBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Key Exchange") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors()
            )
        }
    ) { paddingValues ->
        Text(
            text = "Key Exchange",
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        )
    }
}
