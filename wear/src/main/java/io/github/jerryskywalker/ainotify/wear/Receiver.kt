package io.github.jerryskywalker.ainotify.wear

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import android.os.IBinder
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import io.github.jerryskywalker.ainotify.core.EVENT_EXTRA
import io.github.jerryskywalker.ainotify.core.Entry
import io.github.jerryskywalker.ainotify.core.Feed
import io.github.jerryskywalker.ainotify.core.Inbox
import io.github.jerryskywalker.ainotify.core.Relay
import io.github.jerryskywalker.ainotify.core.WATCH_PATH
import io.github.jerryskywalker.ainotify.core.WATCH_PAYLOAD
import io.github.jerryskywalker.ainotify.core.wireJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private const val REPLIES = "ainotify_replies"
private const val CONNECTION = "ainotify_connection"

object Watch {
    @Volatile private var store: Inbox? = null
    val status = MutableStateFlow("Waiting for phone")

    @Synchronized fun inbox(context: Context): Inbox =
        store ?: Inbox(context.applicationContext, "wear").also { store = it }
}

class PhoneListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        android.util.Log.i("AINotifyWatch", "onDataChanged received ${events.count} events")
        val inbox = Watch.inbox(this)
        if (inbox.settings() != null) {
            android.util.Log.i("AINotifyWatch", "ignoring because settings() != null")
            return
        }
        for (event in events) {
            android.util.Log.i("AINotifyWatch", "event type=${event.type} uri=${event.dataItem.uri.path}")
            if (event.type != DataEvent.TYPE_CHANGED || event.dataItem.uri.path != WATCH_PATH) continue
            try {
                val payload = DataMapItem.fromDataItem(event.dataItem).dataMap.getString(WATCH_PAYLOAD) ?: continue
                val feed = wireJson.decodeFromString<Feed>(payload)
                val fresh = inbox.merge(feed, null)
                android.util.Log.i("AINotifyWatch", "merged feed: events=${feed.events.size} cursor=${feed.nextCursor} fresh=${fresh.size}")
                fresh.sortedBy { it.seq }.takeLast(3).forEach { notifyReply(this, it) }
                if (inbox.settings() == null) Watch.status.value = "Synced from phone"
            } catch (e: Exception) {
                android.util.Log.w("AINotifyWatch", "error handling data event: ${e.message}", e)
            }
        }
    }
}

class DirectReceiver : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CONNECTION, "Relay connection", NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(
            9001,
            Notification.Builder(this, CONNECTION)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("AINotify is listening")
                .setContentIntent(openIntent(this, null))
                .setLocalOnly(true)
                .setOngoing(true)
                .build(),
        )
        if (job?.isActive != true) job = scope.launch { receive() }
        return START_NOT_STICKY
    }

    private suspend fun receive() {
        val inbox = Watch.inbox(this)
        while (true) {
            val settings = inbox.settings()
            if (settings == null) {
                stopSelf()
                return
            }
            try {
                val feed = Relay(settings, BuildConfig.DEBUG).feed(inbox.mailbox.value)
                inbox.merge(feed, settings).takeLast(3).forEach { notifyReply(this, it) }
                Watch.status.value = "Relay connected"
                if (feed.events.size == 50) continue
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Watch.status.value = "Relay unavailable · retrying"
            }
            delay(2_000)
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        Watch.status.value = if (Watch.inbox(this).settings() == null) "Waiting for phone" else "Receiver off"
        super.onDestroy()
    }
}

private fun notifyReply(context: Context, entry: Entry) {
    if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(REPLIES, "AI replies", NotificationManager.IMPORTANCE_DEFAULT))
    manager.notify(
        entry.id, 1,
        Notification.Builder(context, REPLIES)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(entry.heading)
            .setContentText(entry.preview ?: entry.statusLabel)
            .setContentIntent(openIntent(context, entry.id))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setLocalOnly(true)
            .setAutoCancel(true)
            .build(),
    )
}

private fun openIntent(context: Context, id: String?): PendingIntent {
    val intent = Intent(context, MainActivity::class.java)
    if (id != null) {
        intent.data = "ainotify://event/$id".toUri()
        intent.putExtra(EVENT_EXTRA, id)
    }
    return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}
