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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import kotlinx.coroutines.delay
import net.discdd.trick.data.MessagePersistenceManager
import net.discdd.trick.messaging.ChatMessage
import net.discdd.trick.signal.SignalSessionManager
import net.discdd.trick.screens.chat.ChatScreen
import net.discdd.trick.screens.contacts.ContactsListScreen
import net.discdd.trick.screens.messaging.WifiAwareService
import androidx.savedstate.read
import net.discdd.trick.util.urlDecode
import org.koin.compose.koinInject

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
    val messagePersistenceManager: MessagePersistenceManager = koinInject()
    val signalSessionManager: SignalSessionManager = koinInject()

    val debugLogs = remember { mutableStateListOf<String>() }
    val discoveryStatus = remember { mutableStateOf("Waiting for permissions...") }
    val localDeviceId = remember { mutableStateOf("") }
    val connectedPeerIds = remember { mutableStateListOf<String>() }
    val discoveryStarted = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        localDeviceId.value = wifiAwareService.getDeviceId()
    }

    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted) {
            while (true) {
                if (signalSessionManager.isReady) {
                    val rawPeers = wifiAwareService.getConnectedPeers()
                    val peersWithSession = rawPeers.filter { peerId ->
                        signalSessionManager.hasSession(peerId)
                    }
                    connectedPeerIds.clear()
                    connectedPeerIds.addAll(peersWithSession)
                }
                delay(1000)
            }
        }
    }

    fun handleIncomingMessage(
        chatMessage: ChatMessage,
        peerId: String?
    ) {
        val wasEncrypted = chatMessage.encryption_version != null
        val effectivePeerId = peerId ?: chatMessage.sender_id.ifBlank { null }

        val textContent = chatMessage.text_content
        if (textContent != null) {
            val msg = textContent.text
            val isSystemMessage = chatMessage.sender_id == "system" || msg.startsWith("Service discovered:")

            debugLogs.add(
                "[App] Message received${if (wasEncrypted) " (encrypted)" else ""}: $msg"
            )
            println("[App] Message received: $msg")

            // Persist non-system messages
            if (!isSystemMessage && effectivePeerId != null) {
                messagePersistenceManager.persistReceivedTextMessage(
                    peerId = effectivePeerId,
                    content = msg,
                    isEncrypted = wasEncrypted,
                    messageId = chatMessage.message_id.ifBlank { null }
                )
            }
        }

        val photoContent = chatMessage.photo_content
        if (photoContent != null) {
            val imageData = photoContent.data_.toByteArray()
            val filename = photoContent.filename ?: "image"
            debugLogs.add(
                "[App] Image received${if (wasEncrypted) " (encrypted)" else ""}: $filename (${imageData.size} bytes)"
            )
            println("[App] Image received: $filename")

            if (effectivePeerId != null) {
                messagePersistenceManager.persistReceivedImageMessage(
                    peerId = effectivePeerId,
                    imageData = imageData,
                    filename = filename,
                    isEncrypted = wasEncrypted,
                    messageId = chatMessage.message_id.ifBlank { null }
                )
            }
        }
    }

    // Start discovery when permissions are granted
    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted && !discoveryStarted.value) {
            discoveryStatus.value = "Starting..."
            debugLogs.add("[UI] Starting discovery...")
            wifiAwareService.startDiscovery { chatMessage, peerId ->
                handleIncomingMessage(chatMessage, peerId)
            }
            discoveryStatus.value = "Running"
            debugLogs.add("[UI] Discovery running.")
            discoveryStarted.value = true
        }
    }

    fun refreshDiscovery() {
        debugLogs.add("[UI] Manual refresh triggered - stopping discovery...")
        discoveryStatus.value = "Stopping..."
        wifiAwareService.stopDiscovery()
        discoveryStarted.value = false
        debugLogs.add("[UI] Discovery stopped. Restarting...")
        discoveryStatus.value = "Restarting..."
        wifiAwareService.startDiscovery { chatMessage, peerId ->
            handleIncomingMessage(chatMessage, peerId)
        }
        discoveryStatus.value = "Running (refreshed)"
        debugLogs.add("[UI] Discovery restarted successfully.")
        discoveryStarted.value = true
    }

    NavHost(
        navController = navController,
        startDestination = Screen.ContactsList.route
    ) {
        composable(Screen.ContactsList.route) {
            ContactsListScreen(
                onContactClick = { contact ->
                    val peerId = contact.deviceId ?: contact.shortId
                    wifiAwareService.setDesiredPeerId(peerId)
                    refreshDiscovery()
                    navController.navigate(Screen.Chat.createRoute(contact.shortId, peerId))
                },
                onAddContactClick = {
                    navController.navigate(Screen.KeyExchange.route)
                },
                connectedPeerIds = connectedPeerIds.toList(),
                onTestMessagingClick = {
                    val testPeerId = "test-contact"
                    wifiAwareService.setDesiredPeerId(testPeerId)
                    refreshDiscovery()
                    navController.navigate(Screen.Chat.createRoute(testPeerId, testPeerId))
                }
            )
        }

        composable(
            route = Screen.Chat.route,
            arguments = listOf(
                navArgument("shortId") { type = NavType.StringType },
                navArgument("peerId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val shortId = backStackEntry.arguments?.read { getStringOrNull("shortId") } ?: ""
            val peerId = urlDecode(
                backStackEntry.arguments?.read { getStringOrNull("peerId") } ?: shortId,
                "UTF-8"
            )
            val isContactConnected = peerId in connectedPeerIds
            val onPickImageForScreen: (() -> Unit)? = if (onPickImage != null) {
                {
                    onPickImage { data, filename, mimeType ->
                        debugLogs.add("[App] Image picked: $filename (${data.size} bytes)")
                        println("[App] Image picked: $filename")
                        wifiAwareService.sendPictureToPeer(data, filename, mimeType, peerId)
                        messagePersistenceManager.persistSentImageMessage(
                            shortId = shortId,
                            imageData = data,
                            filename = filename,
                            isEncrypted = true
                        )
                    }
                }
            } else null

            ChatScreen(
                shortId = shortId,
                isContactConnected = isContactConnected,
                onSend = { msg ->
                    debugLogs.add("[App] Sending message: $msg")
                    println("[App] Sending message: $msg")
                    wifiAwareService.sendMessageToPeer(msg, peerId)
                    messagePersistenceManager.persistSentTextMessage(
                        shortId = shortId,
                        content = msg,
                        isEncrypted = true
                    )
                },
                onSendPicture = { imageData, filename, mimeType ->
                    debugLogs.add("[App] Sending picture: $filename (${imageData.size} bytes)")
                    println("[App] Sending picture: $filename")
                    wifiAwareService.sendPictureToPeer(imageData, filename, mimeType, peerId)
                    messagePersistenceManager.persistSentImageMessage(
                        shortId = shortId,
                        imageData = imageData,
                        filename = filename,
                        isEncrypted = true
                    )
                },
                onBack = {
                    wifiAwareService.setDesiredPeerId(null)
                    navController.popBackStack()
                },
                onPickImage = onPickImageForScreen
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
