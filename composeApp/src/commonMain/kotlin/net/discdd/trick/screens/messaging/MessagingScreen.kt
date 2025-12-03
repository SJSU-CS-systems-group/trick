package net.discdd.trick.screens.messaging

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
    val filename: String? = null
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

        return true
    }

    override fun hashCode(): Int {
        var result = content.hashCode()
        result = 31 * result + isSent.hashCode()
        result = 31 * result + isServiceMessage.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + (imageData?.contentHashCode() ?: 0)
        result = 31 * result + (filename?.hashCode() ?: 0)
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
@Composable
expect fun rememberImageBitmap(imageData: ByteArray): ImageBitmap?

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
    onPickImage: (() -> Unit)? = null
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
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
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
                Text(
                    text = "WiFi Aware Chat",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
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
                        onClick = { showFullDeviceId = !showFullDeviceId },
                        modifier = Modifier.height(24.dp)
                    ) { Text(text = if (showFullDeviceId) "Less" else "More", fontSize = 10.sp) }
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
                    Button(
                        onClick = { onRefresh() },
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Refresh", fontSize = 12.sp)
                    }
                }
            }
        }

        // Messages list
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { message ->
                MessageBubble(message = message)
            }
        }

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
        Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                // Attachment button (only if onPickImage is provided)
                if (onPickImage != null) {
                    IconButton(
                        onClick = { onPickImage() },
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Attach image"
                        )
                    }
                }
                
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message...") },
                    singleLine = true
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
private fun MessageBubble(message: Message) {
    val isSystemMessage = message.content.startsWith("[System]")
    val isErrorMessage = message.content.startsWith("[Error]")

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = when {
            message.isServiceMessage || isSystemMessage || isErrorMessage -> Arrangement.Center
            message.isSent -> Arrangement.End
            else -> Arrangement.Start
        }
    ) {
        Card(
            modifier = Modifier.widthIn(max = 280.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = when {
                        isErrorMessage -> MaterialTheme.colorScheme.errorContainer
                        isSystemMessage -> MaterialTheme.colorScheme.tertiaryContainer
                        message.isServiceMessage -> MaterialTheme.colorScheme.secondaryContainer
                        message.isSent -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
                shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Display image if it's an image message
                if (message.type == MessageType.IMAGE && message.imageData != null) {
                    val imageBitmap = rememberImageBitmap(message.imageData)
                    if (imageBitmap != null) {
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = message.filename ?: "Image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp),
                            contentScale = ContentScale.Fit
                        )
                        if (message.filename != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = message.filename,
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    message.isSent -> MaterialTheme.colorScheme.onPrimary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }.copy(alpha = 0.7f)
                            )
                        }
                    } else {
                        Text(
                            text = "[Image: ${message.filename ?: "unknown"}]",
                            style = MaterialTheme.typography.bodyMedium,
                            color = when {
                                message.isSent -> MaterialTheme.colorScheme.onPrimary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                } else {
                    // Display text message
                    Text(
                        text = message.content,
                        color = when {
                            isErrorMessage -> MaterialTheme.colorScheme.onErrorContainer
                            isSystemMessage -> MaterialTheme.colorScheme.onTertiaryContainer
                            message.isServiceMessage -> MaterialTheme.colorScheme.onSecondaryContainer
                            message.isSent -> MaterialTheme.colorScheme.onPrimary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
