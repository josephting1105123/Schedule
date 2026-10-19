package com.EntropicSoftwareLab.Schedule.ui.screens.assistant

import com.EntropicSoftwareLab.Schedule.ai.AgentTool
import com.EntropicSoftwareLab.Schedule.ai.EventDraft
import com.EntropicSoftwareLab.Schedule.ai.EventHandles
import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * The boundary where a model's JSON becomes an action. Two things are pinned: that a proposal
 * naming an event nobody searched for is refused, and that the sloppier ways a model writes an
 * absent field all mean absent.
 */
class AgentToolExecutorTest {

    private val handles = EventHandles()
    private val proposedEvents = mutableListOf<EventDraft>()
    private val proposedCancels = mutableListOf<EventInstance>()
    private val proposedUpdates = mutableListOf<ProposedUpdate>()
    private var searched: Triple<String?, LocalDate, LocalDate>? = null
    /** What onResolveColor hands back for the next call. Null means "that account has no such colour". */
    private var colorToResolve: ResolvedColor? = null

    private fun event(id: Long, title: String = "Dentist") = EventInstance(
        eventId = id,
        instanceId = id * 10,
        title = title,
        location = null,
        startMillis = 1_700_000_000_000L,
        endMillis = 1_700_003_600_000L,
        allDay = false,
        calendarId = 1,
        displayColor = 0,
    )

    private val executor = AgentToolExecutor(
        handles = handles,
        onSearch = { query, from, to ->
            searched = Triple(query, from, to)
            listOf(event(1), event(2, "Dentist follow-up"))
        },
        onAvailability = { date, _, _ -> "Free all day on $date." },
        onProposeEvent = { proposedEvents += it },
        onProposeUpdate = { proposedUpdates += it; "Shown to the user as a card." },
        onProposeCancel = { proposedCancels += it },
        onResolveColor = { _, _ -> colorToResolve },
        onLookUp = { question -> "Looked up: $question" },
        today = { LocalDate.of(2026, 8, 5) },
    )

    private fun args(vararg pairs: Pair<String, Any?>) = JSONObject().apply {
        pairs.forEach { (k, v) -> if (v == null) put(k, JSONObject.NULL) else put(k, v) }
    }

    private fun call(name: String, args: JSONObject) = runBlocking { executor.call(name, args) }

    // --- the refusal that matters ------------------------------------------

    @Test
    fun `a proposal naming an event nobody searched for is refused`() {
        val result = call(AgentTool.PROPOSE_CANCEL, args("event" to "e7"))

        assertTrue(proposedCancels.isEmpty())
        // The message names the way out, so the model corrects itself instead of retrying blind.
        assertTrue(result.contains(AgentTool.SEARCH_EVENTS))
    }

    @Test
    fun `an update naming an invented handle is refused too`() {
        val result = call(AgentTool.PROPOSE_UPDATE, args("event" to "e3", "start_time" to "14:00"))
        assertTrue(proposedUpdates.isEmpty())
        assertTrue(result.contains(AgentTool.SEARCH_EVENTS))
    }

    @Test
    fun `a handle that came from a search resolves`() {
        call(AgentTool.SEARCH_EVENTS, args("query" to "dentist"))
        val result = call(AgentTool.PROPOSE_UPDATE, args("event" to "e1", "start_time" to "14:00"))

        assertEquals(1, proposedUpdates.size)
        assertEquals(1L, proposedUpdates.first().event.eventId)
        assertEquals(LocalTime.of(14, 0), proposedUpdates.first().newStart)
        assertTrue(result.contains("card"))
    }

    @Test
    fun `an unknown tool is named back rather than silently ignored`() {
        assertTrue(call("delete_everything", JSONObject()).contains("delete_everything"))
    }

    // --- reading a model's arguments ---------------------------------------

    @Test
    fun `the several ways a model writes an absent field all mean absent`() {
        call(
            AgentTool.PROPOSE_EVENT,
            args(
                "title" to "Gym",
                "date" to "2026-08-07",
                "all_day" to false,
                "start_time" to "06:30",
                "end_time" to JSONObject.NULL,
                "location" to "",
                "description" to "null",
            ),
        )
        val draft = proposedEvents.single()
        assertNull("a JSON null is absent", draft.endTime)
        assertNull("an empty string is absent", draft.location)
        // JSONObject.optString turns a JSON null into the four-character string "null"; a model
        // sometimes sends that literally too, and it must not become the event's description.
        assertNull("the string \"null\" is absent", draft.description)
    }

