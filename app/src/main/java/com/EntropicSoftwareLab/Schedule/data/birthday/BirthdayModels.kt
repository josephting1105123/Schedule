package com.EntropicSoftwareLab.Schedule.data.birthday

import java.time.LocalDate
import java.time.Period

/**
 * A birthday. Two sources merge into one list (see [BirthdayEvents.merge]):
 *  - one saved in this app, which is kept in its own DataStore *and* mirrored to a yearly all-day
 *    event on a Google calendar ([eventId]), so it syncs to Google Calendar and other devices;
 *  - one that only exists on the calendar side — Google Contacts' birthdays, or a yearly all-day
 *    event titled as a birthday — read back from the provider. Its [id] is [BirthdayEvents.idForEvent],
 *    never an app-minted one.
 */
data class Birthday(
    val id: Long,
    val name: String,
    val month: Int,
    val day: Int,
    /** Null when the user didn't give one — no age is ever shown for this birthday. */
    val year: Int?,
    /** The provider event this birthday is mirrored to, or null while it isn't (yet). */
    val eventId: Long? = null,
    /**
     * True when the calendar row can't be written — Google Contacts' birthdays calendar is
     * read-only to every app. Derived on each read, never stored.
     */
    val readOnly: Boolean = false,
    /**
     * Further provider rows for this same birthday — the person in Contacts and in the primary
     * calendar, or one row per year — so event lists can hide every copy. Derived, never stored.
     */
    val otherEventIds: Set<Long> = emptySet(),
) {
    /**
     * The date this birthday falls on in [year]. 29 February falls back to 28 February in a
     * common year rather than skipping the year entirely — the one date-arithmetic trap here.
     */
    fun occurrenceIn(year: Int): LocalDate {
        val clampedDay = day.coerceAtMost(LocalDate.of(year, month, 1).lengthOfMonth())
        return LocalDate.of(year, month, clampedDay)
    }

    /** Years elapsed as of [date], or null when no birth year was given. */
    fun ageOn(date: LocalDate): Int? {
        val birthYear = year ?: return null
        return Period.between(occurrenceIn(birthYear), date).years
    }

    companion object {
        /**
         * [events] less the provider rows that back one of [birthdays], for any surface that shows
         * birthdays in a section of their own and would otherwise list the same one twice.
         */
        fun withoutBirthdayEvents(
            events: List<com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance>,
            birthdays: List<Birthday>,
        ): List<com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance> {
            if (events.isEmpty() || birthdays.isEmpty()) return events
            val ids = HashSet<Long>()
            for (b in birthdays) {
                b.eventId?.let(ids::add)
                ids += b.otherEventIds
            }
            return if (ids.isEmpty()) events else events.filterNot { it.eventId in ids }
        }

        /**
         * Expands every birthday across each year the [from]..[toExclusive) range touches — a
         * range can straddle a new year — keyed by the date it falls on, sorted by name within a
         * day. Empty when the range is empty or backwards.
         */
        fun byDate(
            birthdays: List<Birthday>,
            from: LocalDate,
            toExclusive: LocalDate,
        ): Map<LocalDate, List<Birthday>> {
            if (!from.isBefore(toExclusive)) return emptyMap()
            val result = sortedMapOf<LocalDate, MutableList<Birthday>>()
            for (birthday in birthdays) {
                for (year in from.year..toExclusive.year) {
                    val occurrence = birthday.occurrenceIn(year)
                    if (!occurrence.isBefore(from) && occurrence.isBefore(toExclusive)) {
                        result.getOrPut(occurrence) { mutableListOf() }.add(birthday)
                    }
                }
            }
            return result.mapValues { (_, list) -> list.sortedBy { it.name } }
        }
    }
}
