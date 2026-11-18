package com.EntropicSoftwareLab.Schedule.di

import android.accounts.AccountManager
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.EntropicSoftwareLab.Schedule.data.accounts.AccountPrefsRepository
import com.EntropicSoftwareLab.Schedule.data.accounts.ReadKey
import com.EntropicSoftwareLab.Schedule.data.appearance.EventColorRepository
import com.EntropicSoftwareLab.Schedule.data.birthday.BirthdayRepository
import com.EntropicSoftwareLab.Schedule.data.calendar.CalendarRepository
import com.EntropicSoftwareLab.Schedule.data.calendar.UploadSyncWorker
import com.EntropicSoftwareLab.Schedule.data.settings.SettingsRepository
import com.EntropicSoftwareLab.Schedule.data.task.TaskRepository
import com.EntropicSoftwareLab.Schedule.data.task.TaskSync
import com.EntropicSoftwareLab.Schedule.widget.refreshWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * What a widget tap asked for: show this date, and open the add-event form if [addEvent] is set.
 */
data class WidgetLaunch(val date: LocalDate, val addEvent: Boolean)

/**
 * Hand-rolled dependency graph. Everything is lazy so nothing touches the ContentResolver, disk, or
 * a gigabyte of model weights until a screen actually asks for it.
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    /** Which phone accounts the app uses and what is ticked; every calendar read honours it. */
    val accountPrefsRepository: AccountPrefsRepository by lazy { AccountPrefsRepository(appContext) }

    val calendarRepository: CalendarRepository by lazy {
        CalendarRepository(
            resolver = appContext.contentResolver,
            accountVisible = { account ->
                account in AccountManager.get(appContext).accounts
            },
            // Every write this app makes is pushed to the server now rather than on the sync
            // adapter's own schedule, and the widgets redrawn. requestSync is only queued here, so
            // the write returns at once. UploadSyncWorker then checks the rows really went up and
            // asks again in the background, with backoff, while any are still waiting.
            onWrite = {
                refreshWidgets(appContext)
                runCatching { calendarRepository.requestSync() }
                runCatching { UploadSyncWorker.afterWrite(appContext) }
            },
            accountPrefs = accountPrefsRepository.prefs,
        )
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(appContext).also { repo ->
            // One-time cleanup: wipes a Gemini key and model id saved by builds that had the
            // assistant, so an unused secret doesn't sit in DataStore. Fired on `appScope` (outlives
            // this getter call) and wrapped in `runCatching` — failing to clear a now-unused
            // preference is not worth surfacing anywhere.
            appScope.launch { runCatching { repo.clearLegacyGeminiSettings() } }
        }
    }

    val birthdayRepository: BirthdayRepository by lazy {
        BirthdayRepository(appContext, calendarRepository, accountPrefsRepository)
    }

    /**
     * Tasks — deadlines with a checkbox, stored beside birthdays. They are mirrored to the calendar
     * named Tasks by [taskSync], and every event read skips that calendar, so a task still never
     * shows as an event or a clash. A change to the list kicks off a sync; [appScope] is declared
     * further down, which is safe because the lambda only runs after construction.
     */
    val taskRepository: TaskRepository by lazy {
        TaskRepository(appContext, onChange = { appScope.launch { taskSync.sync() } })
    }

    /**
     * Mirrors tasks to the calendar named Tasks, both ways. That calendar is the go-between with
     * Jarvis, which reads and writes the same one; nothing here talks to Jarvis directly.
     */
    val taskSync: TaskSync by lazy { TaskSync(taskRepository, calendarRepository) }

    /**
     * The colour this app shows for an event, which the provider's 24-colour palette no longer
     * limits. Read by every surface that draws an event, including the widgets.
     */
    val eventColorRepository: EventColorRepository by lazy {
        EventColorRepository(appContext)
    }

    /**
     * Background scope for work that outlives any one screen — the calendar warm-up, for now.
     *
     * `SupervisorJob` so one failed child can't cancel the rest, and never cancelled: it lives as
     * long as the process, which is the same lifetime everything else on this container has.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Keeps asking the accounts to sync for as long as the app is on screen: at once, then every
     * [VISIBLE_SYNC_INTERVAL_MS]. Runs inside `repeatOnLifecycle(STARTED)` in `MainActivity`, so it
     * starts every time the app comes to the front and stops the moment it leaves.
     *
     * Why poll at all: Google Calendar's own app writes to its own store and shows an edit at once,
     * but this app reads the phone's calendar provider, which only Google's sync adapter fills — on
     * its own schedule, often a minute after the edit (reported 2026-10-14). Nothing outside Google
     * can be told when the server changed, so the one lever is asking often while someone is
     * looking. An incremental sync with nothing new is cheap, and none of this runs in the
     * background. It used to be one request on opening, throttled to once a minute, which is why
     * reopening the app straight after an edit usually asked for nothing.
     *
     * Fails quietly without calendar permission — the permission gate is on screen then anyway.
     */
    suspend fun syncWhileVisible() {
        // Birthdays saved while nothing could be written, or deleted in Google since, are put
        // right before the first sync request so a new event goes up with it.
        runCatching { birthdayRepository.reconcile() }
        while (true) {
            // Tasks first, so a task edited since the last pass goes up with this sync request. It
            // never throws; the result is ignored and the next pass tries again.
            runCatching { taskSync.sync() }
            runCatching { calendarRepository.requestSync() }
            delay(VISIBLE_SYNC_INTERVAL_MS)
        }
    }

    private companion object {
        const val VISIBLE_SYNC_INTERVAL_MS = 20_000L
    }

    /**
     * Calendars and their palettes, warmed once so the event editor opens ready rather than filling
     * itself in after the user is already looking at it. See [CalendarDirectory].
     */
    val calendarDirectory: CalendarDirectory by lazy {
        CalendarDirectory(calendarRepository, appScope)
    }

    /**
     * Placed after [appScope] and [calendarDirectory] on purpose: Kotlin initialises in textual
     * order, and this block uses both.
     *
     * When the user changes which accounts are used or ticks something, the warm calendar list is
     * dropped, the widgets are redrawn and a sync is asked for. Android cannot sync one named Google
     * account for a third-party app (see [CalendarRepository.requestSync]), so a change of accounts
     * asks every account to sync. The first value DataStore replays on collection is ignored.
     */
    init {
        appScope.launch {
            // The widgets read the Birthday tick and the hidden birthdays, so those redraw them; only
            // a change to which accounts or calendars are read invalidates the directory and syncs.
            var seen: Triple<ReadKey, Boolean, Set<String>>? = null
            accountPrefsRepository.prefs
                .map { Triple(it.readKey, it.birthdaysShown, it.hiddenBirthdayKeys) }
                .distinctUntilChanged()
                .collect { now ->
                    val before = seen
                    seen = now
                    if (before == null) return@collect // the value DataStore replays on collection
                    if (before.first != now.first) {
                        calendarDirectory.invalidate()
                        runCatching { calendarRepository.requestSync() }
                    }
                    runCatching { refreshWidgets(appContext) }
                }
        }
    }

    /**
     * "Open Today at this date", set by tapping a day on Month or Week (or on the month widget) and
     * cleared by the Today screen once its pager is there.
     *
     * App-scoped rather than carried as a nav argument: the Today tab is reached with
     * `restoreState = true` from the bottom bar, so a route argument would fight the tab restore.
     *
     * **State, not an event.** It was a `SharedFlow` with no replay, relayed through Today's
     * ViewModel and then through a second one-shot to the screen; a tap fires while navigating *to*
     * Today, when the screen isn't composed, and the request went missing on the way — Today simply
     * reopened on whichever day it last showed. Held here, it waits for `DashboardScreen` to read it
     * directly, move the pager, and set it back to null.
     */
    val dayFocus = MutableStateFlow<LocalDate?>(null)

    /**
     * The day Today's pager has settled on, so the "+" button can add an event to the day being
     * looked at rather than to the real today. Written by `DashboardScreen`, read by `AppNavHost`,
     * which composes the button and shares no ViewModel with the pager. Null until Today has shown.
     */
    val todayShownDate = MutableStateFlow<LocalDate?>(null)

    /**
     * A tap on the month widget, parked until the navigation graph is actually on screen.
     *
     * It cannot be delivered directly: `AppNavHost` may not be composed yet (a cold start, or the
     * calendar permission prompt still showing), so a tap handed to a `NavHost` that doesn't exist
     * would vanish. Parked here it simply waits, and fires the moment `AppNavHost` first composes.
     *
     * App-scoped for the same reason [dayFocus] is: a `MutableStateFlow` here outlives every
     * ViewModel and every recomposition. `AppNavHost` sets it back to null once it has acted, so a
     * configuration change doesn't reopen the same day a second time.
     */
    val pendingWidgetLaunch = MutableStateFlow<WidgetLaunch?>(null)

    /**
     * Read fresh on every access rather than cached: the device timezone changes when the user
     * travels, and a stale zone silently shifts every event on screen.
     */
    val zone: ZoneId get() = ZoneId.systemDefault()
}

/** Makes the container reachable from composables without threading it through every signature. */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}
