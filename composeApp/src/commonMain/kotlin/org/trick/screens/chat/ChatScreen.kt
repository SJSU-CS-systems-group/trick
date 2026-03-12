package org.trick.screens.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.trick.screens.messaging.Message
import org.trick.screens.messaging.MessageBubble
import org.trick.screens.messaging.rememberImageBitmap
import org.trick.theme.LocalAppTheme
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    shortId: String,
    isContactConnected: Boolean,
    onSend: (String) -> Unit,
    onSendPicture: (ByteArray, String?, String?) -> Unit,
    onBack: () -> Unit,
    onPickImage: (() -> Unit)? = null,
    viewModel: ChatViewModel = koinViewModel(parameters = { parametersOf(shortId) })
) {
    val contact by viewModel.contact.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val title = contact?.displayName?.takeIf { it.isNotBlank() }
        ?: shortId.ifBlank { "Unknown" }.takeIf { it != "Unknown" }
        ?: "Unknown"

    var text by remember { mutableStateOf("") }
    var previewImageData by remember { mutableStateOf<ByteArray?>(null) }
    val listState = rememberLazyListState()
    val appTheme = LocalAppTheme.current

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = appTheme.onToggleTheme) {
                        Icon(
                            imageVector = Icons.Default.Lightbulb,
                            contentDescription = if (appTheme.isDark) "Switch to light theme" else "Switch to dark theme",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors()
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding()
                .padding(paddingValues)
        ) {
            // Connection status for this contact
            Text(
                text = if (isContactConnected) "Connected" else "Disconnected",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
                color = if (isContactConnected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                }
            )

            // Message list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { message ->
                    MessageBubble(
                        message = message,
                        onImageClick = { imageData -> previewImageData = imageData }
                    )
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
                    if (onPickImage != null) {
                        IconButton(
                            onClick = { onPickImage() },
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Attach image")
                        }
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
                    ) {
                        Text("Send")
                    }
                }
            }
        }
    }

    // Full-screen image preview dialog
    previewImageData?.let { imageData ->
        Dialog(
            onDismissRequest = { previewImageData = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { previewImageData = null },
                contentAlignment = Alignment.Center
            ) {
                val imageBitmap = rememberImageBitmap(imageData)
                if (imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = "Full-screen image preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
    }
}