    @Test
    fun `a missing leading zero is still a time`() {
        call(
            AgentTool.PROPOSE_EVENT,
            args("title" to "Gym", "date" to "2026-08-07", "all_day" to false, "start_time" to "6:30"),
        )
        assertEquals(LocalTime.of(6, 30), proposedEvents.single().startTime)
    }

    @Test
    fun `a stated time beats an all-day flag`() {
        // The one contradiction a model reliably produces. Someone who said a time meant a time.
        call(
            AgentTool.PROPOSE_EVENT,
            args("title" to "Gym", "date" to "2026-08-07", "all_day" to true, "start_time" to "06:30"),
        )
        val draft = proposedEvents.single()
        assertEquals(false, draft.allDay)
        assertEquals(LocalTime.of(6, 30), draft.startTime)
    }

    @Test
    fun `all-day with no time stays all-day`() {
        call(AgentTool.PROPOSE_EVENT, args("title" to "Leave", "date" to "2026-08-07", "all_day" to true))
        val draft = proposedEvents.single()
        assertEquals(true, draft.allDay)
        assertNull(draft.startTime)
    }

    @Test
    fun `a proposal missing its title or date is refused rather than half-built`() {
        assertTrue(call(AgentTool.PROPOSE_EVENT, args("date" to "2026-08-07")).contains("title"))
        assertTrue(call(AgentTool.PROPOSE_EVENT, args("title" to "Gym")).contains("date"))
        assertTrue(proposedEvents.isEmpty())
    }

    @Test
    fun `an update that changes nothing is refused`() {
        call(AgentTool.SEARCH_EVENTS, args("query" to "dentist"))
        val result = call(AgentTool.PROPOSE_UPDATE, args("event" to "e1"))
        assertTrue(proposedUpdates.isEmpty())
        assertTrue(result.contains("nothing in it"))
    }

    // --- colour, resolved inside the tool call ------------------------------

    @Test
    fun `a colour the account offers is resolved onto the update`() {
        colorToResolve = ResolvedColor(key = "citron_key", argb = 0xFFE4C441.toInt())
        call(AgentTool.SEARCH_EVENTS, args("query" to "dentist"))
        val result = call(AgentTool.PROPOSE_UPDATE, args("event" to "e1", "color" to "Citron"))

        assertEquals(1, proposedUpdates.size)
        assertEquals("citron_key", proposedUpdates.first().newColorKey)
        assertEquals(0xFFE4C441.toInt(), proposedUpdates.first().newColorArgb)
        assertTrue(result.contains("card"))
    }

    @Test
    fun `a colour-only change is not treated as empty`() {
        colorToResolve = ResolvedColor(key = "citron_key", argb = 0xFFE4C441.toInt())
        call(AgentTool.SEARCH_EVENTS, args("query" to "dentist"))
        call(AgentTool.PROPOSE_UPDATE, args("event" to "e1", "color" to "Citron"))

        // Reaching onProposeUpdate at all is the assertion — an update whose isEmpty check missed
        // the colour fields would have been refused as "nothing in it" instead.
        assertEquals(1, proposedUpdates.size)
    }

    @Test
    fun `a colour the account doesn't offer is refused, naming the colour`() {
        colorToResolve = null
        call(AgentTool.SEARCH_EVENTS, args("query" to "dentist"))
        val result = call(AgentTool.PROPOSE_UPDATE, args("event" to "e1", "color" to "Citron"))

        assertTrue(proposedUpdates.isEmpty())
        assertTrue(result.contains("Citron"))
    }

    @Test
    fun `a search with no range given looks from today forward`() {
        call(AgentTool.SEARCH_EVENTS, JSONObject())
        val (query, from, to) = searched!!
        assertNull(query)
        assertEquals(LocalDate.of(2026, 8, 5), from)
        assertTrue(to.isAfter(LocalDate.of(2027, 1, 1)))
    }

    @Test
    fun `a backwards range is read the right way round`() {
        call(AgentTool.SEARCH_EVENTS, args("from_date" to "2026-09-01", "to_date" to "2026-08-01"))
        val (_, from, to) = searched!!
        assertTrue(from.isBefore(to))
    }

    @Test
    fun `search results come back as handles, never as ids`() {
        val result = call(AgentTool.SEARCH_EVENTS, args("query" to "dentist"))
        assertTrue(result.contains("e1:"))
        assertTrue(result.contains("e2:"))
        // The provider's own identifiers never reach the model.
        assertTrue(!result.contains("eventId"))
    }
}
