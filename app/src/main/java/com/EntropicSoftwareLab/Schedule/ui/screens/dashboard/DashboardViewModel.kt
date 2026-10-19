package com.EntropicSoftwareLab.Schedule.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.EntropicSoftwareLab.Schedule.data.birthday.Birthday
import com.EntropicSoftwareLab.Schedule.data.appearance.BrandColorAssignment
import com.EntropicSoftwareLab.Schedule.data.appearance.EventColorRepository
import com.EntropicSoftwareLab.Schedule.ui.theme.EventColorPalette
import com.EntropicSoftwareLab.Schedule.data.birthday.BirthdayRepository
import com.EntropicSoftwareLab.Schedule.data.calendar.CalendarInfo
import com.EntropicSoftwareLab.Schedule.data.calendar.CalendarRepository
import com.EntropicSoftwareLab.Schedule.data.calendar.DayStatus
import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance
import com.EntropicSoftwareLab.Schedule.data.task.Task
import com.EntropicSoftwareLab.Schedule.data.task.TaskRepository
import com.EntropicSoftwareLab.Schedule.di.AppContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** The hour the user tapped on the grid, driving the read-only details sheet. */
data class SelectedHour(val date: LocalDate, val hour: Int)

/** Where to move the pager to, and whether that's a smooth scroll or an instant jump. */
data class PageFocusRequest(val page: Int, val animate: Boolean)

data class DashboardUiState(
    /**
     * Normally fixed at creation, so the pager's page [DashboardViewModel.DAY_WINDOW_RADIUS] maps to
     * this date and the maths never shifts. [DashboardViewModel.focusDate] is the one path that moves
     * it — a day tapped in the Month grid can be years away, well outside the pager's own window, so
     * the window re-centres on it rather than failing to reach it at all.
     */
    val anchorDate: LocalDate = LocalDate.now(),
    val visibleDate: LocalDate = LocalDate.now(),
    val eventsByDate: Map<LocalDate, List<EventInstance>> = emptyMap(),
    val calendarsById: Map<Long, CalendarInfo> = emptyMap(),
    val loadingDates: Set<LocalDate> = emptySet(),
    /** Every birthday, unfiltered — resolved onto a specific date only where it's actually asked for. */
    val birthdays: List<Birthday> = emptyList(),
    /** Every task, unfiltered — the whole list is small and each day page filters it for itself. */
    val tasks: List<Task> = emptyList(),
    /**
     * Event id to the colour this app shows for it — the user's own pick where there is one, and a
     * brand colour assigned per day where there isn't. Every surface that draws an event resolves
     * through [com.EntropicSoftwareLab.Schedule.data.appearance.EventColorResolver] with this.
     *
     * Derived, not stored: only the picks are persisted. See [BrandColorAssignment].
     */
    val eventColors: Map<Long, Int> = emptyMap(),
    val error: String? = null,
    val selectedHour: SelectedHour? = null,
    /** A pull-to-refresh is in flight; drives the spinner. */
    val refreshing: Boolean = false,
) {
    /**
     * The day's events less the ones that are birthdays — those are listed under Birthdays, and
     * a birthday synced to Google comes back from the provider as an all-day event too.
     */
    fun eventsOn(date: LocalDate): List<EventInstance> =
        Birthday.withoutBirthdayEvents(eventsByDate[date].orEmpty(), birthdays)
    fun timedEventsOn(date: LocalDate): List<EventInstance> = eventsOn(date).filterNot { it.allDay }
    fun allDayEventsOn(date: LocalDate): List<EventInstance> = eventsOn(date).filter { it.allDay }

    /** Birthdays landing on [date] this year (or whichever year [date] itself falls in). */
    fun birthdaysOn(date: LocalDate): List<Birthday> =
        Birthday.byDate(birthdays, date, date.plusDays(1))[date].orEmpty()

    /** Tasks due on [date], in display order. */
    fun tasksOn(date: LocalDate): List<Task> = Task.on(tasks, date)

    /**
     * Unfinished tasks whose deadline has already passed, for the actual current day only.
     *
     * A day page other than today gets an empty list: "overdue" is a statement about now, and
     * printing it on a page the user swiped back to would describe a deadline as missed while
     * standing on the day it was still due.
     */
    fun overdueTasksOn(date: LocalDate, today: LocalDate): List<Task> =
        if (date == today) Task.overdueAsOf(tasks, today) else emptyList()

    /**
     * True until a day's events have actually arrived. An absent entry means "not fetched yet", not
     * "empty" — without the distinction a page would flash "Nothing scheduled" before it loads.
     */
    fun isLoading(date: LocalDate): Boolean = date !in eventsByDate

    /** What's on at [nowMillis] and what starts next, on the actual current day, for the top card. */
    fun statusAt(nowMillis: Long): DayStatus = DayStatus.of(timedEventsOn(LocalDate.now()), nowMillis)

    /** Calendar id to colour, for the grid's per-event colour assignment. */
    val calendarColors: Map<Long, Int> get() = calendarsById.mapValues { it.value.color }
}

