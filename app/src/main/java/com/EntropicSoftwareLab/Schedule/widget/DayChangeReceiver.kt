package com.EntropicSoftwareLab.Schedule.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Redraws the widgets at local midnight and whenever the user changes the device clock or time
 * zone, so neither widget stays stuck on yesterday until the 30-minute [WidgetRefreshWorker] runs.
 */
class DayChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_TIME_CHANGED &&
            action != Intent.ACTION_TIMEZONE_CHANGED &&
            action != ACTION_MIDNIGHT
        ) {
            return
        }
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                try {
                    refreshWidgets(appContext)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Widget refresh failed on $action", e)
                }
                try {
                    scheduleNextMidnight(appContext)
                } catch (e: Exception) {
                    Log.w(TAG, "Scheduling next midnight alarm failed", e)
                }
            } finally {
                pending?.finish()
            }
        }
    }

    companion object {
        private const val TAG = "DayChangeReceiver"

        const val ACTION_MIDNIGHT = "com.EntropicSoftwareLab.Schedule.action.MIDNIGHT"

        /**
         * Arms an inexact while-idle alarm five seconds past the next local midnight. Re-calling
         * replaces the existing alarm because the [PendingIntent] matches.
         */
        fun scheduleNextMidnight(context: Context) {
            val appContext = context.applicationContext
            val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
            val zone = ZoneId.systemDefault()
            val millis = LocalDate.now(zone)
                .plusDays(1)
                .atStartOfDay(zone)
                .plusSeconds(5)
                .toInstant()
                .toEpochMilli()
            val pi = PendingIntent.getBroadcast(
                appContext,
                0,
                Intent(appContext, DayChangeReceiver::class.java).setAction(ACTION_MIDNIGHT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC, millis, pi)
        }
    }
}
