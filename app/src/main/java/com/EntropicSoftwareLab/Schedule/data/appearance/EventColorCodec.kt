package com.EntropicSoftwareLab.Schedule.data.appearance

import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialises the app's own event colours to the single JSON blob DataStore holds.
 *
 * Decode is total, the same discipline [com.EntropicSoftwareLab.Schedule.data.task.TaskCodec]
 * follows: a corrupt or half-written blob decodes to an empty map instead of throwing, and one
 * unparseable entry is dropped rather than failing the whole map. A colour is cosmetic — losing
 * every colour to one bad record, and with it the way the user tells their week apart at a glance,
 * is the failure worth designing against.
 *
 * A list of objects rather than a JSON object keyed by id: a JSON key is a string, so an id-keyed
 * object would round-trip every `Long` through `String` and back, and a key that failed to parse
 * would be indistinguishable from one that was never written.
 */
object EventColorCodec {

    fun encode(colors: Map<Long, Int>): String = JSONArray().apply {
        colors.forEach { (eventId, argb) ->
            put(JSONObject().put("id", eventId).put("argb", argb))
        }
    }.toString()

    fun decode(json: String?): Map<Long, Int> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length())
                .mapNotNull { array.optJSONObject(it)?.let(::decodeOne) }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    private fun decodeOne(o: JSONObject): Pair<Long, Int>? {
        if (o.isNull("id") || o.isNull("argb")) return null
        val id = runCatching { o.getLong("id") }.getOrNull() ?: return null
        val argb = runCatching { o.getInt("argb") }.getOrNull() ?: return null
        // Force full opacity on the way in. A half-transparent event fill reads as a rendering bug
        // rather than as a colour, and nothing in the picker can produce one — so an alpha here came
        // from a corrupt blob, not from the user.
        return id to (argb or ALPHA_OPAQUE)
    }

    private const val ALPHA_OPAQUE = 0xFF000000.toInt()

}
