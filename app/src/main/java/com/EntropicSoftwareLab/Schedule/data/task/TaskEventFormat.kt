package com.EntropicSoftwareLab.Schedule.data.task

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** A raw row from the Tasks calendar, as CalendarRepository.taskEventRows returns it. */
data class TaskEventRow(
    val eventId: Long,
    val title: String,
    val description: String?,
    val allDay: Boolean,
    val startMillis: Long,
    val timezone: String?,
)

/** The columns CalendarRepository.insertTaskEvent / updateTaskEvent write. */
data class TaskEventFields(
    val title: String,
    val description: String,
    val allDay: Boolean,
    val startMillis: Long,
    val endMillis: Long,
    val timezone: String,
)

/** A task as read off the calendar. */
data class RemoteTask(
    val eventId: Long,
    val syncId: String,
    val title: String,
    val due: LocalDate,
    val dueTime: LocalTime?,
    val notes: String?,
    val done: Boolean,
    val editedAt: Long,
    val tickedAt: Long,
    val show: TaskShow = TaskShow.SameDay,
)

/**
 * How a task is written onto an event of the "Tasks" calendar, and read back. The format is shared
 * with Jarvis, which reads and writes the same calendar; neither app talks to the other, so this
 * is the whole contract:
 *
 * - One task is one event, titled with the task's title.
 * - A timed task is a zero-length event at its due time in the writer's zone, with that zone as
 *   `EVENT_TIMEZONE`. An untimed task is an all-day event (UTC midnight to the next UTC midnight).
 * - The description is the notes, then a tag line as the *last* line:
 *   `[task] id=<syncId> edited=<epoch ms> ticked=<epoch ms> done=<0|1>`, plus an optional
 *   `show=<same|week|until>` (which Today pages show the task; absent means same, and only a
 *   non-default value is written). An event with no valid tag line is not a task and is ignored.
 *
 * Reading goes through the row's own timezone, not the phone's, which is what keeps "17:00" meaning
 * 17:00 after the phone has travelled: [Task.dueTime] is wall-clock with no zone.
 */
object TaskEventFormat {

