package com.EntropicSoftwareLab.Schedule.data.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The learner decides what the assistant believes about the user, and it does it silently — a rule
 * that drifts wrong shows up only as drafts that are quietly a bit worse, weeks later. Everything it
 * promises is pinned here.
 */
class MemoryLearnerTest {

    private fun event(
        title: String,
        start: Int? = null,
        duration: Int? = null,
        location: String? = null,
        allDay: Boolean = false,
    ) = ObservedEvent(title, start, duration, location, allDay)

    private fun confirm(
        memory: AssistantMemory,
        confirmed: ObservedEvent,
        proposed: ObservedEvent? = null,
        text: String = "gym tomorrow",
        sole: Boolean = true,
        day: Long = 100,
    ) = MemoryLearner.observe(
        memory,
        Observation.Confirmed(text, proposed, confirmed, sole),
        day,
    )

    private fun habit(memory: AssistantMemory, key: String) = memory.habits.first { it.key == key }

    // --- promotion ---------------------------------------------------------

    @Test
    fun `one confirmation is not yet a habit`() {
        val memory = confirm(AssistantMemory.EMPTY, event("Gym", start = 390, duration = 90))
        assertEquals(1, memory.habits.size)
        assertFalse(habit(memory, "gym").isReliable)
        assertEquals("", MemoryPrompt.render(memory))
    }

    @Test
    fun `the same event twice becomes a habit the model is told about`() {
        var memory = confirm(AssistantMemory.EMPTY, event("Gym", start = 390, duration = 90))
        memory = confirm(memory, event("Gym", start = 390, duration = 90))

        val gym = habit(memory, "gym")
        assertTrue(gym.isReliable)
        assertEquals(2, gym.supporting)
        assertTrue(MemoryPrompt.render(memory).contains("\"Gym\" usually starts at 06:30, runs 1h30m."))
    }

    @Test
    fun `a quarter hour apart is the same habit, not a change of mind`() {
        var memory = confirm(AssistantMemory.EMPTY, event("Gym", start = 390, duration = 90))
        memory = confirm(memory, event("Gym", start = 400, duration = 90))

        val gym = habit(memory, "gym")
        assertEquals(2, gym.supporting)
        assertEquals(0, gym.contradicting)
        // Agreement keeps the established value: a habit means "usually", not "last time".
        assertEquals(390, gym.startMinute)
    }

    @Test
    fun `a real change is adopted but still counts against confidence`() {
        var memory = confirm(AssistantMemory.EMPTY, event("Gym", start = 390, duration = 90))
        memory = confirm(memory, event("Gym", start = 390, duration = 90))
        memory = confirm(memory, event("Gym", start = 1140, duration = 90))

        val gym = habit(memory, "gym")
        assertEquals(1140, gym.startMinute)
        assertEquals(1, gym.contradicting)
        assertEquals(2, gym.supporting)
    }

    // --- forgetting --------------------------------------------------------

    @Test
    fun `a habit contradicted enough is deleted rather than merely muted`() {
        var memory = confirm(AssistantMemory.EMPTY, event("Gym", start = 390, duration = 60))
        memory = confirm(memory, event("Gym", start = 600, duration = 60))
        memory = confirm(memory, event("Gym", start = 900, duration = 60))
        memory = confirm(memory, event("Gym", start = 1200, duration = 60))

        assertTrue(memory.habits.none { it.key == "gym" })
    }

    @Test
    fun `discarding a card only scores against the habit that shaped it`() {
        var memory = confirm(AssistantMemory.EMPTY, event("Gym", start = 390, duration = 90))
        memory = confirm(memory, event("Gym", start = 390, duration = 90))

        // Unrelated proposal: the memory had no hand in it, so it must not be punished for it.
        val untouched = MemoryLearner.observe(
            memory,
            Observation.Discarded("gym at 2pm", event("Gym", start = 840, duration = 90)),
            101,
        )
        assertEquals(0, habit(untouched, "gym").contradicting)

        // The habit's own values coming back and being binned is a genuine contradiction.
        val scored = MemoryLearner.observe(
            memory,
            Observation.Discarded("gym tomorrow", event("Gym", start = 390, duration = 90)),
            101,
        )
        assertEquals(1, habit(scored, "gym").contradicting)
    }

