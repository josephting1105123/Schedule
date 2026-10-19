package com.EntropicSoftwareLab.Schedule.ai

import com.EntropicSoftwareLab.Schedule.ai.IntentRouter.Intent
import org.junit.Assert.assertEquals
import org.junit.Test

class IntentRouterTest {

    private fun route(text: String) = IntentRouter.route(text)

    @Test
    fun `availability questions route to the query path`() {
        listOf(
            "am I free Tuesday 3pm?",
            "Am I busy tomorrow afternoon",
            "do I have anything on friday?",
            "anything on next week?",
            "what's on tomorrow",
            "when am I free this week",
            "is there a gap on monday morning?",
            "how busy am I thursday",
        ).forEach { assertEquals(it, Intent.CheckAvailability, route(it)) }
    }

    @Test
    fun `event descriptions route to the add path`() {
        listOf(
            "dinner with mum next friday 7ish",
            "dentist tomorrow at 10:30",
            "team standup 9-9:30 then a client call at 2",
            "I'm on leave all day monday",
            "Lunch with Sam Thursday noon",
        ).forEach { assertEquals(it, Intent.AddEvents, route(it)) }
    }

    @Test
    fun `a trailing question mark alone does not make it a question`() {
        // Someone jotting down a tentative plan, not asking about their schedule.
        assertEquals(Intent.AddEvents, route("dinner friday?"))
        assertEquals(Intent.AddEvents, route("coffee with Alex tomorrow 10am?"))
    }

    @Test
    fun `a question mark plus an availability word is a question`() {
        assertEquals(Intent.CheckAvailability, route("friday evening free?"))
    }

    @Test
    fun `routing ignores case and surrounding whitespace`() {
        assertEquals(Intent.CheckAvailability, route("   AM I FREE MONDAY?  "))
    }

    @Test
    fun `empty input defaults to the add path`() {
        assertEquals(Intent.AddEvents, route(""))
        assertEquals(Intent.AddEvents, route("   "))
    }

    @Test
    fun `cancellations route to the cancel path`() {
        listOf(
            "cancel my dentist appointment tomorrow",
            "delete the 3pm standup on friday",
            "remove lunch with Sam",
            "get rid of that meeting on monday",
            "drop the gym session tomorrow",
            "clear my calendar on tuesday",
            "call off the client call",
        ).forEach { assertEquals(it, Intent.CancelEvents, route(it)) }
    }

    /**
     * The reason the cancel check runs before the question openers: this opens exactly like an
     * availability question and isn't one.
     */
    @Test
    fun `a cancel verb beats a question opener`() {
        assertEquals(Intent.CancelEvents, route("can I cancel my 3pm?"))
    }

    @Test
    fun `an event whose name merely contains a cancel word still adds`() {
        // "Cancellation policy review" starts with no cancel verb; the word is part of the title.
        assertEquals(Intent.AddEvents, route("cancellation policy review tuesday 2pm"))
    }
}
