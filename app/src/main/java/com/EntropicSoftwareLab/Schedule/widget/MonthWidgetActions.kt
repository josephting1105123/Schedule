package com.EntropicSoftwareLab.Schedule.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.EntropicSoftwareLab.Schedule.MainActivity
import java.time.LocalDate

/** Per-instance widget state. Two widgets on one launcher browse independently. */
internal object MonthWidgetKeys {
    val OFFSET = intPreferencesKey("month_offset")
    /** The epoch day [OFFSET] was set on — what lets a stale offset expire. See [MonthWidgetState]. */
    val OFFSET_DAY = longPreferencesKey("month_offset_day")
}

/**
 * The intents the widget uses to reach a specific place in the app.
 *
 * There were no deep links at all before this: `MainActivity` had one plain launcher filter and
 * `AppNavHost` builds its own `NavHostController` inside a composable, so nothing outside the
 * process could name a destination. Rather than add URI filters, the extras are read once in
 * `MainActivity` and parked on `AppContainer.pendingWidgetLaunch` — which is what lets the request
 * survive a cold start. See that field for why parking it matters.
 *
 * `SINGLE_TOP` so tapping the widget while the app is already open reuses the instance instead of
 * stacking a second copy of the calendar on top of the first.
 */
internal object WidgetLaunchIntents {

    const val EXTRA_FOCUS_DATE = "com.EntropicSoftwareLab.Schedule.FOCUS_DATE"
    const val EXTRA_ADD_EVENT = "com.EntropicSoftwareLab.Schedule.ADD_EVENT"

    fun openDay(context: Context, date: LocalDate): Intent =
        base(context, "day", date).apply {
            putExtra(EXTRA_FOCUS_DATE, date.toEpochDay())
        }

    /**
     * The day's agenda as a pop-up over the home screen, not the app — see [DayAgendaActivity].
     * Its own task (`taskAffinity=""` in the manifest), so a running app isn't brought forward
     * behind it, and `CLEAR_TASK` so a second tap replaces a pop-up still open rather than stacking.
     */
    fun dayAgenda(context: Context, date: LocalDate): Intent =
        Intent(context, DayAgendaActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            action = "${context.packageName}.WIDGET_DAY_AGENDA"
            // Per-day data URI for the same reason as [base]: PendingIntent reuse ignores extras.
            data = Uri.parse("schedule://widget/agenda/${date.toEpochDay()}")
            putExtra(EXTRA_FOCUS_DATE, date.toEpochDay())
        }

    fun addEvent(context: Context, date: LocalDate): Intent =
        base(context, "add", date).apply {
            putExtra(EXTRA_FOCUS_DATE, date.toEpochDay())
            putExtra(EXTRA_ADD_EVENT, true)
        }

    /**
     * The data URI is load-bearing, not decoration.
     *
     * `PendingIntent` reuse is decided by `Intent.filterEquals`, which compares action, data, type,
     * component and categories — and **ignores extras entirely**. Without a distinct URI per day,
     * all 42 cells would hand the launcher the same intent, the first one cached would win, and
     * every tap would open whichever day happened to be built first.
     */
    private fun base(context: Context, kind: String, date: LocalDate) =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = "${context.packageName}.WIDGET_LAUNCH"
            data = Uri.parse("schedule://widget/$kind/${date.toEpochDay()}")
        }
}
