package com.EntropicSoftwareLab.Schedule.ai

import com.EntropicSoftwareLab.Schedule.data.assistant.AssistantMemory
import com.EntropicSoftwareLab.Schedule.data.assistant.Habit
import com.EntropicSoftwareLab.Schedule.data.assistant.MemoryPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * The prompt is ordered by how often each part changes: instructions, then learned habits, then the
 * date, then the user's text. That ordering is what keeps the expensive part of the request a stable
 * prefix, and nothing enforces it but this test — a well-meant edit that moves the date back to the
 * top would break prefix caching silently and cost nothing visible.
 */
class PromptBuilderTest {

    private val monday = LocalDateTime.of(2026, 8, 3, 9, 15)
    private val zone = "Europe/London"

    private val memory = AssistantMemory(
        habits = listOf(
            Habit("gym", "Gym", startMinute = 390, durationMinutes = 90, supporting = 4),
        ),
    )

    private fun block() = MemoryPrompt.render(memory)

    @Test
    fun `no memory means no memory section`() {
        val prompt = PromptBuilder.eventExtraction("dinner friday", monday, zone)
        assertFalse(prompt.contains("What this user's own confirmed events show"))
        assertTrue(prompt.trimEnd().endsWith("dinner friday"))
    }

    @Test
    fun `everything that changes per request comes after everything that doesn't`() {
        val prompt = PromptBuilder.eventExtraction("dinner friday", monday, zone, block())

        val rules = prompt.indexOf("Extract every calendar event")
        val examples = prompt.indexOf("User: dinner with mum next friday 7ish")
        val habits = prompt.indexOf("What this user's own confirmed events show")
        val date = prompt.indexOf("Today is Monday, 2026-08-03")
        val text = prompt.indexOf("dinner friday")

        assertTrue("rules first", rules in 0 until examples)
        assertTrue("habits after the static block", examples < habits)
        assertTrue("date after the habits", habits < date)
        assertTrue("the user's text last", date < text)
    }

    @Test
    fun `the static prefix is identical whatever the date and text`() {
        val monPrompt = PromptBuilder.eventExtraction("dinner friday", monday, zone, block())
        val tuePrompt = PromptBuilder.eventExtraction(
            "something else entirely",
            monday.plusDays(1).plusHours(3),
            "Asia/Tokyo",
            block(),
        )
        // Everything up to the date line — instructions and learned habits — must be byte-identical,
        // or there is no shared prefix for a cache to hit on.
        val monPrefix = monPrompt.substringBefore("Today is ")
        val tuePrefix = tuePrompt.substringBefore("Today is ")
        assertEquals(monPrefix, tuePrefix)
        assertTrue(monPrefix.contains("\"Gym\" usually starts at 06:30"))
    }

    @Test
    fun `the date still reaches the model, next to the sentence it applies to`() {
        val prompt = PromptBuilder.eventExtraction("dinner friday", monday, zone, block())
        assertTrue(prompt.contains("Today is Monday, 2026-08-03. The current time is 09:15 in Europe/London."))
        // Between them sits only the one-line instruction, so a small model has the date in view.
        val between = prompt.substringAfter("Europe/London.").substringBefore("dinner friday")
        assertTrue(between.length < 80)
    }

    @Test
    fun `availability prompts carry the same memory and the same ordering`() {
        val prompt = PromptBuilder.availabilityQuery("am i free tuesday 3pm?", monday, zone, block())
        val examples = prompt.indexOf("User: am I free tuesday 3pm?")
        val habits = prompt.indexOf("What this user's own confirmed events show")
        val date = prompt.indexOf("Today is Monday")
        assertTrue(examples < habits)
        assertTrue(habits < date)
    }

    @Test
    fun `the model is told the user's own words outrank their habits`() {
        val prompt = PromptBuilder.eventExtraction("gym at 3pm", monday, zone, block())
        assertTrue(
            prompt.contains("Anything the user's own text says beats anything you have been told they usually do.")
        )
    }
}
