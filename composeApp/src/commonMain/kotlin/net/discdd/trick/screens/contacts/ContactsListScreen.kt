package net.discdd.trick.screens.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.discdd.trick.contacts.TrickContact
import org.koin.compose.viewmodel.koinViewModel

/**
 * Main contacts list screen - the home screen of the app.
 * Displays contacts in two sections: Nearby (WiFi Aware connected) and All Contacts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsListScreen(
    onContactClick: (TrickContact) -> Unit,
    onAddContactClick: () -> Unit,
    connectedPeerIds: List<String> = emptyList(),
    onTestMessagingClick: (() -> Unit)? = null,
    viewModel: ContactsListViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(connectedPeerIds) {
        viewModel.updateConnectedPeers(connectedPeerIds)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Contacts",
                        style = MaterialTheme.typography.headlineMedium
                    )
                },
                actions = {
                    // Temporary test button to bypass contacts and go directly to messaging
                    if (onTestMessagingClick != null) {
                        IconButton(onClick = onTestMessagingClick) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "Test Messaging",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddContactClick,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Contact",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (uiState.isEmpty) {
                EmptyContactsState(
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                SectionedContactsList(
                    nearbyContacts = uiState.nearbyContacts,
                    allContacts = uiState.allContacts,
                    onContactClick = onContactClick,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * Lazy list of contacts organized into Nearby and All Contacts sections.
 */
@Composable
private fun SectionedContactsList(
    nearbyContacts: List<TrickContact>,
    allContacts: List<TrickContact>,
    onContactClick: (TrickContact) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier) {
        // Nearby section (only if non-empty)
        if (nearbyContacts.isNotEmpty()) {
            item(key = "nearby_header") {
                SectionHeader(title = "Nearby", count = nearbyContacts.size)
            }
            items(
                items = nearbyContacts,
                key = { "nearby_${it.shortId}" }
            ) { contact ->
                ContactItem(
                    contact = contact,
                    onClick = { onContactClick(contact) }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 80.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }
        }

        // All Contacts section (only if non-empty)
        if (allContacts.isNotEmpty()) {
            item(key = "all_header") {
                SectionHeader(title = "All Contacts", count = allContacts.size)
            }
            items(
                items = allContacts,
                key = { "all_${it.shortId}" }
            ) { contact ->
                ContactItem(
                    contact = contact,
                    onClick = { onContactClick(contact) }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 80.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

/**
 * Empty state shown when there are no contacts.
 */
@Composable
private fun EmptyContactsState(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            modifier = Modifier.padding(bottom = 16.dp),
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
        )

        Text(
            text = "No contacts yet",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Tap + to exchange keys with a contact",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
        )
    }
}
