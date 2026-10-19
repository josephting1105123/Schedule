package com.EntropicSoftwareLab.Schedule.ai

import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance

/**
 * Short opaque names for the events a tool call has actually returned.
 *
 * This is the piece that keeps function calling inside the rule the rest of the app is built on: the
 * model never gets to name an event. It gets `e1`, `e2`, `e3` — handles minted here, only for rows
 * that came back from a real search — and every proposal it makes has to cite one of them. A handle
 * it invents resolves to nothing and the tool call fails with "call search_events first", which is a
 * conversation the user watches happen. The alternative, handing the model provider event ids, means
 * a hallucinated number is a syntactically perfect request to modify somebody's meeting.
 *
 * Handles are also cheaper than ids in both directions: three characters instead of a sixteen-digit
 * number the model has to copy exactly, and a copy error becomes a miss rather than a wrong hit.
 *
 * Scoped to one user message. A handle from the previous turn is deliberately dead — "move that one"
 * two messages later has to search again, which costs one provider read and removes any chance of
 * acting on a row that has since been deleted or changed underneath.
 */
class EventHandles {

    private val byHandle = LinkedHashMap<String, EventInstance>()
    private var next = 1

    /** Mints (or reuses) a handle for [event]. The same occurrence always gets the same one. */
    fun put(event: EventInstance): String {
        byHandle.entries.firstOrNull { it.value.instanceId == event.instanceId }?.let { return it.key }
        val handle = "e${next++}"
        byHandle[handle] = event
        return handle
    }

    fun putAll(events: List<EventInstance>): List<Pair<String, EventInstance>> =
        events.map { put(it) to it }

    /** Null for anything not handed out this turn — including anything the model made up. */
    operator fun get(handle: String?): EventInstance? =
        handle?.trim()?.lowercase()?.let { byHandle[it] }

    fun clear() {
        byHandle.clear()
        next = 1
    }

    val size: Int get() = byHandle.size
}
