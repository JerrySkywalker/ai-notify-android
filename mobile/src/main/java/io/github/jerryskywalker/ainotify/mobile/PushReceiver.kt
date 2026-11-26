package io.github.jerryskywalker.ainotify.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class PushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PUSH_WAKEUP) return
        val eventId = intent.getStringExtra("event_id") ?: return
        Log.i("AINotifyPush", "PushReceiver onReceive: action=${intent.action} event=$eventId")
        PhoneSync.enqueueSync(context.applicationContext, eventId)
    }
}
