package org.trcky.trick.screens.messaging

import android.content.Context
import android.util.Log
import net.discdd.adapter.DDDClientAdapter
import org.trcky.trick.messaging.ChatMessage
import org.trcky.trick.messaging.TrickADU

class TrickDDDManager(private val context: Context) {
    companion object {
        private const val TAG = "TrickDDDManager"
    }

    private var messageCallback: ((ChatMessage, String) -> Unit)? = null

    private val dddAdapter = DDDClientAdapter(context) {
        messageCallback?.let { cb -> processIncoming(cb) }
    }

    /** Returns the local DDD client ID from BundleClient (null if BundleClient not installed). */
    fun getLocalDddClientId(): String? = try {
        dddAdapter.getClientId()
    } catch (e: Exception) {
        Log.w(TAG, "BundleClient not available — cannot get DDD client ID: ${e.message}")
        null
    }

    /** Send an encrypted ChatMessage via DDD bundle network to the given DDD client ID. */
    fun sendViaDDD(destinationClientId: String, chatMessage: ChatMessage) {
        try {
            val adu = TrickADU(
                destination_client_id = destinationClientId,
                chat_message = chatMessage.encodeByteString()
            )
            dddAdapter.createAduToSend().use { stream ->
                stream.write(adu.encode())
            }
            Log.d(TAG, "Sent ADU to DDD for $destinationClientId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send via DDD: ${e.message}", e)
        }
    }

    /** Register ContentObserver to receive DDD messages. No-op if BundleClient is not installed. */
    fun startListening(onMessageReceived: (ChatMessage, String) -> Unit) {
        messageCallback = onMessageReceived
        try {
            dddAdapter.registerForAduAdditions()
        } catch (e: SecurityException) {
            Log.w(TAG, "BundleClient not available — DDD fallback disabled: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register for DDD ADUs: ${e.message}")
        }
    }

    private fun processIncoming(onMessageReceived: (ChatMessage, String) -> Unit) {
        val aduIds = dddAdapter.getIncomingAduIds() ?: return
        if (aduIds.isEmpty()) return
        var lastId = -1L
        for (aduId in aduIds) {
            try {
                val bytes = dddAdapter.receiveAdu(aduId).readBytes()
                val trickAdu = TrickADU.ADAPTER.decode(bytes)
                val chatMessage = ChatMessage.ADAPTER.decode(trickAdu.chat_message)
                onMessageReceived(chatMessage, trickAdu.destination_client_id)
                lastId = aduId
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse incoming ADU $aduId: ${e.message}", e)
            }
        }
        if (lastId >= 0) dddAdapter.deleteReceivedAdusUpTo(lastId)
    }

    /** Unregister ContentObserver. Call when stopping. */
    fun stopListening() {
        dddAdapter.unregisterForAduAdditions()
        messageCallback = null
    }
}
