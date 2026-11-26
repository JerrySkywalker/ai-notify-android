package io.github.jerryskywalker.ainotify.mobile

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

internal fun syncWorkResult(sync: SyncResult, attempt: Int): Result = when (sync) {
    is SyncResult.Failed -> if (attempt < 3) Result.retry() else Result.failure()
    is SyncResult.Success, SyncResult.NoSettings, SyncResult.SettingsChanged -> Result.success()
}

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val eventId = inputData.getString("event_id")
        Log.i("AINotifyWork", "SyncWorker doWork eventId=$eventId attempt=$runAttemptCount")
        return try {
            val sync = PhoneSync.performSync(applicationContext, notify = true)
            val result = syncWorkResult(sync, runAttemptCount)
            Log.i("AINotifyWork", "SyncWorker eventId=$eventId result=${result.javaClass.simpleName} fresh=${(sync as? SyncResult.Success)?.freshCount}")
            result
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Log.w("AINotifyWork", "SyncWorker failed before completing sync")
            syncWorkResult(SyncResult.Failed("Worker failure"), runAttemptCount)
        }
    }
}
