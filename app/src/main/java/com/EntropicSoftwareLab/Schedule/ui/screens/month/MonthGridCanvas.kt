package com.EntropicSoftwareLab.Schedule.ui.screens.month

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.EntropicSoftwareLab.Schedule.data.appearance.EventColorResolver
import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance
import com.EntropicSoftwareLab.Schedule.data.calendar.MonthChip
import com.EntropicSoftwareLab.Schedule.data.calendar.MonthChips
import com.EntropicSoftwareLab.Schedule.data.calendar.WeekChips
import com.EntropicSoftwareLab.Schedule.ui.components.asCalendarColor
import com.EntropicSoftwareLab.Schedule.ui.components.onChipColor
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens
import com.EntropicSoftwareLab.Schedule.ui.theme.TabularFigures
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val CHIP_CORNER = 3.dp
private val DOT_SIZE = 5.dp
private val DOT_GAP = 3.dp
/** Beyond this a day shows [MAX_DOTS] - 1 dots and a "+N"; a row of eight dots is a smear. */
private const val MAX_DOTS = 4
private val CHIP_TEXT_PADDING = 3.dp
private val TODAY_CIRCLE_TOP_PADDING = 2.dp
/** Gap between a cell's last band (or its day number) and its dots. */
private val DOT_TOP_PADDING = 4.dp
private val HAIRLINE_WIDTH = 1.dp
/** A named chip on the full-page grid: Google's month view is about this, at 11sp. */
private val FULL_CHIP_HEIGHT = 16.dp
/** Air under a full-page cell's last row, so it doesn't sit on the next week's rule. */
private val FULL_CELL_BOTTOM = 2.dp

/** What one day cell draws under its bands: a dot per event, then maybe a "+N". */
private class DayDots(val colors: List<Color>, val more: TextLayoutResult?)

/** One week's precomputed geometry: the band layout, every text measured, and each day's dots. */
private class WeekDraw(
    val week: List<LocalDate>,
    val layout: WeekChips,
    val chipLabels: Map<MonthChip, TextLayoutResult>,
    /** Per column, how many band rows are in use there — the dots go directly below them. */
    val bandRowsUsed: IntArray,
    val dots: List<DayDots>,
    /** Columns whose last row carries the `•••` marker — the full-page grid only. */
    val moreColumns: Set<Int> = emptySet(),
)

/**
 * One month's grid, drawn in a single [Canvas] pass instead of composing a node per day, per dot and
 * per chip.
 *
 * **Only all-day and multi-day events get a named band; everything else is a dot**
 * ([MonthChips.isBand]). A cell is about fifty dp wide: timed events named there were "FO…" and
 * "Churc…" stacked two deep with a "+2" under them, which said nothing and made a busy month look
 * broken. The agenda under the grid carries the names and times; the grid says which days are busy
 * and which spans run through them. Bands that don't fit their cell's [MAX_CHIP_ROWS] are counted
 * into that day's dots, so every event on a day is still represented by something.
 *
 * **[nameEveryEvent] is the full-page grid** (2026-10-14, after Google Calendar's month view): cells
 * as tall as [cellHeight] allows, every event named — timed ones too — as many rows as fit, then
 * `•••` in the last row ([MonthChips.layoutWithMoreRow], as on the widget). Names are clipped at the
 * chip's edge rather than ellipsised, so "Orientatio" keeps the letters "Orient…" spent on a mark.
 *
 * Every position is a constant offset off [cellWidth], so all of it is computed once in [remember]
 * blocks keyed on the data and only the draw lambda runs per frame.
 */