    @Test
    fun `confirm then undo leaves the habit below the bar to speak`() {
        var memory = confirm(AssistantMemory.EMPTY, event("Gym", start = 390, duration = 90))
        memory = confirm(memory, event("Gym", start = 390, duration = 90))
        // Two agreements, then the undo of one of them.
        memory = MemoryLearner.observe(
            memory,
            Observation.Discarded("gym tomorrow", event("Gym", start = 390, duration = 90)),
            101,
        )
        // 2 supporting against 1 contradicting is 0.67 — still spoken, which is right: they
        // confirmed it twice and undid it once.
        assertTrue(habit(memory, "gym").isReliable)

        val single = MemoryLearner.observe(
            confirm(AssistantMemory.EMPTY, event("Yoga", start = 600, duration = 60)),
            Observation.Discarded("yoga at 10", event("Yoga", start = 600, duration = 60)),
            101,
        )
        // One confirm, one undo: exactly 0.5, which must not reach the model.
        assertFalse(habit(single, "yoga").isReliable)
    }

    // --- what is learned ---------------------------------------------------

    @Test
    fun `all-day events are learned as all-day`() {
        var memory = confirm(AssistantMemory.EMPTY, event("Leave", allDay = true))
        memory = confirm(memory, event("Leave", allDay = true))
        assertTrue(MemoryPrompt.render(memory).contains("\"Leave\" is usually an all-day event."))
    }

    @Test
    fun `a place seen twice is learned`() {
        var memory = confirm(AssistantMemory.EMPTY, event("Standup", start = 570, location = "Room 2"))
        memory = confirm(memory, event("Standup", start = 570, location = "Room 2"))
        assertTrue(MemoryPrompt.render(memory).contains("at Room 2"))
    }

    @Test
    fun `who you are with does not split the habit`() {
        assertEquals(titleKey("Dinner with mum"), titleKey("Dinner with the Wilsons"))
        assertEquals("dinner", titleKey("Dinner with mum"))
        // Head-final compounds keep both words, or "Team standup" would swallow every team event.
        assertEquals("team standup", titleKey("Team standup"))
        assertEquals("coffee", titleKey("Coffee w/ Tom"))
    }

    @Test
    fun `the default duration follows the median, not one long outlier`() {
        var memory = AssistantMemory.EMPTY
        repeat(4) { memory = confirm(memory, event("Call ${it}", start = 600, duration = 30)) }
        memory = confirm(memory, event("Flight", start = 600, duration = 8 * 60))

        // The flight is a real event and does enter the sample. A running average of the five would
        // read 120 minutes and make every unspecified event four times too long; the median doesn't
        // move. That is the whole reason this isn't an average.
        assertEquals(30, memory.style.defaultDurationMinutes)
        assertTrue(
            MemoryPrompt.render(memory)
                .contains("When no end time is given, assume 30m rather than an hour.")
        )
    }

    @Test
    fun `an hour is the prompt's own default and is not restated`() {
        var memory = AssistantMemory.EMPTY
        repeat(5) { memory = confirm(memory, event("Call ${it}", start = 600, duration = 60)) }
        assertEquals(60, memory.style.defaultDurationMinutes)
        assertFalse(MemoryPrompt.render(memory).contains("rather than an hour"))
    }

    @Test
    fun `title casing is only claimed when it is consistent`() {
        var mixed = AssistantMemory.EMPTY
        repeat(3) { mixed = confirm(mixed, event("Gym ${it}", start = 600, duration = 60)) }
        repeat(3) { mixed = confirm(mixed, event("gym ${it}", start = 600, duration = 60)) }
        assertNull(mixed.style.titleStyle)

        var lower = AssistantMemory.EMPTY
        repeat(6) { lower = confirm(lower, event("gym ${it}", start = 600, duration = 60)) }
        assertEquals(TitleStyle.Lowercase, lower.style.titleStyle)
    }

    // --- corrections -------------------------------------------------------

