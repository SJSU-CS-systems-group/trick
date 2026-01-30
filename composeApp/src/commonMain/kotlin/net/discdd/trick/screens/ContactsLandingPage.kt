package net.discdd.trick.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsLandingPage(onNavigateToMessaging: () -> Unit = {}) {
    Scaffold(
            topBar = {
                TopAppBar(
                        title = {
                            Text(text = "Contacts", style = MaterialTheme.typography.headlineMedium)
                        },
                        actions = {
                            IconButton(onClick = { /* TODO: Add contact action */}) {
                                Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Add Contact",
                                        tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        },
                        colors =
                                TopAppBarDefaults.topAppBarColors(
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        titleContentColor = MaterialTheme.colorScheme.onSurface
                                )
                )
            }
    ) { paddingValues ->
        Surface(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                color = MaterialTheme.colorScheme.background
        ) {
            // Empty state - contacts will be added here later
            Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
            ) {
                Text(
                        text = "No contacts yet",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Temporary navigation button
                Button(onClick = onNavigateToMessaging) { Text("Go to Messaging (Temporary)") }
            }
        }
    }
}

