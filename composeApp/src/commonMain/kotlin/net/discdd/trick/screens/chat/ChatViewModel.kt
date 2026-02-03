package net.discdd.trick.screens.chat

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.discdd.trick.data.Contact
import net.discdd.trick.data.ContactRepository

/**
 * ViewModel for the per-contact Chat screen.
 * Loads the contact by id and exposes it for the app bar (display name / shortId).
 */
class ChatViewModel(
    private val contactId: String,
    private val contactRepository: ContactRepository
) : ViewModel() {

    private val _contact = MutableStateFlow<Contact?>(contactRepository.getContactById(contactId))
    val contact: StateFlow<Contact?> = _contact.asStateFlow()
}
