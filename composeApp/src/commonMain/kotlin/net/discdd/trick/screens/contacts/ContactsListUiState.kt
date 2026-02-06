package net.discdd.trick.screens.contacts

import net.discdd.trick.contacts.TrickContact

/**
 * UI state for the contacts list screen with sectioned contacts.
 */
data class ContactsListUiState(
    val nearbyContacts: List<TrickContact> = emptyList(),
    val allContacts: List<TrickContact> = emptyList()
) {
    val isEmpty: Boolean get() = nearbyContacts.isEmpty() && allContacts.isEmpty()
}
