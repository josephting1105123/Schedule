package com.EntropicSoftwareLab.Schedule.data.birthday

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.EntropicSoftwareLab.Schedule.data.accounts.AccountPrefsRepository
import com.EntropicSoftwareLab.Schedule.data.calendar.CalendarRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/**
 * Its own DataStore file: Preferences DataStore rewrites the whole file on every `edit`, and
 * settings should not be rewritten every time a birthday is added or removed. A corrupt file is
 * replaced with an empty one; birthdays are mirrored to the calendar and come back from it.
 */
private val Context.birthdayStore: DataStore<Preferences> by preferencesDataStore(
    name = "birthdays",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Birthdays, synced both ways with the phone's calendars.
 *
 * Out: every birthday saved here is mirrored to a yearly all-day event on the primary Google
 * calendar ([CalendarRepository.birthdayCalendar]), which Google's sync adapter uploads like any
 * other write. In: [birthdays] merges the app's own list with what the provider holds — Google
 * Contacts' birthdays, birthday events made elsewhere, and edits made in Google Calendar to the
 * events this app wrote. [BirthdayEvents.merge] is the rule; this class only reads and writes.
 *
 * Birthday events are filtered out of the Today and Week event lists by [Birthday.eventId], since
 * both already show a Birthdays section — see `DashboardUiState.eventsOn`.
 */
class BirthdayRepository(
    private val context: Context,
    private val calendar: CalendarRepository,
    private val accountPrefs: AccountPrefsRepository,
) {

    private object Keys {
        val BIRTHDAYS = stringPreferencesKey("birthdays")
    }

    /** Serialises writes and [reconcile], so an add racing a reconcile can't write the event twice. */
    private val writeLock = Mutex()

    private val local: Flow<List<Birthday>> =
        context.birthdayStore.data.map { BirthdayCodec.decode(it[Keys.BIRTHDAYS]) }

    /**
     * The merged list, re-read whenever the stored list or the calendar provider changes. A
     * provider read that fails (no permission yet) shows the stored list alone.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val birthdays: Flow<List<Birthday>> =
        combine(local, calendar.observeChanges()) { list, _ -> list }
            .conflate()
            .mapLatest { list -> BirthdayEvents.merge(list, readRows(list)) }

    /** What the screens draw: nothing when the Birthday tick is off, and never one the user hid. */
    val shownBirthdays: Flow<List<Birthday>> = combine(birthdays, accountPrefs.prefs) { all, p ->
        if (!p.birthdaysShown) emptyList()
        else all.filterNot { BirthdayEvents.hideKey(it) in p.hiddenBirthdayKeys }
    }

    /**
     * Birthdays not drawn right now, for a surface that strips their provider rows (the widgets):
     * all of them when the Birthday tick is off, else the ones the user hid.
     */
    suspend fun suppressed(): List<Birthday> {
        val p = accountPrefs.prefs.first()
        val all = birthdays.first()
        return if (!p.birthdaysShown) all else all.filter { BirthdayEvents.hideKey(it) in p.hiddenBirthdayKeys }
    }

    /** Hides a birthday this app cannot delete (Google Contacts'). Undone by clearHiddenBirthdays in Settings. */
    suspend fun hide(birthday: Birthday) = accountPrefs.hideBirthday(BirthdayEvents.hideKey(birthday))

    private suspend fun readRows(list: List<Birthday>): List<BirthdayEventRow>? =
        runCatching { calendar.birthdayEventRows(list.mapNotNull { it.eventId }) }.getOrNull()

    /**
     * Adds a birthday or replaces the one with the same id, and mirrors it to its event — updating
     * the linked one, or writing a new one when there is none yet. A calendar-only birthday (id from
     * [BirthdayEvents.idForEvent]) is edited by updating its event and from then on kept here too.
     *
     * Throws when the birthday's event is read-only (Google Contacts' birthdays): saving it locally
     * anyway would show one date here and another in Google, which is the bug this class exists to
     * fix.
     */
    suspend fun upsert(birthday: Birthday) = writeLock.withLock {
        val stored = readStored()
        val existing = stored.firstOrNull { it.id == birthday.id }
        var eventId = existing?.eventId ?: birthday.eventId ?: BirthdayEvents.eventIdFor(birthday.id)
        val rows = runCatching { calendar.birthdayEventRows(listOfNotNull(eventId)) }.getOrNull()
        // A linked event we can't read can't be updated, and the merge takes name and day from the
        // event, so saving locally would silently revert the edit.
        if (eventId != null && rows == null) {
            throw IllegalStateException("Couldn't reach the calendar, so the change wasn't saved. Try again.")
        }
        if (eventId != null && rows != null) {
            val row = rows.firstOrNull { it.eventId == eventId }
            when {
                row == null -> eventId = null // deleted elsewhere since; write it afresh below
                !row.writable -> throw IllegalStateException(READ_ONLY_MESSAGE)
                else -> {
                    val updated = runCatching {
                        calendar.updateBirthdayEvent(row.eventId, birthday, LocalDate.now())
                    }.onFailure { Log.w(TAG, "Updating the birthday event failed", it) }.getOrDefault(false)
                    // Storing the edit anyway would show one date here and another in Google.
                    if (!updated) {
                        throw IllegalStateException("Couldn't update the calendar event, so the change wasn't saved. Try again.")
                    }
                }
            }
        }
        if (eventId == null && rows != null) eventId = insertEvent(birthday)
        // A new birthday (no event yet) with rows == null: the provider couldn't be read, so let
        // reconcile write the event once it can, rather than risk a duplicate now.
        writeStored(stored.filterNot { it.id == birthday.id } + birthday.copy(eventId = eventId, readOnly = false))
    }

    /** Removes a birthday here and its event from the calendar. */
    suspend fun delete(id: Long) = writeLock.withLock {
        val stored = readStored()
        val eventId = stored.firstOrNull { it.id == id }?.eventId ?: BirthdayEvents.eventIdFor(id)
        if (eventId != null) {
            val rows = runCatching { calendar.birthdayEventRows(listOf(eventId)) }.getOrNull()
            // Dropping the stored birthday while its event survives would bring it back as a
            // calendar-only birthday, so an unreadable calendar aborts the delete.
            if (rows == null) {
                throw IllegalStateException("Couldn't reach the calendar, so nothing was deleted. Try again.")
            }
            val row = rows.firstOrNull { it.eventId == eventId }
            if (row != null && !row.writable) throw IllegalStateException(READ_ONLY_MESSAGE)
            if (row != null && !calendar.deleteEvent(eventId)) {
                throw IllegalStateException("Couldn't delete the calendar event, so nothing was deleted. Try again.")
            }
        }
        writeStored(stored.filterNot { it.id == id })
    }

    /**
     * Brings the stored list into line with the calendar. Run each time the app comes to the front.
     *  - A birthday with no event yet — saved before birthdays synced, or while no calendar was
     *    writable or permission was missing — gets one.
     *  - A birthday whose event is gone was deleted in Google Calendar or another app, so it is
     *    forgotten here too; keeping it would bring it back on the next reconcile.
     * Does nothing when the provider can't be read.
     */
    suspend fun reconcile() = writeLock.withLock {
        val stored = readStored()
        if (stored.isEmpty()) return@withLock
        val rows = readRows(stored) ?: return@withLock
        val gone = BirthdayEvents.deletedElsewhere(stored, rows).map { it.id }.toSet()
        var changed = gone.isNotEmpty()
        val updated = stored.filterNot { it.id in gone }.map { b ->
            if (b.eventId != null) return@map b
            val id = insertEvent(b) ?: return@map b
            changed = true
            b.copy(eventId = id)
        }
        if (changed) writeStored(updated)
    }

    private suspend fun insertEvent(birthday: Birthday): Long? = runCatching {
        val target = calendar.birthdayCalendar() ?: return@runCatching null
        calendar.insertBirthdayEvent(birthday, target.id, LocalDate.now())
    }.onFailure { Log.w(TAG, "Writing the birthday event failed", it) }.getOrNull()

    private suspend fun readStored(): List<Birthday> = local.first()

    private suspend fun writeStored(list: List<Birthday>) {
        context.birthdayStore.edit { prefs -> prefs[Keys.BIRTHDAYS] = BirthdayCodec.encode(list) }
    }

    companion object {
        private const val TAG = "BirthdayRepository"

        const val READ_ONLY_MESSAGE =
            "This birthday comes from Google Contacts. Change it in Contacts and it will update here."
    }
}
