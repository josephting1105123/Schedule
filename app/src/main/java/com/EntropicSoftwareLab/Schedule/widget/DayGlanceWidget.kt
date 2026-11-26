package com.EntropicSoftwareLab.Schedule.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.EntropicSoftwareLab.Schedule.R
import com.EntropicSoftwareLab.Schedule.data.appearance.BrandColorAssignment
import com.EntropicSoftwareLab.Schedule.data.appearance.EventColorRepository
import com.EntropicSoftwareLab.Schedule.data.appearance.EventColorResolver
import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance
import com.EntropicSoftwareLab.Schedule.data.calendar.HourGrid
import com.EntropicSoftwareLab.Schedule.data.calendar.WeekPaging
import com.EntropicSoftwareLab.Schedule.data.task.TaskGrid
import com.EntropicSoftwareLab.Schedule.data.task.TaskRepository
import com.EntropicSoftwareLab.Schedule.ui.components.eventTint
import com.EntropicSoftwareLab.Schedule.ui.theme.EventColorPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

private val DAY_LABEL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

private val CARD_RADIUS = 22.dp
private val CARD_PADDING_H = 14.dp
private val CARD_PADDING_V = 10.dp
private val LABEL_HEIGHT = 32.dp
private val SECTION_GAP = 6.dp
private val LANE0_HEIGHT = 40.dp
private val EXTRA_LANE_HEIGHT = 22.dp
private val HALF_GAP = 12.dp
private val CELL_GAP = 2.dp
private val CELL_RADIUS = 6.dp
private val BAR_HEIGHT = 16.dp
private val BAR_RADIUS = 4.dp
private val BAR_EDGE_WIDTH = 3.dp
private val MIN_BAR_WIDTH = 2.dp
private val MIN_TITLED_BAR_WIDTH = 14.dp
private val TICK_WIDTH = 3.dp
private val TICK_HALF_WIDTH = 1.5.dp

/** Glance 1.1.1 silently truncates any Row/Column/Box beyond 10 children. */
private const val GLANCE_MAX_CHILDREN = 10

/** Outer Row (10 chunks) × inner Row (10 segments); runs/ticks beyond this cap are dropped. */
private const val MAX_OVERLAY_SEGMENTS = GLANCE_MAX_CHILDREN * GLANCE_MAX_CHILDREN

private val CARD_BG = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFFFFFFFF))
private val CELL_BG = ColorProvider(day = Color(0xFFF7F8FC), night = Color(0xFFF7F8FC))
private val NOW_CELL_BG = ColorProvider(day = Color(0xFFE6EDFF), night = Color(0xFFE6EDFF))
private val INK_SECONDARY = ColorProvider(day = Color(0xFF4F5A6B), night = Color(0xFF4F5A6B))
private val NOW_NUMBER = ColorProvider(day = Color(0xFF1F5AE6), night = Color(0xFF1F5AE6))

/** Fixed dark ink for bar titles and unfinished ticks, matching the light-only Day page. */
private val BAR_TITLE_INK = ColorProvider(day = Color(0xFF18202B), night = Color(0xFF18202B))

/** Same order the picker shows — see [EventColorPalette.assignmentOrder]. */
private val BRAND_PALETTE: List<Int> = EventColorPalette.assignmentOrder.map { it.argb }

private val EMPTY_LANE: List<List<HourGrid.HourSlice>> = List(HourGrid.HOURS_PER_DAY) { emptyList() }

/** Everything the widget draws for [date], read in one pass so every load agrees. */
private data class DayGlanceData(
    val date: LocalDate,
    val lanes: List<List<List<HourGrid.HourSlice>>>,
    val colors: Map<Long, Int>,
    val taskMarks: List<List<TaskGrid.Mark>>,
)

/**
 * Reads events, colours and tasks for [date]. An unreadable calendar, colour store or task list
 * (permission missing, provider down) falls back to empty rather than crashing the host launcher;
 * cancellation still propagates.
 */
