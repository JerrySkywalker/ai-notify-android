package io.github.jerryskywalker.ainotify.core

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

suspend fun sendWearPreview(context: Context, mailbox: Mailbox) {
    val request = PutDataMapRequest.create(WATCH_PATH)
    request.dataMap.putString(
        WATCH_PAYLOAD,
        wireJson.encodeToString(Feed(mailbox.events.take(12).map { it.copy(message = null) }, mailbox.cursor)),
    )
    Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent()).await()
}

suspend fun pairedNodeCount(context: Context): Int =
    Wearable.getNodeClient(context).connectedNodes.await().size
