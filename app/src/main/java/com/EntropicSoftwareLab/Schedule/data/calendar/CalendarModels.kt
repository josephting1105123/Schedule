package com.EntropicSoftwareLab.Schedule.data.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** A calendar the user can read, and (when [isWritable]) add events to. */
data class CalendarInfo(
    val id: Long,
    val displayName: String,
    val accountName: String,
    /** Needed with [accountName] to look up the account's own event-colour palette. */
    val accountType: String = "",
    val color: Int,
    val isPrimary: Boolean,
    val isWritable: Boolean,
)

/**
 * One occurrence of an event. Comes from the Instances table, so a weekly recurring event yields one
 * of these per week rather than a single row with a recurrence rule to interpret.
 *
 * [startMillis]/[endMillis] are absolute UTC instants for timed events. For [allDay] events the
 * provider stores UTC midnight of the *calendar day*, which is not the same instant as local
 * midnight — use [localStart]/[localEnd] rather than converting these directly, or an all-day event
 * will land on the wrong day for anyone east or west of UTC.
 */
data class EventInstance(
    val eventId: Long,
    val instanceId: Long,
    val title: String,
    val location: String?,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val calendarId: Long,
    val displayColor: Int,
    /**
     * The event's own description, when the extended projection came back. Defaulted to null
     * rather than required — see [CalendarRepository.eventInstances] for the fallback that leaves
     * this unset on a provider that rejects the extra column, and every existing named-argument
     * construction site elsewhere relies on this default to keep compiling.
     */
    val notes: String? = null,
    /**
     * The *chosen* colour key (EVENT_COLOR_KEY), not the resolved [displayColor]. Comparing the
     * resolved colour for same-day-colour checks would flag a default-coloured event the moment one
     * other default-coloured event existed on a calendar of that colour — see
     * `EventEditorViewModel.refreshConflicts`. Defaulted to null for the same reason as [notes].
     */
    val eventColorKey: String? = null,
    /**
     * The event's own colour (EVENT_COLOR), or null when it wears its calendar's. Set by Google's
     * sync for a colour picked in Google Calendar even when it has no key this app could write —
     * the 13 of Google's 24 outside the classic 11 — so it is what says "somebody coloured this"
     * alongside [eventColorKey]. Defaulted to null for the same reason as [notes].
     */
    val eventColor: Int? = null,
) {
    fun localStart(zone: ZoneId): LocalDateTime =
        if (allDay) utcMidnightToLocalDate(startMillis).atStartOfDay()
        else Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDateTime()

    fun localEnd(zone: ZoneId): LocalDateTime =
        if (allDay) utcMidnightToLocalDate(endMillis).atStartOfDay()
        else Instant.ofEpochMilli(endMillis).atZone(zone).toLocalDateTime()

    /** Duration in minutes, floored at 0 for the malformed rows some sync adapters produce. */
    val durationMinutes: Long get() = ((endMillis - startMillis) / 60_000L).coerceAtLeast(0L)
}

/** One synced account's state, as far as the phone will say. Null wherever it wouldn't. */
data class AccountSyncStatus(
    val accountName: String,
    val accountType: String,
    /** The phone-wide "Auto-sync data" switch. */
    val masterAutoSync: Boolean?,
    /** This account's own Calendar sync switch. */
    val autoSync: Boolean?,
    /** A sync is running or queued for this account right now. */
    val syncing: Boolean?,
    /** Rows changed on this phone and not yet uploaded. */
    val pendingUploads: Int?,
)

/**
 * Whether [this] belongs on any of the local dates from [from] up to [untilExclusive].
 *
 * A timed event always does: the Instances query was already bounded by those dates' local
 * midnights. An all-day event is stored from UTC midnight to the UTC midnight after its last day,
 * and the query compares those instants against *local* midnights — so east of Greenwich an all-day
 * event ending on the 4th overlaps the first hours of the 5th and was listed on it ("Semester I Final
 * Examination" on Friday the 5th, reported 2026-09-29), and west of it one appeared the day before
 * it starts. Its own dates decide instead. A malformed row whose end isn't after its start still
 * counts for its first day.
 */
fun EventInstance.fallsWithin(from: LocalDate, untilExclusive: LocalDate, zone: ZoneId): Boolean {
    if (!allDay) return true
    val first = localStart(zone).toLocalDate()
    val endExclusive = maxOf(localEnd(zone).toLocalDate(), first.plusDays(1))
    return first < untilExclusive && endExclusive > from
}

/** An event about to be written. Times are local; the repository handles provider encoding. */
data class NewEvent(
    val calendarId: Long,
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean = false,
    val description: String? = null,
    val location: String? = null,
    /** RFC 2445 rule body without the "RRULE:" prefix, e.g. "FREQ=WEEKLY;BYDAY=MO". Null = one-off. */
    val rrule: String? = null,
    /** ARGB for EVENT_COLOR. Null leaves the event on its calendar's colour. */
    val eventColor: Int? = null,
    /** The account's own colour key, which is what survives a sync. Wins over [eventColor]. */
    val eventColorKey: String? = null,
)