@Composable
internal fun MonthGridCanvas(
    month: YearMonth,
    weeks: List<List<LocalDate>>,
    cellWidth: Dp,
    today: LocalDate,
    eventsByDate: Map<LocalDate, List<EventInstance>>,
    zone: ZoneId,
    colorOverrides: Map<Long, Int>,
    onDayClick: (LocalDate) -> Unit,
    onDayLongPress: (LocalDate) -> Unit,
    /** "3 events", "1 birthday" — the grid doesn't know which calendar it is drawing. */
    describeCount: (Int) -> String,
    modifier: Modifier = Modifier,
    cellHeight: Dp = CELL_HEIGHT,
    nameEveryEvent: Boolean = false,
) {
    val colors = AppThemeTokens.colors
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val dayNumberStyle = MaterialTheme.typography.bodyLarge.merge(TabularFigures)
    val chipTextStyle = if (nameEveryEvent) {
        MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 12.sp)
    } else {
        MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 11.sp)
    }
    val chipHeight = if (nameEveryEvent) FULL_CHIP_HEIGHT else CHIP_HEIGHT
    // As many rows of names as the cell holds below its day number, at least one.
    val maxRows = if (nameEveryEvent) {
        ((cellHeight - DAY_NUMBER_ROW - FULL_CELL_BOTTOM) / (FULL_CHIP_HEIGHT + CHIP_GAP)).toInt().coerceAtLeast(1)
    } else {
        MAX_CHIP_ROWS
    }
    val moreTextStyle = MaterialTheme.typography.bodySmall.copy(
        fontSize = 9.sp,
        lineHeight = 10.sp,
        color = colors.textSecondary,
    )

    val cellWidthPx = with(density) { cellWidth.toPx() }
    val rowHeightPx = with(density) { cellHeight.toPx() }
    val chipHeightPx = with(density) { chipHeight.toPx() }
    val chipGapPx = with(density) { CHIP_GAP.toPx() }
    val chipInsetPx = with(density) { CHIP_INSET.toPx() }
    val dayNumberRowPx = with(density) { DAY_NUMBER_ROW.toPx() }
    val dayNumberDiameterPx = with(density) { DAY_NUMBER_DIAMETER.toPx() }
    val dotSizePx = with(density) { DOT_SIZE.toPx() }
    val dotGapPx = with(density) { DOT_GAP.toPx() }
    val dotTopPaddingPx = with(density) { DOT_TOP_PADDING.toPx() }
    val chipCornerPx = with(density) { CHIP_CORNER.toPx() }
    val chipTextPaddingPx = with(density) { CHIP_TEXT_PADDING.toPx() }
    val todayCircleTopPaddingPx = with(density) { TODAY_CIRCLE_TOP_PADDING.toPx() }
    val hairlineWidthPx = with(density) { HAIRLINE_WIDTH.toPx() }

    val moreMarker = remember(textMeasurer, chipTextStyle, colors) {
        textMeasurer.measure(
            text = "•••",
            style = chipTextStyle.copy(color = colors.textSecondary, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
    }

    val weekDraws = remember(
        weeks, eventsByDate, zone, month, colorOverrides, textMeasurer, cellWidthPx, maxRows, nameEveryEvent,
    ) {
        if (nameEveryEvent) {
            return@remember weeks.map { week ->
                val layout = MonthChips.layoutWithMoreRow(week, eventsByDate, zone, maxRows)
                val chipLabels = layout.chips.associateWith { chip ->
                    val startInset = if (chip.continuesBefore) 0f else chipInsetPx
                    val endInset = if (chip.continuesAfter) 0f else chipInsetPx
                    val bandWidthPx = cellWidthPx * chip.span - startInset - endInset
                    val constraintWidth = (bandWidthPx - chipTextPaddingPx * 2).coerceAtLeast(0f).toInt()
                    textMeasurer.measure(
                        text = chip.event.title,
                        style = chipTextStyle,
                        maxLines = 1,
                        softWrap = false,
                        // Clipped, not "…": in a fifty-dp chip the mark costs two letters of name.
                        overflow = TextOverflow.Clip,
                        constraints = Constraints(maxWidth = constraintWidth),
                    )
                }
                WeekDraw(
                    week = week,
                    layout = layout,
                    chipLabels = chipLabels,
                    bandRowsUsed = IntArray(week.size),
                    dots = List(week.size) { DayDots(emptyList(), null) },
                    // With one row there is no row to give up; see layoutWithMoreRow.
                    moreColumns = if (maxRows > 1) layout.hidden.keys else emptySet(),
                )
            }
        }
        weeks.map { week ->
            val bandsByDate = week.associateWith { date ->
                eventsByDate[date].orEmpty().filter { MonthChips.isBand(it, zone) }
            }
            val layout = MonthChips.layout(week, bandsByDate, zone, maxRows)
            val chipLabels = layout.chips.associateWith { chip ->
                val startInset = if (chip.continuesBefore) 0f else chipInsetPx
                val endInset = if (chip.continuesAfter) 0f else chipInsetPx
                val bandWidthPx = cellWidthPx * chip.span - startInset - endInset
                val constraintWidth = (bandWidthPx - chipTextPaddingPx * 2).coerceAtLeast(0f).toInt()
                textMeasurer.measure(
                    text = chip.event.title,
                    style = chipTextStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    constraints = Constraints(maxWidth = constraintWidth),
                )
            }
            val bandRowsUsed = IntArray(week.size)
            layout.chips.forEach { chip ->
                for (column in chip.startColumn until chip.startColumn + chip.span) {
                    bandRowsUsed[column] = maxOf(bandRowsUsed[column], chip.row + 1)
                }
            }
            val dots = week.mapIndexed { column, date ->
                val inMonth = YearMonth.from(date) == month
                val timed = eventsByDate[date].orEmpty().filterNot { MonthChips.isBand(it, zone) }
                val hiddenBands = layout.hidden[column] ?: 0
                val total = timed.size + hiddenBands
                val shown = if (total > MAX_DOTS) MAX_DOTS - 1 else total
                // Timed events keep their own colours; a hidden band has no single colour it could
                // honestly claim in this cell, so it is a neutral dot.
                val dotColors = (timed.map { event ->
                    (EventColorResolver.argbFor(event, colorOverrides) ?: 0).asCalendarColor(colors.green)
                } + List(hiddenBands) { colors.textSecondary })
                    .take(shown)
                    .map { if (inMonth) it else it.copy(alpha = 0.3f) }
                val more = if (total > shown) {
                    textMeasurer.measure(
                        text = "+${total - shown}",
                        style = moreTextStyle,
                        maxLines = 1,
                    )
                } else {
                    null
                }
                DayDots(dotColors, more)
            }
            WeekDraw(week, layout, chipLabels, bandRowsUsed, dots)
        }
    }

    val dayNumberLabels = remember(weeks, month, today, textMeasurer) {
        weeks.flatten().associateWith { date ->
            val inMonth = YearMonth.from(date) == month
            val numberColor = when {
                !inMonth -> colors.textSecondary.copy(alpha = 0.4f)
                date == today -> colors.greenText
                else -> colors.textPrimary
            }
            textMeasurer.measure(
                text = date.dayOfMonth.toString(),
                style = dayNumberStyle.copy(color = numberColor),
            )
        }
    }

    val haptics = LocalHapticFeedback.current

    // The canvas has no child nodes, so TalkBack would find one node for the whole month and no way
    // to reach a day. This layer is 42 empty boxes carrying only semantics — no pointer input, so a
    // finger's tap still falls through to the canvas below, and no drawing, so it costs a layout
    // pass and nothing per frame.
    Box(modifier) {
        Canvas(
            modifier = Modifier
                // A Canvas has no intrinsic size: without both of these it takes the parent's minimum,
                // which a pager page need not pin, and the grid would measure to nothing.
                .width(cellWidth * DAYS_PER_WEEK)
                .height(cellHeight * weeks.size)
                .pointerInput(weeks, month, cellWidthPx, rowHeightPx) {
                    fun dateAt(offset: Offset): LocalDate {
                        val column = (offset.x / cellWidthPx).toInt().coerceIn(0, DAYS_PER_WEEK - 1)
                        val row = (offset.y / rowHeightPx).toInt().coerceIn(0, weeks.size - 1)
                        return weeks[row][column]
                    }
                    detectTapGestures(
                        // A neighbouring month's day opens too: it is drawn, so tapping it and getting
                        // nothing read as the app not responding.
                        onTap = { onDayClick(dateAt(it)) },
                        onLongPress = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDayLongPress(dateAt(it))
                        },
                    )
                },
        ) {
            weekDraws.forEachIndexed { rowIndex, weekDraw ->
                val rowTop = rowHeightPx * rowIndex

                drawLine(
                    color = colors.hairline,
                    start = Offset(0f, rowTop),
                    end = Offset(cellWidthPx * DAYS_PER_WEEK, rowTop),
                    strokeWidth = hairlineWidthPx,
                )

                // The full-page grid rules its columns too: with names running to each cell's edge,
                // the rule is what says where one day stops and the next begins.
                if (nameEveryEvent) {
                    for (column in 1 until DAYS_PER_WEEK) {
                        drawLine(
                            color = colors.hairline,
                            start = Offset(cellWidthPx * column, rowTop),
                            end = Offset(cellWidthPx * column, rowTop + rowHeightPx),
                            strokeWidth = hairlineWidthPx,
                        )
                    }
                }

                weekDraw.week.forEachIndexed { column, date ->
                    val label = dayNumberLabels.getValue(date)
                    val cellLeft = cellWidthPx * column
                    if (date == today) {
                        drawCircle(
                            color = colors.accentMuted,
                            radius = dayNumberDiameterPx / 2f,
                            center = Offset(
                                cellLeft + cellWidthPx / 2f,
                                rowTop + todayCircleTopPaddingPx + dayNumberDiameterPx / 2f,
                            ),
                        )
                    }
                    drawText(
                        textLayoutResult = label,
                        topLeft = Offset(
                            cellLeft + (cellWidthPx - label.size.width) / 2f,
                            rowTop + todayCircleTopPaddingPx + (dayNumberDiameterPx - label.size.height) / 2f,
                        ),
                    )
                }

                weekDraw.layout.chips.forEach { chip ->
                    // A run entirely outside the month is a neighbour's event: visible, quiet.
                    val outside = (chip.startColumn until chip.startColumn + chip.span)
                        .none { YearMonth.from(weekDraw.week[it]) == month }
                    val fill = (EventColorResolver.argbFor(chip.event, colorOverrides) ?: 0)
                        .asCalendarColor(colors.green)
                        .let { if (outside) it.copy(alpha = 0.35f).compositeOver(colors.card) else it }

                    val startInset = if (chip.continuesBefore) 0f else chipInsetPx
                    val endInset = if (chip.continuesAfter) 0f else chipInsetPx
                    val left = cellWidthPx * chip.startColumn + startInset
                    val top = rowTop + dayNumberRowPx + (chipHeightPx + chipGapPx) * chip.row
                    val width = cellWidthPx * chip.span - startInset - endInset
                    val rect = Rect(left, top, left + width, top + chipHeightPx)

                    // A band cut by the week boundary runs square to the edge; only a real end of the run
                    // gets the rounded corner, so Sunday-to-Monday reads as one event.
                    val realCorner = CornerRadius(chipCornerPx, chipCornerPx)
                    val squareCorner = CornerRadius.Zero
                    val roundRect = RoundRect(
                        rect = rect,
                        topLeft = if (chip.continuesBefore) squareCorner else realCorner,
                        bottomLeft = if (chip.continuesBefore) squareCorner else realCorner,
                        topRight = if (chip.continuesAfter) squareCorner else realCorner,
                        bottomRight = if (chip.continuesAfter) squareCorner else realCorner,
                    )
                    drawPath(Path().apply { addRoundRect(roundRect) }, color = fill)

                    val label = weekDraw.chipLabels.getValue(chip)
                    drawText(
                        textLayoutResult = label,
                        topLeft = Offset(
                            left + chipTextPaddingPx,
                            top + (chipHeightPx - label.size.height) / 2f,
                        ),
                        color = onChipColor(fill),
                    )
                }

                weekDraw.moreColumns.forEach { column ->
                    val top = rowTop + dayNumberRowPx + (chipHeightPx + chipGapPx) * (maxRows - 1)
                    drawText(
                        textLayoutResult = moreMarker,
                        topLeft = Offset(
                            cellWidthPx * column + (cellWidthPx - moreMarker.size.width) / 2f,
                            top + (chipHeightPx - moreMarker.size.height) / 2f,
                        ),
                    )
                }

                // Dots sit directly under whatever bands the day has, not at the foot of the cell, so a
                // day with no bands doesn't read as a gap with a stray dot at the bottom.
                weekDraw.dots.forEachIndexed { column, day ->
                    if (day.colors.isEmpty() && day.more == null) return@forEachIndexed
                    val cellLeft = cellWidthPx * column
                    val moreWidth = day.more?.let { it.size.width + dotGapPx } ?: 0f
                    val dotsWidth = if (day.colors.isEmpty()) 0f
                    else day.colors.size * dotSizePx + (day.colors.size - 1) * dotGapPx
                    var x = cellLeft + (cellWidthPx - dotsWidth - moreWidth) / 2f
                    val top = rowTop + dayNumberRowPx +
                        (chipHeightPx + chipGapPx) * weekDraw.bandRowsUsed[column] + dotTopPaddingPx
                    day.colors.forEach { color ->
                        drawCircle(
                            color = color,
                            radius = dotSizePx / 2f,
                            center = Offset(x + dotSizePx / 2f, top + dotSizePx / 2f),
                        )
                        x += dotSizePx + dotGapPx
                    }
                    day.more?.let { label ->
                        drawText(
                            textLayoutResult = label,
                            topLeft = Offset(x, top + (dotSizePx - label.size.height) / 2f),
                            alpha = if (YearMonth.from(weekDraw.week[column]) == month) 1f else 0.4f,
                        )
                    }
                }
            }
        }
        DaySemanticsLayer(
            weeks = weeks,
            cellWidth = cellWidth,
            cellHeight = cellHeight,
            today = today,
            eventsByDate = eventsByDate,
            onDayClick = onDayClick,
            onDayLongPress = onDayLongPress,
            describeCount = describeCount,
        )
    }
}

private val DAY_DESCRIPTION: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM")

@Composable
private fun DaySemanticsLayer(
    weeks: List<List<LocalDate>>,
    cellWidth: Dp,
    cellHeight: Dp,
    today: LocalDate,
    eventsByDate: Map<LocalDate, List<EventInstance>>,
    onDayClick: (LocalDate) -> Unit,
    onDayLongPress: (LocalDate) -> Unit,
    describeCount: (Int) -> String,
) {
    Column {
        weeks.forEach { week ->
            Row {
                week.forEach { date ->
                    val count = eventsByDate[date].orEmpty().distinctBy { it.instanceId }.size
                    val description = buildString {
                        append(date.format(DAY_DESCRIPTION))
                        if (date == today) append(", today")
                        append(", ")
                        append(if (count == 0) "nothing on" else describeCount(count))
                    }
                    Box(
                        Modifier
                            .size(cellWidth, cellHeight)
                            .semantics {
                                contentDescription = description
                                onClick(label = "Open day") { onDayClick(date); true }
                                onLongClick(label = "Show agenda") { onDayLongPress(date); true }
                            },
                    )
                }
            }
        }
    }
}
