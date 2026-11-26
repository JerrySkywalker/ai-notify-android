package io.github.jerryskywalker.ainotify.core

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.security.MessageDigest
import kotlinx.coroutines.tasks.await

suspend fun sendWearPreview(context: Context, mailbox: Mailbox) {
    val request = PutDataMapRequest.create(WATCH_PATH)
    request.dataMap.putString(
        WATCH_PAYLOAD,
        wireJson.encodeToString(wearPreview(mailbox)),
    )
    request.dataMap.putLong("updated_at", System.currentTimeMillis())
    Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent()).await()
}

internal fun wearPreview(mailbox: Mailbox): Feed {
    // Older relays lack an ID. Keep their addresses and credentials off the Data Layer.
    val stream = mailbox.streamId ?: mailbox.relay?.let { relay ->
        MessageDigest.getInstance("SHA-256").digest(relay.toByteArray())
            .joinToString("", prefix = "legacy:") { "%02x".format(it) }
    }
    return Feed(mailbox.events.take(12).map { it.copy(message = null) }, mailbox.cursor, stream)
}

suspend fun pairedNodeCount(context: Context): Int =
    Wearable.getNodeClient(context).connectedNodes.await().size
