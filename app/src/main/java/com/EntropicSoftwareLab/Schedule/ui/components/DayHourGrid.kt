package com.EntropicSoftwareLab.Schedule.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance
import com.EntropicSoftwareLab.Schedule.data.calendar.HourGrid
import com.EntropicSoftwareLab.Schedule.data.task.TaskGrid
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens
import com.EntropicSoftwareLab.Schedule.ui.theme.TabularFigures
import kotlin.math.floor

/**
 * Twelve cells across a phone leaves each one around 25dp wide, so a taller cell would read as a
 * column rather than a square. Below the 48dp touch guidance, like the month grid's day cells —
 * the details sheet and the agenda below both give the same events a full-size target.
 */
private val CELL_HEIGHT = 32.dp

/** A deadline is a moment, so its mark is a hairline. Wide enough to see on a ~25dp cell, no more. */
private val DEADLINE_TICK_WIDTH = 3.dp

/** Between neighbouring hour cells, and between lanes. */
private val CELL_GAP = 2.dp

/** Below this a bar shows no title — about four letters at 10sp. */
private val MIN_TITLED_BAR_WIDTH = 30.dp

/**
 * A day as 24 hour cells: midnight to noon on the top half, noon to midnight below.
 *
 * Events that clash get their own lane — a second row of the same 12 hours — rather than splitting
 * an hour between them. Within a lane, an event is one bar from its start to its end, crossing the
 * gaps between cells (see [HourGrid.runs]), and titled where it's wide enough; a meeting ending at
 * half past stops halfway across that hour's cell. The two rules keep
 * the reading unambiguous: horizontal extent always means time, and a stacked row always means a
 * clash.
 *
 * A task deadline is drawn as a tick *over* the top lane, never as a fill and never as a lane of its
 * own — both of those would state something false under the rules above. A fill would claim the task
 * occupies an hour; a lane would report it as a clash. A deadline occupies nothing and clashes with
 * nothing, so it gets a mark with no width and no row.
 *
 * Tapping a free cell means "book this hour"; tapping an occupied one means "what's this?" — the
 * caller decides what each does.
 *
 * @param lanes from [HourGrid.lanes]; lane 0 always renders so free hours stay tappable.
 * @param colorFor the colour assigned to an event for this day.
 * @param taskMarks from [TaskGrid.byHour], indexed by hour. Empty for a day with no deadlines.
 * @param currentHour highlighted as "now", or null when the grid isn't showing today.
 */
@Composable
fun DayHourGrid(
    lanes: List<List<List<HourGrid.HourSlice>>>,
    colorFor: (EventInstance) -> Color,
    onHourTap: (hour: Int, cellIsFree: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    taskMarks: List<List<TaskGrid.Mark>> = emptyList(),
    currentHour: Int? = null,
) {
    val d = AppThemeTokens.dimens
    // Each half carries its own scale, sitting directly above the cells it describes. One shared
    // scale between the two rows reads as a single 24-hour axis, which makes both rows look like
    // they span the whole day.
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(d.spaceMd)) {
        HalfDaySection(lanes, colorFor, onHourTap, taskMarks, hours = 0 until 12, currentHour = currentHour)
        HalfDaySection(lanes, colorFor, onHourTap, taskMarks, hours = 12 until 24, currentHour = currentHour)
    }
}

/** One half of the day: its hour scale, then the lane rows beneath it. */
@Composable
private fun HalfDaySection(
    lanes: List<List<List<HourGrid.HourSlice>>>,
    colorFor: (EventInstance) -> Color,
    onHourTap: (hour: Int, cellIsFree: Boolean) -> Unit,
    taskMarks: List<List<TaskGrid.Mark>>,
    hours: IntRange,
    currentHour: Int?,
) {
    val d = AppThemeTokens.dimens
    Column(verticalArrangement = Arrangement.spacedBy(d.spaceXxs)) {
        HalfDayLabels(hours, currentHour)
        HalfDay(lanes, colorFor, onHourTap, taskMarks, hours = hours, currentHour = currentHour)
    }
}

/**
 * The lane rows for one half of the day.
 *
 * A lane empty across these twelve hours is dropped, so a morning-only clash doesn't leave a blank
 * second row hanging under the afternoon. Lane 0 always survives — it carries the free-hour
 * tap targets even on a day with nothing in it.
 */
