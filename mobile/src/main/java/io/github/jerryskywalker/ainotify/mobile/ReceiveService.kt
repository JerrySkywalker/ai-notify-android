package io.github.jerryskywalker.ainotify.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.annotation.StringRes
import io.github.jerryskywalker.ainotify.core.Inbox
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

internal enum class ReceiverStatus(@get:StringRes val label: Int) {
    OFF(R.string.receiver_off), STARTING(R.string.receiver_starting), LISTENING(R.string.receiver_on),
    WEAR(R.string.receiver_wear), RETRY(R.string.receiver_retry), PUSH(R.string.receiver_push),
}

internal object Phone {
    @Volatile private var store: Inbox? = null
    @Volatile private var actions: PhoneWorkspace? = null
    val status = MutableStateFlow(ReceiverStatus.OFF)

    @Synchronized fun workspace(context: Context): PhoneWorkspace =
        actions ?: PhoneWorkspace(context.applicationContext).also { actions = it }

    @Synchronized fun inbox(context: Context): Inbox =
        store ?: Inbox(context.applicationContext, fullHistory = true).also { store = it }

    fun updatePushStatus(context: Context) {
        if (status.value == ReceiverStatus.OFF && Preferences(context).current.value.pushEnabled) {
            status.value = ReceiverStatus.PUSH
        } else if (status.value == ReceiverStatus.PUSH && !Preferences(context).current.value.pushEnabled) {
            status.value = ReceiverStatus.OFF
        }
    }
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
                .setContentIntent(openIntent(this, null))
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
        while (true) {
            when (PhoneSync.performSync(this@ReceiveService, notify = true)) {
                is SyncResult.Success -> {
                    val nodes = withTimeoutOrNull(1_500) { wearable { pairedNodeCount(this@ReceiveService) } } ?: 0
                    Phone.status.value = if (nodes > 0) ReceiverStatus.WEAR else ReceiverStatus.LISTENING
                }
                is SyncResult.NoSettings -> {
                    stopSelf()
                    return
                }
                is SyncResult.SettingsChanged -> {
                    continue
                }
                is SyncResult.Failed -> {
                    Phone.status.value = ReceiverStatus.RETRY
                }
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

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        val pushActive = Preferences(this).current.value.pushEnabled
        Phone.status.value = if (pushActive) ReceiverStatus.PUSH else ReceiverStatus.OFF
        super.onDestroy()
    }
}
