package com.EntropicSoftwareLab.Schedule.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import com.EntropicSoftwareLab.Schedule.ai.GeminiModels
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "schedule_settings")

/** User preferences. Calendar data itself lives in the provider, so this stays small. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val DEFAULT_CALENDAR_ID = longPreferencesKey("default_calendar_id")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
    }

    /** null until the user (or first-launch defaulting) picks one. */
    val defaultCalendarId: Flow<Long?> =
        context.dataStore.data.map { it[Keys.DEFAULT_CALENDAR_ID] }

    suspend fun setDefaultCalendarId(id: Long) {
        context.dataStore.edit { it[Keys.DEFAULT_CALENDAR_ID] = id }
    }

    /**
     * The user's Gemini API key, or null.
     *
     * No longer settable from anywhere reachable: the Assistant tab was removed from the bottom bar
     * and Settings no longer shows a key field, so nothing in the running app ever writes a non-blank
     * value here again. [AppContainer][com.EntropicSoftwareLab.Schedule.di.AppContainer] clears any
     * value left over from before this removal once, on startup, via [clearGeminiApiKey]. The flow and
     * setter stay only because the still-compiled-but-unreachable `ai/` code
     * (`RoutingAssistant`, `GeminiAgent`) and `ui/screens/assistant/` read them; removing the storage
     * itself would break that code even though nothing navigates to it.
     *
     * Stored in the same DataStore as everything else — plain, not encrypted. That's a deliberate
     * limit worth stating: it's a free-tier personal key the user pasted themselves, guarding at
     * most their own quota, and on a non-rooted device app-private storage isn't readable by other
     * apps. A key with real spend behind it would warrant EncryptedSharedPreferences or the
     * Keystore; this one doesn't earn that complexity.
     */
    val geminiApiKey: Flow<String?> = context.dataStore.data.map { it[Keys.GEMINI_API_KEY] }

    /**
     * Which Gemini model every cloud call uses, defaulting to [GeminiModels.DEFAULT].
     *
     * Editable because a model id cannot be verified offline and they are retired over time — the
     * app once pinned one that now 404s for new keys. Blank means "use the default" rather than
     * "send an empty model", so clearing the field recovers from a bad entry.
     */
    val geminiModel: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.GEMINI_MODEL]?.takeIf { it.isNotBlank() } ?: GeminiModels.DEFAULT
    }

    suspend fun setGeminiModel(model: String?) {
        context.dataStore.edit { prefs ->
            val trimmed = model?.trim()
            if (trimmed.isNullOrBlank()) prefs.remove(Keys.GEMINI_MODEL)
            else prefs[Keys.GEMINI_MODEL] = trimmed
        }
    }

    suspend fun setGeminiApiKey(key: String?) {
        context.dataStore.edit { prefs ->
            val trimmed = key?.trim()
            if (trimmed.isNullOrBlank()) prefs.remove(Keys.GEMINI_API_KEY)
            else prefs[Keys.GEMINI_API_KEY] = trimmed
        }
    }

    /**
     * Removes any previously-saved key outright. Used once, at startup, to wipe a key a user pasted
     * before the Gemini feature was removed — an unreachable stored key is still a stored secret.
     */
    suspend fun clearGeminiApiKey() = setGeminiApiKey(null)
}
