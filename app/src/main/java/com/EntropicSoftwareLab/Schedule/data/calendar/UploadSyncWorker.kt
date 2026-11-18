package com.EntropicSoftwareLab.Schedule.data.calendar

import android.content.ContentResolver
import kotlinx.coroutines.CancellationException

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.EntropicSoftwareLab.Schedule.ScheduleApp
import java.util.concurrent.TimeUnit

/**
 * Keeps asking Google to upload this app's calendar edits until they are gone, with the app closed.
 *
 * Every write the app makes marks its rows DIRTY, and only Google's sync adapter uploads them, on
 * its own schedule, which a battery saver can hold off. This asks again until the count of waiting
 * rows reaches zero. It requests a sync ONLY when rows are dirty, and a sync clears them, so unlike
 * `CalendarChangeWorker` it cannot loop.
 *
 * It needs no network itself (the app declares no INTERNET permission and must stay that way); the
 * CONNECTED constraint only avoids asking when the adapter couldn't upload anyway. Everything is
 * caught: a missing calendar permission is a quiet [Result.success], never a failure.
 */
class UploadSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // `container` is lateinit and WorkManager can start a job before Application.onCreate has
        // set it; try again later rather than lose the retry ladder.
        val container = try {
            (applicationContext as? ScheduleApp)?.container
        } catch (e: UninitializedPropertyAccessException) {
            return Result.retry()
        } ?: return Result.success()
        val repo = container.calendarRepository
        // The user turned the phone's sync off: a manual request would override that choice.
        val masterOn = runCatching { ContentResolver.getMasterSyncAutomatically() }.getOrDefault(true)
        if (!masterOn) return Result.success()
        val pending = try {
            repo.pendingUploadCount()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return Result.success()
        // Tells the Settings timing whether the rows went up since the last look.
        try {
            container.recordUploadTiming()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Diagnostic only.
        }
        if (pending == 0) return Result.success()
        try {
            repo.requestUploadSync()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Asking failed; the retry below asks again.
        }
        val isPeriodic = inputData.getBoolean(KEY_PERIODIC, false)
        // Periodic runs never retry (a periodic request's retry just delays its next period);
        // one-shot runs retry with backoff until nothing is waiting or MAX_ATTEMPTS.
        return if (!isPeriodic && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    companion object {
        private const val KEY_PERIODIC = "periodic"
        private const val WORK_AFTER_WRITE = "calendar_upload"
        private const val WORK_PERIODIC = "calendar_upload_periodic"
        private const val MAX_ATTEMPTS = 8

        private val online: Constraints
            get() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /**
         * After a write: checks 15 seconds later that the rows really went up (the immediate
         * `requestSync` in `onWrite` covers "now"), then retries with exponential backoff. REPLACE,
         * so each new write restarts the retry ladder.
         */
        fun afterWrite(context: Context) {
            val request = OneTimeWorkRequestBuilder<UploadSyncWorker>()
                .setConstraints(online)
                .setInitialDelay(15, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_AFTER_WRITE,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        /** A 15-minute safety net for anything the one-shot ladder gave up on. KEEP, so a no-op once scheduled. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<UploadSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(online)
                .setInputData(workDataOf(KEY_PERIODIC to true))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
