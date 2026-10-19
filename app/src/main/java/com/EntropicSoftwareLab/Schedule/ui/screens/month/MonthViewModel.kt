package com.EntropicSoftwareLab.Schedule.ui.screens.month

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.EntropicSoftwareLab.Schedule.data.birthday.Birthday
import com.EntropicSoftwareLab.Schedule.data.appearance.BrandColorAssignment
import com.EntropicSoftwareLab.Schedule.data.appearance.EventColorRepository
import com.EntropicSoftwareLab.Schedule.ui.theme.EventColorPalette
import com.EntropicSoftwareLab.Schedule.data.birthday.BirthdayRepository
import com.EntropicSoftwareLab.Schedule.data.calendar.CalendarRepository
import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance
import com.EntropicSoftwareLab.Schedule.data.calendar.MonthChips
import com.EntropicSoftwareLab.Schedule.data.calendar.localDateToUtcMidnight
import com.EntropicSoftwareLab.Schedule.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * How many months of events [MonthViewModel] keeps in memory at once.
 *
 * The steady state is smaller than this — three required (the window) plus up to two prefetching
 * on each side of wherever the user is — but a session that swipes back and forth can leave a
 * previous centre's prefetch still warm alongside the current one. The cap just stops that growing
 * without bound over a long session; whatever it evicts is cheap to re-fetch if it's ever needed
 * again.
 */
private const val MAX_CACHED_MONTHS = 9

/**
 * How many months either side of the one on show are published to the grid: the shown month's
 * neighbours are composed off screen, and their spill-over days reach one month further.
 */
private const val PUBLISHED_RADIUS = 2L

/**
 * The colours [BrandColorAssignment] hands out to events nobody has coloured.
 *
 * Same order the picker shows — [EventColorPalette.assignmentOrder] is [EventColorPalette.brand]
 * itself now that brand is six distinct hues rather than a light-to-deep ramp of one.
 */
private val BRAND_PALETTE: List<Int> = EventColorPalette.assignmentOrder.map { it.argb }

/** Which of the two calendars the Month page is showing. Events and birthdays never mix. */
enum class CalendarMode { Events, Birthdays }

/** One day in the agenda: the date, plus events or birthdays — never both at once. */
data class AgendaDay(
    val date: LocalDate,
    val events: List<EventInstance> = emptyList(),
    val birthdays: List<Birthday> = emptyList(),
)

data class MonthUiState(
    /** The month the grid is showing. Only the grid changes it — see [MonthViewModel.onGridSwipedTo]. */
    val visibleMonth: YearMonth = YearMonth.now(),
    val selectedDate: LocalDate = LocalDate.now(),
    val mode: CalendarMode = CalendarMode.Events,
    /** Event id to its colour: the user's pick, else a brand colour assigned per day. */
    val eventColors: Map<Long, Int> = emptyMap(),
    /**
     * Occurrences grouped by every local day they touch, for the grid's chips.
     *
     * Spans the whole loaded window rather than [visibleMonth], because the days spilling in from
     * the neighbouring months carry their events on the grid too.
     */
    val eventsByDate: Map<LocalDate, List<EventInstance>> = emptyMap(),
    /** Birthdays, resolved onto real dates, over the same window [eventsByDate] covers. */
    val birthdaysByDate: Map<LocalDate, List<Birthday>> = emptyMap(),
    /**
     * Birthday chip id (`-birthday.id`) to a brand colour, assigned per day by the same rule events
     * use, so two birthdays on one day differ. Birthdays have no calendar colour of their own, and
     * without this every chip was drawn colourless.
     */
    val birthdayColors: Map<Long, Int> = emptyMap(),
    /**
     * Days of [visibleMonth] with something on them, ascending, plus the selected day and today.
     *
     * Bounded to the visible month on purpose: the agenda is the month's detail view, so scrolling
     * it can reach every day of the month and no day outside it. Changing month is the grid's job.
     */
    val agenda: List<AgendaDay> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
) {
    /**
     * What the grid's chip layer actually draws.
     *
     * In birthday mode each birthday is projected into a synthetic single-day all-day
     * [EventInstance] so the grid can reuse [MonthChips]' well-tested packing logic rather than a
     * birthday-specific fork of it. `eventId = -birthday.id` is the sentinel: a real provider id is
     * never negative, so nothing downstream — the edit route included — can mistake a birthday chip
     * for an editable calendar event. Events mode never sees a birthday and birthday mode never sees
     * an event, by construction.
     */
    /** The colours for whatever [gridEventsByDate] holds in the current [mode]. */
    val chipColors: Map<Long, Int>
        get() = when (mode) {
            CalendarMode.Events -> eventColors
            CalendarMode.Birthdays -> birthdayColors
        }

    val gridEventsByDate: Map<LocalDate, List<EventInstance>>
        get() = when (mode) {
            CalendarMode.Events -> eventsByDate
            CalendarMode.Birthdays -> birthdaysByDate.mapValues { (date, list) ->
                list.map { it.toChipEvent(date) }
            }
        }
}

