package com.EntropicSoftwareLab.Schedule.data.assistant

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Its own DataStore file for the same reason the task and birthday stores do: Preferences DataStore rewrites
 * the whole file on every `edit`, and settings should not be rewritten — nor carry a growing JSON
 * blob — every time a draft card is confirmed.
 */
private val Context.memoryStore: DataStore<Preferences> by preferencesDataStore(name = "assistant_memory")

/**
 * Persistence for what the assistant has learned, plus the one cache in this feature that earns its
 * keep: the rendered prompt block.
 *
 * The block is derived from a value that changes only when the user confirms something, while it is
 * read on every single request. Rendering it per request would rebuild the same string from the same
 * inputs several times a minute; [cached] holds the last (memory → text) pair and re-renders only
 * when the memory it was built from is no longer the current one. Keyed on the value rather than a
 * revision counter, so a decode that yields an equal memory reuses the string and no counter can
 * drift out of step with the thing it counts.
 */
class AssistantMemoryRepository(private val context: Context) {

    private object Keys {
        val MEMORY = stringPreferencesKey("memory")
        val ENABLED = booleanPreferencesKey("learning_enabled")
    }

    val memory: Flow<AssistantMemory> =
        context.memoryStore.data.map { MemoryCodec.decode(it[Keys.MEMORY]) }

    /** On by default. Off keeps everything already learned but stops both reading and writing it. */
    val enabled: Flow<Boolean> = context.memoryStore.data.map { it[Keys.ENABLED] ?: true }

    @Volatile
    private var cached: Pair<AssistantMemory, String>? = null

    /** The text to splice into a prompt, or "" when there is nothing learned or learning is off. */
    suspend fun promptBlock(): String {
        // One snapshot, read once. Two separate `.first()` calls can straddle a write and let a
        // request go out carrying habits from before the user switched learning off a moment ago.
        val prefs = context.memoryStore.data.first()
        if (prefs[Keys.ENABLED] == false) return ""
        val current = MemoryCodec.decode(prefs[Keys.MEMORY])
        if (current.isEmpty) return ""
        cached?.let { (key, text) -> if (key == current) return text }
        val rendered = MemoryPrompt.render(current)
        cached = current to rendered
        return rendered
    }

    /**
     * Folds one confirmation or discard into the stored memory.
     *
     * The read-modify-write sits inside a single `edit`, which DataStore serialises per file, so two
     * cards confirmed in the same instant can't each read the same memory and write back a version
     * missing the other's lesson.
     */
    suspend fun record(observation: Observation, epochDay: Long) {
        context.memoryStore.edit { prefs ->
            // Checked inside the edit, so switching learning off and confirming a card at the same
            // moment can't slip a lesson in behind the switch.
            if (prefs[Keys.ENABLED] == false) return@edit
            val current = MemoryCodec.decode(prefs[Keys.MEMORY])
            val updated = MemoryLearner.observe(current, observation, epochDay)
            if (updated != current) prefs[Keys.MEMORY] = MemoryCodec.encode(updated)
        }
    }

    /** Drops one learned habit the user disagrees with, leaving the rest intact. */
    suspend fun forget(key: String) {
        context.memoryStore.edit { prefs ->
            val current = MemoryCodec.decode(prefs[Keys.MEMORY])
            prefs[Keys.MEMORY] = MemoryCodec.encode(
                current.copy(habits = current.habits.filterNot { it.key == key })
            )
        }
    }

    suspend fun forgetCorrection(userText: String) {
        context.memoryStore.edit { prefs ->
            val current = MemoryCodec.decode(prefs[Keys.MEMORY])
            prefs[Keys.MEMORY] = MemoryCodec.encode(
                current.copy(corrections = current.corrections.filterNot { it.userText == userText })
            )
        }
    }

    suspend fun clear() {
        context.memoryStore.edit { it.remove(Keys.MEMORY) }
    }

    suspend fun setEnabled(value: Boolean) {
        context.memoryStore.edit { it[Keys.ENABLED] = value }
    }
}
