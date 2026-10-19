package com.EntropicSoftwareLab.Schedule.ui.screens.eventeditor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.EntropicSoftwareLab.Schedule.data.appearance.BrandColorAssignment
import com.EntropicSoftwareLab.Schedule.data.appearance.EventColorRepository
import com.EntropicSoftwareLab.Schedule.data.appearance.EventColorResolver
import com.EntropicSoftwareLab.Schedule.data.calendar.CalendarInfo
import com.EntropicSoftwareLab.Schedule.data.calendar.CalendarRepository
import com.EntropicSoftwareLab.Schedule.data.calendar.EventColorOption
import com.EntropicSoftwareLab.Schedule.data.calendar.GoogleCalendarColors
import com.EntropicSoftwareLab.Schedule.data.calendar.EventDetails
import com.EntropicSoftwareLab.Schedule.data.calendar.EventEdit
import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance
import com.EntropicSoftwareLab.Schedule.data.calendar.FreeBusy
import com.EntropicSoftwareLab.Schedule.data.calendar.NewEvent
import com.EntropicSoftwareLab.Schedule.data.calendar.RecurrenceScope
import com.EntropicSoftwareLab.Schedule.data.calendar.RepeatSelection
import com.EntropicSoftwareLab.Schedule.data.calendar.parseRepeat
import com.EntropicSoftwareLab.Schedule.data.calendar.toRRule
import com.EntropicSoftwareLab.Schedule.data.settings.SettingsRepository
import com.EntropicSoftwareLab.Schedule.di.AppContainer
import com.EntropicSoftwareLab.Schedule.di.CalendarDirectory
import com.EntropicSoftwareLab.Schedule.ui.theme.EventColorPalette
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** How long a date/time edit settles before the conflict check runs. See [EventEditorViewModel]. */
private const val CONFLICT_DEBOUNCE_MS = 150L

/** Stands in for an event not saved yet when previewing its automatic colour; no real id is negative. */
private const val PREVIEW_EVENT_ID = -1L

private val BRAND_PALETTE: List<Int> = EventColorPalette.assignmentOrder.map { it.argb }

/** Which write is waiting on the user choosing which occurrences it applies to. */
enum class PendingWrite { Save, Delete }

/**
 * One form, two jobs: creating an event and editing an existing one.
 *
 * They share every field, every validation rule and the same conflict check, so splitting them into
 * two screens would mean maintaining that twice and letting them drift. [editing] is the only thing
 * that differs, and it changes three things: the header, the presence of a Delete button, and
 * whether a save goes to `insertEvent` or `updateEvent`.
 */
