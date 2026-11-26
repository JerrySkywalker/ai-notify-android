package io.github.jerryskywalker.ainotify.mobile

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AinotifyFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Log.i("AINotifyFCM", "onNewToken received")
        val prefs = Preferences(applicationContext)
        prefs.update { it.copy(fcmToken = token) }
        if (prefs.current.value.fcmEnabled) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch {
                val fid = FirebaseManager.getInstallationId(applicationContext)
                val success = PhoneSync.registerPushToken(
                    applicationContext,
                    token,
                    deviceType = "fcm",
                    appId = fid ?: "io.github.jerryskywalker.ainotify",
                )
                Log.i("AINotifyFCM", "onNewToken registered with Relay: $success")
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        if (!Preferences(applicationContext).current.value.fcmEnabled) return
        val eventId = remoteMessage.data["event_id"] ?: return
        val msgId = remoteMessage.messageId
        Log.i("AINotifyFCM", "onMessageReceived: msgId=$msgId eventId=$eventId")
        PhoneSync.enqueueSync(applicationContext, eventId)
    }
}