private suspend fun loadDayGlance(context: Context, date: LocalDate): DayGlanceData {
    val zone = ZoneId.systemDefault()

    val byDate: Map<LocalDate, List<EventInstance>> = try {
        MonthWidgetCache.load(context, YearMonth.from(date), zone, WeekPaging.FIRST_DAY)
    } catch (e: SecurityException) {
        emptyMap()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyMap()
    }

    val colors: Map<Long, Int> = try {
        val picks = EventColorRepository(context).overrides.first()
        BrandColorAssignment.forDates(byDate, picks, BRAND_PALETTE)
    } catch (e: SecurityException) {
        emptyMap()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyMap()
    }

    val todays = byDate[date].orEmpty()
    val lanes = HourGrid.lanes(todays, date, zone)

    val dueOnDate = try {
        TaskRepository(context).tasks.first().filter { it.due == date }
    } catch (e: SecurityException) {
        emptyList()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    return DayGlanceData(
        date = date,
        lanes = lanes,
        colors = colors,
        taskMarks = TaskGrid.byHour(dueOnDate),
    )
}

class DayGlanceWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // No load before provideContent: the label row and the bare hour grid draw at once, and
        // each day's data arrives afterwards in one assignment (the LaunchedEffect below), like
        // MonthWidget.
        val startGeneration = MonthWidgetCache.generation.value

        provideContent {
            val today = LocalDate.now()
            var offset by remember { mutableStateOf(0) }
            var offsetDay by remember { mutableStateOf(today.toEpochDay()) }
            val shown = DayWidgetPaging.dayFor(today, offset, offsetDay)
            val loaded = remember { mutableStateMapOf<LocalDate, DayGlanceData>() }
            var loadedGeneration by remember { mutableStateOf(startGeneration) }
            // Glance 1.1.1's update/updateAll don't restart provideGlance while a session is alive,
            // so a refresh bumps this and the live session reloads itself (same as MonthWidget).
            val generation by MonthWidgetCache.generation.collectAsState()
            LaunchedEffect(shown, generation) {
                val stale = generation != loadedGeneration
                // `shown` is loaded first so the day on screen fills before its neighbours are warmed.
                for (day in listOf(shown, shown.plusDays(1), shown.minusDays(1))) {
                    if (stale || day !in loaded) loaded[day] = loadDayGlance(context, day)
                }
                loadedGeneration = generation
                val keep = if (stale) 1L else 2L
                loaded.keys
                    .filter { abs(ChronoUnit.DAYS.between(shown, it)) > keep }
                    .forEach { loaded.remove(it) }
            }
            val step: (Int) -> Unit = { delta ->
                offset = DayWidgetPaging.step(today, offset, offsetDay, delta)
                offsetDay = today.toEpochDay()
            }
            val backToToday: () -> Unit = {
                offset = 0
                offsetDay = today.toEpochDay()
            }
            val data = loaded[shown]

            GlanceTheme {
                DayGlanceBody(
                    context = context,
                    shown = shown,
                    today = today,
                    data = data,
                    onStep = step,
                    onBackToToday = backToToday,
                )
            }
        }
    }
}