class MonthViewModel(
    private val repository: CalendarRepository,
    private val birthdayRepository: BirthdayRepository,
    private val eventColorRepository: EventColorRepository,
    private val zoneProvider: () -> ZoneId,
) : ViewModel() {

    private val _state = MutableStateFlow(MonthUiState())
    val state: StateFlow<MonthUiState> = _state.asStateFlow()

    /**
     * One-shot "put this date under the agenda's centre line" commands.
     *
     * Deliberately an event and not state: driving scrolling off state that the scroll itself could
     * also write would make the two chase each other. Only the calendar grid — a tap or a month
     * swipe — emits here; reading down the agenda never does.
     */
    private val _agendaScrollRequests = MutableSharedFlow<LocalDate>(extraBufferCapacity = 1)
    val agendaScrollRequests: SharedFlow<LocalDate> = _agendaScrollRequests.asSharedFlow()

    /** Middle month of the loaded event window; the window itself spans this ±1 month. */
    private var windowCenter: YearMonth = YearMonth.now()

    /**
     * Events already fetched, keyed by the single month they fall in.
     *
     * Keying by month rather than caching the combined three-month window is what lets a swipe
     * reuse two-thirds of what it needs: the month just left already pulled in both its neighbours,
     * so moving one month over is missing only the new far edge. [reload] also warms one month
     * further out on each side in the background, so by the time the user is looking at a month,
     * its own neighbours' neighbours are usually already here too — see its doc for why that's what
     * "already finished loading" requires.
     */
    private val monthCache = LinkedHashMap<YearMonth, List<EventInstance>>()

    /** Every birthday, unfiltered — the source [birthdaysForWindow] resolves onto real dates. */
    private var allBirthdays: List<Birthday> = emptyList()

    init {
        // observeChanges() emits once immediately, so this covers the initial load too. Every
        // emission after that means the provider changed under us, which makes every cached month
        // suspect — there's no cheap way to know which one, so all of them go.
        viewModelScope.launch {
            repository.observeChanges().collect {
                monthCache.clear()
                reload()
            }
        }
        // No provider change fires when this app recolours an event, so the chips repaint off this
        // rather than off observeChanges().
        viewModelScope.launch {
            eventColorRepository.overrides.collect { map ->
                colorOverrides = map
                _state.update { it.copy(eventColors = recolour(it.eventsByDate)) }
            }
        }
        // Birthdays are a small in-memory list, not a paged fetch — recomputing the window is cheap
        // enough to do synchronously on every change, unlike the event cache above.
        viewModelScope.launch {
            birthdayRepository.birthdays.collect { list ->
                allBirthdays = list
                val byDate = birthdaysForWindow(windowCenter)
                _state.update {
                    it.copy(
                        birthdaysByDate = byDate,
                        birthdayColors = birthdayColours(byDate),
                        agenda = buildAgenda(it.eventsByDate, byDate, it.visibleMonth, it.selectedDate, it.mode),
                    )
                }
            }
        }
    }

    /** Swaps which calendar the page shows. The agenda rebuilds against whichever is now current. */
    fun setMode(mode: CalendarMode) {
        _state.update { current ->
            if (current.mode == mode) return@update current
            current.copy(
                mode = mode,
                agenda = buildAgenda(
                    current.eventsByDate, current.birthdaysByDate, current.visibleMonth,
                    current.selectedDate, mode,
                ),
            )
        }
    }

    /** A day cell was tapped. Moves the agenda to match. */
    fun selectDate(date: LocalDate) {
        showMonth(YearMonth.from(date), date)
        _agendaScrollRequests.tryEmit(date)
    }

    /**
     * The grid was swiped to [month]. No-op when it is already the month on show — that is the grid
     * catching up to a tap, not the user asking to go somewhere.
     */
    fun onGridSwipedTo(month: YearMonth) {
        val current = _state.value
        if (month == current.visibleMonth) return
        // Land on the month's first day that actually has something on it, so a swipe doesn't dump
        // the user on an empty 1st when the month's content starts on the 12th.
        selectDate(landingDay(contentDates(current, month), month, forward = true))
    }

    /**
     * The agenda was dragged past the edge of the visible month — [forward] true to continue into
     * next month, false for the previous one.
     *
     * Lands on the first day with content going forward, the last going back — see [landingDay] —
     * rather than [onGridSwipedTo]'s "first day with something on it" either way: the user is
     * reading chronologically past the edge of what's on screen, so continuing anywhere but the
     * direction they were dragging towards would read as a skip, not a continuation.
     */
    fun continueAgenda(forward: Boolean) {
        val current = _state.value
        val month = current.visibleMonth.let { if (forward) it.plusMonths(1) else it.minusMonths(1) }
        selectDate(landingDay(contentDates(current, month), month, forward))
    }

    private fun contentDates(state: MonthUiState, month: YearMonth): Set<LocalDate> = when (state.mode) {
        CalendarMode.Events -> state.eventsByDate.keys
        CalendarMode.Birthdays -> state.birthdaysByDate.keys
    }

    /** Puts [month] on the grid with [date] selected, reloading the window if it moved. */
    private fun showMonth(month: YearMonth, date: LocalDate) {
        val windowChanged = month != windowCenter
        if (windowChanged) windowCenter = month
        val birthdaysByDate = birthdaysForWindow(windowCenter)
        _state.update {
            it.copy(
                visibleMonth = month,
                selectedDate = date,
                birthdaysByDate = birthdaysByDate,
                birthdayColors = birthdayColours(birthdaysByDate),
                // Rebuilt rather than patched: the agenda belongs to the month, so a month change
                // replaces it wholesale, and buildAgenda already forces the selected day in even
                // when nothing is on it.
                agenda = buildAgenda(it.eventsByDate, birthdaysByDate, month, date, it.mode),
            )
        }
        if (windowChanged) {
            viewModelScope.launch { reload() }
        }
    }

    /** [allBirthdays] resolved onto real dates over the same ±[PUBLISHED_RADIUS] months as events. */
    private fun birthdaysForWindow(center: YearMonth): Map<LocalDate, List<Birthday>> =
        Birthday.byDate(
            allBirthdays,
            center.minusMonths(PUBLISHED_RADIUS).atDay(1),
            center.plusMonths(PUBLISHED_RADIUS + 1).atDay(1),
        )

    /**
     * Loads whatever [windowCenter]'s three-month window is still missing, publishes it, then warms
     * the cache one month further out on each side in the background.
     *
     * A swipe rarely needs a real fetch: the month just left already pulled in both its neighbours
     * as part of its own window, so moving one month over leaves only the new far edge missing. The
     * background step is what keeps that true for the *next* swipe too — while the user is looking
     * at August, October and June quietly start loading, so a swipe either way lands on something
     * already there instead of a fresh query.
     */
    private suspend fun reload() {
        val center = windowCenter
        val required = listOf(center.minusMonths(1), center, center.plusMonths(1))
        if (!ensureLoaded(required, center)) return
        prefetch(center.minusMonths(2))
        prefetch(center.plusMonths(2))
    }

    /**
     * Warms [month] into the cache if it isn't already there, and republishes if it lands inside
     * what's on show. Best-effort — see call site.
     *
     * The republish is what makes this more than a cache warm-up: the grid keeps the months either
     * side composed, and their own spill-over days belong to *these* months, so until this lands
     * those days of a pre-built neighbour would slide in bare.
     */
    private fun prefetch(month: YearMonth) {
        if (monthCache.containsKey(month)) return
        viewModelScope.launch {
            // A failure here just means the month gets fetched for real if the user actually swipes
            // to it, same as before prefetching existed — nothing user-facing to report.
            val loaded = runCatching {
                monthCache[month] = repository.eventsInRange(
                    startDate = month.atDay(1),
                    endDateExclusive = month.plusMonths(1).atDay(1),
                    zone = zoneProvider(),
                )
            }.isSuccess
            trimCache()
            val center = windowCenter
            if (loaded && abs(ChronoUnit.MONTHS.between(center, month)) <= PUBLISHED_RADIUS) {
                publish(center)
            }
        }
    }

    /**
     * Fetches whichever of [months] aren't already cached, then republishes [center]'s window.
     *
     * Takes [center] rather than reading [windowCenter], and checks it hasn't moved on before
     * publishing: a slow fetch that started for a month the user has since swiped past shouldn't
     * overwrite what's now on screen with a window that's no longer the one showing.
     */
    private suspend fun ensureLoaded(months: List<YearMonth>, center: YearMonth): Boolean {
        val missing = months.filterNot { monthCache.containsKey(it) }
        if (missing.isNotEmpty()) {
            try {
                val zone = zoneProvider()
                for (month in missing) {
                    monthCache[month] = repository.eventsInRange(
                        startDate = month.atDay(1),
                        endDateExclusive = month.plusMonths(1).atDay(1),
                        zone = zone,
                    )
                }
            } catch (e: SecurityException) {
                if (windowCenter == center) {
                    _state.update { it.copy(loading = false, error = "Calendar access was revoked.") }
                }
                return false
            } catch (e: Exception) {
                if (windowCenter == center) {
                    _state.update { it.copy(loading = false, error = e.message ?: "Couldn't read the calendar.") }
                }
                return false
            }
            trimCache()
        }
        if (windowCenter != center) return true
        publish(center)
        return true
    }

    /**
     * Rebuilds [MonthUiState.eventsByDate]/[MonthUiState.agenda] from the cache around [center].
     *
     * Publishes ±[PUBLISHED_RADIUS] months, not the ±1 [ensureLoaded] waits for: the grid keeps the
     * previous and next months composed, and the days those spill in from either side fall two
     * months out. Whatever of that outer ring isn't cached yet is simply absent until [prefetch]
     * lands it and publishes again.
     *
     * The grouping and colouring run on [Dispatchers.Default]. Five months of occurrences is cheap,
     * but it was running on the main thread in the frames right after a swipe settled, which is
     * exactly where a dropped frame is noticed.
     */
    private suspend fun publish(center: YearMonth) {
        val months = (-PUBLISHED_RADIUS..PUBLISHED_RADIUS).map { center.plusMonths(it) }
        val events = months.flatMap { monthCache[it].orEmpty() }
        val zone = zoneProvider()
        val overrides = colorOverrides
        val (byDate, colours) = withContext(Dispatchers.Default) {
            // A multi-day event has to appear on every day it covers, not just the day it starts. It
            // also sits in both months' cache entries when it crosses a boundary, so collapse those.
            val grouped = MonthChips.byDate(events.distinctBy { it.instanceId }, zone)
            grouped to BrandColorAssignment.forDates(grouped, overrides, BRAND_PALETTE)
        }
        // The user may have swiped on while that ran; a stale window must not overwrite theirs.
        if (windowCenter != center) return
        _state.update {
            it.copy(
                eventsByDate = byDate,
                eventColors = colours,
                agenda = buildAgenda(byDate, it.birthdaysByDate, it.visibleMonth, it.selectedDate, it.mode),
                loading = false,
                error = null,
            )
        }
    }

    /** The user's own picks. Held aside so a newly published window can be recoloured from them. */
    private var colorOverrides: Map<Long, Int> = emptyMap()

    /**
     * Picks, plus a brand colour for every event nobody coloured.
     *
     * Fed the whole loaded window rather than one month: [BrandColorAssignment.forDates] walks dates
     * in order and never recolours an event it has already placed, which is what keeps a multi-day
     * band one colour where it crosses a month boundary.
     */
    private fun birthdayColours(byDate: Map<LocalDate, List<Birthday>>): Map<Long, Int> =
        BrandColorAssignment.forDates(
            byDate.mapValues { (date, list) -> list.map { it.toChipEvent(date) } },
            emptyMap(),
            BRAND_PALETTE,
        )

    private fun recolour(byDate: Map<LocalDate, List<EventInstance>>): Map<Long, Int> =
        BrandColorAssignment.forDates(byDate, colorOverrides, BRAND_PALETTE)

    /** Evicts whatever's farthest from [windowCenter] until the cache is back under its cap. */
    private fun trimCache() {
        while (monthCache.size > MAX_CACHED_MONTHS) {
            val farthest = monthCache.keys
                .maxByOrNull { abs(ChronoUnit.MONTHS.between(windowCenter, it)) }
                ?: return
            monthCache.remove(farthest)
        }
    }

    companion object {
        /**
         * The days of [month] worth listing: those with content in [mode], plus [selectedDate] and
         * today when they fall inside it — the agenda needs something to centre on, and an empty
         * today that silently vanishes reads as a bug.
         *
         * [eventsByDate]/[birthdaysByDate] cover a wider window than [month]; everything outside it
         * is dropped here, which is what stops the agenda scrolling into a month the grid isn't
         * showing. Exactly one of [AgendaDay.events]/[AgendaDay.birthdays] is ever populated, per
         * [mode] — events mode never carries a birthday and birthday mode never carries an event.
         */
        fun buildAgenda(
            eventsByDate: Map<LocalDate, List<EventInstance>>,
            birthdaysByDate: Map<LocalDate, List<Birthday>>,
            month: YearMonth,
            selectedDate: LocalDate,
            mode: CalendarMode,
            today: LocalDate = LocalDate.now(),
        ): List<AgendaDay> {
            val dates = sortedSetOf<LocalDate>()
            when (mode) {
                CalendarMode.Events -> eventsByDate.keys.filterTo(dates) { YearMonth.from(it) == month }
                CalendarMode.Birthdays -> birthdaysByDate.keys.filterTo(dates) { YearMonth.from(it) == month }
            }
            if (YearMonth.from(selectedDate) == month) dates += selectedDate
            if (YearMonth.from(today) == month) dates += today
            // An empty month still needs one row, or the agenda is a blank panel with no scale.
            if (dates.isEmpty()) dates += month.atDay(1)
            return dates.map { date ->
                when (mode) {
                    CalendarMode.Events -> AgendaDay(date, events = eventsByDate[date].orEmpty())
                    CalendarMode.Birthdays -> AgendaDay(date, birthdays = birthdaysByDate[date].orEmpty())
                }
            }
        }

        /**
         * Where a long scroll past the edge of [month] lands: the earliest day in [month] carrying
         * content when moving [forward], the latest moving back — never a bare 1st/last day that may
         * have nothing on it, and never the *other* end of the month either.
         */
        fun landingDay(datesWithContent: Set<LocalDate>, month: YearMonth, forward: Boolean): LocalDate {
            val inMonth = datesWithContent.filter { YearMonth.from(it) == month }
            return if (forward) inMonth.minOrNull() ?: month.atDay(1)
            else inMonth.maxOrNull() ?: month.atEndOfMonth()
        }

        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MonthViewModel(
                    container.calendarRepository,
                    container.birthdayRepository,
                    container.eventColorRepository,
                ) { container.zone } as T
        }
    }
}

/**
 * Projects a birthday occurrence into a single-day all-day [EventInstance] so the grid can draw it
 * with [MonthChips]' existing packing logic. `eventId`/`instanceId` are `-birthday.id` — negative,
 * which no real provider row ever is — the sentinel [MonthUiState.gridEventsByDate] documents.
 */
private fun Birthday.toChipEvent(date: LocalDate): EventInstance = EventInstance(
    eventId = -id,
    instanceId = -id,
    title = name,
    location = null,
    startMillis = localDateToUtcMidnight(date),
    endMillis = localDateToUtcMidnight(date.plusDays(1)),
    allDay = true,
    calendarId = 0L,
    displayColor = 0,
)
