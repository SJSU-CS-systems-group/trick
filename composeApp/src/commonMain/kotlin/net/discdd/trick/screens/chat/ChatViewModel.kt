package net.discdd.trick.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import net.discdd.trick.contacts.NativeContactsManager
import net.discdd.trick.contacts.TrickContact
import net.discdd.trick.data.ImageStorage
import net.discdd.trick.data.MessageRepository
import net.discdd.trick.screens.messaging.Message
import net.discdd.trick.screens.messaging.MessageType

/**
 * ViewModel for the per-contact Chat screen.
 * Loads the contact by shortId and exposes it for the app bar (display name).
 * Observes persisted messages from the database reactively.
 */
class ChatViewModel(
    private val shortId: String,
    private val nativeContactsManager: NativeContactsManager,
    private val messageRepository: MessageRepository,
    private val imageStorage: ImageStorage
) : ViewModel() {

    private val _contact = MutableStateFlow<TrickContact?>(nativeContactsManager.getContactByShortId(shortId))
    val contact: StateFlow<TrickContact?> = _contact.asStateFlow()

    val messages: StateFlow<List<Message>> = messageRepository.getMessagesByShortIdFlow(shortId)
        .map { persistedMessages ->
            persistedMessages.map { pm ->
                val imageData = if (pm.type == MessageType.IMAGE && pm.imagePath != null) {
                    imageStorage.loadImage(pm.imagePath)
                } else null

                Message(
                    content = pm.content,
                    isSent = pm.isSent,
                    isServiceMessage = false,
                    type = pm.type,
                    imageData = imageData,
                    filename = pm.imagePath?.substringAfterLast('/')?.substringAfter('_'),
                    isEncrypted = pm.isEncrypted,
                    peerId = null
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
}