@Composable
private fun DayGlanceBody(
    context: Context,
    shown: LocalDate,
    today: LocalDate,
    data: DayGlanceData?,
    onStep: (Int) -> Unit,
    onBackToToday: () -> Unit,
) {
    val density = context.resources.displayMetrics.density
    val size = LocalSize.current
    val innerW = size.width - CARD_PADDING_H * 2
    val (cellW, gap) = snappedCellGeometry(innerW, CELL_GAP, density)
    val barH = BAR_HEIGHT
    val currentHour = if (shown == today) LocalTime.now().hour else -1
    val lanes = data?.lanes.orEmpty()
    val colors = data?.colors.orEmpty()
    val taskMarks = data?.taskMarks.orEmpty()
    val openDay = actionStartActivity(WidgetLaunchIntents.openDay(context, shown))

    // 3 children (limit 10): label row, section gap, scrollable halves list.
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(CARD_BG)
            .cornerRadius(CARD_RADIUS)
            .padding(horizontal = CARD_PADDING_H, vertical = CARD_PADDING_V)
            .clickable(openDay),
    ) {
        // 5 children (limit 10): widget title, date, spacer, previous day, next day.
        Row(
            modifier = GlanceModifier.fillMaxWidth().height(LABEL_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Day at a glance",
                modifier = GlanceModifier.defaultWeight(),
                maxLines = 1,
                style = TextStyle(
                    color = INK_SECONDARY,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.SansSerif,
                ),
            )
            Text(
                text = shown.format(DAY_LABEL_FORMAT),
                maxLines = 1,
                style = TextStyle(
                    color = INK_SECONDARY,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.SansSerif,
                ),
                modifier = if (shown != today) {
                    GlanceModifier
                        .semantics { contentDescription = "Back to today" }
                        .clickable(onBackToToday)
                } else {
                    GlanceModifier
                },
            )
            Spacer(GlanceModifier.width(8.dp))
            DayChevron(
                icon = R.drawable.ic_widget_chevron_left,
                description = "Previous day",
                tint = INK_SECONDARY,
                boxWidth = 44.dp,
                boxHeight = 32.dp,
                iconSize = 20.dp,
            ) { onStep(-1) }
            DayChevron(
                icon = R.drawable.ic_widget_chevron_right,
                description = "Next day",
                tint = INK_SECONDARY,
                boxWidth = 44.dp,
                boxHeight = 32.dp,
                iconSize = 20.dp,
            ) { onStep(+1) }
        }

        Spacer(GlanceModifier.height(SECTION_GAP))

        LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            item(itemId = 0L) {
                // Bottom padding sits on an outer wrapper so both halves keep their exact halfH content box.
                Box(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .padding(bottom = HALF_GAP)
                        .clickable(openDay),
                ) {
                    Half(
                        hours = 0 until 12,
                        lanes = lanes,
                        colors = colors,
                        taskMarks = taskMarks,
                        currentHour = currentHour,
                        cellW = cellW,
                        gap = gap,
                        density = density,
                        barH = barH,
                    )
                }
            }
            item(itemId = 1L) {
                Half(
                    hours = 12 until 24,
                    lanes = lanes,
                    colors = colors,
                    taskMarks = taskMarks,
                    currentHour = currentHour,
                    cellW = cellW,
                    gap = gap,
                    density = density,
                    barH = barH,
                    modifier = GlanceModifier.clickable(openDay),
                )
            }
        }
    }
}

/**
 * Horizontal offset in dp of a time [t] hours (0f..12f) from the half-day row's first hour.
 *
 * An end on a whole hour ([isEnd]) is the right edge of the cell before it, not the left edge of
 * the next, so a bar ending at 10:00 doesn't reach across the gap and touch the 10:00 cell. Same
 * rule as `DayHourGrid`'s `xAt`.
 */
internal fun hourOffsetX(t: Float, cellW: Dp, gap: Dp, isEnd: Boolean = false): Dp {
    val whole = floor(t).toInt()
    val cell = if (isEnd && whole > 0 && t == whole.toFloat()) whole - 1 else whole
    val i = min(cell, 11).coerceAtLeast(0)
    val f = t - i
    return (cellW + gap) * i + cellW * f
}

/** [x] rounded to the nearest whole device pixel, back in dp. */
internal fun snapToPx(x: Dp, density: Float): Dp = (Math.round(x.value * density) / density).dp

/**
 * [px] converted to dp with a +0.25px bias so Glance produces [px] whether it truncates (padding,
 * and API 29/30 fixed widths) or rounds (API 31+ fixed widths). Use only when emitting a final size
 * to a Glance modifier — never in intermediate position maths.
 */
internal fun emitPx(px: Int, density: Float): Dp = ((px + 0.25f) / density).dp

private fun emitSnapped(x: Dp, density: Float): Dp = emitPx(Math.round(x.value * density), density)

/**
 * Cell width and gap in whole device pixels (returned as dp), so 12 cells, 11 gaps and every bar
 * edge land on the same pixel grid. Cell width is floored so the row never exceeds [innerW].
 */
