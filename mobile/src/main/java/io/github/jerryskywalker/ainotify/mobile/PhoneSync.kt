package io.github.jerryskywalker.ainotify.mobile

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.net.toUri
import io.github.jerryskywalker.ainotify.core.EVENT_EXTRA
import io.github.jerryskywalker.ainotify.core.Entry
import io.github.jerryskywalker.ainotify.core.Relay
import io.github.jerryskywalker.ainotify.core.pairedNodeCount
import io.github.jerryskywalker.ainotify.core.sendWearPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

const val ACTION_PUSH_WAKEUP = "io.github.jerryskywalker.ainotify.PUSH_WAKEUP"
const val RECEIVER_CHANNEL = "ainotify_connection"
const val REPLY_CHANNEL = "ainotify_replies"
const val SERVICE_ID = 9001

sealed class SyncResult {
    data class Success(val freshCount: Int, val streamId: String?, val cursor: Long) : SyncResult()
    data object NoSettings : SyncResult()
    data object SettingsChanged : SyncResult()
    data class Failed(val reason: String) : SyncResult()
}

object PhoneSync {
    private val syncLock = Mutex()
    @Volatile private var lastWearCursor: Pair<String?, Long>? = null

    suspend fun performSync(context: Context, notify: Boolean = true): SyncResult {
        return syncLock.withLock {
            val appContext = context.applicationContext
            val inbox = Phone.inbox(appContext)
            val settings = inbox.settings() ?: return SyncResult.NoSettings
            val workspace = Phone.workspace(appContext)
            try {
                val relay = Relay(settings, BuildConfig.DEBUG)
                val mailboxBefore = inbox.mailbox.value
                val feed = relay.feed(mailboxBefore)
                val fresh = inbox.merge(feed, settings)
                if (inbox.settings() != settings) return SyncResult.SettingsChanged

                if (notify && fresh.isNotEmpty()) {
                    fresh.takeLast(5).forEach { entry ->
                        notifyReply(appContext, entry)
                    }
                }

                val mailboxAfter = inbox.mailbox.value
                val currentCursor = mailboxAfter.streamId to mailboxAfter.cursor
                val nodes = withTimeoutOrNull(2_000) {
                    try {
                        pairedNodeCount(context)
                    } catch (e: Exception) {
                        android.util.Log.w("AINotifySync", "pairedNodeCount error: ${e.message}")
                        0
                    }
                } ?: 0

                android.util.Log.i("AINotifySync", "performSync fresh=${fresh.size} nodes=$nodes lastWear=$lastWearCursor curr=$currentCursor")

                if (nodes > 0 && (fresh.isNotEmpty() || lastWearCursor != currentCursor)) {
                    val sent = withTimeoutOrNull(3_000) {
                        try {
                            android.util.Log.i("AINotifySync", "Sending wear preview...")
                            sendWearPreview(context, mailboxAfter)
                            android.util.Log.i("AINotifySync", "Sent wear preview OK!")
                            true
                        } catch (e: Exception) {
                            android.util.Log.w("AINotifySync", "sendWearPreview error: ${e.message}")
                            false
                        }
                    }
                    if (sent == true) {
                        lastWearCursor = currentCursor
                    }
                }

                workspace.sync(settings.relay, relay) { inbox.settings() == settings }
                SyncResult.Success(fresh.size, mailboxAfter.streamId, mailboxAfter.cursor)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                SyncResult.Failed(error.message ?: "Unknown sync error")
            }
        }
    }

    fun enqueueSync(context: Context, eventId: String? = null) {
        try {
            val dataBuilder = androidx.work.Data.Builder()
            if (eventId != null) {
                dataBuilder.putString("event_id", eventId)
            }
            val request = androidx.work.OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(dataBuilder.build())
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            androidx.work.WorkManager.getInstance(context).enqueue(request)
        } catch (e: Exception) {
            android.util.Log.w("AINotifySync", "WorkManager enqueue failed: ${e.message}")
        }
    }

    suspend fun registerPushToken(
        context: Context,
        token: String,
        deviceType: String = "android",
        appId: String = "io.github.jerryskywalker.ainotify",
    ): Boolean {
        val appContext = context.applicationContext
        val inbox = Phone.inbox(appContext)
        val settings = inbox.settings() ?: return false
        return try {
            val relay = Relay(settings, BuildConfig.DEBUG)
            relay.registerDevice(token, deviceType, appId)
        } catch (_: Exception) {
            false
        }
    }

    suspend fun unregisterPushToken(context: Context, token: String): Boolean {
        val appContext = context.applicationContext
        val inbox = Phone.inbox(appContext)
        val settings = inbox.settings() ?: return false
        return try {
            val relay = Relay(settings, BuildConfig.DEBUG)
            relay.unregisterDevice(token)
        } catch (_: Exception) {
            false
        }
    }
}

internal fun notifyReply(context: Context, entry: Entry) {
    if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val localized = Preferences.localized(context)
    val notifications = context.getSystemService(NotificationManager::class.java)
    notifications.createNotificationChannel(
        NotificationChannel(REPLY_CHANNEL, localized.getString(R.string.channel_replies), NotificationManager.IMPORTANCE_DEFAULT),
    )
    val preview = entry.preview ?: localized.getString(if (entry.kind == "test") R.string.test_message else R.string.reply_complete)
    notifications.notify(
        entry.id, 1,
        Notification.Builder(context, REPLY_CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(entry.heading)
            .setContentText(preview)
            .setStyle(Notification.BigTextStyle().bigText(preview))
            .setContentIntent(openIntent(context, entry.id))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setLocalOnly(true)
            .setAutoCancel(true)
            .build(),
    )
}

internal fun openIntent(context: Context, id: String?): PendingIntent {
    val intent = Intent(context, MainActivity::class.java)
    if (id != null) {
        intent.data = "ainotify://event/$id".toUri()
        intent.putExtra(EVENT_EXTRA, id)
    }
    return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}
