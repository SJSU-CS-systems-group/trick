package org.trcky.trick.screens.messaging

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class MessageType {
    TEXT,
    IMAGE
}

data class Message(
        val content: String,
        val isSent: Boolean,
        val isServiceMessage: Boolean = false,
        val type: MessageType = MessageType.TEXT,
        val imageData: ByteArray? = null,
        val filename: String? = null,
        val isEncrypted: Boolean = false,
        val peerId: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as Message

        if (content != other.content) return false
        if (isSent != other.isSent) return false
        if (isServiceMessage != other.isServiceMessage) return false
        if (type != other.type) return false
        if (imageData != null) {
            if (other.imageData == null) return false
            if (!imageData.contentEquals(other.imageData)) return false
        } else if (other.imageData != null) return false
        if (filename != other.filename) return false
        if (isEncrypted != other.isEncrypted) return false
        if (peerId != other.peerId) return false

        return true
    }

    override fun hashCode(): Int {
        var result = content.hashCode()
        result = 31 * result + isSent.hashCode()
        result = 31 * result + isServiceMessage.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + (imageData?.contentHashCode() ?: 0)
        result = 31 * result + (filename?.hashCode() ?: 0)
        result = 31 * result + isEncrypted.hashCode()
        result = 31 * result + (peerId?.hashCode() ?: 0)
        return result
    }
}

// Helper function to get short ID (first 8 characters)
fun getShortDeviceId(deviceId: String): String {
    return if (deviceId.length >= 8) {
        deviceId.substring(0, 8)
    } else {
        deviceId
    }
}

// Helper function to convert ByteArray to ImageBitmap
@Composable expect fun rememberImageBitmap(imageData: ByteArray): ImageBitmap?

@Composable
fun MessagingScreen(
        messages: List<Message>,
        onSend: (String) -> Unit,
        onSendPicture: (ByteArray, String?, String?) -> Unit,
        debugLogs: List<String>,
        discoveryStatus: String,
        lastReceivedMessage: String,
        lastSentMessage: String,
        onRefresh: () -> Unit,
        localDeviceId: String,
        connectedPeerIds: List<String>,
        onPickImage: (() -> Unit)? = null,
        onNavigateToKeyDistribution: (() -> Unit)? = null,
        onNavigateToContacts: (() -> Unit)? = null
) {
    var text by remember { mutableStateOf("") }
    var showFullDeviceId by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
            modifier =
                    Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
    ) {
        // Header with status and device IDs
        Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                colors =
                        CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                            text = "WiFi Aware Chat",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                    )
                    if (onNavigateToContacts != null) {
                        Button(
                                onClick = { onNavigateToContacts() }
                        ) { Text("Contacts") }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                // Device ID section
                Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                            text = "Your ID: ",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                    )
                    Text(
                            text =
                                    if (showFullDeviceId) localDeviceId
                                    else getShortDeviceId(localDeviceId),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            modifier =
                                    Modifier.weight(1f).clickable {
                                        showFullDeviceId = !showFullDeviceId
                                    }
                    )
                    TextButton(
                            onClick = { showFullDeviceId = !showFullDeviceId }
                    ) { Text(text = if (showFullDeviceId) "Less" else "More") }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Connected peers section
                if (connectedPeerIds.isNotEmpty()) {
                    Column {
                        Text(
                                text = "Connected to:",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        connectedPeerIds.forEach { peerId ->
                            Text(
                                    text = "  • ${getShortDeviceId(peerId)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color =
                                            MaterialTheme.colorScheme.onPrimaryContainer.copy(
                                                    alpha = 0.8f
                                            )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                } else {
                    Text(
                            text = "No peers connected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Action buttons row
                if (onNavigateToKeyDistribution != null) {
                    Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                                onClick = { onNavigateToKeyDistribution() },
                                modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                    Icons.Default.Settings,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Key Distribution")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Status row
                Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                            text = "Status: $discoveryStatus",
                            style = MaterialTheme.typography.bodyMedium
                    )
                    Button(onClick = { onRefresh() }) {
                        Text("Refresh")
                    }
                }
            }
        }

        // Messages list
        LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
        ) { items(messages) { message -> MessageBubble(message = message) } }

        // Debug logs (collapsible)
        var showDebugLogs by remember { mutableStateOf(false) }
        Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors =
                        CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
        ) {
            Column {
                Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                            text = "Debug Logs",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                    )
                    TextButton(onClick = { showDebugLogs = !showDebugLogs }) {
                        Text(if (showDebugLogs) "Hide" else "Show")
                    }
                }

                if (showDebugLogs) {
                    LazyColumn(
                            modifier = Modifier.height(120.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(debugLogs.takeLast(20)) { log ->
                            Text(
                                    text = log,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        // Message input
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)
            )
            Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom
            ) {
                // Attachment button (only if onPickImage is provided)
                if (onPickImage != null) {
                    IconButton(
                            onClick = { onPickImage() },
                            modifier = Modifier.padding(end = 4.dp)
                    ) { Icon(imageVector = Icons.Default.Add, contentDescription = "Attach image") }
                }

                OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Type a message...") },
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                        onClick = {
                            if (text.isNotBlank()) {
                                onSend(text)
                                text = ""
                            }
                        },
                        enabled = text.isNotBlank()
                ) { Text("Send") }
            }
        }
    }
}

