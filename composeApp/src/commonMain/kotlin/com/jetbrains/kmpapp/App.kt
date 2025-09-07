package com.jetbrains.kmpapp

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
import com.jetbrains.kmpapp.screens.messaging.MessagingScreen
import com.jetbrains.kmpapp.screens.messaging.Message
import com.jetbrains.kmpapp.screens.messaging.WifiAwareService
import kotlin.native.concurrent.ThreadLocal

@Composable
fun App(wifiAwareService: WifiAwareService) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    ) {
        Surface {
            val messages = remember { mutableStateListOf<Message>() }
            val debugLogs = remember { mutableStateListOf<String>() }
            val discoveryStatus = remember { mutableStateOf("Not started") }
            val lastReceivedMessage = remember { mutableStateOf("") }
            val lastSentMessage = remember { mutableStateOf("") }

            // Start discovery and update messages when a new message is received
            LaunchedEffect(Unit) {
                discoveryStatus.value = "Starting..."
                debugLogs.add("[UI] Starting discovery...")
                wifiAwareService.startDiscovery { msg ->
                    debugLogs.add("[App] Message received: $msg")
                    println("[App] Message received: $msg")
                    val message = Message(
                        content = msg,
                        isSent = false,
                        isServiceMessage = msg.startsWith("Service discovered:")
                    )
                    messages.add(message)
                    lastReceivedMessage.value = msg
                }
                discoveryStatus.value = "Running"
                debugLogs.add("[UI] Discovery running.")
            }
            fun refreshDiscovery() {
                debugLogs.add("[UI] Manual refresh triggered.")
                discoveryStatus.value = "Refreshing..."
                wifiAwareService.startDiscovery { msg ->
                    debugLogs.add("[App] Message received (refresh): $msg")
                    println("[App] Message received (refresh): $msg")
                    val message = Message(
                        content = msg,
                        isSent = false,
                        isServiceMessage = msg.startsWith("Service discovered:")
                    )
                    messages.add(message)
                    lastReceivedMessage.value = msg
                }
                discoveryStatus.value = "Running (refreshed)"
                debugLogs.add("[UI] Discovery refreshed.")
            }
            MessagingScreen(
                messages = messages,
                onSend = { msg ->
                    debugLogs.add("[App] Sending message: $msg")
                    println("[App] Sending message: $msg")
                    wifiAwareService.sendMessage(msg)
                    val message = Message(
                        content = msg,
                        isSent = true,
                        isServiceMessage = false
                    )
                    messages.add(message)
                    lastSentMessage.value = msg
                },
                debugLogs = debugLogs,
                discoveryStatus = discoveryStatus.value,
                lastReceivedMessage = lastReceivedMessage.value,
                lastSentMessage = lastSentMessage.value,
                onRefresh = { refreshDiscovery() }
            )
        }
    }   
}