data class EventEditorUiState(
    val editing: Boolean = false,
    val loading: Boolean = false,
    val title: String = "",
    val startDate: LocalDate = LocalDate.now(),
    val startTime: LocalTime = nextRoundHour(),
    /**
     * Last day of the event, *inclusive* — the day a user would name out loud. The provider stores
     * an exclusive end for all-day events; that conversion happens on the way out, in [toEventEdit],
     * rather than being carried around in the state where it would read as an off-by-one.
     */
    val endDate: LocalDate = LocalDate.now(),
    val endTime: LocalTime = nextRoundHour().plusHours(1),
    val allDay: Boolean = false,
    val notes: String = "",
    val location: String = "",
    val calendars: List<CalendarInfo> = emptyList(),
    val selectedCalendarId: Long? = null,
    val repeat: RepeatSelection = RepeatSelection.None,
    /**
     * ARGB the user picked, or null for Automatic.
     *
     * The picker offers only [colorOptions] — the account's own Google colours — so at save time
     * [GoogleCalendarColors.nearestAvailable] finds this exact colour and writes its
     * `EVENT_COLOR_KEY`, and Google Calendar shows the same colour. An older event carrying a
     * colour outside the account's palette still saves as its nearest.
     */
    val selectedColor: Int? = null,
    /** The selected calendar's account palette, canonicalized onto Google's 24 — what the picker offers. */
    val colorOptions: List<EventColorOption> = emptyList(),
    val conflicts: List<EventInstance> = emptyList(),
    /**
     * Colours already worn by another event on [startDate], as resolved ARGB.
     *
     * Resolved rather than keyed, now that the app's own colour usually has no key at all: the rule
     * is about whether two events on one day *look* alike, which is a question about the colour on
     * screen. A colour in this set is hinted in the picker, never blocked — with a whole spectrum
     * available, refusing one is noise rather than help.
     *
     * This knowingly reintroduces a false positive the keyed version was written to avoid: several
     * uncoloured events on one calendar all resolve to that calendar's colour, so it lands here once
     * and any palette swatch matching it exactly gets hinted. That was unacceptable while the match
     * *disabled* a swatch; as a hint it costs a small ring, and the calendar's own colour rarely
     * equals one of the palette's thirty exactly. Do not "fix" this by going back to keys — the
     * user's colour usually has no key to compare.
     */
    val colorsUsedToday: Set<Int> = emptySet(),
    /**
     * The brand colour "Automatic" would give this event on its start day, so the Automatic swatch
     * previews the real colour rather than always the first of the brand. Null until worked out.
     */
    val automaticColor: Int? = null,
    /** Event id to the app's own colour. See [EventColorRepository]. */
    val colorOverrides: Map<Long, Int> = emptyMap(),
    val saving: Boolean = false,
    /** Set once the write landed; the screen pops on it. */
    val finished: Boolean = false,
    val error: String? = null,
    /** The row being edited, or null in create mode. */
    val details: EventDetails? = null,
    /** Non-null while the "this / this and following / all" sheet is up. */
    val pendingWrite: PendingWrite? = null,
    /** A holiday or birthday feed can be opened but not changed. */
    val readOnly: Boolean = false,
) {
    val startDateTime: LocalDateTime
        get() = if (allDay) startDate.atStartOfDay() else LocalDateTime.of(startDate, startTime)

    /** Exclusive end, in the form the provider and the conflict check both want. */
    val endDateTime: LocalDateTime
        get() = if (allDay) endDate.plusDays(1).atStartOfDay() else LocalDateTime.of(endDate, endTime)

    val canSave: Boolean
        get() = title.isNotBlank() && selectedCalendarId != null && !saving && !readOnly &&
            !loading && endDateTime.isAfter(startDateTime)

    val selectedCalendar: CalendarInfo?
        get() = calendars.firstOrNull { it.id == selectedCalendarId }

    /** True when a write has to ask which occurrences it applies to. */
    val isRecurringSeries: Boolean get() = details?.isRecurring == true

    /** Spans more than one day, which is what makes the end-date row worth showing. */
    val isMultiDay: Boolean get() = endDate != startDate
}

/** Default to the next whole hour — the most likely intent when opening the form. */
private fun nextRoundHour(): LocalTime =
    LocalTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)

/**
 * An hour after [start], or the last minute of the day when that would wrap past midnight. A same-day
 * end of 00:00 reads as "before the start" and blocks saving — which is exactly what tapping the
 * 23:00 cell on the grid would otherwise produce.
 */
private fun defaultEndFor(start: LocalTime): LocalTime {
    val end = start.plusHours(1)
    return if (end > start) end else LocalTime.of(23, 59)
}

/**
 * [initialDate]/[initialStartTime] seed a new event from a tapped hour on the Today grid.
 * [editEventId] and its instance bounds instead load an existing row for editing.
 *
 * All of them are constructor params rather than something applied later because the ViewModel is
 * built once per back-stack entry: the seed lands before the first frame and can never overwrite an
 * edit the user has already made.
 */
