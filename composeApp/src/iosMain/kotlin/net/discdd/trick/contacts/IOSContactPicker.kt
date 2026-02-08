package net.discdd.trick.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Contacts.*
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Result from the iOS contact picker.
 */
data class IOSContactPickerResult(
    val contactIdentifier: String,
    val nativeContactId: Long,
    val displayName: String
)

/**
 * iOS contact picker screen using Compose UI.
 * Fetches contacts from CNContactStore and displays them in a searchable list.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalForeignApi::class)
@Composable
fun IOSContactPickerScreen(
    onContactPicked: (IOSContactPickerResult) -> Unit,
    onDismiss: () -> Unit
) {
    var contacts by remember { mutableStateOf<List<IOSContactPickerResult>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var permissionGranted by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var limitedAccess by remember { mutableStateOf(false) }

    // Check and request contacts permission
    LaunchedEffect(Unit) {
        val status = CNContactStore.authorizationStatusForEntityType(CNEntityType.CNEntityTypeContacts)
        when (status) {
            CNAuthorizationStatusNotDetermined -> {
                val store = CNContactStore()
                store.requestAccessForEntityType(CNEntityType.CNEntityTypeContacts) { granted, _ ->
                    dispatch_async(dispatch_get_main_queue()) {
                        permissionGranted = granted
                        permissionDenied = !granted
                        isLoading = false
                    }
                }
            }
            CNAuthorizationStatusDenied, CNAuthorizationStatusRestricted -> {
                permissionDenied = true
                isLoading = false
            }
            CNAuthorizationStatusAuthorized -> {
                permissionGranted = true
            }
            else -> {
                // Limited access (iOS 18+) — can access selected contacts only
                permissionGranted = true
                limitedAccess = true
            }
        }
    }

    // Fetch contacts when permission is granted
    LaunchedEffect(permissionGranted) {
        if (permissionGranted && contacts.isEmpty()) {
            isLoading = true
            contacts = withContext(Dispatchers.Default) { fetchAllIOSContacts() }
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select Contact") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            permissionDenied -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "Contacts permission is required to link keys to contacts",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Please enable contacts access in Settings",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            }
            else -> {
                val filteredContacts = if (searchQuery.isBlank()) {
                    contacts
                } else {
                    contacts.filter { it.displayName.contains(searchQuery, ignoreCase = true) }
                }

                Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                    if (limitedAccess) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "Only selected contacts are visible",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                TextButton(onClick = {
                                    NSURL(string = UIApplicationOpenSettingsURLString)?.let {
                                        UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any>(), null)
                                    }
                                }) {
                                    Text("Grant Full Access in Settings")
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("Search contacts") },
                        singleLine = true
                    )

                    LazyColumn {
                        items(
                            items = filteredContacts,
                            key = { it.contactIdentifier }
                        ) { contact ->
                            ListItem(
                                headlineContent = { Text(contact.displayName) },
                                modifier = Modifier.clickable {
                                    onContactPicked(contact)
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Fetch all contacts from iOS Contacts framework.
 * Uses CNContactFetchRequest to enumerate all contacts across all containers.
 */
@OptIn(ExperimentalForeignApi::class)
private fun fetchAllIOSContacts(): List<IOSContactPickerResult> {
    val contactStore = CNContactStore()
    val keysToFetch: List<Any> = listOf(
        CNContactIdentifierKey,
        CNContactGivenNameKey,
        CNContactFamilyNameKey
    )

    val results = mutableListOf<IOSContactPickerResult>()

    try {
        val fetchRequest = CNContactFetchRequest(keysToFetch = keysToFetch)
        contactStore.enumerateContactsWithFetchRequest(fetchRequest, error = null) { contact, _ ->
            if (contact != null) {
                val name = "${contact.givenName} ${contact.familyName}".trim()
                if (name.isNotEmpty()) {
                    results.add(
                        IOSContactPickerResult(
                            contactIdentifier = contact.identifier,
                            nativeContactId = contact.identifier.hashCode().toLong(),
                            displayName = name
                        )
                    )
                }
            }
        }
    } catch (e: Exception) {
        println("IOSContactPicker: Failed to fetch contacts: ${e.message}")
    }

    return results.distinctBy { it.contactIdentifier }.sortedBy { it.displayName }
}
