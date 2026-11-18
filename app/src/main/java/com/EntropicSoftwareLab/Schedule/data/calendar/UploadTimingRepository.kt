package com.EntropicSoftwareLab.Schedule.data.calendar

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** Its own DataStore file; a corrupt one is replaced with an empty one, since this is only a diagnostic. */
private val Context.uploadTimingStore: DataStore<Preferences> by preferencesDataStore(
    name = "upload_timing",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Remembers, per account, when a dirty row was first seen and how long the last upload took (first
 * dirty row seen to the count back at zero), for the Settings sync card. See [UploadTiming].
 */
class UploadTimingRepository(private val context: Context) {

    private object Keys {
        val TIMINGS = stringPreferencesKey("timings")
    }

    val timings: Flow<Map<CalendarAccount, UploadTimingEntry>> =
        context.uploadTimingStore.data
            .map { UploadTimingCodec.decode(it[Keys.TIMINGS]) }
            .catch { e ->
                if (e is IOException) emit(emptyMap()) else throw e
            }

    /**
     * Folds a look at the provider into the stored timings. [read] runs INSIDE the edit, which
     * DataStore serialises: several callers race (writes, the change observer, two workers), and a
     * read taken before waiting for the edit could be folded in after a newer one, leaving a clock
     * running for an upload that already finished. A null read (the provider couldn't be read) leaves
     * the stored timings alone, since it must never be taken for an upload finishing.
     */
    suspend fun record(
        nowMs: Long = System.currentTimeMillis(),
        read: suspend () -> Map<CalendarAccount, Int>?,
    ) {
        context.uploadTimingStore.edit { store ->
            val dirty = read() ?: return@edit
            val next = UploadTiming.observe(UploadTimingCodec.decode(store[Keys.TIMINGS]), dirty, nowMs)
            store[Keys.TIMINGS] = UploadTimingCodec.encode(next)
        }
    }
}