@Composable
private fun HalfDay(
    lanes: List<List<List<HourGrid.HourSlice>>>,
    colorFor: (EventInstance) -> Color,
    onHourTap: (hour: Int, cellIsFree: Boolean) -> Unit,
    taskMarks: List<List<TaskGrid.Mark>>,
    hours: IntRange,
    currentHour: Int?,
) {
    val visible = lanes.filterIndexed { index, lane ->
        index == 0 || hours.any { lane.getOrElse(it) { emptyList() }.isNotEmpty() }
    }.ifEmpty { listOf(List(HourGrid.HOURS_PER_DAY) { emptyList() }) }

    Column(verticalArrangement = Arrangement.spacedBy(CELL_GAP)) {
        visible.forEachIndexed { laneIndex, lane ->
            // Deadlines ride the top row only. Repeating them down every lane would read as one
            // deadline per clashing event, and lane 0 is the one row guaranteed to be on screen.
            val laneMarks = if (laneIndex == 0) taskMarks else emptyList()
            val runs = remember(lane, hours) { HourGrid.runs(lane, hours) }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(CELL_HEIGHT),
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
                ) {
                    hours.forEach { hour ->
                        val slices = lane.getOrElse(hour) { emptyList() }
                        HourCell(
                            slices = slices,
                            marks = laneMarks.getOrElse(hour) { emptyList() },
                            hour = hour,
                            // The *cell's* own emptiness, not the hour's. An hour covered by an
                            // all-day camp in lane 0 still has empty cells in the lanes above it,
                            // and tapping one of those means "put something here" — answering that
                            // with the details of the camp leaves no way to add an event during a
                            // long event at all.
                            //
                            // Deadlines are deliberately not consulted: a task books no time, so an
                            // hour carrying one is still an hour the user can put a meeting in.
                            onClick = { onHourTap(hour, slices.isEmpty()) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                }
                LaneOverlay(
                    runs = runs,
                    marks = laneMarks,
                    hours = hours,
                    colorFor = colorFor,
                    nowOffset = currentHour?.let { it - hours.first }?.takeIf { it in 0 until hours.count() },
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }
}

/**
 * What sits on top of one lane's cells: each event as a single bar from where it starts to where it
 * ends — across the gaps between cells, so a two-hour lecture reads as one block and not two — then
 * the deadline ticks, then the outline marking the current hour.
 *
 * Nothing here takes pointer input, so a tap goes straight through to the cell underneath.
 */
@Composable
private fun LaneOverlay(
    runs: List<HourGrid.Run>,
    marks: List<List<TaskGrid.Mark>>,
    hours: IntRange,
    colorFor: (EventInstance) -> Color,
    nowOffset: Int?,
    modifier: Modifier = Modifier,
) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    val shape = RoundedCornerShape(d.radiusXs)
    val count = hours.count()
    BoxWithConstraints(modifier) {
        val cellWidth = (maxWidth - CELL_GAP * (count - 1)) / count

        // Hours from the row's start to dp. An end on a whole hour is the right edge of the cell
        // before it, not the left edge of the next — otherwise a bar ending at 10:00 would reach
        // across the gap and touch the 10:00 cell.
        fun xAt(t: Float, isEnd: Boolean): Dp {
            val whole = floor(t).toInt()
            val cell = if (isEnd && whole > 0 && t == whole.toFloat()) whole - 1 else whole
            return (cellWidth + CELL_GAP) * cell + cellWidth * (t - cell)
        }

        runs.forEach { run ->
            val left = xAt(run.start, isEnd = false)
            val width = xAt(run.end, isEnd = true) - left
            val fill = colorFor(run.event)
            Box(
                Modifier
                    .offset(x = left)
                    .width(width)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(fill),
                contentAlignment = Alignment.Center,
            ) {
                // Only where there's room for a few letters: an 8dp sliver with "…" in it is noise.
                if (width >= MIN_TITLED_BAR_WIDTH) {
                    Text(
                        text = run.event.title,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                        color = onChipColor(fill),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }

        // Drawn after the bars so a deadline stays visible on top of the event it falls inside —
        // which is the case worth seeing, not an edge case. Ink rather than a palette colour: the
        // event palette avoids the neutrals, so a tick can never be mistaken for one more event.
        // Clamped inside its own cell, so a deadline at :00 never lands in the hour before.
        marks.forEachIndexed { hour, hourMarks ->
            val offset = hour - hours.first
            if (offset !in 0 until count) return@forEachIndexed
            val cellLeft = (cellWidth + CELL_GAP) * offset
            hourMarks.forEach { mark ->
                val x = (cellLeft + cellWidth * mark.fraction)
                    .coerceAtMost(cellLeft + cellWidth - DEADLINE_TICK_WIDTH)
                Box(
                    Modifier
                        .offset(x = x)
                        .width(DEADLINE_TICK_WIDTH)
                        .fillMaxHeight()
                        .background(if (mark.done) colors.textSecondary else colors.textPrimary),
                )
            }
        }

        nowOffset?.let { offset ->
            Box(
                Modifier
                    .offset(x = (cellWidth + CELL_GAP) * offset)
                    .width(cellWidth)
                    .fillMaxHeight()
                    .border(d.hairlineWidth * 1.5f, colors.greenText, shape),
            )
        }
    }
}

@Composable
private fun HourCell(
    slices: List<HourGrid.HourSlice>,
    marks: List<TaskGrid.Mark>,
    hour: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    val label = buildString {
        append(hourLabel(hour))
        append(", ")
        append(
            when (slices.size) {
                0 -> "free"
                1 -> slices.first().event.title
                else -> slices.joinToString(", ") { it.event.title }
            },
        )
        // Spoken after the hour's events, and as "due" rather than as another event — a screen
        // reader hearing a bare title has no other way to tell a deadline from a meeting.
        marks.forEach { mark ->
            append(", due: ${mark.title}")
            if (mark.done) append(", done")
        }
    }

    // The empty hour only: the event bars and ticks are drawn over it by LaneOverlay.
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(d.radiusXs))
            .background(colors.accentMuted)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
    )
}

/**
 * The scale for one half-day row: every hour numbered, each label sharing its cell's weight so it
 * sits directly over the cell it names. Numbers run 0–23 rather than am/pm pairs, matching the
 * 24-hour clock the agenda and the details sheet use — and making it impossible to read a row as
 * anything but the twelve hours it covers.
 */
@Composable
private fun HalfDayLabels(hours: IntRange, currentHour: Int?) {
    val colors = AppThemeTokens.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        hours.forEach { hour ->
            Text(
                text = hour.toString(),
                style = MaterialTheme.typography.bodySmall.merge(TabularFigures),
                color = if (hour == currentHour) colors.greenText else colors.textSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Spoken form for the cell's content description, in the same 24-hour clock as the agenda. */
private fun hourLabel(hour: Int): String = "%02d:00".format(hour)