    private val TAG_LINE = Regex("""^\[task]\s+(.*)$""")
    private val ID_SHAPE = Regex("""[A-Za-z0-9-]{1,64}""")
    private val WHITESPACE = Regex("""\s+""")
    private val BREAK = Regex("""<\s*br\s*/?\s*>""", RegexOption.IGNORE_CASE)
    private val BLOCK_TAG = Regex("""</?(div|p|li)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val HTML_TAG = Regex("""</?[A-Za-z][^>]*>""")
    private val HTML_SHAPE = Regex("""<\s*(br|div|p|li)\b|&[#A-Za-z0-9]+;""", RegexOption.IGNORE_CASE)
    private val CLAIM = Regex("""\[task]\s+.*?\bid=([A-Za-z0-9-]{1,64})(?=\s|$)""")

    fun tag(
        syncId: String,
        editedAt: Long,
        tickedAt: Long,
        done: Boolean,
        show: TaskShow = TaskShow.SameDay,
    ): String =
        "[task] id=$syncId edited=$editedAt ticked=$tickedAt done=${if (done) 1 else 0}" +
            if (show != TaskShow.SameDay) " show=${show.wire}" else ""

    /** Notes, then the tag line last. [Task.syncId] must be set. */
    fun description(task: Task): String {
        val syncId = requireNotNull(task.syncId) { "a task needs a syncId before it is written" }
        val tag = tag(syncId, task.editedAt, task.tickedAt, task.done, task.show)
        val notes = task.notes?.trim().orEmpty()
        return if (notes.isNotEmpty()) "$notes\n\n$tag" else tag
    }

    fun fields(task: Task, zone: ZoneId): TaskEventFields {
        val time = task.dueTime
        return if (time != null) {
            val start = task.due.atTime(time.truncatedTo(ChronoUnit.MINUTES))
                .atZone(zone).toInstant().toEpochMilli()
            TaskEventFields(
                title = task.title.trim(),
                description = description(task),
                allDay = false,
                startMillis = start,
                endMillis = start,
                timezone = zone.id,
            )
        } else {
            TaskEventFields(
                title = task.title.trim(),
                description = description(task),
                allDay = true,
                startMillis = utcMidnight(task.due),
                endMillis = utcMidnight(task.due.plusDays(1)),
                timezone = "UTC",
            )
        }
    }

    /**
     * Reading is strict first: line endings are made LF, the tag is the last line that has the tag
     * shape (so a note typed after it in Google Calendar still reads, as notes), and every other
     * line is notes. Only a description with no newline at all that also looks like what Google's
     * web editor produces (`<br>`, `<div>`, `<p>` or an `&...;` entity) is run through [htmlToText]
     * first. The phone always writes a newline between notes and tag, so its own rows never take
     * that path, and notes such as "John <j@x.com>" or a literal "&amp;" come back unchanged.
     *
     * Null when the row has no valid tag line or no title: it is not one of ours to touch (but see
     * [claimedSyncId] for telling "not ours" from "ours and unreadable").
     */
    fun decode(row: TaskEventRow, zone: ZoneId): RemoteTask? {
        val title = row.title.trim()
        if (title.isEmpty()) return null
        val lines = readable(row.description ?: return null).split("\n")
        val tagIndex = lines.indexOfLast { TAG_LINE.matches(it.trim()) }
        if (tagIndex < 0) return null
        val rest = TAG_LINE.matchEntire(lines[tagIndex].trim())?.groupValues?.get(1) ?: return null

        val keys = rest.split(WHITESPACE).mapNotNull { token ->
            val eq = token.indexOf('=')
            if (eq <= 0) null else token.substring(0, eq) to token.substring(eq + 1)
        }.toMap()
        val syncId = keys["id"]?.takeIf { ID_SHAPE.matches(it) } ?: return null

        val notes = lines.filterIndexed { i, _ -> i != tagIndex }
            .joinToString("\n").trim().takeIf { it.isNotEmpty() }

        val due: LocalDate
        val dueTime: LocalTime?
        if (row.allDay) {
            due = Instant.ofEpochMilli(row.startMillis).atZone(ZoneOffset.UTC).toLocalDate()
            dueTime = null
        } else {
            val rowZone = row.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: zone
            val local = Instant.ofEpochMilli(row.startMillis).atZone(rowZone).toLocalDateTime()
            due = local.toLocalDate()
            dueTime = local.toLocalTime().truncatedTo(ChronoUnit.MINUTES)
        }

        return RemoteTask(
            eventId = row.eventId,
            syncId = syncId,
            title = title,
            due = due,
            dueTime = dueTime,
            notes = notes,
            done = keys["done"] == "1",
            editedAt = keys["edited"]?.toLongOrNull() ?: 0L,
            tickedAt = keys["ticked"]?.toLongOrNull() ?: 0L,
            show = TaskShow.fromWire(keys["show"]),
        )
    }

    /**
     * The syncId a row says it carries, found anywhere in its description, even when [decode] can't
     * read the row (title cleared, tag damaged). The sync treats such a row as "present but
     * unreadable", never as absent: absent plus a known id would delete the phone's copy.
     */
    fun claimedSyncId(row: TaskEventRow): String? {
        val description = row.description ?: return null
        return CLAIM.findAll(readable(description)).lastOrNull()?.groupValues?.get(1)
    }

    /** LF line endings, then the HTML reading only if the text has Google's web-editor shape. */
    private fun readable(description: String): String {
        val text = description.replace("\r\n", "\n").replace("\r", "\n")
        return if (!text.contains('\n') && HTML_SHAPE.containsMatchIn(text)) htmlToText(text) else text
    }

    /**
     * Undoes what Google's web editor does to a description: `<br>` and block tags become
     * newlines, other tags go, entities are unescaped. `&amp;` goes last so "&amp;lt;" reads as the
     * text "&lt;", not as "<".
     */
    private fun htmlToText(text: String): String = text
        .replace(BREAK, "\n")
        .replace(BLOCK_TAG, "\n")
        .replace(HTML_TAG, "")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&amp;", "&")

    private fun utcMidnight(date: LocalDate): Long =
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}