internal fun snappedCellGeometry(innerW: Dp, gap: Dp, density: Float): Pair<Dp, Dp> {
    val gapPx = Math.round(gap.value * density).coerceAtLeast(1)
    val cellPx = floor(((innerW.value * density) - gapPx * 11) / 12f).toInt()
        .coerceAtLeast(Math.round(10f * density))
    return (cellPx / density).dp to (gapPx / density).dp
}

/** Height of one 12-hour half with [visibleLanes] stacked lanes (lane 0 plus any clash bands). */
internal fun halfHeight(visibleLanes: Int): Dp =
    LANE0_HEIGHT + EXTRA_LANE_HEIGHT * (visibleLanes - 1).coerceAtLeast(0)

/**
 * Replaces spaces and control whitespace with U+00A0 so RemoteViews' `maxLines = 1` TextView clips
 * mid-word at a character boundary (matching the Today page) instead of dropping the trailing word.
 */
internal fun noBreakTitle(title: String): String = buildString(title.length) {
    for (ch in title) {
        append(
            when (ch) {
                ' ', '\n', '\r', '\t' -> '\u00A0'
                else -> ch
            },
        )
    }
}

private sealed interface BarSegment {
    data class Gap(val width: Dp) : BarSegment
    data class Bar(val width: Dp, val fill: Color, val title: String) : BarSegment
}

private sealed interface TickSegment {
    data class Gap(val width: Dp) : TickSegment
    data class Tick(val done: Boolean) : TickSegment
}

/**
 * One 12-hour half of the day. Lane 0 is [LANE0_HEIGHT] tall with the hour number at top-left and
 * its bar near the bottom; each clash lane active in [hours] adds an [EXTRA_LANE_HEIGHT] band to
 * the cells' height with a vertically centred bar, matching `DayHourGrid`.
 */
