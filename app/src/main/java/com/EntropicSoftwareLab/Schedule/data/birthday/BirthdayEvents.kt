package com.EntropicSoftwareLab.Schedule.data.birthday

import java.time.LocalDate

/**
 * One provider event that is, or might be, a birthday — the Events-table fields the merge needs.
 * [date] is the series' first day ([android.provider.CalendarContract.Events.DTSTART] read as a
 * date), which is all a yearly rule needs to say which day of the year it lands on.
 */
data class BirthdayEventRow(
    val eventId: Long,
    val title: String,
    val date: LocalDate,
    val rrule: String?,
    val ownerAccount: String?,
    val writable: Boolean,
)

/**
 * The pure half of birthday sync, both ways: how a birthday is written as a calendar event, how a
 * calendar event is recognised as a birthday, and how the two lists become one.
 *
 * Google gives third-party apps no way to create an entry in its own Birthdays calendar (those come
 * from Contacts), so an app birthday goes up as an ordinary yearly all-day event titled
 * "<name>'s birthday" on the user's primary Google calendar. Coming back, a row counts as a birthday
 * when it is on Google Contacts' birthdays calendar, or when it is an all-day event titled as one
 * ([isBirthdayTitle]) — the second rule is also what recognises this app's own events after a reinstall
 * has wiped the link.
 */
object BirthdayEvents {

    /** The owner account of the calendar Google's sync adapter fills from Contacts. */
    const val CONTACTS_OWNER_SUFFIX = "#contacts@group.v.calendar.google.com"

    /**
     * Calendar-only birthdays get ids far above any app-minted one (a millisecond timestamp, about
     * 1.8e12), so the two can never collide, and positive, so the Month grid's `eventId = -id`
     * sentinel stays negative for every birthday chip.
     */
    const val EVENT_ID_OFFSET = 1L shl 62

    fun idForEvent(eventId: Long): Long = EVENT_ID_OFFSET + eventId

    /** The provider event behind a calendar-only birthday's [id], or null for an app-minted id. */
    fun eventIdFor(id: Long): Long? = if (id >= EVENT_ID_OFFSET) id - EVENT_ID_OFFSET else null

    private const val SUFFIX = "'s birthday"

    fun titleFor(name: String): String = name.trim() + SUFFIX

    /**
     * The person's name out of an event title: "Sarah's birthday" (straight or curly apostrophe),
     * "Sarah’s Birthday" and "Birthday: Sarah" all give "Sarah". Anything else is kept whole —
     * Google Contacts' birthday events are titled with the name alone in some locales.
     */
    fun nameFrom(title: String): String {
        val t = title.trim()
        val possessive = Regex("""^(.+?)\s*['’]s\s+birthday$""", RegexOption.IGNORE_CASE)
        possessive.find(t)?.let { return it.groupValues[1].trim() }
        val prefixed = Regex("""^birthday\s*[:\-–]\s*(.+)$""", RegexOption.IGNORE_CASE)
        prefixed.find(t)?.let { return it.groupValues[1].trim() }
        val trailing = Regex("""^(.+?)\s+birthday$""", RegexOption.IGNORE_CASE)
        trailing.find(t)?.let { return it.groupValues[1].trim() }
        return t
    }

    /**
     * Yearly, and for 29 February the last day of February: a bare `FREQ=YEARLY` anchored on a
     * leap day only recurs every fourth year, while [Birthday.occurrenceIn] shows it on the 28th in
     * a common year. The two must agree or Google and the app show the birthday on different days.
     */
    fun rruleFor(month: Int, day: Int): String =
        if (month == 2 && day == 29) "FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=-1" else "FREQ=YEARLY"

    /**
     * The first day of the series. The birth year when there is one, so Google's own view starts
     * where the life did; this year otherwise. A leap-day birthday must start on a real 29 February,
     * so with no year it starts in the most recent leap year.
     */
    fun seriesStart(birthday: Birthday, today: LocalDate): LocalDate {
        var year = birthday.year ?: today.year
        if (birthday.month == 2 && birthday.day == 29) {
            while (!java.time.Year.isLeap(year.toLong())) year--
            return LocalDate.of(year, 2, 29)
        }
        return birthday.occurrenceIn(year)
    }

