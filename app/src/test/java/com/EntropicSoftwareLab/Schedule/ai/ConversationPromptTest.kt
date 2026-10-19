package com.EntropicSoftwareLab.Schedule.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * The block that gives the model a memory of the current chat. Its two rules are easy to break by
 * accident and invisible when broken — the symptom is only that answers get slightly worse.
 */
class ConversationPromptTest {

    private var nextId = 0

    private fun user(text: String, files: List<String> = emptyList()) =
        ChatEntry.User(nextId++, text, files)

    private fun note(text: String) = ChatEntry.Note(nextId++, text)

    private fun draft(
        title: String = "Gym",
        date: LocalDate = LocalDate.of(2026, 8, 7),
        start: LocalTime? = LocalTime.of(6, 30),
        end: LocalTime? = LocalTime.of(8, 0),
        allDay: Boolean = false,
    ) = EventDraft(title = title, date = date, startTime = start, endTime = end, allDay = allDay)

    private fun card(id: Int, committed: Boolean = false, draft: EventDraft = draft()) = DraftCard(
        id = id,
        draft = draft,
        committedEventId = if (committed) 99L else null,
    )

    @Test
    fun `the message being answered is not repeated above itself`() {
        val entries = listOf(user("dinner friday 7pm"), note("Added."), user("make it 8"))
        val rendered = ConversationPrompt.render(entries, emptyList(), emptyList())

        assertTrue("the earlier turn should be there", rendered.contains("dinner friday 7pm"))
        // The newest entry is the question sitting at the end of the prompt already. Repeating it
        // here invites the model to answer it twice.
        assertFalse("the current message must not appear", rendered.contains("make it 8"))
    }

    @Test
    fun `nothing to say on the first message`() {
        assertEquals("", ConversationPrompt.render(listOf(user("hello")), emptyList(), emptyList()))
        assertEquals("", ConversationPrompt.render(emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun `a proposed card is summarised, and says whether it was added`() {
        val entries = listOf(
            user("gym tomorrow"),
            ChatEntry.Drafts(nextId++, listOf(1)),
            user("and again on thursday"),
        )
        val rendered = ConversationPrompt.render(entries, listOf(card(1, committed = true)), emptyList())

        assertTrue(rendered.contains("Gym on 2026-08-07 at 06:30–08:00"))
        assertTrue(rendered.contains("(added)"))
    }

    @Test
    fun `a card the user never confirmed says so`() {
        val entries = listOf(
            user("gym tomorrow"),
            ChatEntry.Drafts(nextId++, listOf(1)),
            user("actually no"),
        )
        val rendered = ConversationPrompt.render(entries, listOf(card(1, committed = false)), emptyList())
        assertTrue(rendered.contains("(not added)"))
    }

    @Test
    fun `an all-day draft reads as all day rather than as a missing time`() {
        val entries = listOf(
            user("leave monday"),
            ChatEntry.Drafts(nextId++, listOf(1)),
            user("and tuesday"),
        )
        val rendered = ConversationPrompt.render(
            entries,
            listOf(card(1, draft = draft(title = "Leave", start = null, end = null, allDay = true))),
            emptyList(),
        )
        assertTrue(rendered.contains("Leave on 2026-08-07 all day"))
    }

    @Test
    fun `attachments are named so a follow-up about them means something`() {
        val entries = listOf(user("", files = listOf("timetable.pdf")), user("add the tuesday ones"))
        val rendered = ConversationPrompt.render(entries, emptyList(), emptyList())
        assertTrue(rendered.contains("[attached timetable.pdf]"))
    }

    @Test
    fun `only the recent turns travel`() {
        val entries = (1..30).map { user("message $it") }
        val rendered = ConversationPrompt.render(entries, emptyList(), emptyList())
        val lines = rendered.lines().filter { it.startsWith("User:") }

        assertEquals(ConversationPrompt.MAX_TURNS, lines.size)
        assertFalse("the oldest turns are dropped", rendered.contains("message 1\n"))
        // 29 is the last entry before the one being answered (30).
        assertTrue(rendered.contains("message 29"))
    }

    @Test
    fun `a pasted essay does not become a permanent passenger`() {
        val entries = listOf(user("x".repeat(5_000)), user("what about it"))
        val rendered = ConversationPrompt.render(entries, emptyList(), emptyList())
        val longest = rendered.lines().maxOf { it.length }
        assertTrue("one line was $longest characters", longest < 200)
    }

    @Test
    fun `an unparseable turn carries nothing forward`() {
        val entries = listOf(ChatEntry.Unparseable(nextId++, "asdfgh"), user("try again"))
        assertEquals("", ConversationPrompt.render(entries, emptyList(), emptyList()))
    }
}
