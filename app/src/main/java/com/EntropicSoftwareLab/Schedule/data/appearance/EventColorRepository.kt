package com.EntropicSoftwareLab.Schedule.data.appearance

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Its own DataStore file, the same reason [com.EntropicSoftwareLab.Schedule.data.task.TaskRepository]
 * has one: Preferences DataStore rewrites the whole file on every `edit`, and recolouring an event
 * should not rewrite the task list.
 */
private val Context.eventColorStore: DataStore<Preferences> by
    preferencesDataStore(name = "event_colors")

/**
 * **Retired** (2026-09-29): no longer read — see [overrides]. What follows describes what it did.
 *
 * The colour this app shows for an event, which is no longer the colour Google Calendar stores.
 *
 * The provider only round-trips a colour from its own 24-entry palette — write it a hex it has never
 * heard of and the event comes back wearing the calendar's default. That constraint belongs to
 * Google Calendar's UI, and this app is the one the user actually looks at, so the exact colour they
 * picked is kept here and the *nearest* Google colour is still written to the provider alongside it
 * (see `GoogleCalendarColors.nearestAvailable`). Other calendar apps stay roughly in step; this one
 * shows what was chosen.
 *
 * **Keyed by event id, not by occurrence.** That matches how the provider stores colour — one row
 * per series — so recolouring a recurring event recolours the whole series, which is what the
 * calendar itself would do. A single occurrence recoloured through `RecurrenceScope.ThisEvent`
 * becomes a separate provider row with its own id, and so gets its own entry here for free.
 */
class EventColorRepository(private val context: Context) {

    private object Keys {
        val COLORS = stringPreferencesKey("event_colors")
    }

    /**
     * Event id to ARGB — **always empty now**.
     *
     * Retired 2026-09-29, the user's call: the picker offers Google Calendar's own colours and a
     * pick is written as the account's `EVENT_COLOR_KEY`, so what Google shows is what this app
     * shows. Colours stored here before that only ever reached Google as an approximation, and the
     * user chose to see Google's colour for those events rather than keep a colour only this phone
     * knew. The flow stays, empty, so the resolver's merge order and every caller are unchanged;
     * [forget] still tidies old entries as events are saved or deleted.
     */
    val overrides: Flow<Map<Long, Int>> = flowOf(emptyMap())

    /**
     * Sets the app's colour for one event, or clears it when [argb] is null.
     *
     * Reads and writes inside the same `edit` block rather than taking a caller-held copy of the
     * map, for the reason `TaskRepository.setDone` does: two edits in flight from different screens
     * must not undo each other.
     */
    suspend fun setColor(eventId: Long, argb: Int?) {
        context.eventColorStore.edit { prefs ->
            val current = EventColorCodec.decode(prefs[Keys.COLORS])
            val next = if (argb == null) current - eventId else current + (eventId to argb)
            prefs[Keys.COLORS] = EventColorCodec.encode(next)
        }
    }

    /**
     * Drops the entry for a deleted event.
     *
     * Called from the delete paths rather than swept periodically: an id the provider has reused
     * would otherwise hand a new event a colour its creator never picked, and there is no cheap way
     * to ask the provider which of a few hundred stored ids still exist.
     */
    suspend fun forget(eventId: Long) = setColor(eventId, null)
}