class EventEditorViewModel(
    private val repository: CalendarRepository,
    private val settings: SettingsRepository,
    private val eventColors: EventColorRepository,
    private val directory: CalendarDirectory,
    private val zoneProvider: () -> ZoneId,
    initialDate: LocalDate? = null,
    initialStartTime: LocalTime? = null,
    private val editEventId: Long? = null,
    private val editInstanceStart: Long = 0L,
    private val editInstanceEnd: Long = 0L,
) : ViewModel() {

    private val _state = MutableStateFlow(
        EventEditorUiState(editing = editEventId != null, loading = editEventId != null).let { d ->
            val start = initialStartTime ?: d.startTime
            val date = initialDate ?: d.startDate
            d.copy(
                startDate = date,
                endDate = date,
                startTime = start,
                endTime = defaultEndFor(start),
            )
        },
    )
    val state: StateFlow<EventEditorUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Read once up front rather than collected: the editor needs the app's colours to seed
            // the form and to work out what today already wears, and a live collection here would
            // fight the user's own edits mid-session.
            val overrides = runCatching { eventColors.overrides.first() }.getOrDefault(emptyMap())
            _state.update { it.copy(colorOverrides = overrides) }
            try {
                // Warm when the app has been open a moment, so the dropdown, the swatches and the
                // Save button are all live on the first frame instead of arriving a beat later. Falls
                // back to a real query when nothing is warm yet — see CalendarDirectory.
                val calendars = directory.writableCalendars()
                val stored = settings.defaultCalendarId.first()
                val chosen = calendars.firstOrNull { it.id == stored }
                    ?: calendars.firstOrNull { it.isPrimary }
                    ?: calendars.firstOrNull()
                _state.update { it.copy(calendars = calendars, selectedCalendarId = chosen?.id) }
                loadColorOptions(_state.value.selectedCalendar)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Couldn't load calendars.") }
            }
            if (editEventId != null) loadForEditing(editEventId) else refreshConflicts()
        }
    }

    private suspend fun loadForEditing(eventId: Long) {
        try {
            val zone = zoneProvider()
            val details = repository.eventDetails(eventId, editInstanceStart, editInstanceEnd, zone)
            if (details == null) {
                _state.update {
                    it.copy(loading = false, error = "That event no longer exists.", readOnly = true)
                }
                return
            }
            // The stored end is exclusive for an all-day event; the form shows the last day the user
            // would name, so a one-day event doesn't render as spanning two.
            val lastDay = if (details.allDay) details.instanceEnd.toLocalDate().minusDays(1)
            else details.instanceEnd.toLocalDate()
            _state.update {
                it.copy(
                    loading = false,
                    details = details,
                    title = details.title,
                    notes = details.description.orEmpty(),
                    location = details.location.orEmpty(),
                    allDay = details.allDay,
                    startDate = details.instanceStart.toLocalDate(),
                    startTime = details.instanceStart.toLocalTime(),
                    endDate = maxOf(lastDay, details.instanceStart.toLocalDate()),
                    // An all-day event's stored end is exclusive midnight, so reading it straight
                    // through leaves 00:00 in a field the user only sees after switching All day
                    // off — at which point the form is unsaveable for a reason nothing explains.
                    endTime = if (details.allDay) defaultEndFor(details.instanceStart.toLocalTime())
                    else details.instanceEnd.toLocalTime(),
                    repeat = parseRepeat(details.rrule, zone),
                    // The provider's colour, which is Google's: the picker offers only the
                    // account's own colours, so this is exactly what Google Calendar shows.
                    selectedColor = details.eventColor,
                    selectedCalendarId = details.calendarId,
                    readOnly = !details.isWritable,
                )
            }
            refreshConflicts()
        } catch (e: SecurityException) {
            _state.update { it.copy(loading = false, error = "Calendar access was revoked.", readOnly = true) }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: "Couldn't open that event.") }
        }
    }

    // --- field edits ------------------------------------------------------

    fun setTitle(value: String) = _state.update { it.copy(title = value) }
    fun setNotes(value: String) = _state.update { it.copy(notes = value) }
    fun setLocation(value: String) = _state.update { it.copy(location = value) }

    fun setStartDate(value: LocalDate) {
        _state.update { current ->
            // Carry the span rather than letting the end fall behind the start.
            val span = java.time.temporal.ChronoUnit.DAYS.between(current.startDate, current.endDate)
            current.copy(startDate = value, endDate = value.plusDays(span.coerceAtLeast(0L)))
        }
        refreshConflicts()
    }

    fun setEndDate(value: LocalDate) {
        _state.update { it.copy(endDate = maxOf(value, it.startDate)) }
        refreshConflicts()
    }

    fun setStartTime(value: LocalTime) {
        _state.update { current ->
            // Dragging start past end would otherwise produce a negative-length event; carry the
            // original duration along instead.
            val duration = Duration.between(current.startTime, current.endTime)
            val carried = if (duration.isNegative || duration.isZero) null
            else value.plusMinutes(duration.toMinutes())
            val sameDay = current.startDate == current.endDate
            val newEnd = when {
                !sameDay -> current.endTime
                carried != null && carried > value -> carried
                else -> defaultEndFor(value)
            }
            current.copy(startTime = value, endTime = newEnd)
        }
        refreshConflicts()
    }

    fun setEndTime(value: LocalTime) {
        _state.update { it.copy(endTime = value) }
        refreshConflicts()
    }

    fun setAllDay(value: Boolean) {
        _state.update { it.copy(allDay = value) }
        refreshConflicts()
    }

    fun setCalendar(id: Long) {
        _state.update { it.copy(selectedCalendarId = id) }
        loadColorOptions(_state.value.selectedCalendar)
        // Only remember the choice when creating. Opening someone's birthday feed to fix a typo
        // shouldn't silently redirect where new events land.
        if (!_state.value.editing) viewModelScope.launch { settings.setDefaultCalendarId(id) }
    }

    fun setRepeat(value: RepeatSelection) {
        _state.update { it.copy(repeat = value) }
    }

    /** [argb] null means "whatever the calendar uses" — the provider gets no colour at all. */
    fun setColor(argb: Int?) = _state.update { it.copy(selectedColor = argb) }

    /**
     * Loads the palette for whichever account the chosen calendar belongs to.
     *
     * This is no longer what the picker shows — it shows the app's own palette plus any custom
     * colour. It is only the set of colours the *account* offers, kept so a save can pick the
     * nearest one to write to `EVENT_COLOR_KEY`.
     *
     * Which is also why changing calendar no longer clears the user's colour. It used to, because a
     * key from one account is meaningless to another; an ARGB is meaningless to neither, and the
     * nearest-key mapping is recomputed against the new account at save time anyway.
     */
    private fun loadColorOptions(calendar: CalendarInfo?) {
        if (calendar == null) {
            _state.update { it.copy(colorOptions = emptyList()) }
            return
        }
        // The warm copy when there is one; a query when there isn't. Either way this no longer holds
        // up the swatch strip, which now comes from the app's own palette rather than the account's.
        directory.colorsByCalendar.value[calendar.id]?.let { warm ->
            _state.update { it.copy(colorOptions = warm) }
            return
        }
        viewModelScope.launch {
            val options = runCatching { directory.colorsFor(calendar) }.getOrDefault(emptyList())
            _state.update { it.copy(colorOptions = options) }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    // --- conflicts --------------------------------------------------------

    /**
     * The in-flight conflict check, cancelled when a newer one starts.
     *
     * Every date and time setter calls [refreshConflicts], so dragging a time picker used to queue a
     * provider query per frame and let the results race to write the same state — the last one to
     * *return* won, which is not necessarily the last one asked for.
     */
    private var conflictJob: Job? = null

    private fun refreshConflicts() {
        conflictJob?.cancel()
        conflictJob = viewModelScope.launch {
            // Long enough to swallow a picker drag, short enough that letting go feels immediate.
            delay(CONFLICT_DEBOUNCE_MS)
            val current = _state.value
            try {
                val zone = zoneProvider()
                // Colour usage is read before either early return below: an all-day event and one
                // with no calendar chosen yet both still need to know which colours are already
                // spoken for today, even though neither one runs the time-overlap check.
                val existing = repository.eventsOnDay(current.startDate, zone)
                    // An event can't clash with itself, and its own colour must not block
                    // re-picking it. Without this, opening any existing event and saving it
                    // unchanged reports a conflict — and a used colour — against its own row.
                    .filterNot { it.eventId == current.details?.eventId }
                // This event joins the day as if nobody had coloured it, which is what "Automatic"
                // means, and the brand colours are handed out the way the grid hands them out.
                // Without it the Automatic swatch could only guess, and always showed Blue.
                val myId = current.details?.eventId ?: PREVIEW_EVENT_ID
                val startAt = if (current.allDay) {
                    current.startDate.atStartOfDay()
                } else {
                    current.startDate.atTime(current.startTime)
                }
                val startMillis = startAt.atZone(zone).toInstant().toEpochMilli()
                val me = EventInstance(
                    eventId = myId,
                    instanceId = myId,
                    title = current.title,
                    location = null,
                    startMillis = startMillis,
                    endMillis = startMillis + 1,
                    allDay = current.allDay,
                    calendarId = 0L,
                    displayColor = 0,
                )
                val shown = BrandColorAssignment.forDay(
                    events = existing + me,
                    overrides = current.colorOverrides - myId,
                    palette = BRAND_PALETTE,
                )
                val automatic = shown[myId]
                // Resolved through the same path the grid and agenda draw with, brand colours
                // included, so the hint marks the colours the user can actually see on this day.
                val usedColors = existing
                    .mapNotNull { EventColorResolver.argbFor(it, shown) }
                    .toSet()

                if (current.allDay) {
                    _state.update {
                        it.copy(
                            conflicts = emptyList(),
                            colorsUsedToday = usedColors,
                            automaticColor = automatic,
                        )
                    }
                    return@launch
                }
                val draft = current.toNewEvent(zone)
                if (draft == null) {
                    _state.update { it.copy(colorsUsedToday = usedColors, automaticColor = automatic) }
                    return@launch
                }
                _state.update {
                    it.copy(
                        conflicts = FreeBusy.conflictsWith(draft, existing, zone),
                        colorsUsedToday = usedColors,
                        automaticColor = automatic,
                    )
                }
            } catch (e: Exception) {
                // A failed lookup must not block saving; both checks are advisory.
                _state.update { it.copy(conflicts = emptyList(), colorsUsedToday = emptySet()) }
            }
        }
    }

    // --- saving -----------------------------------------------------------

    /**
     * Starts a save. A recurring series first has to ask which occurrences it applies to; everything
     * else writes straight through.
     */
    fun save() {
        val current = _state.value
        if (!current.canSave) return
        if (current.editing && current.isRecurringSeries) {
            _state.update { it.copy(pendingWrite = PendingWrite.Save) }
        } else {
            performSave(RecurrenceScope.AllEvents)
        }
    }

    /** Starts a cancellation. Same rule as [save] for a recurring series. */
    fun delete() {
        val current = _state.value
        if (!current.editing || current.readOnly || current.saving) return
        if (current.isRecurringSeries) {
            _state.update { it.copy(pendingWrite = PendingWrite.Delete) }
        } else {
            performDelete(RecurrenceScope.AllEvents)
        }
    }

    fun dismissScopePrompt() = _state.update { it.copy(pendingWrite = null) }

    /** Runs whichever write was waiting, now that the user has chosen its reach. */
    fun confirmScope(scope: RecurrenceScope) {
        val pending = _state.value.pendingWrite ?: return
        _state.update { it.copy(pendingWrite = null) }
        when (pending) {
            PendingWrite.Save -> performSave(scope)
            PendingWrite.Delete -> performDelete(scope)
        }
    }

    private fun performSave(scope: RecurrenceScope) {
        val current = _state.value
        val zone = zoneProvider()
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val ok = if (current.editing) {
                    val details = current.details
                    if (details == null) false
                    else repository.updateEvent(details, current.toEventEdit(zone), scope, zone)
                        .also { saved ->
                            // The colour now lives in the provider as the account's key; drop any
                            // app-only colour left over from before, so nothing shadows it.
                            if (saved) forgetAppColor(details.eventId)
                        }
                } else {
                    current.toNewEvent(zone)?.let { draft ->
                        repository.insertEvent(draft, zone) != null
                    } ?: false
                }
                if (ok) _state.update { it.copy(saving = false, finished = true) }
                else _state.update {
                    it.copy(saving = false, error = "The calendar rejected the change.")
                }
            } catch (e: SecurityException) {
                _state.update { it.copy(saving = false, error = "Calendar write access was revoked.") }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = e.message ?: "Couldn't save the event.") }
            }
        }
    }

    /**
     * Drops an app-only colour stored for [eventId] before Google's colours became the picker.
     *
     * Nothing reads those any more (see `EventColorRepository.overrides`); this just tidies the
     * store as events are touched. Failure is swallowed: the event itself is already written.
     */
    private suspend fun forgetAppColor(eventId: Long) {
        runCatching { eventColors.forget(eventId) }
    }

    private fun performDelete(scope: RecurrenceScope) {
        val details = _state.value.details ?: return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val ok = repository.deleteEvent(details, scope, zoneProvider())
                // Only a whole-series delete removes the row this colour is keyed to. Cancelling one
                // occurrence leaves the series — and its colour — in place.
                if (ok && scope == RecurrenceScope.AllEvents) {
                    runCatching { eventColors.forget(details.eventId) }
                }
                if (ok) _state.update { it.copy(saving = false, finished = true) }
                else _state.update {
                    it.copy(saving = false, error = "The calendar wouldn't remove that event.")
                }
            } catch (e: SecurityException) {
                _state.update { it.copy(saving = false, error = "Calendar write access was revoked.") }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = e.message ?: "Couldn't cancel the event.") }
            }
        }
    }

    companion object {
        fun factory(
            container: AppContainer,
            initialDate: LocalDate? = null,
            initialStartTime: LocalTime? = null,
            editEventId: Long? = null,
            editInstanceStart: Long = 0L,
            editInstanceEnd: Long = 0L,
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                EventEditorViewModel(
                    container.calendarRepository,
                    container.settingsRepository,
                    container.eventColorRepository,
                    container.calendarDirectory,
                    { container.zone },
                    initialDate,
                    initialStartTime,
                    editEventId,
                    editInstanceStart,
                    editInstanceEnd,
                ) as T
        }
    }
}

