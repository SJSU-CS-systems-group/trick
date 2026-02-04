package net.discdd.trick.screens.chat

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.discdd.trick.contacts.NativeContactsManager
import net.discdd.trick.contacts.TrickContact

/**
 * ViewModel for the per-contact Chat screen.
 * Loads the contact by shortId and exposes it for the app bar (display name).
 */
class ChatViewModel(
    private val shortId: String,
    private val nativeContactsManager: NativeContactsManager
) : ViewModel() {

    private val _contact = MutableStateFlow<TrickContact?>(nativeContactsManager.getContactByShortId(shortId))
    val contact: StateFlow<TrickContact?> = _contact.asStateFlow()
}
