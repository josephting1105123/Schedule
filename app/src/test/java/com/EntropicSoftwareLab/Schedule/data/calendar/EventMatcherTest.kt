package com.EntropicSoftwareLab.Schedule.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class EventMatcherTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kuala_Lumpur")
    private val day: LocalDate = LocalDate.of(2026, 7, 20)

    private data class Query(
        override val titleQuery: String? = null,
        override val date: LocalDate? = null,
        override val startTime: LocalTime? = null,
        override val endTime: LocalTime? = null,
    ) : CancellationQueryLike

    private var nextId = 1L

    private fun event(
        title: String,
        from: LocalTime = LocalTime.of(9, 0),
        to: LocalTime = LocalTime.of(10, 0),
        on: LocalDate = day,
        allDay: Boolean = false,
    ): EventInstance {
        val id = nextId++
        val start = if (allDay) on.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        else on.atTime(from).atZone(zone).toInstant().toEpochMilli()
        val end = if (allDay) on.plusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        else on.atTime(to).atZone(zone).toInstant().toEpochMilli()
        return EventInstance(
            eventId = id,
            instanceId = id,
            title = title,
            location = null,
            startMillis = start,
            endMillis = end,
            allDay = allDay,
            calendarId = 1L,
            displayColor = 0,
        )
    }

    // --- the refusal that matters -----------------------------------------

    /**
     * "cancel" on its own must not resolve to everything in the next month. A query with nothing in
     * it is refused rather than answered generously.
     */
    @Test
    fun `an empty query matches nothing`() {
        val candidates = listOf(event("Dentist"), event("Standup"))
        assertTrue(EventMatcher.match(Query(), candidates, zone).isEmpty())
    }

    // --- title matching ---------------------------------------------------

    @Test
    fun `a partial name finds the full one`() {
        val dentist = event("Dentist appointment with Dr Yeo")
        val matches = EventMatcher.match(
            Query(titleQuery = "dentist", date = day),
            listOf(dentist, event("Standup")),
            zone,
        )
        assertEquals(listOf(dentist), matches)
    }

    @Test
    fun `matching ignores case and punctuation`() {
        val lunch = event("Lunch w/ Sam")
        val matches = EventMatcher.match(
            Query(titleQuery = "LUNCH SAM", date = day),
            listOf(lunch, event("Gym")),
            zone,
        )
        assertEquals(listOf(lunch), matches)
    }

    @Test
    fun `a query token prefixing a title word counts`() {
        val meeting = event("Meet the new starters")
        val matches = EventMatcher.match(
            Query(titleQuery = "starter", date = day),
            listOf(meeting),
            zone,
        )
        assertEquals(listOf(meeting), matches)
    }

    /**
     * "appointment" and "meeting" appear in half of everyone's calendar. If they carried weight, a
     * query naming one event would surface every meeting in the month.
     */
    @Test
    fun `generic words alone do not match everything`() {
        val candidates = listOf(
            event("Dentist appointment"),
            event("Team meeting"),
            event("Board meeting"),
        )
        val matches = EventMatcher.match(
            Query(titleQuery = "appointment meeting", date = day),
            candidates,
            zone,
        )
        assertTrue("generic-only query should match nothing", matches.isEmpty())
    }

    @Test
    fun `an unrelated name does not match`() {
        val matches = EventMatcher.match(
            Query(titleQuery = "dentist", date = day),
            listOf(event("Gym"), event("Standup")),
            zone,
        )
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `a better title match ranks first`() {
        val exact = event("Dentist", from = LocalTime.of(16, 0), to = LocalTime.of(17, 0))
        val partial = event("Dentist and pharmacy run", from = LocalTime.of(8, 0), to = LocalTime.of(9, 0))
        val matches = EventMatcher.match(
            Query(titleQuery = "dentist pharmacy", date = day),
            listOf(exact, partial),
            zone,
        )
        // "dentist pharmacy" accounts for both of the second title's words and one of the first's.
        assertEquals(partial, matches.first())
    }

    @Test
    fun `the shortlist is capped`() {
        val many = (1..12).map { event("Review $it", from = LocalTime.of(8, 0), to = LocalTime.of(9, 0)) }
        val matches = EventMatcher.match(Query(titleQuery = "review", date = day), many, zone)
        assertEquals(EventMatcher.MAX_MATCHES, matches.size)
    }

    // --- time matching ----------------------------------------------------

    @Test
    fun `a named time finds an event already running at it`() {
        val long = event("Workshop", from = LocalTime.of(14, 30), to = LocalTime.of(16, 0))
        val matches = EventMatcher.match(
            Query(date = day, startTime = LocalTime.of(15, 0)),
            listOf(long, event("Gym", from = LocalTime.of(7, 0), to = LocalTime.of(8, 0))),
            zone,
        )
        assertEquals(listOf(long), matches)
    }

    /** Half-open, matching FreeBusy: an event ending at 15:00 does not occupy 15:00. */
    @Test
    fun `an event ending exactly at the named time is not running then`() {
        val ends = event("Standup", from = LocalTime.of(14, 0), to = LocalTime.of(15, 0))
        val matches = EventMatcher.match(
            Query(date = day, startTime = LocalTime.of(15, 0)),
            listOf(ends),
            zone,
        )
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `a different day does not match`() {
        val tomorrow = event("Dentist", on = day.plusDays(1))
        val matches = EventMatcher.match(
            Query(titleQuery = "dentist", date = day),
            listOf(tomorrow),
            zone,
        )
        assertTrue(matches.isEmpty())
    }

    /**
     * An all-day event is stored at UTC midnight. Comparing its clock times in the device zone would
     * put it on the wrong day for anyone off UTC — the same trap the rest of the app guards.
     */
    @Test
    fun `an all-day event matches the day it covers`() {
        val leave = event("Leave", on = day, allDay = true)
        assertEquals(
            listOf(leave),
            EventMatcher.match(Query(titleQuery = "leave", date = day), listOf(leave), zone),
        )
        assertTrue(
            EventMatcher.match(
                Query(titleQuery = "leave", date = day.plusDays(1)),
                listOf(leave),
                zone,
            ).isEmpty(),
        )
    }

    @Test
    fun `a date with no title returns everything that day, earliest first`() {
        val morning = event("Gym", from = LocalTime.of(7, 0), to = LocalTime.of(8, 0))
        val afternoon = event("Review", from = LocalTime.of(15, 0), to = LocalTime.of(16, 0))
        val matches = EventMatcher.match(Query(date = day), listOf(afternoon, morning), zone)
        assertEquals(listOf(morning, afternoon), matches)
    }

    // --- search window ----------------------------------------------------

    @Test
    fun `a named date searches that day alone`() {
        val (from, to) = EventMatcher.searchWindow(Query(date = day), today = day.minusDays(5))
        assertEquals(day, from)
        assertEquals(day.plusDays(1), to)
    }

    /**
     * A named thing with no date is a search of the calendar, not a look at the next month.
     *
     * This used to be today-forward-30-days, and that is what made "remove any MLBB events" answer
     * with whichever few happened to be near — which reads as the assistant not knowing where they
     * are. Backwards as well, because clearing out something that has finished is a normal want.
     */
    @Test
    fun `a named thing with no date searches the calendar either side of today`() {
        val today = LocalDate.of(2026, 7, 1)
        val (from, to) = EventMatcher.searchWindow(Query(titleQuery = "mlbb"), today = today)
        assertTrue("should look into the past", from.isBefore(today))
        assertTrue("should look most of a year ahead", to.isAfter(today.plusMonths(6)))
    }

    /** With no title to search on, the old near-future horizon is still the right one. */
    @Test
    fun `a bare time with no title still only looks ahead`() {
        val today = LocalDate.of(2026, 7, 1)
        val (from, to) = EventMatcher.searchWindow(Query(startTime = LocalTime.of(15, 0)), today = today)
        assertEquals(today, from)
        assertTrue(to.isAfter(today))
        assertTrue(to.isBefore(today.plusMonths(3)))
    }

    // --- the bulk clean-up ------------------------------------------------

    /**
     * The regression this whole widening exists for.
     *
     * "any mlbb related" tokenised to three words of which exactly one could ever match a title.
     * That scored 0.33 against a floor of 0.34, so the request that most obviously means "find all
     * of these" matched nothing at all — and the failure was silent.
     */
    @Test
    fun `any X related finds the X events`() {
        val candidates = listOf(
            event("MLBB Ranked", on = day),
            event("MLBB scrim", on = day),
            event("Dentist", on = day),
        )
        val matches = EventMatcher.match(Query(titleQuery = "any mlbb related", date = day), candidates, zone)
        assertEquals(2, matches.size)
        assertTrue(matches.all { it.title.startsWith("MLBB") })
    }

    @Test
    fun `asking for all of something is read off the user's own words`() {
        assertTrue(EventMatcher.isBulkRequest("remove any mlbb related events"))
        assertTrue(EventMatcher.isBulkRequest("delete all my standups"))
        assertTrue(EventMatcher.isBulkRequest("clear every gym session"))
        assertFalse(EventMatcher.isBulkRequest("cancel my 3pm"))
        // "call" contains "all" — whole words only, or half the verbs trigger a bulk delete.
        assertFalse(EventMatcher.isBulkRequest("cancel the call with Sam"))
    }

    @Test
    fun `a bulk request may return more than the usual shortlist`() {
        val candidates = (1..12).map { event("Standup $it", on = day) }
        val normal = EventMatcher.match(Query(titleQuery = "standup", date = day), candidates, zone)
        assertEquals(EventMatcher.MAX_MATCHES, normal.size)

        val bulk = EventMatcher.match(
            query = Query(titleQuery = "standup", date = day),
            candidates = candidates,
            zone = zone,
            limit = EventMatcher.MAX_BULK_MATCHES,
        )
        assertEquals(12, bulk.size)
    }

    /**
     * A quantifier and a generic noun both tokenise to nothing and mean opposite things.
     *
     * "Cancel all of tomorrow" named no thing on purpose, so everything that day is the answer.
     * "Cancel my appointment tomorrow" named one thing badly, and answering *that* with everything
     * on the day offers up the wrong event to delete. The difference is drawn before tokenising.
     */
    @Test
    fun `a quantifier means everything, a vague noun means refuse`() {
        val candidates = listOf(event("Dentist", on = day), event("Standup", on = day))

        val quantifier = EventMatcher.match(Query(titleQuery = "all", date = day), candidates, zone)
        assertEquals(2, quantifier.size)

        val vagueNoun = EventMatcher.match(Query(titleQuery = "appointment", date = day), candidates, zone)
        assertTrue("a vague noun with a day must still refuse", vagueNoun.isEmpty())

        val quantifierNoDay = EventMatcher.match(Query(titleQuery = "everything"), candidates, zone)
        assertTrue("cancel everything must not mean a year of calendar", quantifierNoDay.isEmpty())
    }

    // --- ordering over a wide window --------------------------------------

    /**
     * Searching a year either side puts a match from two months ago next to one from tomorrow. The
     * upcoming one is nearly always the one meant, so equally-good matches lead with what is still
     * to come.
     */
    @Test
    fun `equally good matches lead with what is still to come`() {
        val now = LocalDateTime.of(2026, 7, 20, 12, 0)
        val past = event("Dentist", on = day.minusDays(40))
        val soon = event("Dentist", on = day.plusDays(3))
        val later = event("Dentist", on = day.plusDays(200))

        val matches = EventMatcher.match(
            query = Query(titleQuery = "dentist"),
            candidates = listOf(past, later, soon),
            zone = zone,
            now = now,
        )
        assertEquals(listOf(soon.eventId, later.eventId, past.eventId), matches.map { it.eventId })
    }
}
