package com.EntropicSoftwareLab.Schedule.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * These cases are the actual failure modes of a small instruct model: prose around the JSON,
 * markdown fences, a bare object instead of an array, missing fields, and invented values. The app
 * has to degrade to "I couldn't read that" rather than to a wrong calendar entry.
 */
class LlmJsonParserTest {

    private val today: LocalDate = LocalDate.of(2026, 7, 19)
    private val now: LocalDateTime = today.atTime(14, 30)

    private fun drafts(raw: String) = LlmJsonParser.parseEventDrafts(raw, today)

    // --- extraction tolerance --------------------------------------------

    @Test
    fun `clean array parses`() {
        val result = drafts("""[{"title":"Dinner","date":"2026-07-24","start_time":"19:00","end_time":"20:00"}]""")
        val value = (result as AssistantResult.Success).value
        assertEquals(1, value.size)
        assertEquals("Dinner", value[0].title)
        assertEquals(LocalDate.of(2026, 7, 24), value[0].date)
        assertEquals(LocalTime.of(19, 0), value[0].startTime)
        assertEquals(LocalTime.of(20, 0), value[0].endTime)
    }

    @Test
    fun `markdown fences are stripped`() {
        val raw = "```json\n[{\"title\":\"Gym\",\"date\":\"2026-07-20\",\"start_time\":\"07:00\"}]\n```"
        val value = (drafts(raw) as AssistantResult.Success).value
        assertEquals("Gym", value[0].title)
    }

    @Test
    fun `surrounding prose is ignored`() {
        val raw = "Sure! Here is the event you asked for:\n" +
            "[{\"title\":\"Call\",\"date\":\"2026-07-21\",\"start_time\":\"09:00\"}]\n" +
            "Let me know if you need anything else."
        val value = (drafts(raw) as AssistantResult.Success).value
        assertEquals("Call", value[0].title)
    }

    @Test
    fun `a bare object is accepted as a single event`() {
        val value = (drafts("""{"title":"Solo","date":"2026-07-20","start_time":"10:00"}""") as AssistantResult.Success).value
        assertEquals(1, value.size)
        assertEquals("Solo", value[0].title)
    }

    @Test
    fun `an empty array means no events, not a failure`() {
        val value = (drafts("[]") as AssistantResult.Success).value
        assertTrue(value.isEmpty())
    }

    @Test
    fun `nested braces inside strings do not truncate extraction`() {
        val raw = """[{"title":"Review {draft}","date":"2026-07-20","start_time":"10:00"}]"""
        val value = (drafts(raw) as AssistantResult.Success).value
        assertEquals("Review {draft}", value[0].title)
    }

    // --- rejection --------------------------------------------------------

    @Test
    fun `plain prose with no JSON is unparseable`() {
        assertTrue(drafts("I'm not sure what you mean.") is AssistantResult.Unparseable)
    }

