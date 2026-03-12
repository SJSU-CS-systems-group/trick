package org.trick.screens.contacts

import org.trick.contacts.TrickContact

/**
 * UI state for the contacts list screen with sectioned contacts.
 */
data class ContactsListUiState(
    val connectedContacts: List<TrickContact> = emptyList(),
    val allContacts: List<TrickContact> = emptyList()
) {
    val isEmpty: Boolean get() = connectedContacts.isEmpty() && allContacts.isEmpty()
}
