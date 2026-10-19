package com.EntropicSoftwareLab.Schedule.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class RecurrenceTest {

    // 2026-07-20 is a Monday.
    private val monday: LocalDate = LocalDate.of(2026, 7, 20)
    private val at9 = monday.atTime(9, 0)

    // Deliberately not UTC: a UTC-only test would pass while the UNTIL conversion was wrong for
    // every real user. +08:00 has no DST, so the offset is the only variable in play.
    private val kl: ZoneId = ZoneId.of("Asia/Kuala_Lumpur")

    private fun rule(
        freq: RecurrenceFreq,
        interval: Int = 1,
        end: RecurrenceEnd = RecurrenceEnd.Never,
    ) = RecurrenceRule(freq, interval, end)

    // --- building rules ---------------------------------------------------

    @Test
    fun `daily and yearly need no anchor fields`() {
        assertEquals("FREQ=DAILY", rule(RecurrenceFreq.Daily).toRRule(at9, kl, allDay = false))
        assertEquals("FREQ=YEARLY", rule(RecurrenceFreq.Yearly).toRRule(at9, kl, allDay = false))
    }

    @Test
    fun `weekly anchors to the start weekday`() {
        val codes = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
        codes.forEachIndexed { offset, code ->
            val start = monday.plusDays(offset.toLong()).atTime(9, 0)
            assertEquals(
                "FREQ=WEEKLY;BYDAY=$code",
                rule(RecurrenceFreq.Weekly).toRRule(start, kl, allDay = false),
            )
        }
    }

    @Test
    fun `monthly anchors to the start day of month`() {
        assertEquals(
            "FREQ=MONTHLY;BYMONTHDAY=20",
            rule(RecurrenceFreq.Monthly).toRRule(at9, kl, allDay = false),
        )
        assertEquals(
            "FREQ=MONTHLY;BYMONTHDAY=31",
            rule(RecurrenceFreq.Monthly)
                .toRRule(LocalDate.of(2026, 1, 31).atTime(9, 0), kl, allDay = false),
        )
    }

    @Test
    fun `interval of one is omitted so untouched events do not churn`() {
        assertEquals("FREQ=DAILY", rule(RecurrenceFreq.Daily, interval = 1).toRRule(at9, kl, false))
        assertEquals(
            "FREQ=DAILY;INTERVAL=3",
            rule(RecurrenceFreq.Daily, interval = 3).toRRule(at9, kl, false),
        )
    }

    @Test
    fun `count is written as COUNT`() {
        assertEquals(
            "FREQ=WEEKLY;BYDAY=MO;COUNT=10",
            rule(RecurrenceFreq.Weekly, end = RecurrenceEnd.AfterCount(10)).toRRule(at9, kl, false),
        )
    }

    /**
     * The one that would silently lose a day. UNTIL on a timed series must be UTC, so "until the
     * 30th" for a user at +08:00 is 30 Aug 23:59:59 local = 30 Aug 15:59:59 UTC. Writing the local
     * clock time instead would cut the last day short for everyone east of Greenwich.
     */
    @Test
    fun `timed UNTIL is the last local second expressed in UTC`() {
        val until = RecurrenceEnd.OnDate(LocalDate.of(2026, 8, 30))
        assertEquals(
            "FREQ=DAILY;UNTIL=20260830T155959Z",
            rule(RecurrenceFreq.Daily, end = until).toRRule(at9, kl, allDay = false),
        )
    }

    @Test
    fun `all-day UNTIL is a plain inclusive date with no zone conversion`() {
        val until = RecurrenceEnd.OnDate(LocalDate.of(2026, 8, 30))
        assertEquals(
            "FREQ=DAILY;UNTIL=20260830",
            rule(RecurrenceFreq.Daily, end = until).toRRule(at9, kl, allDay = true),
        )
    }

    // --- reading rules back -----------------------------------------------

    @Test
    fun `a blank rule is no repeat`() {
        assertEquals(RepeatSelection.None, parseRepeat(null, kl))
        assertEquals(RepeatSelection.None, parseRepeat("", kl))
        assertEquals(RepeatSelection.None, parseRepeat("   ", kl))
    }

    @Test
    fun `the RRULE prefix is tolerated`() {
        assertEquals(
            RepeatSelection.Simple(rule(RecurrenceFreq.Daily)),
            parseRepeat("RRULE:FREQ=DAILY", kl),
        )
    }

    @Test
    fun `interval and count round-trip`() {
        val parsed = parseRepeat("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO;COUNT=6", kl)
        assertEquals(
            RepeatSelection.Simple(
                rule(RecurrenceFreq.Weekly, interval = 2, end = RecurrenceEnd.AfterCount(6)),
            ),
            parsed,
        )
    }

    @Test
    fun `a UTC UNTIL comes back as the local date the user chose`() {
        val parsed = parseRepeat("FREQ=DAILY;UNTIL=20260830T155959Z", kl)
        assertEquals(
            RepeatSelection.Simple(
                rule(RecurrenceFreq.Daily, end = RecurrenceEnd.OnDate(LocalDate.of(2026, 8, 30))),
            ),
            parsed,
        )
    }

    /** Building then reading back has to land where it started, or an edit walks the end date. */
    @Test
    fun `until survives a build-then-parse round trip`() {
        val original = rule(
            RecurrenceFreq.Weekly,
            interval = 2,
            end = RecurrenceEnd.OnDate(LocalDate.of(2026, 12, 31)),
        )
        val built = original.toRRule(at9, kl, allDay = false)
        assertEquals(RepeatSelection.Simple(original), parseRepeat(built, kl))
    }

    /**
     * The rule that keeps an unrelated edit from rewriting someone's series. Anything this app can't
     * rebuild has to come back verbatim, not as the nearest thing it can express.
     */
    @Test
    fun `rules outside the vocabulary are kept verbatim rather than approximated`() {
        val exotic = listOf(
            "FREQ=MONTHLY;BYSETPOS=-1;BYDAY=FR",   // last Friday of the month
            "FREQ=WEEKLY;BYDAY=MO,WE,FR",          // several weekdays
            "FREQ=MONTHLY;BYMONTHDAY=-1",          // last day of the month
            "FREQ=HOURLY",                         // a frequency with no UI
            "FREQ=DAILY;COUNT=5;UNTIL=20260830",   // malformed: both ends
        )
        exotic.forEach { body ->
            val parsed = parseRepeat(body, kl)
            assertTrue("$body should be unsupported", parsed is RepeatSelection.Unsupported)
            assertEquals(body, (parsed as RepeatSelection.Unsupported).rrule)
        }
    }

    @Test
    fun `an unreadable UNTIL does not silently become repeats-forever`() {
        val parsed = parseRepeat("FREQ=DAILY;UNTIL=not-a-date", kl)
        assertTrue(parsed is RepeatSelection.Unsupported)
    }

    // --- capping a series -------------------------------------------------

    @Test
    fun `capping cuts a timed series one second before the occurrence`() {
        // Series starts Mon 09:00; cut at the following Monday's occurrence.
        val capped = capRRuleBefore(
            rrule = "FREQ=WEEKLY;BYDAY=MO",
            instanceStart = monday.plusDays(7).atTime(9, 0),
            seriesStart = at9,
            zone = kl,
            allDay = false,
        )
        // 27 Jul 09:00 +08:00 minus one second = 27 Jul 00:59:59 UTC.
        assertEquals("FREQ=WEEKLY;BYDAY=MO;UNTIL=20260727T005959Z", capped)
    }

    @Test
    fun `capping an all-day series ends it the day before`() {
        val capped = capRRuleBefore(
            rrule = "FREQ=DAILY",
            instanceStart = monday.plusDays(3).atStartOfDay(),
            seriesStart = monday.atStartOfDay(),
            zone = kl,
            allDay = true,
        )
        assertEquals("FREQ=DAILY;UNTIL=20260722", capped)
    }

    @Test
    fun `capping drops any end the rule already had`() {
        val capped = capRRuleBefore(
            rrule = "FREQ=DAILY;COUNT=20",
            instanceStart = monday.plusDays(2).atTime(9, 0),
            seriesStart = at9,
            zone = kl,
            allDay = false,
        )
        // 22 Jul 09:00 +08:00 minus one second is 00:59:59 UTC on the 22nd, not the 21st — the
        // offset moves the clock back across midnight but not the date.
        assertEquals("FREQ=DAILY;UNTIL=20260722T005959Z", capped)
    }

    /**
     * Capping at the first occurrence would leave an UNTIL earlier than DTSTART — a series that can
     * never fire but still exists as a row. Null tells the repository to delete instead.
     */
    @Test
    fun `capping at or before the series start is refused`() {
        assertNull(
            capRRuleBefore("FREQ=DAILY", at9, at9, kl, allDay = false),
        )
        assertNull(
            capRRuleBefore("FREQ=DAILY", at9.minusDays(1), at9, kl, allDay = false),
        )
    }

    // --- labels -----------------------------------------------------------

    @Test
    fun `labels name the anchor so the choice is not ambiguous`() {
        assertEquals("Does not repeat", RepeatSelection.None.label(monday))
        assertEquals("Weekly on Monday", rule(RecurrenceFreq.Weekly).label(monday))
        assertEquals("Monthly on day 20", rule(RecurrenceFreq.Monthly).label(monday))
        assertEquals("Every 2 weeks on Monday", rule(RecurrenceFreq.Weekly, 2).label(monday))
        assertEquals(
            "Daily, 5 times",
            rule(RecurrenceFreq.Daily, end = RecurrenceEnd.AfterCount(5)).label(monday),
        )
        assertEquals(
            "Daily, until 30 Aug 2026",
            rule(RecurrenceFreq.Daily, end = RecurrenceEnd.OnDate(LocalDate.of(2026, 8, 30)))
                .label(monday),
        )
    }

    @Test
    fun `an unsupported rule says so rather than describing itself wrongly`() {
        assertEquals(
            "Custom repeat (kept as is)",
            RepeatSelection.Unsupported("FREQ=MONTHLY;BYSETPOS=-1;BYDAY=FR").label(monday),
        )
    }

    // --- durations --------------------------------------------------------

    private fun at(hour: Int, minute: Int = 0): LocalDateTime = monday.atTime(hour, minute)

    @Test
    fun `timed durations are whole seconds`() {
        assertEquals("PT3600S", rfc2445Duration(at(9), at(10), allDay = false))
        assertEquals("PT5400S", rfc2445Duration(at(9), at(10, 30), allDay = false))
    }

    @Test
    fun `timed durations spanning midnight keep counting`() {
        assertEquals(
            "PT7200S",
            rfc2445Duration(at(23), monday.plusDays(1).atTime(1, 0), allDay = false),
        )
    }

    @Test
    fun `all-day durations are whole days with a one-day floor`() {
        assertEquals("P1D", rfc2445Duration(at(0), monday.plusDays(1).atStartOfDay(), allDay = true))
        assertEquals("P3D", rfc2445Duration(at(0), monday.plusDays(3).atStartOfDay(), allDay = true))
        // A same-day all-day draft still has to last a day, matching the exclusive-DTEND rule.
        assertEquals("P1D", rfc2445Duration(at(0), at(0), allDay = true))
    }

    @Test
    fun `degenerate timed drafts floor at zero rather than going negative`() {
        assertEquals("PT0S", rfc2445Duration(at(10), at(9), allDay = false))
    }
}
