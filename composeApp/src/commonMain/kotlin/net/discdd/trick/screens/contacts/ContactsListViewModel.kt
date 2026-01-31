package net.discdd.trick.screens.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import net.discdd.trick.data.Contact
import net.discdd.trick.data.ContactRepository

/**
 * ViewModel for the Contacts List screen.
 * Exposes contacts as a StateFlow for reactive UI updates.
 */
class ContactsListViewModel(
    private val contactRepository: ContactRepository
) : ViewModel() {

    /**
     * StateFlow of all contacts, sorted by last message timestamp (most recent first).
     * Automatically updates when the database changes.
     */
    val contacts: StateFlow<List<Contact>> = contactRepository.getAllContactsFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
}

