package com.EntropicSoftwareLab.Schedule.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// A corrupt file is replaced with an empty one: every setting returns to its default.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "schedule_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** User preferences. Calendar data itself lives in the provider, so this stays small. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val DEFAULT_CALENDAR_ID = longPreferencesKey("default_calendar_id")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    }

    /** null until the user (or first-launch defaulting) picks one. */
    val defaultCalendarId: Flow<Long?> =
        context.dataStore.data.map { it[Keys.DEFAULT_CALENDAR_ID] }

    suspend fun setDefaultCalendarId(id: Long) {
        context.dataStore.edit { it[Keys.DEFAULT_CALENDAR_ID] = id }
    }

    /** True once the user has finished the first-open privacy and permissions pages. */
    val onboardingDone: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.ONBOARDING_DONE] ?: false }

    suspend fun markOnboardingDone() {
        context.dataStore.edit { it[Keys.ONBOARDING_DONE] = true }
    }

    /** Removes the Gemini key and model id saved by builds that had the assistant. An unused stored key is still a stored secret. */
    suspend fun clearLegacyGeminiSettings() {
        context.dataStore.edit {
            it.remove(Keys.GEMINI_API_KEY)
            it.remove(Keys.GEMINI_MODEL)
        }
    }
}
