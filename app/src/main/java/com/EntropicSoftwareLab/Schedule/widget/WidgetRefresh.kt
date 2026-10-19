package com.EntropicSoftwareLab.Schedule.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import android.provider.CalendarContract
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Keeps the widgets honest.
 *
 * Glance can't hold a ContentObserver — the widget process isn't alive between updates — so freshness
 * comes from two places: a periodic worker for the slow drift (an event added on the web, the day
 * rolling over), and an immediate push after anything this app writes.
 */
class WidgetRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            refreshWidgets(applicationContext)
            Result.success()
        } catch (e: Exception) {
            // A failed refresh just means stale data until the next run; retrying forever would
            // burn battery for no gain.
            Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "today_widget_refresh"

        /**
         * 30 minutes is WorkManager's floor for periodic work anyway, and it bounds midnight
         * rollover staleness to that same window.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

/**
 * Redraws both widgets whenever the calendar provider changes — a Google sync, another calendar app,
 * this app — without waiting for the 30-minute [WidgetRefreshWorker].
 *
 * A content-URI trigger fires once and is spent, so each run arms the next before it returns.
 * `APPEND_OR_REPLACE` rather than `REPLACE`: this run is itself the unique work, and replacing it
 * would cancel the run doing the enqueueing. The delays batch a sync's burst of row writes into one
 * redraw instead of dozens.
 */
class CalendarChangeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        runCatching { refreshWidgets(applicationContext) }
        runCatching { enqueue(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE) }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "calendar_change_watch"

        fun watch(context: Context) = enqueue(context, ExistingWorkPolicy.KEEP)

        private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
            val constraints = Constraints.Builder()
                .addContentUriTrigger(CalendarContract.CONTENT_URI, true)
                .setTriggerContentUpdateDelay(2, TimeUnit.SECONDS)
                .setTriggerContentMaxDelay(10, TimeUnit.SECONDS)
                .build()
            val request = OneTimeWorkRequestBuilder<CalendarChangeWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
        }
    }
}

/**
 * Push an update now — call after any write so no widget contradicts the app.
 *
 * Both widgets, each in its own `runCatching`: one failing must not skip the other, and a widget
 * type nobody has placed on a launcher still throws from `updateAll` on some hosts. One function
 * rather than one call per widget at each site, so a new write path can't refresh half of them.
 */
suspend fun refreshWidgets(context: Context) {
    // Before either update: a refresh means the calendar may have changed, and the month widget
    // would otherwise redraw from the neighbour months it warmed before the change.
    MonthWidgetCache.clear()
    runCatching { TodayWidget().updateAll(context) }
    runCatching { MonthWidget().updateAll(context) }
}
