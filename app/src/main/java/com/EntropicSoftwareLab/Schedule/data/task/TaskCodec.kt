package com.EntropicSoftwareLab.Schedule.data.task

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/**
 * Serialises the task list to the single JSON blob DataStore holds.
 *
 * Decode is total, the same discipline [com.EntropicSoftwareLab.Schedule.data.birthday.BirthdayCodec]
 * follows: a corrupt or half-written blob decodes to an empty list instead of throwing, and one
 * unparseable entry is dropped rather than failing the whole list. Losing every task to one bad
 * record is the failure worth designing against.
 *
 * The date is an epoch day and the time a second of the day, both plain integers — no formatter, no
 * locale, and nothing that can be read back through the wrong timezone. A task's deadline is
 * wall-clock by definition (see [Task]), so there is no instant here to convert.
 */
object TaskCodec {

    fun encode(tasks: List<Task>): String =
        JSONArray().apply { tasks.forEach { put(encodeOne(it)) } }.toString()

    fun decode(json: String?): List<Task> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::decodeOne) }
        }.getOrDefault(emptyList())
    }

    private fun encodeOne(t: Task) = JSONObject()
        .put("id", t.id)
        .put("title", t.title)
        .put("due", t.due.toEpochDay())
        .put("dueSecond", t.dueTime?.toSecondOfDay() ?: JSONObject.NULL)
        .put("notes", t.notes ?: JSONObject.NULL)
        .put("done", t.done)

    private fun decodeOne(o: JSONObject): Task? {
        val title = o.stringOrNull("title")?.takeIf { it.isNotBlank() } ?: return null
        // A task with no readable deadline has nothing to show it against, so it is dropped rather
        // than defaulted onto today — a task silently appearing on the wrong day is worse than one
        // that is gone.
        if (o.isNull("due")) return null
        val due = runCatching { LocalDate.ofEpochDay(o.getLong("due")) }.getOrNull() ?: return null
        return Task(
            id = o.optLong("id", 0L),
            title = title,
            due = due,
            // An out-of-range second degrades to "no time" rather than dropping the task: the
            // deadline day survives, which is the part the user would miss.
            dueTime = o.intOrNull("dueSecond")
                ?.takeIf { it in 0 until SECONDS_PER_DAY }
                ?.let { LocalTime.ofSecondOfDay(it.toLong()) },
            notes = o.stringOrNull("notes"),
            done = o.optBoolean("done", false),
        )
    }

    private const val SECONDS_PER_DAY = 24 * 60 * 60
}

/** JSONObject.optString turns a JSON null into the string "null"; this doesn't. */
private fun JSONObject.stringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

private fun JSONObject.intOrNull(name: String): Int? = if (isNull(name)) null else optInt(name)