/** A merged span of occupied time on a single day, in absolute millis. */
data class BusyBlock(val startMillis: Long, val endMillis: Long)

/**
 * A stretch of time claimed by more than one event, and everything claiming it.
 *
 * [startMillis]/[endMillis] bound the double-booked window itself, not the events — two meetings
 * running 14:00–15:00 and 14:30–16:00 clash from 14:30 to 15:00, and that half hour is what the user
 * has to resolve. [events] holds every event overlapping that window, earliest first.
 */
data class Clash(
    val startMillis: Long,
    val endMillis: Long,
    val events: List<EventInstance>,
) {
    // Always a timed window — all-day events are excluded from clash detection — so these convert
    // straight through the zone, with none of the UTC-midnight handling [EventInstance] needs.
    fun localStart(zone: ZoneId): LocalDateTime =
        Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDateTime()

    fun localEnd(zone: ZoneId): LocalDateTime =
        Instant.ofEpochMilli(endMillis).atZone(zone).toLocalDateTime()
}

/**
 * All-day events are stored at UTC midnight of the intended calendar day. Reading the date in UTC
 * (not the device zone) recovers the day the user actually meant.
 */
internal fun utcMidnightToLocalDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()

/** Inverse of [utcMidnightToLocalDate], for writing all-day events back to the provider. */
internal fun localDateToUtcMidnight(date: LocalDate): Long =
    date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()

/**
 * Which occurrences an edit or a cancellation applies to.
 *
 * Only meaningful for a recurring series; a one-off event is always [AllEvents] and the picker isn't
 * shown. The three map onto three genuinely different provider operations, not onto one operation
 * with a flag — see `CalendarRepository.updateEvent` and `deleteEvent`.
 */
enum class RecurrenceScope {
    /** Just the occurrence the user opened. Written as an exception to the series. */
    ThisEvent,

    /** That occurrence and every later one. Caps the original series and re-creates the tail. */
    ThisAndFollowing,

    /** The whole series, past occurrences included. */
    AllEvents,
}

/**
 * An event read back in full, for editing.
 *
 * Separate from [EventInstance] because the two answer different questions. An instance is one
 * occurrence on a grid and carries only what a grid needs; this is the underlying row — the parts a
 * user can change, including the ones the Instances table doesn't expose at all (description, the
 * recurrence rule, the event's own timezone).
 *
 * [instanceStart]/[instanceEnd] are the occurrence the user actually tapped, which for a recurring
 * series is not the series' own start. Both are needed: the editor shows the occurrence, while
 * [RecurrenceScope.AllEvents] has to be applied relative to the series.
 */
data class EventDetails(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val description: String?,
    val location: String?,
    val allDay: Boolean,
    /** Start of the tapped occurrence, local. */
    val instanceStart: LocalDateTime,
    /**
     * The same occurrence as the raw instant the provider stores.
     *
     * Kept alongside [instanceStart] rather than recomputed from it. Recovering it would mean
     * choosing a zone to convert back through, and the only correct choice is the one [instanceStart]
     * was built with — a detail a caller can get wrong silently, producing an exception filed against
     * an instant no occurrence has. Carrying the original number removes the choice.
     */
    val instanceStartMillis: Long,
    /** End of the tapped occurrence, local. Exclusive for an all-day event. */
    val instanceEnd: LocalDateTime,
    /** Start of the underlying series, local. Equal to [instanceStart] for a one-off. */
    val seriesStart: LocalDateTime,
    /** Raw RRULE body, or null for a one-off. */
    val rrule: String?,
    /** ARGB from EVENT_COLOR, or null when the event inherits its calendar's colour. */
    val eventColor: Int?,
    /** The account colour key the row carries, when it was set from a palette rather than a hex. */
    val eventColorKey: String? = null,
    /** The row's own EVENT_TIMEZONE, kept so an unrelated edit doesn't relocate the event. */
    val timeZone: String?,
    /** True when the row can be written to — a holiday or birthday feed can't be. */
    val isWritable: Boolean,
) {
    val isRecurring: Boolean get() = !rrule.isNullOrBlank()
}

/** The edited values written back over an existing event. Mirrors [NewEvent] minus the calendar. */
data class EventEdit(
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean,
    val description: String? = null,
    val location: String? = null,
    /** RRULE body, or null for a one-off. */
    val rrule: String? = null,
    val eventColor: Int? = null,
    /**
     * The account's own colour key. Preferred over [eventColor] when present — see `eventValues`
     * for why the two are mutually exclusive.
     */
    val eventColorKey: String? = null,
)
