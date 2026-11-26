package io.github.jerryskywalker.ainotify.mobile

import android.app.Application
import android.util.Log
import androidx.work.Configuration

class AinotifyApp : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()
}
