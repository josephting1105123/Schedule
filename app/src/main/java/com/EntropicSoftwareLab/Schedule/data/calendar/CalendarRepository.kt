package com.EntropicSoftwareLab.Schedule.data.calendar

import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import com.EntropicSoftwareLab.Schedule.data.birthday.Birthday
import com.EntropicSoftwareLab.Schedule.data.birthday.BirthdayEventRow
import com.EntropicSoftwareLab.Schedule.data.birthday.BirthdayEvents
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * Reads and writes the device's synced calendars through [CalendarContract]. Anything added here
 * reaches Google Calendar through the account's own sync adapter — this app never talks to Google's
 * servers itself, so there is no OAuth, no API key and no network permission.
 *
 * Callers must hold READ_CALENDAR (and WRITE_CALENDAR to insert); every method throws
 * [SecurityException] otherwise.
 */
class CalendarRepository(
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Runs after any successful write. One hook here rather than a call at every save site, so a
     * new write path can't forget to refresh the widget.
     */
    private val onWrite: suspend () -> Unit = {},
    /**
     * Whether Android will tell this app about [Account]'s sync settings — true exactly for the
     * accounts `AccountManager` lists to it. For any other account `getSyncAutomatically` and
     * `isSyncActive` answer false whatever the truth, so [syncStatus] reports those as unknown
     * rather than as "off".
     */
    private val accountVisible: (Account) -> Boolean = { true },
) {

    /**
     * Asks every account's calendar to sync now — what a pull on Today means.
     *
     * Re-reading the provider alone would only show what the phone already has; the point of a pull
     * is "fetch what changed on the server". This returns as soon as the request is queued: the
     * sync adapter runs on its own schedule, and whatever it writes arrives through [observeChanges]
     * like any other sync.
     *
     * **The account is null on purpose.** Since Android 8 `ContentService.requestSync` returns
     * silently when the caller has no access to the account it names, and a Google account belongs
     * to Play Services — so naming each account, as this used to, asked for syncs that never ran.
     * `hasAccountAccess` skips the check for a null account, which `SyncManager.scheduleSync` reads
     * as "every running account with an adapter for this authority". No account chooser, nothing
     * for the user to grant. The remaining check, `hasAuthorityAccess` (targetSdk 34+), needs the
     * calendar sync adapter's package to be visible, which is what the manifest's `<queries>` entry
     * for `android.content.SyncAdapter` is for.
     */
    suspend fun requestSync() {
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        withContext(io) {
            runCatching { ContentResolver.requestSync(null, CalendarContract.AUTHORITY, extras) }
        }
    }

    /**
     * Where each synced account stands, for the Settings sync card: whether the phone lets it sync
     * on its own, and how many of this app's changes are still waiting to go up.
     *
     * The app writes the ordinary way — as a normal caller, so the provider marks every row it
     * touches DIRTY for Google's adapter to upload — and nothing in the app can push harder than
     * asking for a sync. So when changes don't reach Google, the answer is on the phone: auto-sync
     * off for the account (Settings → Accounts), a battery saver holding background sync, or the
     * adapter failing on a row. This makes the first two visible and counts the rows stuck behind
     * them. Every part is read in its own runCatching: a provider that hides DIRTY from ordinary
     * callers leaves [AccountSyncStatus.pendingUploads] null rather than the whole card empty.
     */
    suspend fun syncStatus(): List<AccountSyncStatus> = withContext(io) {
        val master = runCatching { ContentResolver.getMasterSyncAutomatically() }.getOrNull()
        listCalendars()
            .map { it.accountName to it.accountType }
            .distinct()
            .filter { (_, type) -> type != CalendarContract.ACCOUNT_TYPE_LOCAL }
            .map { (name, type) ->
                val account = Account(name, type)
                val visible = runCatching { accountVisible(account) }.getOrDefault(false)
                AccountSyncStatus(
                    accountName = name,
                    accountType = type,
                    masterAutoSync = master,
                    autoSync = if (!visible) null else runCatching {
                        ContentResolver.getSyncAutomatically(account, CalendarContract.AUTHORITY)
                    }.getOrNull(),
                    syncing = if (!visible) null else runCatching {
                        ContentResolver.isSyncActive(account, CalendarContract.AUTHORITY) ||
                            ContentResolver.isSyncPending(account, CalendarContract.AUTHORITY)
                    }.getOrNull(),
                    pendingUploads = runCatching {
                        resolver.query(
                            CalendarContract.Events.CONTENT_URI,
                            arrayOf(CalendarContract.Events._ID),
                            "${CalendarContract.Events.DIRTY} = 1 AND " +
                                "${CalendarContract.Events.ACCOUNT_NAME} = ? AND " +
                                "${CalendarContract.Events.ACCOUNT_TYPE} = ?",
                            arrayOf(name, type),
                            null,
                        )?.use { it.count }
                    }.getOrNull(),
                )
            }
    }

    suspend fun listCalendars(): List<CalendarInfo> = withContext(io) {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.ACCOUNT_TYPE,
        )
        val out = mutableListOf<CalendarInfo>()
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.VISIBLE} = 1",
            null,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val access = c.getInt(5)
                out += CalendarInfo(
                    id = c.getLong(0),
                    displayName = c.getString(1) ?: "(unnamed)",
                    accountName = c.getString(2) ?: "",
                    accountType = c.getString(6) ?: "",
                    color = c.getInt(3),
                    isPrimary = c.getInt(4) == 1,
                    // Holiday and birthday feeds come back read-only; they're fine to display but
                    // must never be offered as a write target.
                    isWritable = access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                )
            }
        }
        out
    }

    suspend fun listWritableCalendars(): List<CalendarInfo> = listCalendars().filter { it.isWritable }

    /**
     * The account's event colours, canonicalized by [GoogleCalendarColors.canonicalize] so the
     * picker's order never depends on cursor order and a colour the account genuinely offers is
     * never dropped for naming the same thing as another. A colour the account actually offers
     * carries its real `COLOR_KEY` and is selectable; a canonical slot it doesn't is a disabled
     * placeholder, so the picker shows the full 24 rather than shrinking around a partial read.
     *
     * Empty — not 24 disabled placeholders — for an account with no palette of its own, or no
     * account identity at all (blank name or type below): there is nothing real to canonicalize
     * against, and 24 inert swatches over "this account offers no colours" would contradict the
     * caption above them.
     */
    suspend fun eventColors(calendar: CalendarInfo): List<EventColorOption> = withContext(io) {
        if (calendar.accountName.isBlank() || calendar.accountType.isBlank()) return@withContext emptyList()
        val projection = arrayOf(
            CalendarContract.Colors.COLOR_KEY,
            CalendarContract.Colors.COLOR,
        )
        val out = mutableListOf<EventColorOption>()
        // Scoped to the cursor read alone. The old version wrapped the whole loop in one
        // runCatching, so a throw mid-iteration silently returned whatever had been read so
        // far — a partial palette that looked like "this account has 6 colours" and changed
        // shape between launches depending on where the throw landed.
        try {
            resolver.query(
                CalendarContract.Colors.CONTENT_URI,
                projection,
                "${CalendarContract.Colors.ACCOUNT_NAME} = ? AND " +
                    "${CalendarContract.Colors.ACCOUNT_TYPE} = ? AND " +
                    "${CalendarContract.Colors.COLOR_TYPE} = ?",
                arrayOf(
                    calendar.accountName,
                    calendar.accountType,
                    CalendarContract.Colors.TYPE_EVENT.toString(),
                ),
                // By _ID, which is the order the sync adapter inserted them in — the palette's own
                // order, and the one Google Calendar's picker shows.
                "${CalendarContract.Colors._ID} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val key = c.getString(0) ?: continue
                    out += EventColorOption(
                        key = key,
                        argb = c.getInt(1),
                        // Google's keys are its fixed colour ids, so name by key first: the hex
                        // the adapter reports for them can be an older shade than the 24 below,
                        // too far off for nameFor, which left usable colours unnamed and faded.
                        name = GoogleCalendarColors.nameForKey(key, calendar.accountType)
                            ?: GoogleCalendarColors.nameFor(c.getInt(1)),
                    )
                }
            }
        } catch (_: Exception) {
            // A failed or partial read must not masquerade as "this account offers N colours" —
            // an empty live list below is the honest fallback, the same as a genuinely bare palette.
            out.clear()
        }
        // Deduplicated on the colour itself: an account can carry two keys for the same value, and
        // two identical swatches side by side is a picker that looks broken.
        val distinct = out.distinctBy { it.argb and 0xFFFFFF }
        // No real colours at all: an empty result, not 24 disabled placeholders sitting above copy
        // that says the account offers none.
        if (distinct.isEmpty()) emptyList() else GoogleCalendarColors.canonicalize(distinct)
    }

    /**
     * Occurrences overlapping [startMillis, endMillis).
     *
     * Queries the Instances table, which expands recurrence rules for us — the Events table would
     * return one row per series and silently hide every repeat.
     */
    suspend fun eventInstances(startMillis: Long, endMillis: Long): List<EventInstance> = withContext(io) {
        // The Instances URI carries its window as two trailing path segments: .../instances/when/<begin>/<end>
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startMillis)
        ContentUris.appendId(builder, endMillis)
        val uri: Uri = builder.build()
        val baseProjection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances._ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        // DESCRIPTION and EVENT_COLOR_KEY compile because Instances implements EventsColumns, but
        // whether CalendarProvider2's projection map actually exposes them can't be verified offline
        // — a rejected column throws IllegalArgumentException at query() time, and every calendar
        // read in the app funnels through this one function. Try the richer projection first; if the
        // provider rejects it, fall back to the base one and leave notes/eventColorKey null rather
        // than let the whole app fail to read the calendar over two extra columns.
        val extendedProjection = baseProjection + arrayOf(
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_COLOR_KEY,
            CalendarContract.Instances.EVENT_COLOR,
        )
        fun query(projection: Array<String>) = resolver.query(
            uri,
            projection,
            "${CalendarContract.Instances.VISIBLE} = 1",
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )
        val (cursor, extended) = try {
            query(extendedProjection) to true
        } catch (e: IllegalArgumentException) {
            query(baseProjection) to false
        }

        val out = mutableListOf<EventInstance>()
        cursor?.use { c ->
            while (c.moveToNext()) {
                // An invitation the user declined doesn't occupy their time.
                if (c.getInt(9) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                out += EventInstance(
                    eventId = c.getLong(0),
                    instanceId = c.getLong(1),
                    title = c.getString(2)?.takeIf { it.isNotBlank() } ?: "(no title)",
                    location = c.getString(3)?.takeIf { it.isNotBlank() },
                    startMillis = c.getLong(4),
                    endMillis = c.getLong(5),
                    allDay = c.getInt(6) == 1,
                    calendarId = c.getLong(7),
                    displayColor = c.getInt(8),
                    notes = if (extended) c.getString(10)?.takeIf { it.isNotBlank() } else null,
                    eventColorKey = if (extended) c.getString(11)?.takeIf { it.isNotBlank() } else null,
                    eventColor = if (extended && !c.isNull(12)) c.getInt(12) else null,
                )
            }
        }
        // Every read in the app funnels through here, so collapsing duplicates once fixes the
        // agenda, the hour grid, the clash detector and the widget together — and stops two copies
        // of one holiday being reported as a double booking.
        EventDedupe.collapseIdentical(out)
    }

    /** Occurrences touching [date] in [zone]. */
    suspend fun eventsOnDay(date: LocalDate, zone: ZoneId): List<EventInstance> {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return eventInstances(start, end).filter { it.fallsWithin(date, date.plusDays(1), zone) }
    }

    /** Occurrences touching [startDate] up to but not including [endDateExclusive], in [zone]. */
    suspend fun eventsInRange(
        startDate: LocalDate,
        endDateExclusive: LocalDate,
        zone: ZoneId,
    ): List<EventInstance> {
        val start = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = endDateExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
        return eventInstances(start, end).filter { it.fallsWithin(startDate, endDateExclusive, zone) }
    }

    /** Occurrences touching the whole month containing [anyDayInMonth]. */
    suspend fun eventsInMonth(anyDayInMonth: LocalDate, zone: ZoneId): List<EventInstance> {
        val first = anyDayInMonth.withDayOfMonth(1)
        val start = first.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = first.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return eventInstances(start, end).filter { it.fallsWithin(first, first.plusMonths(1), zone) }
    }

    /**
     * Inserts [event] and returns its new event id, or null if the provider rejected it.
     *
     * EVENT_TIMEZONE is required by the provider; omitting it makes the insert fail or land at an
     * unpredictable time. All-day events additionally have to be encoded as UTC midnight — see
     * [localDateToUtcMidnight].
     *
     * A recurring event ([NewEvent.rrule] set) carries RRULE + DURATION and *no* DTEND: the
     * provider rejects a row holding both, because each occurrence's end comes from the duration.
     */
    suspend fun insertEvent(event: NewEvent, zone: ZoneId): Long? = withContext(io) {
        // Encoding lives in eventValues, shared with the update path — one place where the all-day,
        // timezone and RRULE-vs-DTEND rules are applied, so insert and update cannot drift apart.
        val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, eventValues(event, zone))
        val id = uri?.lastPathSegment?.toLongOrNull()
        if (id != null) onWrite()
        id
    }

    /** Deletes an event by id; returns true if a row was removed. Backs the post-insert Undo. */
    suspend fun deleteEvent(eventId: Long): Boolean = withContext(io) {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val deleted = resolver.delete(uri, null, null) > 0
        if (deleted) onWrite()
        deleted
    }

    // ---- editing an existing event ---------------------------------------

    /**
     * The full row behind [instance], or null if it has since been deleted.
     *
     * The Instances table can't answer this on its own: RRULE lives only on Events, and an editor
     * that can't read it can't avoid wiping it on save. (DESCRIPTION is also read here rather than
     * trusted from [eventInstances] — that copy is best-effort and silently absent on a provider
     * that rejects the extended projection; the editor needs it guaranteed.) DTSTART is read from the
     * same row so the *series* start is known alongside the occurrence the user tapped — the two
     * differ for every repeat after the first, and scoped edits need both.
     */
    suspend fun eventDetails(
        eventId: Long,
        instanceStartMillis: Long,
        instanceEndMillis: Long,
        zone: ZoneId,
    ): EventDetails? = withContext(io) {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.EVENT_COLOR,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Events.EVENT_COLOR_KEY,
        )
        resolver.query(uri, projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val allDay = c.getInt(3) == 1
            val dtStart = c.getLong(4)
            // All-day instants are UTC midnight of the intended day, so both the occurrence and the
            // series anchor have to be read in UTC rather than converted through the device zone.
            fun localOf(millis: Long): LocalDateTime =
                if (allDay) utcMidnightToLocalDate(millis).atStartOfDay()
                else java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()
            EventDetails(
                eventId = eventId,
                calendarId = c.getLong(6),
                title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "(no title)",
                description = c.getString(1)?.takeIf { it.isNotBlank() },
                location = c.getString(2)?.takeIf { it.isNotBlank() },
                allDay = allDay,
                instanceStart = localOf(instanceStartMillis),
                instanceStartMillis = instanceStartMillis,
                instanceEnd = localOf(instanceEndMillis),
                seriesStart = localOf(dtStart),
                rrule = c.getString(5)?.takeIf { it.isNotBlank() },
                eventColor = if (c.isNull(7)) null else c.getInt(7),
                eventColorKey = c.getString(10)?.takeIf { it.isNotBlank() },
                timeZone = c.getString(8),
                isWritable = c.getInt(9) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
            )
        }
    }

    /**
     * Writes [edit] over [details], applying to whichever occurrences [scope] names.
     *
     * The three scopes are three different provider operations, not one operation with a flag:
     *  - [RecurrenceScope.AllEvents] updates the row in place. The series keeps its own start date;
     *    the edit is applied as a *shift*, so moving a Tuesday occurrence an hour later moves every
     *    occurrence an hour later rather than collapsing the series onto one date.
     *  - [RecurrenceScope.ThisEvent] inserts an exception through [CalendarContract.Events.CONTENT_EXCEPTION_URI].
     *    An exception is a standalone event, so it must carry no RRULE of its own.
     *  - [RecurrenceScope.ThisAndFollowing] caps the original series just before this occurrence and
     *    inserts a fresh series carrying the edited values. When this occurrence *is* the first, there
     *    is nothing to keep behind it and the whole series is edited instead.
     */
    suspend fun updateEvent(
        details: EventDetails,
        edit: EventEdit,
        scope: RecurrenceScope,
        zone: ZoneId,
    ): Boolean = withContext(io) {
        if (!details.isWritable) return@withContext false
        val effective = if (details.isRecurring) scope else RecurrenceScope.AllEvents
        val ok = when (effective) {
            RecurrenceScope.AllEvents -> updateWholeSeries(details, edit, zone)
            RecurrenceScope.ThisEvent -> insertException(details, edit, zone)
            RecurrenceScope.ThisAndFollowing -> splitSeries(details, edit, zone)
        }
        if (ok) onWrite()
        ok
    }

    private fun updateWholeSeries(details: EventDetails, edit: EventEdit, zone: ZoneId): Boolean {
        // Applying the edit as a shift is what keeps "all events" from meaning "move the series to
        // the day I happened to open". The delta is measured on the occurrence the user edited and
        // applied to the series anchor.
        val shifted = if (details.isRecurring) {
            val delta = java.time.Duration.between(details.instanceStart, edit.start)
            val length = java.time.Duration.between(edit.start, edit.end)
            val newStart = details.seriesStart.plus(delta)
            edit.copy(start = newStart, end = newStart.plus(length))
        } else {
            edit
        }
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, details.eventId)
        return resolver.update(uri, eventValues(shifted, zone, details.timeZone), null, null) > 0
    }

    private fun insertException(details: EventDetails, edit: EventEdit, zone: ZoneId): Boolean {
        val uri = Uri.withAppendedPath(
            CalendarContract.Events.CONTENT_EXCEPTION_URI,
            details.eventId.toString(),
        )
        // An exception replaces one occurrence with a standalone event: no RRULE, and the provider
        // identifies which occurrence by the instant it originally started at.
        //
        // The provider whitelists the columns an exception may carry (`ALLOWED_IN_EXCEPTION` in
        // CalendarProvider2) and DTEND is not one of them: it derives DTEND from DURATION itself,
        // and refuses the insert with "Exceptions can't overwrite dtend" otherwise. So the length
        // goes in as DURATION — non-null, or the provider fails to parse it and returns nothing.
        // STATUS is set because the provider marks an exception tentative unless told otherwise.
        val values = eventValues(edit.copy(rrule = null), zone, details.timeZone).apply {
            remove(CalendarContract.Events.DTEND)
            val duration = if (edit.allDay) {
                val endDate = maxOf(edit.end.toLocalDate(), edit.start.toLocalDate().plusDays(1))
                rfc2445Duration(edit.start, endDate.atStartOfDay(), allDay = true)
            } else {
                rfc2445Duration(edit.start, edit.end, allDay = false)
            }
            put(CalendarContract.Events.DURATION, duration)
            put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
            put(
                CalendarContract.Events.ORIGINAL_INSTANCE_TIME,
                originalInstanceTime(details),
            )
        }
        return resolver.insert(uri, values) != null
    }

    private fun splitSeries(details: EventDetails, edit: EventEdit, zone: ZoneId): Boolean {
        val rrule = details.rrule ?: return updateWholeSeries(details, edit, zone)
        val capped = capRRuleBefore(
            rrule = rrule,
            instanceStart = details.instanceStart,
            seriesStart = details.seriesStart,
            zone = zone,
            allDay = details.allDay,
        ) ?: return updateWholeSeries(details, edit, zone)

        // Cap first. If the tail insert then fails the user sees a shortened series rather than a
        // duplicated one, which is the recoverable direction — they can undo by editing the end date.
        val parentUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, details.eventId)
        val parentValues = ContentValues().apply { put(CalendarContract.Events.RRULE, capped) }
        if (resolver.update(parentUri, parentValues, null, null) <= 0) return false

        // The tail continues the same series, so it keeps the original's timezone rather than
        // adopting whichever one the user happens to be standing in when they split it.
        val tailValues = eventValues(
            edit.copy(rrule = edit.rrule?.let { tailRRule(it, details, zone) }),
            zone,
            details.timeZone,
        ).apply { put(CalendarContract.Events.CALENDAR_ID, details.calendarId) }
        return resolver.insert(CalendarContract.Events.CONTENT_URI, tailValues) != null
    }

    /**
     * The rule for the new tail series.
     *
     * A COUNT can't simply be carried over — `COUNT=10` split at the fourth occurrence would give
     * the tail ten more, not the seven that remain. The provider already knows how to expand the
     * rule, so the remaining occurrences are counted off the Instances table rather than reimplemented
     * here. An UNTIL needs no adjustment: it is an absolute date and still means the same day.
     */
    private fun tailRRule(rrule: String, details: EventDetails, zone: ZoneId): String {
        if (!rrule.contains("COUNT=", ignoreCase = true)) return rrule
        val remaining = countRemainingInstances(details, zone)
        val withoutCount = rrule.split(';')
            .filter { !it.trim().startsWith("COUNT=", ignoreCase = true) && it.isNotBlank() }
        return if (remaining <= 0) withoutCount.joinToString(";")
        else (withoutCount + "COUNT=$remaining").joinToString(";")
    }

    /** How many occurrences of [details]' series start at or after the tapped one. */
    private fun countRemainingInstances(details: EventDetails, zone: ZoneId): Int {
        val from = details.instanceStart.atZone(zone).toInstant().toEpochMilli()
        // A COUNT rule is finite by definition, so a wide window is enough to see all of it.
        val to = details.instanceStart.plusYears(50).atZone(zone).toInstant().toEpochMilli()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, from)
        ContentUris.appendId(builder, to)
        return resolver.query(
            builder.build(),
            arrayOf(CalendarContract.Instances._ID),
            "${CalendarContract.Instances.EVENT_ID} = ?",
            arrayOf(details.eventId.toString()),
            null,
        )?.use { it.count } ?: 0
    }

    /**
     * Cancels [details], applying to whichever occurrences [scope] names.
     *
     * [RecurrenceScope.ThisEvent] does not delete anything — a single occurrence of a series has no
     * row of its own to remove. It is cancelled by inserting an exception marked STATUS_CANCELED,
     * which is what makes the occurrence disappear while the rest of the series survives.
     */
    suspend fun deleteEvent(
        details: EventDetails,
        scope: RecurrenceScope,
        zone: ZoneId,
    ): Boolean = withContext(io) {
        if (!details.isWritable) return@withContext false
        val effective = if (details.isRecurring) scope else RecurrenceScope.AllEvents
        val ok = when (effective) {
            RecurrenceScope.AllEvents -> {
                val uri = ContentUris.withAppendedId(
                    CalendarContract.Events.CONTENT_URI,
                    details.eventId,
                )
                resolver.delete(uri, null, null) > 0
            }

            RecurrenceScope.ThisEvent -> {
                val uri = Uri.withAppendedPath(
                    CalendarContract.Events.CONTENT_EXCEPTION_URI,
                    details.eventId.toString(),
                )
                val values = ContentValues().apply {
                    put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, originalInstanceTime(details))
                    put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED)
                }
                resolver.insert(uri, values) != null
            }

            RecurrenceScope.ThisAndFollowing -> {
                val capped = details.rrule?.let {
                    capRRuleBefore(
                        rrule = it,
                        instanceStart = details.instanceStart,
                        seriesStart = details.seriesStart,
                        zone = zone,
                        allDay = details.allDay,
                    )
                }
                val uri = ContentUris.withAppendedId(
                    CalendarContract.Events.CONTENT_URI,
                    details.eventId,
                )
                if (capped == null) {
                    // Cancelling from the first occurrence onward is cancelling the series. An UNTIL
                    // earlier than DTSTART would instead leave a row that renders nowhere but exists.
                    resolver.delete(uri, null, null) > 0
                } else {
                    val values = ContentValues().apply {
                        put(CalendarContract.Events.RRULE, capped)
                    }
                    resolver.update(uri, values, null, null) > 0
                }
            }
        }
        if (ok) onWrite()
        ok
    }

    /**
     * The instant the provider files an exception under: the occurrence's own start, exactly as the
     * Instances table reported it.
     *
     * Used verbatim rather than rebuilt from [EventDetails.instanceStart]. Both the all-day
     * UTC-midnight encoding and the timezone the local value was derived in are already baked into
     * this number, and every attempt to reconstruct it has to get both right — get either wrong and
     * the exception is filed against an instant no occurrence starts at, so the event the user asked
     * to cancel quietly stays on their calendar.
     */
    private fun originalInstanceTime(details: EventDetails): Long = details.instanceStartMillis

    /**
     * ContentValues for a write, shared by insert and update so both obey the same encoding rules.
     *
     * Every optional column is written even when empty. On an insert that is merely redundant; on an
     * update it is the whole point — skipping a null would leave the old description in place, so
     * clearing a field in the editor would silently fail to clear it.
     *
     * RRULE/DURATION and DTEND are mutually exclusive and both are always written, one of them as
     * null. That is what lets an event change between one-off and recurring: leaving the unused
     * column at its old value gives the provider a row carrying both, which it rejects.
     */
    private fun eventValues(
        edit: EventEdit,
        zone: ZoneId,
        /**
         * The row's existing EVENT_TIMEZONE, on an update. The edited times are wall-clock in the
         * device zone and DTSTART is written as an absolute instant either way, so the event never
         * moves — but a recurring series expands its later occurrences against this column, and
         * overwriting it re-anchors every future repeat to the phone's zone. Editing the title of a
         * meeting created in London while sitting in Kuala Lumpur shouldn't shift next week's.
         */
        existingTimeZone: String? = null,
    ): ContentValues {
        val recurring = !edit.rrule.isNullOrBlank()
        return ContentValues().apply {
            put(CalendarContract.Events.TITLE, edit.title)
            putOrNull(CalendarContract.Events.DESCRIPTION, edit.description)
            putOrNull(CalendarContract.Events.EVENT_LOCATION, edit.location)
            // The key and the raw colour are mutually exclusive — the provider rejects a row
            // carrying both, and it derives EVENT_COLOR from the key itself. The key is preferred
            // whenever the account has one, because that is the form Google's sync adapter
            // round-trips; a raw hex it doesn't recognise comes back as the calendar's default.
            // Both columns are still always written, one of them null, for the same reason
            // RRULE/DTEND are: leaving the unused one at its old value is how an event ends up
            // wearing a colour the user just cleared.
            if (edit.eventColorKey != null) {
                put(CalendarContract.Events.EVENT_COLOR_KEY, edit.eventColorKey)
            } else {
                putNull(CalendarContract.Events.EVENT_COLOR_KEY)
                if (edit.eventColor == null) putNull(CalendarContract.Events.EVENT_COLOR)
                else put(CalendarContract.Events.EVENT_COLOR, edit.eventColor)
            }
            putOrNull(CalendarContract.Events.RRULE, edit.rrule)

            if (edit.allDay) {
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                put(CalendarContract.Events.DTSTART, localDateToUtcMidnight(edit.start.toLocalDate()))
                val endDate = maxOf(edit.end.toLocalDate(), edit.start.toLocalDate().plusDays(1))
                if (recurring) {
                    put(
                        CalendarContract.Events.DURATION,
                        rfc2445Duration(edit.start, endDate.atStartOfDay(), allDay = true),
                    )
                    putNull(CalendarContract.Events.DTEND)
                } else {
                    put(CalendarContract.Events.DTEND, localDateToUtcMidnight(endDate))
                    putNull(CalendarContract.Events.DURATION)
                }
            } else {
                put(CalendarContract.Events.ALL_DAY, 0)
                put(
                    CalendarContract.Events.EVENT_TIMEZONE,
                    existingTimeZone?.takeIf { it.isNotBlank() } ?: TimeZone.getTimeZone(zone).id,
                )
                put(CalendarContract.Events.DTSTART, edit.start.atZone(zone).toInstant().toEpochMilli())
                if (recurring) {
                    put(
                        CalendarContract.Events.DURATION,
                        rfc2445Duration(edit.start, edit.end, allDay = false),
                    )
                    putNull(CalendarContract.Events.DTEND)
                } else {
                    put(CalendarContract.Events.DTEND, edit.end.atZone(zone).toInstant().toEpochMilli())
                    putNull(CalendarContract.Events.DURATION)
                }
            }
        }
    }

    /** [eventValues] for a brand-new row, which additionally names the calendar it belongs to. */
    private fun eventValues(event: NewEvent, zone: ZoneId): ContentValues =
        eventValues(
            EventEdit(
                title = event.title,
                start = event.start,
                end = event.end,
                allDay = event.allDay,
                description = event.description,
                location = event.location,
                eventColorKey = event.eventColorKey,
                rrule = event.rrule,
                eventColor = event.eventColor,
            ),
            zone,
        ).apply { put(CalendarContract.Events.CALENDAR_ID, event.calendarId) }

    private fun ContentValues.putOrNull(key: String, value: String?) {
        if (value.isNullOrBlank()) putNull(key) else put(key, value)
    }

    // ---- birthdays -------------------------------------------------------

    /**
     * Where an app birthday is written: the primary calendar of a synced (non-local) account, so it
     * reaches Google; failing that any writable synced calendar, then any writable one at all.
     * Null when nothing is writable, and the birthday stays on the phone until something is.
     */
    suspend fun birthdayCalendar(): CalendarInfo? {
        val writable = listWritableCalendars()
        val synced = writable.filter { it.accountType != CalendarContract.ACCOUNT_TYPE_LOCAL }
        return synced.firstOrNull { it.isPrimary } ?: synced.firstOrNull() ?: writable.firstOrNull()
    }

    /**
     * Every Events row that is, or may be, a birthday: rows on Google Contacts' birthdays calendar,
     * all-day rows whose title starts or ends with "birthday", and the rows in [linkedIds] whatever they
     * now look like — a linked event renamed in Google Calendar must still be found, or the app
     * would read the rename as a deletion. Deleted rows (DELETED = 1, waiting for the adapter to
     * upload the delete) are left out, as they are everywhere else.
     *
     * The Events table, not Instances: a birthday is one yearly series, and its first day is all
     * the merge needs.
     */
    suspend fun birthdayEventRows(linkedIds: Collection<Long>): List<BirthdayEventRow> = withContext(io) {
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.OWNER_ACCOUNT,
            CalendarContract.Events.CALENDAR_ACCESS_LEVEL,
        )
        val idList = linkedIds.joinToString(",")
        val linked = if (idList.isEmpty()) "" else " OR ${CalendarContract.Events._ID} IN ($idList)"
        val selection = "${CalendarContract.Events.DELETED} = 0 AND (" +
            "(${CalendarContract.Events.VISIBLE} = 1 AND ${CalendarContract.Events.ALL_DAY} = 1 AND (" +
            "${CalendarContract.Events.OWNER_ACCOUNT} LIKE ? OR " +
            "${CalendarContract.Events.TITLE} LIKE ? OR ${CalendarContract.Events.TITLE} LIKE ?" +
            "))$linked)"
        // A coarse net; BirthdayEvents.isBirthday decides. LIKE is case-insensitive for ASCII.
        val args = arrayOf("%" + BirthdayEvents.CONTACTS_OWNER_SUFFIX, "%birthday", "birthday%")
        val out = mutableListOf<BirthdayEventRow>()
        resolver.query(CalendarContract.Events.CONTENT_URI, projection, selection, args, null)?.use { c ->
            while (c.moveToNext()) {
                val allDay = c.getInt(3) == 1
                val start = c.getLong(2)
                out += BirthdayEventRow(
                    eventId = c.getLong(0),
                    title = c.getString(1).orEmpty(),
                    // All-day rows sit at UTC midnight of their day; a linked row someone turned into
                    // a timed event in Google is read on the device's own calendar.
                    date = if (allDay) utcMidnightToLocalDate(start)
                    else java.time.Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate(),
                    rrule = c.getString(4)?.takeIf { it.isNotBlank() },
                    ownerAccount = c.getString(5),
                    writable = c.getInt(6) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                )
            }
        }
        out
    }

    /**
     * Writes [birthday] to [calendarId] as a yearly all-day event and returns its id. Goes through
     * [insertEvent], so the all-day, timezone and RRULE-vs-DTEND rules are the ones every other
     * write obeys, and the widgets refresh and a sync is requested like after any other write.
     */
    suspend fun insertBirthdayEvent(birthday: Birthday, calendarId: Long, today: LocalDate): Long? {
        val start = BirthdayEvents.seriesStart(birthday, today)
        return insertEvent(
            NewEvent(
                calendarId = calendarId,
                title = BirthdayEvents.titleFor(birthday.name),
                start = start.atStartOfDay(),
                end = start.plusDays(1).atStartOfDay(),
                allDay = true,
                rrule = BirthdayEvents.rruleFor(birthday.month, birthday.day),
            ),
            ZoneId.of("UTC"),
        )
    }

    /**
     * Moves or renames a birthday's event. Only the columns a birthday owns are written — not
     * [eventValues], which nulls every optional column and would wipe a description, place or
     * colour the user gave the event in Google Calendar.
     */
    suspend fun updateBirthdayEvent(eventId: Long, birthday: Birthday, today: LocalDate): Boolean =
        withContext(io) {
            val start = BirthdayEvents.seriesStart(birthday, today)
            val values = ContentValues().apply {
                put(CalendarContract.Events.TITLE, BirthdayEvents.titleFor(birthday.name))
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                put(CalendarContract.Events.DTSTART, localDateToUtcMidnight(start))
                put(CalendarContract.Events.RRULE, BirthdayEvents.rruleFor(birthday.month, birthday.day))
                put(CalendarContract.Events.DURATION, "P1D")
                putNull(CalendarContract.Events.DTEND)
            }
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val ok = resolver.update(uri, values, null, null) > 0
            if (ok) onWrite()
            ok
        }

    /**
     * Emits once immediately, then whenever any calendar data changes — an in-app insert, a Google
     * sync landing, or an edit made in another app. [conflate] keeps a burst of sync notifications
     * from queuing up a re-query per event.
     */
    fun observeChanges(): Flow<Unit> = callbackFlow {
        trySend(Unit)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        resolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate()
}

/** Convenience for callers holding a [LocalDateTime] pair rather than millis. */
fun LocalDateTime.toEpochMillis(zone: ZoneId): Long = atZone(zone).toInstant().toEpochMilli()
