package org.trick.screens.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.trick.contacts.NativeContactsManager
import org.trick.contacts.TrickContact
import org.trick.data.MessageMetadataRepository

/**
 * ViewModel for the Contacts List screen.
 * Combines native Android contacts (with Trick key data) with message metadata.
 */
class ContactsListViewModel(
    private val nativeContactsManager: NativeContactsManager,
    private val messageMetadataRepository: MessageMetadataRepository
) : ViewModel() {

    private val _connectedPeerIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * StateFlow of all Trick contacts, enriched with message metadata.
     * Sorted by last message timestamp (most recent first).
     * Automatically updates when either data source changes.
     */
    val contacts: StateFlow<List<TrickContact>> = combine(
        nativeContactsManager.observeTrickContacts(),
        messageMetadataRepository.getAllMetadataFlow()
    ) { trickContacts, metadataList ->
        // Create a map for quick metadata lookup
        val metadataMap = metadataList.associateBy { it.shortId }

        // Enrich contacts with message metadata
        trickContacts.map { contact ->
            val metadata = metadataMap[contact.shortId]
            contact.copy(
                lastMessageAt = metadata?.lastMessageAt,
                lastMessagePreview = metadata?.lastMessagePreview
            )
        }.sortedByDescending { it.lastMessageAt ?: 0L }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    /**
     * UI state with contacts partitioned into connected and all sections.
     */
    val uiState: StateFlow<ContactsListUiState> = combine(
        contacts,
        _connectedPeerIds
    ) { allContacts, peerIds ->
        val (connected, others) = allContacts.partition { contact ->
            contact.deviceId != null && contact.deviceId in peerIds
        }
        ContactsListUiState(
            connectedContacts = connected,
            allContacts = others
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ContactsListUiState()
    )

    /**
     * Update the set of connected peer IDs from WiFi Aware.
     */
    fun updateConnectedPeers(peerIds: List<String>) {
        _connectedPeerIds.value = peerIds.toSet()
    }
}