    @Test
    fun `a corrected draft is remembered as an example`() {
        val memory = confirm(
            AssistantMemory.EMPTY,
            confirmed = event("Gym", start = 390, duration = 90),
            proposed = event("Gym", start = 540, duration = 60),
            text = "gym tomorrow",
        )
        assertEquals(1, memory.corrections.size)
        assertEquals(
            "- \"gym tomorrow\" → Gym, 06:30–08:00",
            MemoryPrompt.correctionLine(memory.corrections.first()),
        )
    }

    @Test
    fun `a draft accepted unchanged teaches no example`() {
        val unchanged = event("Gym", start = 390, duration = 90)
        val memory = confirm(AssistantMemory.EMPTY, confirmed = unchanged, proposed = unchanged)
        assertTrue(memory.corrections.isEmpty())
    }

    @Test
    fun `one card out of several is never paired with the whole sentence`() {
        // The regression this rule exists for: pairing "gym at 7, standup 9:30, lunch at 1" with a
        // single event teaches by example that a three-event sentence yields one event.
        val memory = confirm(
            AssistantMemory.EMPTY,
            confirmed = event("Gym", start = 390, duration = 90),
            proposed = event("Gym", start = 420, duration = 60),
            text = "gym at 7, standup 9:30, lunch at 1",
            sole = false,
        )
        assertTrue(memory.corrections.isEmpty())
    }

    @Test
    fun `an essay pasted in once does not become a permanent prompt passenger`() {
        val memory = confirm(
            AssistantMemory.EMPTY,
            confirmed = event("Gym", start = 390, duration = 90),
            proposed = event("Gym", start = 540, duration = 60),
            text = "x".repeat(MemoryLearner.MAX_CORRECTION_TEXT + 1),
        )
        assertTrue(memory.corrections.isEmpty())
    }

    @Test
    fun `the same phrase corrected again replaces the old example`() {
        var memory = confirm(
            AssistantMemory.EMPTY,
            confirmed = event("Gym", start = 390, duration = 90),
            proposed = event("Gym", start = 540, duration = 60),
            text = "gym tomorrow",
        )
        memory = confirm(
            memory,
            confirmed = event("Gym", start = 1200, duration = 60),
            proposed = event("Gym", start = 540, duration = 60),
            text = "Gym tomorrow",
        )
        assertEquals(1, memory.corrections.size)
        assertEquals(1200, memory.corrections.first().event.startMinute)
    }

    @Test
    fun `corrections and habits are both capped`() {
        var memory = AssistantMemory.EMPTY
        repeat(MemoryLearner.MAX_CORRECTIONS + 4) { i ->
            memory = confirm(
                memory,
                confirmed = event("Thing $i", start = 600, duration = 90),
                proposed = event("Thing $i", start = 540, duration = 60),
                text = "thing $i",
            )
        }
        assertEquals(MemoryLearner.MAX_CORRECTIONS, memory.corrections.size)

        var many = AssistantMemory.EMPTY
        repeat(MemoryLearner.MAX_HABITS + 5) { i ->
            many = confirm(many, event("Event $i", start = 600, duration = 60), day = i.toLong())
        }
        assertEquals(MemoryLearner.MAX_HABITS, many.habits.size)
        // The stalest go first, so the survivors are the most recent.
        assertTrue(many.habits.none { it.key == "event 0" })
    }

    @Test
    fun `a blank title is not filed as a habit`() {
        val memory = confirm(AssistantMemory.EMPTY, event("   ", start = 600, duration = 60))
        assertTrue(memory.habits.isEmpty())
    }

    @Test
    fun `only eight habits reach the prompt however many are known`() {
        var memory = AssistantMemory.EMPTY
        repeat(12) { i ->
            memory = confirm(memory, event("Event $i", start = 600, duration = 90))
            memory = confirm(memory, event("Event $i", start = 600, duration = 90))
        }
        val lines = MemoryPrompt.render(memory).lines().filter { it.startsWith("- \"") }
        assertEquals(MemoryPrompt.MAX_HABIT_LINES, lines.size)
    }
}
