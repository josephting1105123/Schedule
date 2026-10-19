package com.EntropicSoftwareLab.Schedule.data.birthday

import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialises the birthday list to the single JSON blob DataStore holds.
 *
 * Decode is total, the same discipline [com.EntropicSoftwareLab.Schedule.data.assistant.MemoryCodec]
 * follows: a corrupt or half-written blob decodes to an empty list instead of throwing, and one
 * unparseable entry is dropped rather than failing the whole list.
 */
object BirthdayCodec {

    fun encode(birthdays: List<Birthday>): String =
        JSONArray().apply { birthdays.forEach { put(encodeOne(it)) } }.toString()

    fun decode(json: String?): List<Birthday> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::decodeOne) }
        }.getOrDefault(emptyList())
    }

    private fun encodeOne(b: Birthday) = JSONObject()
        .put("id", b.id)
        .put("name", b.name)
        .put("month", b.month)
        .put("day", b.day)
        .put("year", b.year ?: JSONObject.NULL)
        .put("eventId", b.eventId ?: JSONObject.NULL)

    private fun decodeOne(o: JSONObject): Birthday? {
        val name = o.stringOrNull("name")?.takeIf { it.isNotBlank() } ?: return null
        val month = o.optInt("month", 0).takeIf { it in 1..12 } ?: return null
        val day = o.optInt("day", 0).takeIf { it in 1..31 } ?: return null
        return Birthday(
            id = o.optLong("id", 0L),
            name = name,
            month = month,
            day = day,
            year = o.intOrNull("year"),
            eventId = if (o.isNull("eventId")) null else o.optLong("eventId"),
        )
    }
}

/** JSONObject.optString turns a JSON null into the string "null"; this doesn't. */
private fun JSONObject.stringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

private fun JSONObject.intOrNull(name: String): Int? = if (isNull(name)) null else optInt(name)
