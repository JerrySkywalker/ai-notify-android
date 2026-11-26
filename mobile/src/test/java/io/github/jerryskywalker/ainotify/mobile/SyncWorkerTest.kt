package io.github.jerryskywalker.ainotify.mobile

import androidx.work.ListenableWorker.Result
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncWorkerTest {
    @Test fun retriesFailedSyncWithinAttemptLimit() {
        val unavailable = SyncResult.Failed("Relay temporarily unavailable")
        assertEquals(Result.retry(), syncWorkResult(unavailable, 0))
        assertEquals(Result.retry(), syncWorkResult(unavailable, 2))
        assertEquals(Result.failure(), syncWorkResult(unavailable, 3))
    }

    @Test fun completesForSuccessAndObsoleteWork() {
        assertEquals(Result.success(), syncWorkResult(SyncResult.Success(1, "stream", 1), 1))
        assertEquals(Result.success(), syncWorkResult(SyncResult.NoSettings, 0))
        assertEquals(Result.success(), syncWorkResult(SyncResult.SettingsChanged, 0))
    }
}
