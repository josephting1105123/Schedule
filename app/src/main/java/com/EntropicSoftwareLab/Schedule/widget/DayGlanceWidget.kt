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
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
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
private val CARD_PADDING = 14.dp
private val LABEL_HEIGHT = 24.dp
private val SECTION_GAP = 6.dp
private val CELL_GAP = 2.dp
private val HOUR_LABEL_CLEARANCE = 20.dp
private val BAR_EDGE_WIDTH = 3.dp
private val MIN_BAR_WIDTH = 2.dp
private val MIN_TITLED_BAR_WIDTH = 24.dp
private val MIN_TITLED_BAR_HEIGHT = 12.dp
private val TICK_WIDTH = 3.dp
private val TICK_HALF_WIDTH = 1.5.dp

/** Glance 1.1.1 silently truncates any Row/Column/Box beyond 10 children. */
private const val GLANCE_MAX_CHILDREN = 10

/** Outer Row (10 chunks) × inner Row (10 segments); runs/ticks beyond this cap are dropped. */
private const val MAX_OVERLAY_SEGMENTS = GLANCE_MAX_CHILDREN * GLANCE_MAX_CHILDREN

/** Fixed dark ink for bar titles: eventTint(..., 0.28f) is blended over white and is always pale. */
private val BAR_TITLE_INK = ColorProvider(day = Color(0xFF18202B), night = Color(0xFF18202B))

/** Same order the picker shows — see [EventColorPalette.assignmentOrder]. */
private val BRAND_PALETTE: List<Int> = EventColorPalette.assignmentOrder.map { it.argb }

/** Everything the widget draws for [date], read in one pass so the seed and every live reload agree. */
private data class DayGlanceData(
    val date: LocalDate,
    val lane0: List<List<HourGrid.HourSlice>>?,
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
    // Only lane 0 is drawn here. Clash lanes (index >= 1) are not drawn: a 4x2 widget has no
    // vertical room to stack extra bands, and the Agenda widget and the app show the full detail.
    val lane0 = HourGrid.lanes(todays, date, zone).getOrNull(0)

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
        lane0 = lane0,
        colors = colors,
        taskMarks = TaskGrid.byHour(dueOnDate),
    )
}

class DayGlanceWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Generation first: a refresh landing during the load below leaves this behind, so the
        // effect in provideContent reloads instead of trusting a seed that may predate it.
        val seedGeneration = MonthWidgetCache.generation.value
        // Read before provideContent so the first frame already has data.
        val seed = loadDayGlance(context, LocalDate.now())

        provideContent {
            val today = LocalDate.now()
            var offset by remember { mutableStateOf(0) }
            var offsetDay by remember { mutableStateOf(today.toEpochDay()) }
            val shown = DayWidgetPaging.dayFor(today, offset, offsetDay)
            val loaded = remember {
                mutableStateMapOf<LocalDate, DayGlanceData>().apply { put(seed.date, seed) }
            }
            var loadedGeneration by remember { mutableStateOf(seedGeneration) }
            // Glance 1.1.1's update/updateAll don't restart provideGlance while a session is alive,
            // so a refresh bumps this and the live session reloads itself (same as MonthWidget).
            val generation by MonthWidgetCache.generation.collectAsState()
            LaunchedEffect(shown, generation) {
                val stale = generation != loadedGeneration
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
    val innerW = size.width - CARD_PADDING * 2
    val (cellW, gap) = snappedCellGeometry(innerW, CELL_GAP, density)
    val avail = size.height - CARD_PADDING * 2 - LABEL_HEIGHT - SECTION_GAP - SECTION_GAP
    val halfH = (avail / 2).coerceIn(24.dp, 96.dp)
    // 20dp = 17dp top clearance (2dp top padding + "%02d" hour number) + 3dp bottom inset.
    val barH = (halfH - HOUR_LABEL_CLEARANCE).coerceAtLeast(6.dp)
    val currentHour = if (shown == today) LocalTime.now().hour else -1
    val lane0 = data?.lane0
    val colors = data?.colors.orEmpty()
    val taskMarks = data?.taskMarks.orEmpty()

    // 4 children (limit 10): label row, morning half wrapper, spacer, afternoon half.
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(CARD_RADIUS)
            .padding(CARD_PADDING)
            .clickable(actionStartActivity(WidgetLaunchIntents.openDay(context, shown))),
    ) {
        // 4 children (limit 10): widget title, date, previous day, next day.
        Row(
            modifier = GlanceModifier.fillMaxWidth().height(LABEL_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Day at a glance",
                modifier = GlanceModifier.defaultWeight(),
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.SansSerif,
                ),
            )
            Text(
                text = shown.format(DAY_LABEL_FORMAT),
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
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
            DayChevron(R.drawable.ic_widget_chevron_left, "Previous day") { onStep(-1) }
            DayChevron(R.drawable.ic_widget_chevron_right, "Next day") { onStep(+1) }
        }

        // Top padding sits on an outer wrapper so both halves keep the exact same halfH content box.
        Box(modifier = GlanceModifier.fillMaxWidth().padding(top = SECTION_GAP)) {
            Half(
                hours = 0 until 12,
                lane0 = lane0,
                colors = colors,
                taskMarks = taskMarks,
                currentHour = currentHour,
                cellW = cellW,
                gap = gap,
                density = density,
                halfH = halfH,
                barH = barH,
            )
        }

        Spacer(GlanceModifier.height(SECTION_GAP))

        Half(
            hours = 12 until 24,
            lane0 = lane0,
            colors = colors,
            taskMarks = taskMarks,
            currentHour = currentHour,
            cellW = cellW,
            gap = gap,
            density = density,
            halfH = halfH,
            barH = barH,
        )
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

private sealed interface BarSegment {
    data class Gap(val width: Dp) : BarSegment
    data class Bar(val width: Dp, val fill: Color, val title: String) : BarSegment
}

private sealed interface TickSegment {
    data class Gap(val width: Dp) : TickSegment
    data class Tick(val done: Boolean) : TickSegment
}

@Composable
private fun Half(
    hours: IntRange,
    lane0: List<List<HourGrid.HourSlice>>?,
    colors: Map<Long, Int>,
    taskMarks: List<List<TaskGrid.Mark>>,
    currentHour: Int,
    cellW: Dp,
    gap: Dp,
    density: Float,
    halfH: Dp,
    barH: Dp,
) {
    val runs = if (lane0 != null) HourGrid.runs(lane0, hours) else emptyList()
    val barSegments = buildBarSegments(runs, colors, cellW, gap, density)
    val tickSegments = buildTickSegments(hours, taskMarks, cellW, gap, density)
    val emittedCellW = emitSnapped(cellW, density)
    val emittedGap = emitSnapped(gap, density)
    val tickW = emitPx(Math.round(TICK_WIDTH.value * density), density)

    // Up to 3 stacked children (limit 10): cells (back), bars (middle), ticks (front).
    // Nothing inside is clickable, so taps pass through to the card's openDay action.
    Box(modifier = GlanceModifier.fillMaxWidth().height(halfH)) {
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
                        val cellColour = if (isCurrent) {
                            GlanceTheme.colors.primaryContainer
                        } else {
                            GlanceTheme.colors.surfaceVariant
                        }
                        val numberColour = if (isCurrent) {
                            GlanceTheme.colors.onPrimaryContainer
                        } else {
                            GlanceTheme.colors.onSurfaceVariant
                        }
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
                                    .background(cellColour)
                                    .cornerRadius(5.dp)
                                    .padding(start = 3.dp, top = 2.dp),
                            ) {
                                Text(
                                    text = "%02d".format(hour),
                                    maxLines = 1,
                                    style = TextStyle(
                                        color = numberColour,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.SansSerif,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

        // b. Bars (middle): at most 10 chunks of at most 10 segments each.
        if (barSegments.isNotEmpty()) {
            // At most 10 children (GLANCE_MAX_CHILDREN).
            Row(
                modifier = GlanceModifier.fillMaxSize().padding(bottom = 3.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                barSegments.chunked(GLANCE_MAX_CHILDREN).take(GLANCE_MAX_CHILDREN).forEach { chunk ->
                    // At most 10 children (GLANCE_MAX_CHILDREN).
                    Row(
                        modifier = GlanceModifier.fillMaxHeight(),
                        verticalAlignment = Alignment.Bottom,
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

        // c. Ticks (front), only when this half has deadline marks.
        if (tickSegments.isNotEmpty()) {
            // At most 10 children (GLANCE_MAX_CHILDREN).
            Row(modifier = GlanceModifier.fillMaxSize()) {
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
                                        .background(
                                            if (segment.done) {
                                                GlanceTheme.colors.onSurfaceVariant
                                            } else {
                                                GlanceTheme.colors.onSurface
                                            },
                                        )
                                        .cornerRadius(TICK_HALF_WIDTH),
                                ) {}
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventBar(width: Dp, barH: Dp, fill: Color, title: String, density: Float) {
    val widthPx = Math.round(width.value * density)
    val edgePx = Math.round(BAR_EDGE_WIDTH.value * density)
    val bodyPx = widthPx - edgePx
    // 1 or 2 children (limit 10): 3dp leading edge and optional tinted body.
    Row(modifier = GlanceModifier.width(emitPx(widthPx, density)).height(barH)) {
        Box(
            modifier = GlanceModifier
                .width(emitPx(minOf(widthPx, edgePx), density))
                .fillMaxHeight()
                .background(fill),
        ) {}
        if (bodyPx > 0) {
            Box(
                modifier = GlanceModifier
                    .width(emitPx(bodyPx, density))
                    .fillMaxHeight()
                    .background(eventTint(fill, 0.28f))
                    .padding(start = 3.dp, top = 2.dp, end = 2.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                if (width >= MIN_TITLED_BAR_WIDTH && barH >= MIN_TITLED_BAR_HEIGHT) {
                    Text(
                        text = title,
                        maxLines = if (barH >= 28.dp) 2 else 1,
                        style = TextStyle(
                            color = BAR_TITLE_INK,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.SansSerif,
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