/**
 * The colours [BrandColorAssignment] hands out to events nobody has coloured.
 *
 * Same order the picker shows — [EventColorPalette.assignmentOrder] is [EventColorPalette.brand]
 * itself now that brand is six distinct hues rather than a light-to-deep ramp of one.
 */
private val BRAND_PALETTE: List<Int> = EventColorPalette.assignmentOrder.map { it.argb }

/**
 * Backs a page-per-day Today screen. Each day's events are cached in [DashboardUiState.eventsByDate]
 * so a swipe shows the neighbour instantly; the visible day and its immediate neighbours are always
 * kept warm, and the cache is trimmed so it can't grow without bound.
 */
class DashboardViewModel(
    private val repository: CalendarRepository,
    private val birthdayRepository: BirthdayRepository,
    private val taskRepository: TaskRepository,
    private val eventColorRepository: EventColorRepository,
    private val zoneProvider: () -> ZoneId,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    /**
     * Where the pager should move next, held until the screen has acted on it — see [focusDate].
     *
     * State, not a one-shot event. It used to be a `SharedFlow` with no replay, and a tap on a day in
     * Month or Week emits *while navigating to Today*: the Today screen isn't composed at that moment,
     * so nothing was collecting and the request was dropped on the floor. Today then reopened on
     * whichever page it last showed, which read as the tap going to the wrong date. Parked here, the
     * request waits for the screen to compose, which moves the pager and then calls [consumeFocus].
     */
    private val _pendingFocus = MutableStateFlow<PageFocusRequest?>(null)
    val pendingFocus: StateFlow<PageFocusRequest?> = _pendingFocus.asStateFlow()

    /** The screen has moved the pager to [request]; clear it so it isn't replayed. */
    fun consumeFocus(request: PageFocusRequest) {
        _pendingFocus.compareAndSet(request, null)
    }

    init {
        viewModelScope.launch {
            // Emits immediately, then again on every provider change — an in-app insert, a Google
            // sync landing, or an edit made in another app. Each refresh rebuilds the visible window
            // from scratch rather than patching it, so no page can survive showing stale rows.
            repository.observeChanges().collect { onCalendarChanged() }
        }
        viewModelScope.launch {
            birthdayRepository.birthdays.collect { list -> _state.update { it.copy(birthdays = list) } }
        }
        viewModelScope.launch {
            taskRepository.tasks.collect { list -> _state.update { it.copy(tasks = list) } }
        }
        // Collected rather than read once: recolouring an event in the editor must repaint the grid
        // and the agenda behind it on the way back, and no provider change fires for a colour this
        // app stores itself.
        viewModelScope.launch {
            eventColorRepository.overrides.collect { map ->
                colorOverrides = map
                _state.update { it.copy(eventColors = recolour(it.eventsByDate, map)) }
            }
        }
    }

    /** The user's own picks. Kept aside so a newly loaded day can be recoloured without re-reading. */
    private var colorOverrides: Map<Long, Int> = emptyMap()

    /**
     * Picks plus per-day brand assignment, recomputed whenever either input changes.
     *
     * Per day rather than across the cache: the rule is that events *sharing a day* look different,
     * and assigning across a week would run out of colours immediately.
     */
    private fun recolour(
        eventsByDate: Map<LocalDate, List<EventInstance>>,
        overrides: Map<Long, Int>,
    ): Map<Long, Int> = BrandColorAssignment.forDates(eventsByDate, overrides, BRAND_PALETTE)

    /** Called by the pager when a new day settles into view. */
    fun showDate(date: LocalDate) {
        _state.update { it.copy(visibleDate = date) }
        ensureLoaded(date)
        ensureLoaded(date.minusDays(1))
        ensureLoaded(date.plusDays(1))
        trimCache(date)
    }

    /**
     * Called while the pager is still moving, for whichever page it is heading to.
     *
     * Loading only on settle meant a fast fling landed on a page nobody had asked for yet, and the
     * user watched "Reading your calendar…" on a day the app could have fetched while their finger
     * was still down. This deliberately does not touch `visibleDate` — the day on screen is still
     * the settled one — and `ensureLoaded` already ignores anything cached or in flight, so calling
     * it on every drag frame costs a set lookup.
     */
    fun prefetch(date: LocalDate) {
        ensureLoaded(date)
        ensureLoaded(date.minusDays(1))
        ensureLoaded(date.plusDays(1))
    }

    /**
     * Moves the pager to [date].
     *
     * When it's within the pager's ±[DAY_WINDOW_RADIUS] window the request just names the page and
     * the screen scrolls smoothly. Outside it — a day tapped in the Month grid can be anywhere across
     * ±24 months — the anchor re-centres on [date] first, so the window always reaches it, and the
     * screen jumps rather than animating: there is no continuous path of pages between the old window
     * and the new one for a scroll to travel across.
     */
    /**
     * The pager page showing [date], re-centring the window on it first when it lies outside.
     *
     * For [AppContainer.dayFocus][com.EntropicSoftwareLab.Schedule.di.AppContainer.dayFocus]: the
     * screen reads that itself and moves its own pager, so there is no second hand-off to lose.
     */
    fun pageFor(date: LocalDate): Int {
        val daysFromAnchor = ChronoUnit.DAYS.between(_state.value.anchorDate, date)
        val page = if (abs(daysFromAnchor) <= DAY_WINDOW_RADIUS) {
            (DAY_WINDOW_RADIUS + daysFromAnchor).toInt()
        } else {
            _state.update { it.copy(anchorDate = date) }
            DAY_WINDOW_RADIUS
        }
        _state.update { it.copy(visibleDate = date) }
        return page
    }

    fun focusDate(date: LocalDate, animate: Boolean = true) {
        val current = _state.value
        val daysFromAnchor = ChronoUnit.DAYS.between(current.anchorDate, date)
        if (abs(daysFromAnchor) <= DAY_WINDOW_RADIUS) {
            _pendingFocus.value =
                PageFocusRequest((DAY_WINDOW_RADIUS + daysFromAnchor).toInt(), animate = animate)
        } else {
            _state.update { it.copy(anchorDate = date) }
            _pendingFocus.value = PageFocusRequest(DAY_WINDOW_RADIUS, animate = false)
        }
        // The visible date follows at once, so anything reading it (the Today pill, "Up next")
        // doesn't show the old day for the frames before the pager settles.
        _state.update { it.copy(visibleDate = date) }
    }

    /**
     * Ticks or unticks a task.
     *
     * Fire-and-forget with no optimistic state write: the repository's own flow is the single source
     * of truth for the list and re-emits within a frame, so writing the new value here as well would
     * put a second copy on screen that a failed write could leave permanently wrong.
     */
    fun setTaskDone(id: Long, done: Boolean) {
        viewModelScope.launch {
            runCatching { taskRepository.setDone(id, done) }
                .onFailure { e ->
                    _state.update { it.copy(error = e.message ?: "Couldn't update that task.") }
                }
        }
    }

    fun selectHour(date: LocalDate, hour: Int) =
        _state.update { it.copy(selectedHour = SelectedHour(date, hour)) }

    fun dismissHourSheet() = _state.update { it.copy(selectedHour = null) }

    /** Fetches [date] unless it's already cached or a fetch is in flight. */
    private fun ensureLoaded(date: LocalDate) {
        val current = _state.value
        if (date in current.eventsByDate || date in current.loadingDates) return
        _state.update { it.copy(loadingDates = it.loadingDates + date) }
        viewModelScope.launch {
            try {
                val events = repository.eventsOnDay(date, zoneProvider())
                _state.update {
                    val merged = it.eventsByDate + (date to events)
                    it.copy(
                        eventsByDate = merged,
                        eventColors = recolour(merged, colorOverrides),
                        loadingDates = it.loadingDates - date,
                        error = null,
                    )
                }
            } catch (e: SecurityException) {
                _state.update {
                    it.copy(loadingDates = it.loadingDates - date, error = "Calendar access was revoked.")
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loadingDates = it.loadingDates - date,
                        error = e.message ?: "Couldn't read the calendar.",
                    )
                }
            }
        }
    }

    private var followUpSyncs: Job? = null

    /**
     * Pull-to-refresh: ask the accounts to sync, then re-read the visible days at once so the user
     * sees the spinner end on current local data. What the sync brings back lands later through
     * [CalendarRepository.observeChanges], the same way any background sync does.
     *
     * The spinner is held for at least [MIN_REFRESH_MILLIS]. The local re-read takes a few tens of
     * milliseconds, so without a floor the spinner blinked and vanished and the pull read as having
     * done nothing — reported on a device, 2026-09-28.
     */
    fun refresh() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true) }
        // Ask again a few times after the pull. One request answers only with what Google's server
        // holds at that instant; an edit made a moment ago in Google Calendar is often still on its
        // way up, so the sync finds nothing and the change waited for Google's own next sync, about
        // a minute later (reported 2026-10-14). Whatever a later sync writes arrives through
        // observeChanges like any other. A new pull restarts the sequence rather than stacking.
        followUpSyncs?.cancel()
        followUpSyncs = viewModelScope.launch {
            for (gap in FOLLOW_UP_SYNC_GAPS_MILLIS) {
                delay(gap)
                runCatching { repository.requestSync() }
            }
        }
        viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            try {
                runCatching { repository.requestSync() }
                onCalendarChanged()
                delay((MIN_REFRESH_MILLIS - (System.currentTimeMillis() - startedAt)).coerceAtLeast(0L))
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    private suspend fun onCalendarChanged() {
        val zone = zoneProvider()
        try {
            val calendars = repository.listCalendars().associateBy { it.id }
            // Read the visible day after the calendar query rather than before it: these are four
            // round-trips to the provider, and the user can swipe several days during them.
            val center = _state.value.visibleDate
            val window = (-1..1).map { center.plusDays(it.toLong()) }
            val fresh = window.associateWith { repository.eventsOnDay(it, zone) }
            _state.update {
                it.copy(
                    calendarsById = calendars,
                    // Replace, don't merge: any page outside the window is dropped and refetched
                    // when it next settles, so nothing stale lingers behind a swipe.
                    eventsByDate = fresh,
                    eventColors = recolour(fresh, colorOverrides),
                    loadingDates = emptySet(),
                    error = null,
                )
            }
            // If the user swiped past the window while those queries ran, the page they're on now
            // was just evicted and nothing else would fetch it — the pager only reloads on settle.
            showDate(_state.value.visibleDate)
        } catch (e: SecurityException) {
            _state.update { it.copy(error = "Calendar access was revoked.") }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "Couldn't read the calendar.") }
        }
    }

    /** Keeps only the visible day and three either side, so scrolling far never piles up memory. */
    private fun trimCache(center: LocalDate) {
        _state.update { state ->
            // Wider than the ±3 it was: prefetch now reaches ±2 around a page the pager is only
            // heading towards, and trimming at ±3 on settle would evict what was just fetched.
            val keep = (-4..4).map { center.plusDays(it.toLong()) }.toSet()
            state.copy(eventsByDate = state.eventsByDate.filterKeys { it in keep })
        }
    }

    companion object {
        /** A second: long enough to register as "it refreshed", short enough not to feel slow. */
        private const val MIN_REFRESH_MILLIS = 1_000L

        /** Follow-up sync requests after a pull: at 4s, 10s and 20s. */
        private val FOLLOW_UP_SYNC_GAPS_MILLIS = listOf(4_000L, 6_000L, 10_000L)

        /**
         * How far the day pager reaches either side of the anchor. A year covers any plausible swipe
         * while keeping the page count small enough to be an ordinary bounded pager.
         */
        const val DAY_WINDOW_RADIUS = 365

        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DashboardViewModel(
                    repository = container.calendarRepository,
                    birthdayRepository = container.birthdayRepository,
                    taskRepository = container.taskRepository,
                    eventColorRepository = container.eventColorRepository,
                ) { container.zone } as T
        }
    }
}