@Composable
fun MessageBubble(message: Message, onImageClick: ((ByteArray) -> Unit)? = null) {
    val isErrorMessage = message.content.startsWith("[Error]")
    
    // Remove [System] prefix if it exists (for backward compatibility)
    val displayContent = if (message.content.startsWith("[System]")) {
        message.content.removePrefix("[System]").trim()
    } else {
        message.content
    }

    // Suppress error messages from being displayed in the UI
    if (isErrorMessage) {
        return
    }

    Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement =
                    when {
                        message.isServiceMessage || isErrorMessage ->
                                Arrangement.Center
                        message.isSent -> Arrangement.End
                        else -> Arrangement.Start
                    }
    ) {
        Card(
                modifier = Modifier.widthIn(max = 280.dp),
                colors =
                        CardDefaults.cardColors(
                                containerColor =
                                        when {
                                            isErrorMessage ->
                                                    MaterialTheme.colorScheme.errorContainer
                                            message.isServiceMessage ->
                                                    MaterialTheme.colorScheme.secondaryContainer
                                            message.isSent -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.surfaceVariant
                                        }
                        ),
                shape = when {
                    message.isServiceMessage || isErrorMessage -> RoundedCornerShape(20.dp)
                    message.isSent -> RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
                    else -> RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)
                }
        ) {
            Column(modifier = Modifier.padding(
                    if (message.type == MessageType.IMAGE && message.imageData != null) 4.dp else 12.dp
            )) {
                // Display image if it's an image message
                if (message.type == MessageType.IMAGE && message.imageData != null) {
                    val imageBitmap = rememberImageBitmap(message.imageData)
                    if (imageBitmap != null) {
                        Image(
                                bitmap = imageBitmap,
                                contentDescription = "Image",
                                modifier = Modifier.fillMaxWidth().then(
                                    if (onImageClick != null) {
                                        Modifier.clickable { onImageClick(message.imageData!!) }
                                    } else {
                                        Modifier
                                    }
                                ),
                                contentScale = ContentScale.Fit
                        )
                    } else {
                        Text(
                                text = "[Image]",
                                style = MaterialTheme.typography.bodyMedium,
                                color =
                                        when {
                                            message.isSent -> MaterialTheme.colorScheme.onPrimary
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                        )
                    }
                } else {
                    // Display text message
                    Text(
                            text = displayContent,
                            color =
                                    when {
                                        isErrorMessage -> MaterialTheme.colorScheme.onErrorContainer
                                        message.isServiceMessage ->
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                        message.isSent -> MaterialTheme.colorScheme.onPrimary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            style = MaterialTheme.typography.bodyMedium
                    )
                }

                // Show encryption indicator for encrypted messages
                if (message.isEncrypted &&
                                !message.isServiceMessage &&
                                !isErrorMessage
                ) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Encrypted",
                                modifier = Modifier.size(12.dp),
                                tint =
                                        when {
                                            message.isSent ->
                                                    MaterialTheme.colorScheme.onPrimary.copy(
                                                            alpha = 0.7f
                                                    )
                                            else ->
                                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                            alpha = 0.7f
                                                    )
                                        }
                        )
                        Text(
                                text = "Encrypted",
                                style = MaterialTheme.typography.labelSmall,
                                color =
                                        when {
                                            message.isSent ->
                                                    MaterialTheme.colorScheme.onPrimary.copy(
                                                            alpha = 0.7f
                                                    )
                                            else ->
                                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                            alpha = 0.7f
                                                    )
                                        }
                        )
                    }
                }
            }
        }
    }
}
