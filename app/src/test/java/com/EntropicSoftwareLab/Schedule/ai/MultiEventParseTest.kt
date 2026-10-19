package com.EntropicSoftwareLab.Schedule.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Multi-event replies, which used to collapse to a single card.
 *
 * The parser read only the first balanced JSON value in the reply, so a model that listed one object
 * per line instead of wrapping them in an array lost every event after the first — silently, with no
 * "I couldn't read that" to hint at it. A model with no response schema does exactly that regularly,
 * which is why this only bit the on-device path.
 */
class MultiEventParseTest {

    private val today: LocalDate = LocalDate.of(2026, 7, 19)

    private fun drafts(raw: String) = LlmJsonParser.parseEventDrafts(raw, today)

    private fun titles(raw: String): List<String> =
        (drafts(raw) as AssistantResult.Success).value.map { it.title }

    @Test
    fun `an array of four events yields four drafts`() {
        val raw = """
            [{"title":"Gym","date":"2026-07-20","start_time":"07:00","all_day":false},
             {"title":"Standup","date":"2026-07-20","start_time":"09:30","all_day":false},
             {"title":"Lunch with Sam","date":"2026-07-20","start_time":"13:00","all_day":false},
             {"title":"Dentist","date":"2026-07-20","start_time":"16:15","all_day":false}]
        """.trimIndent()
        assertEquals(listOf("Gym", "Standup", "Lunch with Sam", "Dentist"), titles(raw))
    }

    @Test
    fun `newline-delimited objects are all read, not just the first`() {
        // The regression: no array wrapper, one object per line.
        val raw = """
            {"title":"Standup","date":"2026-07-20","start_time":"09:00","all_day":false}
            {"title":"Client call","date":"2026-07-20","start_time":"14:00","all_day":false}
        """.trimIndent()
        assertEquals(listOf("Standup", "Client call"), titles(raw))
    }

    @Test
    fun `comma-separated objects with no array wrapper are all read`() {
        val raw = """{"title":"A","date":"2026-07-20","all_day":true}, """ +
            """{"title":"B","date":"2026-07-21","all_day":true}"""
        assertEquals(listOf("A", "B"), titles(raw))
    }

    @Test
    fun `two separate arrays in one reply are merged`() {
        val raw = """[{"title":"First","date":"2026-07-20","all_day":true}]""" + "\n" +
            """[{"title":"Second","date":"2026-07-21","all_day":true}]"""
        assertEquals(listOf("First", "Second"), titles(raw))
    }

    @Test
    fun `prose between the objects does not stop the scan`() {
        val raw = "Here's the first one:\n" +
            """{"title":"Gym","date":"2026-07-20","start_time":"07:00","all_day":false}""" + "\n" +
            "and the second:\n" +
            """{"title":"Dentist","date":"2026-07-20","start_time":"16:15","all_day":false}""" + "\n" +
            "Hope that helps!"
        assertEquals(listOf("Gym", "Dentist"), titles(raw))
    }

    @Test
    fun `a malformed fragment does not discard the well-formed events beside it`() {
        val raw = """{"title":"Good","date":"2026-07-20","start_time":"10:00","all_day":false}""" +
            "\n{not json at all}\n" +
            """{"title":"Also good","date":"2026-07-21","start_time":"11:00","all_day":false}"""
        assertEquals(listOf("Good", "Also good"), titles(raw))
    }

    @Test
    fun `fields survive the flattening intact`() {
        val raw = """
            {"title":"Standup","date":"2026-07-20","start_time":"09:00","end_time":"09:30","all_day":false,"location":"Room 2","assumption":null}
            {"title":"Leave","date":"2026-07-22","start_time":null,"end_time":null,"all_day":true,"location":null,"assumption":null}
        """.trimIndent()
        val value = (drafts(raw) as AssistantResult.Success).value
        assertEquals(2, value.size)

        assertEquals(LocalTime.of(9, 0), value[0].startTime)
        assertEquals(LocalTime.of(9, 30), value[0].endTime)
        assertEquals("Room 2", value[0].location)
        assertEquals(false, value[0].allDay)

        assertEquals(true, value[1].allDay)
        assertEquals(LocalDate.of(2026, 7, 22), value[1].date)
        // A null in JSON must not reach the UI as the four-character string "null".
        assertEquals(null, value[1].location)
        assertEquals(null, value[1].startTime)
    }

    // --- the old guarantees still hold -----------------------------------

    @Test
    fun `an empty array is still no events rather than a failure`() {
        assertTrue((drafts("[]") as AssistantResult.Success).value.isEmpty())
    }

    @Test
    fun `prose with no JSON at all is still unparseable`() {
        assertTrue(drafts("I'm not sure what you mean.") is AssistantResult.Unparseable)
    }

    @Test
    fun `a reply of only unreadable entries is still unparseable`() {
        assertTrue(drafts("""{"foo":"bar"}""" + "\n" + """{"baz":1}""") is AssistantResult.Unparseable)
    }

    @Test
    fun `truncated JSON is still unparseable`() {
        assertTrue(drafts("""[{"title":"Cut off","date":"2026-07-20"""") is AssistantResult.Unparseable)
    }
}
