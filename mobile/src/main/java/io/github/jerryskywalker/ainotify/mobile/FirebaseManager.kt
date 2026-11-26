package io.github.jerryskywalker.ainotify.mobile

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

object FirebaseManager {
    fun isAvailable(context: Context): Boolean {
        return try {
            FirebaseApp.getApps(context).isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun getInstallationId(context: Context): String? {
        return try {
            if (isAvailable(context)) {
                FirebaseInstallations.getInstance().id.await()
            } else null
        } catch (e: Throwable) {
            Log.w("AINotifyFCM", "Failed to get FID: ${e.message}")
            null
        }
    }

    suspend fun getFcmToken(context: Context): String? {
        return try {
            if (isAvailable(context)) {
                FirebaseMessaging.getInstance().token.await()
            } else null
        } catch (e: Throwable) {
            Log.w("AINotifyFCM", "Failed to get FCM token: ${e.message}")
            null
        }
    }

    suspend fun deleteToken(context: Context): Boolean {
        return try {
            if (isAvailable(context)) {
                FirebaseMessaging.getInstance().deleteToken().await()
                true
            } else false
        } catch (e: Throwable) {
            Log.w("AINotifyFCM", "Failed to delete FCM token: ${e.message}")
            false
        }
    }
}