    fun isContactsCalendar(ownerAccount: String?): Boolean =
        ownerAccount?.endsWith(CONTACTS_OWNER_SUFFIX, ignoreCase = true) == true

    private val BIRTHDAY_TITLE = Regex(
        """^(.+?\s*['’]s\s+birthday|.+\s+birthday|birthday\s*[:\-–]\s*.+)$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Whether a title names a birthday: "Sarah's birthday", "Sarah birthday", "Birthday: Sarah".
     * Shape, not substring — "Mum's birthday dinner" is an event that happens to be about one.
     */
    fun isBirthdayTitle(title: String): Boolean = BIRTHDAY_TITLE.matches(title.trim())

    /**
     * Whether a calendar row with no link to an app birthday should be shown as one: anything on
     * Google Contacts' birthdays calendar, or an all-day row titled as a birthday.
     *
     * No repeat rule is required. Google Calendar's own Birthday event type (2024) reaches the
     * provider without a yearly RRULE, so requiring one left those under "All day" on Today
     * (reported 2026-10-16). A birthday recurs by definition; [Birthday.occurrenceIn] supplies
     * every year whatever the row carries.
     */
    fun isBirthday(row: BirthdayEventRow): Boolean =
        isContactsCalendar(row.ownerAccount) || isBirthdayTitle(row.title)

    /**
     * The one list every screen reads.
     *
     * [rows] null means the provider couldn't be read (no permission, a provider error): the app's
     * own birthdays are shown as stored, because showing none would read as having lost them.
     * Otherwise:
     *  - an app birthday linked to a row takes its name and day from that row, so a rename or a
     *    move made in Google Calendar shows here; its birth year stays the app's, which Google
     *    doesn't carry;
     *  - an app birthday linked to a row that no longer exists was deleted elsewhere and is left
     *    out — [BirthdayRepository.reconcile] then forgets it;
     *  - every other row recognised by [isBirthday] is a calendar-only birthday, once per name and
     *    day, so the same person in Contacts and in the primary calendar isn't listed twice; the
     *    copies' ids go on [Birthday.otherEventIds].
     */
    fun merge(local: List<Birthday>, rows: List<BirthdayEventRow>?): List<Birthday> {
        if (rows == null) return local
        val byId = rows.associateBy { it.eventId }
        val out = mutableListOf<Birthday>()
        for (b in local) {
            val linked = b.eventId
            if (linked == null) {
                out += b
                continue
            }
            val row = byId[linked] ?: continue
            out += b.copy(
                name = nameFrom(row.title).ifBlank { b.name },
                month = row.date.monthValue,
                day = row.date.dayOfMonth,
                readOnly = !row.writable,
            )
        }
        val linkedIds = local.mapNotNull { it.eventId }.toSet()
        // Key to its index in [out]: a second row for the same person and day (the same birthday in
        // Contacts and in the primary calendar, or one row per year) is folded into the first as
        // an extra event id, so the event lists hide every copy rather than only the one kept.
        val seen = HashMap<String, Int>()
        out.forEachIndexed { index, b -> seen.putIfAbsent(key(b.name, b.month, b.day), index) }
        for (row in rows.sortedBy { it.eventId }) {
            if (row.eventId in linkedIds || !isBirthday(row)) continue
            val name = nameFrom(row.title)
            if (name.isBlank()) continue
            val k = key(name, row.date.monthValue, row.date.dayOfMonth)
            val existing = seen[k]
            if (existing != null) {
                val kept = out[existing]
                out[existing] = kept.copy(otherEventIds = kept.otherEventIds + row.eventId)
                continue
            }
            seen[k] = out.size
            out += Birthday(
                id = idForEvent(row.eventId),
                name = name,
                month = row.date.monthValue,
                day = row.date.dayOfMonth,
                year = null,
                eventId = row.eventId,
                readOnly = !row.writable,
            )
        }
        return out
    }

    /** App birthdays whose linked row is gone — deleted in Google Calendar or another app. */
    fun deletedElsewhere(local: List<Birthday>, rows: List<BirthdayEventRow>): List<Birthday> {
        val ids = rows.map { it.eventId }.toSet()
        return local.filter { it.eventId != null && it.eventId !in ids }
    }

    private fun key(name: String, month: Int, day: Int) = "${name.trim().lowercase()}|$month|$day"
}
