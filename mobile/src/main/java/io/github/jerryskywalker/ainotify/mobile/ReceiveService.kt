package io.github.jerryskywalker.ainotify.mobile

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.core.net.toUri
import android.os.Build
import android.os.IBinder
import io.github.jerryskywalker.ainotify.core.EVENT_EXTRA
import io.github.jerryskywalker.ainotify.core.Entry
import io.github.jerryskywalker.ainotify.core.Inbox
import io.github.jerryskywalker.ainotify.core.Relay
import io.github.jerryskywalker.ainotify.core.pairedNodeCount
import io.github.jerryskywalker.ainotify.core.sendWearPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val RECEIVER_CHANNEL = "ainotify_connection"
private const val REPLY_CHANNEL = "ainotify_replies"
private const val SERVICE_ID = 9001

internal enum class ReceiverStatus(@get:StringRes val label: Int) {
    OFF(R.string.receiver_off), STARTING(R.string.receiver_starting), LISTENING(R.string.receiver_on),
    WEAR(R.string.receiver_wear), RETRY(R.string.receiver_retry),
}

internal object Phone {
    @Volatile private var store: Inbox? = null
    @Volatile private var actions: PhoneWorkspace? = null
    val status = MutableStateFlow(ReceiverStatus.OFF)

    @Synchronized fun workspace(context: Context): PhoneWorkspace =
        actions ?: PhoneWorkspace(context.applicationContext).also { actions = it }

    @Synchronized fun inbox(context: Context): Inbox =
        store ?: Inbox(context.applicationContext, fullHistory = true).also { store = it }
}

class ReceiveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val localized get() = Preferences.localized(this)
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        notifications.createNotificationChannel(
            NotificationChannel(RECEIVER_CHANNEL, localized.getString(R.string.channel_connection), NotificationManager.IMPORTANCE_LOW),
        )
        notifications.createNotificationChannel(
            NotificationChannel(REPLY_CHANNEL, localized.getString(R.string.channel_replies), NotificationManager.IMPORTANCE_DEFAULT),
        )
        startForeground(
            SERVICE_ID,
            Notification.Builder(this, RECEIVER_CHANNEL)
                .setContentTitle(localized.getString(R.string.notification_listening))
                .setContentText(localized.getString(R.string.notification_connection))
                .setContentIntent(openIntent(null))
                .setOngoing(true)
                .setLocalOnly(true)
                .setSmallIcon(R.drawable.ic_notify)
                .build(),
        )
        if (job?.isActive != true) {
            Phone.status.value = ReceiverStatus.STARTING
            job = scope.launch { receive() }
        }
        return START_NOT_STICKY
    }

    private suspend fun receive() {
        val inbox = Phone.inbox(this)
        val workspace = Phone.workspace(this)
        var wearCursor = -1L
        var wearNodes = 0
        var previousSettings = inbox.settings()
        while (true) {
            val settings = inbox.settings()
            if (settings == null) {
                stopSelf()
                return
            }
            if (settings != previousSettings) {
                wearCursor = -1
                previousSettings = settings
            }
            try {
                val relay = Relay(settings, BuildConfig.DEBUG)
                val feed = relay.feed(inbox.mailbox.value.cursor)
                val fresh = inbox.merge(feed, settings)
                fresh.takeLast(5).forEach(::notifyReply)
                val mailbox = inbox.mailbox.value
                val nodes = withTimeoutOrNull(800) { wearable { pairedNodeCount(this@ReceiveService) } } ?: 0
                if (nodes > 0 && (wearCursor != mailbox.cursor || wearNodes == 0)) {
                    val sent = withTimeoutOrNull(1200) { wearable { sendWearPreview(this@ReceiveService, mailbox); true } }
                    if (sent == true) wearCursor = mailbox.cursor
                }
                wearNodes = nodes
                workspace.sync(settings.relay, relay) { inbox.settings() == settings }
                Phone.status.value = if (nodes > 0) ReceiverStatus.WEAR else ReceiverStatus.LISTENING
                if (feed.events.size == 50) continue
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Phone.status.value = ReceiverStatus.RETRY
            }
            delay(2_000)
        }
    }

    private suspend fun <T> wearable(action: suspend () -> T): T? = try {
        action()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun notifyReply(entry: Entry) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val preview = entry.preview ?: localized.getString(if (entry.kind == "test") R.string.test_message else R.string.reply_complete)
        notifications.notify(
            entry.id, 1,
            Notification.Builder(this, REPLY_CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(entry.heading)
                .setContentText(preview)
                .setStyle(Notification.BigTextStyle().bigText(preview))
                .setContentIntent(openIntent(entry.id))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setLocalOnly(true)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun openIntent(id: String?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        if (id != null) {
            intent.data = "ainotify://event/$id".toUri()
            intent.putExtra(EVENT_EXTRA, id)
        }
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        Phone.status.value = ReceiverStatus.OFF
        super.onDestroy()
    }
}