    @Test
    fun `truncated JSON is unparseable`() {
        assertTrue(drafts("""[{"title":"Cut off","date":"2026-07-20"""") is AssistantResult.Unparseable)
    }

    @Test
    fun `entries with no usable fields are unparseable rather than invented`() {
        assertTrue(drafts("""[{"foo":"bar"}]""") is AssistantResult.Unparseable)
    }

    @Test
    fun `an entry missing a title is dropped`() {
        // One good entry, one junk entry: keep the good one rather than failing the whole reply.
        val value = (drafts(
            """[{"title":"Good","date":"2026-07-20","start_time":"10:00"},{"date":"2026-07-21"}]"""
        ) as AssistantResult.Success).value
        assertEquals(1, value.size)
        assertEquals("Good", value[0].title)
    }

    // --- field repair -----------------------------------------------------

    @Test
    fun `a missing end time defaults to one hour`() {
        val value = (drafts("""[{"title":"Coffee","date":"2026-07-20","start_time":"15:00"}]""") as AssistantResult.Success).value
        assertEquals(LocalTime.of(16, 0), value[0].endTime)
    }

    @Test
    fun `an explicit duration is honoured over the default`() {
        val value = (drafts(
            """[{"title":"Dentist","date":"2026-07-20","start_time":"10:30","duration_minutes":30}]"""
        ) as AssistantResult.Success).value
        assertEquals(LocalTime.of(11, 0), value[0].endTime)
    }

    @Test
    fun `an end before the start is repaired and flagged`() {
        val value = (drafts(
            """[{"title":"Backwards","date":"2026-07-20","start_time":"15:00","end_time":"14:00"}]"""
        ) as AssistantResult.Success).value
        assertEquals(LocalTime.of(16, 0), value[0].endTime)
        assertTrue(value[0].assumption!!.contains("assumed 1 hour"))
    }

    @Test
    fun `all-day events carry no times`() {
        val value = (drafts(
            """[{"title":"Leave","date":"2026-07-20","all_day":true}]"""
        ) as AssistantResult.Success).value
        assertTrue(value[0].allDay)
        assertNull(value[0].startTime)
    }

    @Test
    fun `the assumption note survives to the confirmation card`() {
        val value = (drafts(
            """[{"title":"Dinner","date":"2026-07-24","start_time":"19:00","assumption":"Read \"7ish\" as 19:00."}]"""
        ) as AssistantResult.Success).value
        assertEquals("Read \"7ish\" as 19:00.", value[0].assumption)
    }

    // --- value validation -------------------------------------------------

    @Test
    fun `an impossible date is rejected`() {
        assertNull(LlmJsonParser.parseDate("2026-13-45", today))
    }

    @Test
    fun `a wildly distant date is rejected rather than scheduled`() {
        // A model that returns 2099 has misread the input; better to fail visibly.
        assertNull(LlmJsonParser.parseDate("2099-01-01", today))
    }

    @Test
    fun `an out-of-range time is rejected rather than clamped`() {
        assertNull(LlmJsonParser.parseTime("25:00"))
        assertNull(LlmJsonParser.parseTime("12:75"))
    }

    @Test
    fun `single-digit hours parse`() {
        assertEquals(LocalTime.of(9, 5), LlmJsonParser.parseTime("9:05"))
    }

    @Test
    fun `the literal string null is treated as absent`() {
        assertNull(LlmJsonParser.parseTime("null"))
    }

    // --- availability queries ---------------------------------------------

    @Test
    fun `an availability query resolves to a window`() {
        val result = LlmJsonParser.parseAvailabilityQuery(
            """{"date":"2026-07-21","start_time":"15:00","end_time":"16:00","phrasing":"Tuesday at 15:00"}""",
            now,
        )
        val query = (result as AssistantResult.Success).value
        assertEquals(LocalDateTime.of(2026, 7, 21, 15, 0), query.start)
        assertEquals(LocalDateTime.of(2026, 7, 21, 16, 0), query.end)
        assertEquals("Tuesday at 15:00", query.phrasing)
    }

    @Test
    fun `a query with no time covers the whole day`() {
        val query = (LlmJsonParser.parseAvailabilityQuery(
            """{"date":"2026-07-21","start_time":null,"end_time":null,"phrasing":"Tuesday"}""",
            now,
        ) as AssistantResult.Success).value
        assertEquals(LocalDateTime.of(2026, 7, 21, 0, 0), query.start)
        assertEquals(LocalDateTime.of(2026, 7, 22, 0, 0), query.end)
    }

    @Test
    fun `a query with only a start assumes an hour`() {
        val query = (LlmJsonParser.parseAvailabilityQuery(
            """{"date":"2026-07-21","start_time":"15:00","phrasing":"Tuesday at 15:00"}""",
            now,
        ) as AssistantResult.Success).value
        assertEquals(LocalDateTime.of(2026, 7, 21, 16, 0), query.end)
    }

    @Test
    fun `an unreadable query is unparseable`() {
        assertTrue(
            LlmJsonParser.parseAvailabilityQuery("no idea sorry", now) is AssistantResult.Unparseable
        )
    }
}