/** The RRULE body for whatever the Repeats row is set to, or null when it doesn't repeat. */
private fun EventEditorUiState.rruleFor(zone: ZoneId): String? = when (val r = repeat) {
    RepeatSelection.None -> null
    // Written back byte-for-byte. A rule this app can't model must survive an unrelated edit
    // untouched rather than being flattened into the nearest thing it can express.
    is RepeatSelection.Unsupported -> r.rrule
    is RepeatSelection.Simple -> r.rule.toRRule(startDateTime, zone, allDay)
}

/**
 * The account colour closest to what the user picked, or null when they chose the calendar default.
 *
 * This is the whole hybrid in one line. The exact colour is kept by this app; the provider gets the
 * nearest thing its palette can hold, so the event still reads as roughly that colour in Google
 * Calendar instead of falling back to the calendar's default everywhere else the user looks.
 */
internal fun EventEditorUiState.nearestAccountColorKey(): String? {
    val argb = selectedColor ?: return null
    return GoogleCalendarColors.nearestAvailable(argb, colorOptions)?.key
}

internal fun EventEditorUiState.toNewEvent(zone: ZoneId): NewEvent? {
    val calendarId = selectedCalendarId ?: return null
    return NewEvent(
        calendarId = calendarId,
        title = title.trim(),
        start = startDateTime,
        end = endDateTime,
        allDay = allDay,
        description = notes.takeIf { it.isNotBlank() },
        location = location.takeIf { it.isNotBlank() },
        rrule = rruleFor(zone),
        eventColor = selectedColor,
        eventColorKey = nearestAccountColorKey(),
    )
}

internal fun EventEditorUiState.toEventEdit(zone: ZoneId): EventEdit = EventEdit(
    title = title.trim(),
    start = startDateTime,
    end = endDateTime,
    allDay = allDay,
    description = notes.takeIf { it.isNotBlank() },
    location = location.takeIf { it.isNotBlank() },
    rrule = rruleFor(zone),
    // Colour untouched: write back exactly what the event had. Otherwise a colour set in Google
    // Calendar that this app can't write (Cherry Blossom, Pumpkin …) would be swapped for its
    // nearest writable one by an edit to the title.
    eventColor = if (colorUnchanged()) details?.eventColor else selectedColor,
    eventColorKey = if (colorUnchanged()) details?.eventColorKey else nearestAccountColorKey(),
)

/** True when editing and the colour is still the one the event was opened with. */
internal fun EventEditorUiState.colorUnchanged(): Boolean =
    details != null && selectedColor == details.eventColor