@Composable
private fun Half(
    hours: IntRange,
    lanes: List<List<List<HourGrid.HourSlice>>>,
    colors: Map<Long, Int>,
    taskMarks: List<List<TaskGrid.Mark>>,
    currentHour: Int,
    cellW: Dp,
    gap: Dp,
    density: Float,
    barH: Dp,
    modifier: GlanceModifier = GlanceModifier,
) {
    // Capped at GLANCE_MAX_CHILDREN (10): Glance 1.1.1 silently drops Column children past 10,
    // so lanes beyond the 10th are not drawn.
    val visible = lanes
        .filterIndexed { i, lane ->
            i == 0 || hours.any { lane.getOrElse(it) { emptyList() }.isNotEmpty() }
        }
        .ifEmpty { listOf(EMPTY_LANE) }
        .take(GLANCE_MAX_CHILDREN)
    val halfH = halfHeight(visible.size)
    val laneBarSegments = visible.map { lane ->
        buildBarSegments(HourGrid.runs(lane, hours), colors, cellW, gap, density)
    }
    val tickSegments = buildTickSegments(hours, taskMarks, cellW, gap, density)
    val emittedCellW = emitSnapped(cellW, density)
    val emittedGap = emitSnapped(gap, density)
    val tickW = emitPx(Math.round(TICK_WIDTH.value * density), density)

    // Up to 4 stacked children (limit 10): cells (back), bars, ticks, now outline (front).
    // Nothing inside is clickable, so taps pass through to the list item's openDay action.
    Box(modifier = modifier.fillMaxWidth().height(halfH)) {
        // a. Cells (back): 2 inner Rows of 6 because Glance 1.1.1 silently drops children past 10.
        val hourList = hours.toList()
        // 2 children (limit 10): first 6 hours, second 6 hours.
        Row(modifier = GlanceModifier.fillMaxSize()) {
            hourList.chunked(6).forEach { sixHours ->
                // 6 children (limit 10).
                Row(modifier = GlanceModifier.fillMaxHeight()) {
                    sixHours.forEach { hour ->
                        val isLast = hour == hours.last
                        val isCurrent = hour == currentHour
                        // Gap is padding on the outer Box: Glance paints background across own padding.
                        Box(
                            modifier = GlanceModifier
                                .fillMaxHeight()
                                .let { if (isLast) it else it.padding(end = emittedGap) },
                        ) {
                            Box(
                                modifier = GlanceModifier
                                    .width(emittedCellW)
                                    .fillMaxHeight()
                                    .background(if (isCurrent) NOW_CELL_BG else CELL_BG)
                                    .cornerRadius(CELL_RADIUS)
                                    .padding(start = 3.dp, top = 3.dp),
                            ) {
                                Text(
                                    text = "%02d".format(hour),
                                    maxLines = 1,
                                    style = TextStyle(
                                        color = if (isCurrent) NOW_NUMBER else INK_SECONDARY,
                                        fontSize = 9.5.sp,
                                        fontWeight = if (isCurrent) FontWeight.Bold else null,
                                        fontFamily = FontFamily.SansSerif,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

        // b. Bars (middle): 1 Column of up to 10 visible lanes (limit 10); each lane has at most
        // 10 chunks of at most 10 segments.
        if (laneBarSegments.any { it.isNotEmpty() }) {
            // At most 10 children (GLANCE_MAX_CHILDREN).
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                laneBarSegments.forEachIndexed { laneIndex, segments ->
                    val isTopLane = laneIndex == 0
                    val laneHeight = if (isTopLane) LANE0_HEIGHT else EXTRA_LANE_HEIGHT
                    if (segments.isEmpty()) {
                        Spacer(GlanceModifier.height(laneHeight))
                    } else {
                        val vAlign = if (isTopLane) Alignment.Bottom else Alignment.CenterVertically
                        // At most 10 children (GLANCE_MAX_CHILDREN).
                        Row(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .height(laneHeight)
                                .let { if (isTopLane) it.padding(bottom = 3.dp) else it },
                            verticalAlignment = vAlign,
                        ) {
                            segments.chunked(GLANCE_MAX_CHILDREN).take(GLANCE_MAX_CHILDREN).forEach { chunk ->
                                // At most 10 children (GLANCE_MAX_CHILDREN).
                                Row(
                                    modifier = GlanceModifier.fillMaxHeight(),
                                    verticalAlignment = vAlign,
                                ) {
                                    chunk.forEach { segment ->
                                        when (segment) {
                                            is BarSegment.Gap -> Spacer(GlanceModifier.width(emitSnapped(segment.width, density)))
                                            is BarSegment.Bar -> EventBar(
                                                width = segment.width,
                                                barH = barH,
                                                fill = segment.fill,
                                                title = segment.title,
                                                density = density,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // c. Ticks on lane 0 only, when this half has deadline marks.
        if (tickSegments.isNotEmpty()) {
            // At most 10 children (GLANCE_MAX_CHILDREN).
            Row(modifier = GlanceModifier.fillMaxWidth().height(LANE0_HEIGHT)) {
                tickSegments.chunked(GLANCE_MAX_CHILDREN).take(GLANCE_MAX_CHILDREN).forEach { chunk ->
                    // At most 10 children (GLANCE_MAX_CHILDREN).
                    Row(modifier = GlanceModifier.fillMaxHeight()) {
                        chunk.forEach { segment ->
                            when (segment) {
                                is TickSegment.Gap -> Spacer(GlanceModifier.width(emitSnapped(segment.width, density)))
                                is TickSegment.Tick -> Box(
                                    modifier = GlanceModifier
                                        .width(tickW)
                                        .fillMaxHeight()
                                        .background(if (segment.done) INK_SECONDARY else BAR_TITLE_INK)
                                        .cornerRadius(TICK_HALF_WIDTH),
                                ) {}
                            }
                        }
                    }
                }
            }
        }

        // d. Current-hour outline (front), over bars and ticks so bars don't hide its sides.
        if (currentHour in hours) {
            // 1 or 2 children (limit 10): optional offset spacer, outline box.
            Row(modifier = GlanceModifier.fillMaxSize()) {
                val index = currentHour - hours.first
                if (index > 0) Spacer(GlanceModifier.width(emitSnapped(hourOffsetX(index.toFloat(), cellW, gap), density)))
                Box(
                    modifier = GlanceModifier
                        .width(emittedCellW)
                        .fillMaxHeight()
                        .background(ImageProvider(R.drawable.widget_now_outline)),
                ) {}
            }
        }
    }
}

@Composable
private fun EventBar(width: Dp, barH: Dp, fill: Color, title: String, density: Float) {
    val widthPx = Math.round(width.value * density)
    val edgePx = Math.round(BAR_EDGE_WIDTH.value * density)
    val bodyPx = widthPx - edgePx
    Box(
        modifier = GlanceModifier
            .width(emitPx(widthPx, density))
            .height(barH)
            .background(eventTint(fill, 0.28f))
            .cornerRadius(BAR_RADIUS),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                GlanceModifier
                    .width(emitPx(minOf(widthPx, edgePx), density))
                    .fillMaxHeight()
                    .background(fill),
            ) {}
            if (bodyPx > 0 && width >= MIN_TITLED_BAR_WIDTH) {
                Box(
                    GlanceModifier
                        .width(emitPx(bodyPx, density))
                        .fillMaxHeight()
                        .padding(start = 2.dp, end = 1.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = noBreakTitle(title),
                        maxLines = 1,
                        style = TextStyle(
                            color = BAR_TITLE_INK,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }
            }
        }
    }
}

private fun buildBarSegments(
    runs: List<HourGrid.Run>,
    colors: Map<Long, Int>,
    cellW: Dp,
    gap: Dp,
    density: Float,
): List<BarSegment> {
    if (runs.isEmpty()) return emptyList()
    val minBarW = snapToPx(MIN_BAR_WIDTH, density)
    val out = mutableListOf<BarSegment>()
    var cursor = 0.dp
    for (run in runs) {
        val startX = snapToPx(hourOffsetX(run.start, cellW, gap), density)
        val endX = snapToPx(hourOffsetX(run.end, cellW, gap, isEnd = true), density)
        val gapW = snapToPx(startX - cursor, density)
        val needed = if (gapW > 0.dp) 2 else 1
        if (out.size + needed > MAX_OVERLAY_SEGMENTS) break
        if (gapW > 0.dp) {
            out += BarSegment.Gap(gapW)
            cursor = snapToPx(cursor + gapW, density)
        }
        val barW = maxOf(snapToPx(endX - startX, density), minBarW)
        val fill = EventColorResolver.argbFor(run.event, colors).orBrand()
        out += BarSegment.Bar(width = barW, fill = fill, title = run.event.title)
        cursor = snapToPx(cursor + barW, density)
    }
    return out
}

private fun buildTickSegments(
    hours: IntRange,
    taskMarks: List<List<TaskGrid.Mark>>,
    cellW: Dp,
    gap: Dp,
    density: Float,
): List<TickSegment> {
    val sorted = hours
        .flatMap { h -> taskMarks.getOrElse(h) { emptyList() } }
        .sortedWith(compareBy({ (it.hour - hours.first) + it.fraction }, { it.done }))
    if (sorted.isEmpty()) return emptyList()

    val tickW = snapToPx(TICK_WIDTH, density)
    val out = mutableListOf<TickSegment>()
    var cursor = 0.dp
    var lastTickX: Dp? = null
    for (mark in sorted) {
        val t = (mark.hour - hours.first) + mark.fraction
        val xt = snapToPx(hourOffsetX(t, cellW, gap), density)
        val prev = lastTickX
        if (prev != null && snapToPx(xt - prev, density) < tickW) continue

        val left = snapToPx(xt - TICK_HALF_WIDTH, density).coerceAtLeast(cursor)
        val gapW = snapToPx(left - cursor, density)
        val needed = if (gapW > 0.dp) 2 else 1
        if (out.size + needed > MAX_OVERLAY_SEGMENTS) break
        if (gapW > 0.dp) {
            out += TickSegment.Gap(gapW)
            cursor = snapToPx(cursor + gapW, density)
        }
        out += TickSegment.Tick(done = mark.done)
        cursor = snapToPx(cursor + tickW, density)
        lastTickX = xt
    }
    return out
}

class DayGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DayGlanceWidget()
}
