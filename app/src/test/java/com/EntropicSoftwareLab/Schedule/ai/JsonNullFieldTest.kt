package com.EntropicSoftwareLab.Schedule.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * JSON nulls must not reach the UI as the word "null".
 *
 * `org.json`'s `optString` returns the four-character string "null" for a JSON null rather than an
 * empty string, so the obvious `optString(k).ifBlank { null }` silently passes it through. The
 * prompt asks the model to write null for `location` and `assumption` whenever it hasn't inferred
 * anything, which makes this the common path — a draft card was rendering a lightbulb captioned
 * "null" on nearly every event.
 */
class JsonNullFieldTest {

    private val today: LocalDate = LocalDate.of(2026, 3, 10)

    private fun firstDraft(json: String): EventDraft {
        val result = LlmJsonParser.parseEventDrafts(json, today)
        val success = result as? AssistantResult.Success
            ?: error("expected a parsed draft, got $result")
        return success.value.first()
    }

    @Test
    fun `json null location and assumption become kotlin null`() {
        val draft = firstDraft(
            """[{"title":"Work","date":"2026-03-10","start_time":"06:30","end_time":"15:00",
               "all_day":false,"location":null,"assumption":null}]"""
        )
        assertNull(draft.location)
        assertNull(draft.assumption)
    }

    @Test
    fun `the literal string null is also treated as absent`() {
        // Small models frequently emit "null" as a quoted string rather than a JSON null.
        val draft = firstDraft(
            """[{"title":"Work","date":"2026-03-10","start_time":"06:30","end_time":"15:00",
               "all_day":false,"location":"null","assumption":"NULL"}]"""
        )
        assertNull(draft.location)
        assertNull(draft.assumption)
    }

    @Test
    fun `missing keys become null rather than empty strings`() {
        val draft = firstDraft(
            """[{"title":"Work","date":"2026-03-10","start_time":"06:30","end_time":"15:00","all_day":false}]"""
        )
        assertNull(draft.location)
        assertNull(draft.assumption)
    }

    @Test
    fun `real values still survive`() {
        val draft = firstDraft(
            """[{"title":"Work","date":"2026-03-10","start_time":"06:30","end_time":"15:00",
               "all_day":false,"location":"Zus Coffee","assumption":"Read 630am as 06:30."}]"""
        )
        assertEquals("Zus Coffee", draft.location)
        assertEquals("Read 630am as 06:30.", draft.assumption)
    }

    @Test
    fun `whitespace-only values are treated as absent`() {
        val draft = firstDraft(
            """[{"title":"Work","date":"2026-03-10","start_time":"06:30","end_time":"15:00",
               "all_day":false,"location":"   ","assumption":""}]"""
        )
        assertNull(draft.location)
        assertNull(draft.assumption)
    }

    @Test
    fun `a null title drops the draft rather than titling an event null`() {
        // Dropping the only entry leaves nothing readable in a non-empty array, which the parser
        // reports as unparseable rather than as "no events" — the retry path depends on that
        // distinction, so assert the failure kind and not just the absence of a draft.
        val result = LlmJsonParser.parseEventDrafts(
            """[{"title":null,"date":"2026-03-10","all_day":true}]""",
            today,
        )
        assertEquals(AssistantResult.Unparseable::class.java, result.javaClass)
    }

    @Test
    fun `a null title does not discard its siblings`() {
        val result = LlmJsonParser.parseEventDrafts(
            """[{"title":null,"date":"2026-03-10","all_day":true},
                {"title":"Work","date":"2026-03-10","start_time":"06:30","end_time":"15:00","all_day":false}]""",
            today,
        )
        val drafts = (result as AssistantResult.Success).value
        assertEquals(1, drafts.size)
        assertEquals("Work", drafts.first().title)
    }
}
