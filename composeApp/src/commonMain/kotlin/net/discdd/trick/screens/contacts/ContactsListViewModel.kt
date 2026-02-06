package net.discdd.trick.screens.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import net.discdd.trick.contacts.NativeContactsManager
import net.discdd.trick.contacts.TrickContact
import net.discdd.trick.data.MessageMetadataRepository

/**
 * ViewModel for the Contacts List screen.
 * Combines native Android contacts (with Trick key data) with message metadata.
 */
class ContactsListViewModel(
    private val nativeContactsManager: NativeContactsManager,
    private val messageMetadataRepository: MessageMetadataRepository
) : ViewModel() {

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
}

