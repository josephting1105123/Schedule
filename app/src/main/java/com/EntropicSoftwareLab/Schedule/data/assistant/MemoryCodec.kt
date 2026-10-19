package com.EntropicSoftwareLab.Schedule.data.assistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialises [AssistantMemory] to the single JSON blob DataStore holds.
 *
 * Every decode is total, the same discipline [com.EntropicSoftwareLab.Schedule.data.task.TaskCodec]
 * follows: a corrupt or half-written blob decodes to an empty memory instead of throwing. Losing
 * what the assistant had learned is a bad day; crashing the assistant tab because one field is a
 * string where an int was expected is a worse one, and this data is worth strictly less than the
 * calendar it sits next to.
 *
 * Unknown fields are ignored and missing ones default, so an older build reading a newer blob
 * degrades to fewer habits rather than to none.
 */
object MemoryCodec {

    fun encode(memory: AssistantMemory): String = JSONObject()
        .put("habits", JSONArray().apply { memory.habits.forEach { put(encodeHabit(it)) } })
        .put("corrections", JSONArray().apply { memory.corrections.forEach { put(encodeCorrection(it)) } })
        .put("style", encodeStyle(memory.style))
        .toString()

    fun decode(json: String?): AssistantMemory {
        if (json.isNullOrBlank()) return AssistantMemory.EMPTY
        return runCatching {
            val root = JSONObject(json)
            AssistantMemory(
                habits = root.optJSONArray("habits").objects().mapNotNull(::decodeHabit),
                corrections = root.optJSONArray("corrections").objects().mapNotNull(::decodeCorrection),
                style = decodeStyle(root.optJSONObject("style")),
            )
        }.getOrDefault(AssistantMemory.EMPTY)
    }

    private fun encodeHabit(habit: Habit) = JSONObject()
        .put("key", habit.key)
        .put("title", habit.title)
        .put("start", habit.startMinute ?: JSONObject.NULL)
        .put("duration", habit.durationMinutes ?: JSONObject.NULL)
        .put("location", habit.location ?: JSONObject.NULL)
        .put("allDay", habit.allDay)
        .put("supporting", habit.supporting)
        .put("contradicting", habit.contradicting)
        .put("lastSeen", habit.lastSeenEpochDay)

    private fun decodeHabit(o: JSONObject): Habit? {
        val key = o.stringOrNull("key")?.takeIf { it.isNotBlank() } ?: return null
        return Habit(
            key = key,
            title = o.stringOrNull("title")?.takeIf { it.isNotBlank() } ?: key,
            startMinute = o.intOrNull("start"),
            durationMinutes = o.intOrNull("duration"),
            location = o.stringOrNull("location"),
            allDay = o.optBoolean("allDay", false),
            supporting = o.optInt("supporting", 0).coerceAtLeast(0),
            contradicting = o.optInt("contradicting", 0).coerceAtLeast(0),
            lastSeenEpochDay = o.optLong("lastSeen", 0L),
        )
    }

    private fun encodeCorrection(correction: Correction) = JSONObject()
        .put("text", correction.userText)
        .put("event", encodeEvent(correction.event))
        .put("epochDay", correction.epochDay)

    private fun decodeCorrection(o: JSONObject): Correction? {
        val text = o.stringOrNull("text")?.takeIf { it.isNotBlank() } ?: return null
        val event = o.optJSONObject("event")?.let(::decodeEvent) ?: return null
        return Correction(userText = text, event = event, epochDay = o.optLong("epochDay", 0L))
    }

    private fun encodeEvent(event: ObservedEvent) = JSONObject()
        .put("title", event.title)
        .put("start", event.startMinute ?: JSONObject.NULL)
        .put("duration", event.durationMinutes ?: JSONObject.NULL)
        .put("location", event.location ?: JSONObject.NULL)
        .put("allDay", event.allDay)

    private fun decodeEvent(o: JSONObject): ObservedEvent? {
        val title = o.stringOrNull("title")?.takeIf { it.isNotBlank() } ?: return null
        return ObservedEvent(
            title = title,
            startMinute = o.intOrNull("start"),
            durationMinutes = o.intOrNull("duration"),
            location = o.stringOrNull("location"),
            allDay = o.optBoolean("allDay", false),
        )
    }

    private fun encodeStyle(style: StyleProfile) = JSONObject()
        .put("durations", JSONArray().apply { style.recentDurations.forEach { put(it) } })
        .put("lowercase", style.lowercaseTitles)
        .put("capitalised", style.capitalisedTitles)

    private fun decodeStyle(o: JSONObject?): StyleProfile {
        if (o == null) return StyleProfile()
        val durations = o.optJSONArray("durations")
        return StyleProfile(
            recentDurations = (0 until (durations?.length() ?: 0))
                .mapNotNull { durations?.optInt(it, 0)?.takeIf { d -> d > 0 } }
                .takeLast(MemoryLearner.DURATION_WINDOW),
            lowercaseTitles = o.optInt("lowercase", 0).coerceAtLeast(0),
            capitalisedTitles = o.optInt("capitalised", 0).coerceAtLeast(0),
        )
    }
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/** JSONObject.optString turns a JSON null into the string "null"; this doesn't. */
private fun JSONObject.stringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

private fun JSONObject.intOrNull(name: String): Int? = if (isNull(name)) null else optInt(name)
