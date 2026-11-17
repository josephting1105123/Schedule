package com.EntropicSoftwareLab.Schedule.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
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

    /** Removes the Gemini key and model id saved by builds that had the assistant. An unused stored key is still a stored secret. */
    suspend fun clearLegacyGeminiSettings() {
        context.dataStore.edit {
            it.remove(Keys.GEMINI_API_KEY)
            it.remove(Keys.GEMINI_MODEL)
        }
    }
}
