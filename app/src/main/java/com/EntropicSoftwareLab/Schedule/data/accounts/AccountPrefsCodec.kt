package com.EntropicSoftwareLab.Schedule.data.accounts

import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialises [AccountPrefs] to the single JSON blob DataStore holds. Decode is total, like
 * [com.EntropicSoftwareLab.Schedule.data.birthday.BirthdayCodec]: a corrupt blob decodes to the
 * defaults (every account used, everything ticked) rather than throwing.
 */
object AccountPrefsCodec {

    fun encode(prefs: AccountPrefs): String = JSONObject()
        .put("added", JSONArray().apply {
            prefs.added.forEach {
                put(JSONObject().put("name", it.name).put("type", it.type).put("nickname", it.nickname))
            }
        })
        .put("hiddenAccounts", JSONArray().apply { prefs.hiddenAccounts.forEach { put(it) } })
        .put("hiddenCalendarIds", JSONArray().apply { prefs.hiddenCalendarIds.forEach { put(it) } })
        .put("birthdaysShown", prefs.birthdaysShown)
        .put("hiddenBirthdayKeys", JSONArray().apply { prefs.hiddenBirthdayKeys.forEach { put(it) } })
        .toString()

    fun decode(json: String?): AccountPrefs {
        if (json.isNullOrBlank()) return AccountPrefs()
        return runCatching {
            val o = JSONObject(json)
            AccountPrefs(
                added = o.optJSONArray("added").objects().mapNotNull { a ->
                    val name = a.optString("name", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    AddedAccount(
                        name = name,
                        type = a.optString("type", ""),
                        nickname = a.optString("nickname", ""),
                    )
                },
                hiddenAccounts = o.optJSONArray("hiddenAccounts").strings().toSet(),
                hiddenCalendarIds = o.optJSONArray("hiddenCalendarIds").longs().toSet(),
                birthdaysShown = o.optBoolean("birthdaysShown", true),
                hiddenBirthdayKeys = o.optJSONArray("hiddenBirthdayKeys").strings().toSet(),
            )
        }.getOrDefault(AccountPrefs())
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { i ->
            if (isNull(i)) null else optString(i).takeIf { it.isNotEmpty() }
        }

    private fun JSONArray?.longs(): List<Long> =
        if (this == null) emptyList() else (0 until length()).map { optLong(it) }
}
