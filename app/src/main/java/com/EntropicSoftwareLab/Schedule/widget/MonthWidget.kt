package com.EntropicSoftwareLab.Schedule.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.EntropicSoftwareLab.Schedule.R
import com.EntropicSoftwareLab.Schedule.data.appearance.EventColorRepository
import com.EntropicSoftwareLab.Schedule.data.appearance.EventColorResolver
import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance
import com.EntropicSoftwareLab.Schedule.data.calendar.MonthChip
import com.EntropicSoftwareLab.Schedule.data.calendar.DayDots
import com.EntropicSoftwareLab.Schedule.data.calendar.MonthChips
import com.EntropicSoftwareLab.Schedule.data.calendar.MonthGrid
import com.EntropicSoftwareLab.Schedule.data.calendar.MonthWidgetState
import com.EntropicSoftwareLab.Schedule.data.calendar.WeekPaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import kotlin.math.abs

private val MONTH_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM")
private val MONTH_AND_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")

/**
 * A month on the home screen: `‹` `›` to page, `+` to add, a day to open that day.
 *
 * ## It follows the reference design, not the Month page
 *
 * It was the Month page's calendar at widget scale; it is now drawn to match a reference the user
 * supplied: a dark plum card ([MonthWidgetColors]), the month's name alone as the title, chevrons,
 * a lavender pill for `+`, sans-serif type, a grid ruled both ways, and `•••` in a day's last row
 * when that day has more than fits ([MonthChips.layoutWithMoreRow]). Neighbouring months' days
 * recede by their numbers only; their bands keep full colour, as the reference's do.
 *
 * ## The constraint that shapes all of this
 *
 * **A Glance `Row`/`Column` silently truncates to 10 children** — `LayoutSelection.kt` does
 * `numChildren.coerceAtMost(10)` and only logs a warning, so an eleventh child vanishes with no
 * crash and nothing on screen to say why. The outer column here is header + weekday row + six weeks
 * = **8**. That is the budget. A week is a two-layer `Box`: behind, a row of seven day columns
 * carrying the vertical rules and the tap target; in front, a column of rule + day numbers + up to
 * two chip rows = **4**. The vertical rules are column *backgrounds*
 * (`widget_month_column_rule.xml`) for the same reason — seven days plus six separator views would
 * be thirteen children.
 *
 * ## Why the grid is laid out in measured dp
 *
 * `defaultWeight()` in Glance is `Dimension.Expand` — equal division, no fractional weights — and
 * there is no absolute positioning. Laid out with it, an event spanning Monday to Friday could only
 * be five adjacent equal boxes sharing a colour, with its title clipped to the first cell ("Orient…"
 * where the reference prints "Orientation (Week of Welcome) - WOW"). So the grid takes the widget's
 * real width under `SizeMode.Exact`, splits it with [MonthGrid.columnEdges], and draws each run as
 * **one** box as wide as the columns it covers — wide enough for its own title. Every row (weekday
 * initials, the ruled day columns, day numbers, bands) takes its widths from the same edges, so they
 * line up however dp-to-px rounding falls, and the last column of every row keeps `defaultWeight()`
 * as the safety net that absorbs whatever `LocalSize` under-reports: a bad size costs a slightly wide
 * last day rather than a blank strip down the card. Ported from the unmerged
 * `claude/calendar-ui-redesign-5faa39` branch (2026-09-27), which is where `columnEdges` came from.
 *
 * A run is rounded whenever both its ends are real, whatever its span — it is one view, so there is
 * no internal seam to notch. A run cut by the week boundary stays square so Sunday-to-Monday reads as
 * one event carrying on. (`cornerRadius` is API 31+ only and `minSdk` here is 29, which is why the
 * card, the pill and the disc behind today are shape drawables.)
 *
 * The inset that separates two adjacent single-day chips is padding on an **outer** box holding an
 * inner one that carries the fill. Glance paints a view's background across its own padding, so
 * insetting the filled box would move its text and leave the band exactly as wide as it was.
 *
 * ## Paging is instant because the month lives in the session and its neighbours are preloaded
 *
 * `‹`/`›` are lambda actions that change the shown month inside the running session — no broadcast,
 * no restart of [provideGlance] — and the session keeps the months either side loaded, re-warming
 * them in the background after every change, so a tap in either direction redraws from memory.
 * [MonthWidgetCache] sits underneath, so a session that has to start over still finds them.
 */
class MonthWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    /**
     * `Exact`, not `Responsive`: under `Responsive`, `LocalSize` reports whichever breakpoint was
     * matched rather than the space the widget got, and the column widths are computed from it — see
     * the class doc. [Geometry.forHeight] still picks one of its four designs from the real height.
     */
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        // Sunday first, matching the app's Week and Month pages — see WeekPaging.FIRST_DAY.
        val firstDayOfWeek = WeekPaging.FIRST_DAY
        val thisMonth = YearMonth.from(today)

        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val offset = prefs[MonthWidgetKeys.OFFSET] ?: 0
        val offsetDay = prefs[MonthWidgetKeys.OFFSET_DAY] ?: 0L
        val initialMonth = MonthWidgetState.monthFor(today, offset, offsetDay)

        // The first frame already has its month's data, the same discipline TodayWidget follows, so
        // there is no loading state to design at this size.
        val initialEvents = loadMonth(context, initialMonth, zone, firstDayOfWeek) ?: emptyMap()

        val overrides = try {
            EventColorRepository(context).overrides.first()
        } catch (e: Exception) {
            emptyMap()
        }

        provideContent {
            // The month on show lives *here*, in the running session, not only in the widget's
            // stored state. `‹`/`›` used to be broadcast actions that wrote the offset and asked
            // Glance to update — a round trip through a receiver and a restart of this whole
            // function before anything moved. Now they are lambda actions that change this state
            // directly: the session recomposes with a month whose events are already in [loaded],
            // and the offset is persisted in the background for the next time the session starts.
            var shown by remember { mutableStateOf(initialMonth) }
            val loaded = remember {
                mutableStateMapOf<YearMonth, Map<LocalDate, List<EventInstance>>>().apply {
                    put(initialMonth, initialEvents)
                }
            }
            val scope = rememberCoroutineScope()
            // A calendar write or periodic refresh clears the cache and bumps this; a live session
            // would otherwise keep drawing the months it loaded before the change.
            val generation by MonthWidgetCache.generation.collectAsState()

            // Whenever the month changes: make sure it is loaded, then warm the month either side
            // in the background, so the *next* tap in either direction also lands on data that is
            // already here. Months more than two away are dropped to keep the session small.
            LaunchedEffect(shown, generation) {
                for (month in listOf(shown, shown.plusMonths(1), shown.minusMonths(1))) {
                    loadMonth(context, month, zone, firstDayOfWeek)?.let { loaded[month] = it }
                }
                loaded.keys
                    .filter { abs(ChronoUnit.MONTHS.between(shown, it)) > 2 }
                    .forEach { loaded.remove(it) }
            }

            val step: (Int) -> Unit = { delta ->
                val current = ChronoUnit.MONTHS.between(thisMonth, shown).toInt()
                val next = MonthWidgetState.step(today, current, today.toEpochDay(), delta)
                shown = thisMonth.plusMonths(next.toLong())
                scope.launch {
                    runCatching {
                        updateAppWidgetState(context, id) { stored ->
                            stored[MonthWidgetKeys.OFFSET] = next
                            stored[MonthWidgetKeys.OFFSET_DAY] = today.toEpochDay()
                        }
                    }
                }
            }

            // The title takes the widget back to this month. Same persistence as a step, so a session
            // that restarts doesn't put the user back where they had browsed to.
            val backToThisMonth: () -> Unit = {
                shown = thisMonth
                scope.launch {
                    runCatching {
                        updateAppWidgetState(context, id) { stored ->
                            stored[MonthWidgetKeys.OFFSET] = 0
                            stored[MonthWidgetKeys.OFFSET_DAY] = today.toEpochDay()
                        }
                    }
                }
            }

            GlanceTheme {
                MonthBody(
                    month = shown,
                    today = today,
                    days = MonthGrid.days(shown, firstDayOfWeek),
                    firstDayOfWeek = firstDayOfWeek,
                    eventsByDate = loaded[shown].orEmpty(),
                    overrides = overrides,
                    zone = zone,
                    onStep = step,
                    onBackToThisMonth = backToThisMonth,
                    context = context,
                )
            }
        }
    }
}

/**
 * [month]'s events from [MonthWidgetCache], or null when the read failed for a reason worth
 * retrying — the caller then keeps whatever it already showed. A revoked permission is an empty
 * month rather than a failure: an empty grid beats crashing the launcher.
 */
private suspend fun loadMonth(
    context: Context,
    month: YearMonth,
    zone: ZoneId,
    firstDayOfWeek: DayOfWeek,
): Map<LocalDate, List<EventInstance>>? = try {
    MonthWidgetCache.load(context, month, zone, firstDayOfWeek)
} catch (e: SecurityException) {
    emptyMap()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/**
 * The sizes the grid is drawn at, chosen from the height the widget was given.
 *
 * The arithmetic that has to keep working, against the breakpoint height less 16dp of vertical card
 * padding — text rows counted at their fixed heights, since nothing here can measure one:
 *
 * | | 180 | 280 | 380 | 440 |
 * |---|---|---|---|---|
 * | header + weekday row | 36+20 | 40+20 | 44+20 | 48+20 |
 * | per week (rule + day row + chip rows) | 1+16 | 1+17+15 | 1+18+2×15 | 1+21+2×18 |
 * | six weeks | 102 | 198 | 294 | 348 |
 * | **total / available** | 158 / 164 | 258 / 264 | 358 / 364 | 416 / 424 |
 *
 * The column headings are the *lowest* height each design is chosen at, and [forHeight]'s thresholds
 * are those numbers. Under `SizeMode.Exact` the widget can be any height, not one of four
 * breakpoints, so a threshold below a design's total would hand that design a widget too short for
 * it — the thresholds used to be 260/360/440, which under `Exact` put 358dp of design into a 360dp
 * widget less padding.
 * The slack in the last row is the point. A RemoteViews `LinearLayout` does not scroll and does not
 * shrink its children: content past the bottom is simply not drawn, so overrunning this table costs
 * a whole week row with nothing on screen to say one is missing. The weeks are laid out with
 * `defaultWeight()` rather than fixed heights for the same reason — at a large system font scale
 * every text row grows, and dividing the leftover space six ways clips the bottom of one chip
 * instead of dropping the last week entirely.
 */
private data class Geometry(
    val headerHeight: Dp,
    val titleSize: TextUnit,
    val pillHeight: Dp,
    val daySize: TextUnit,
    /** Holds the day number and, when today, the disc behind it — so it is also the disc's diameter. */
    val dayRow: Dp,
    val chipRows: Int,
    val chipHeight: Dp,
    val chipGap: Dp,
    val chipTextSize: TextUnit,
) {
    companion object {
        fun forHeight(height: Dp): Geometry = when {
            // Numbers only: 16dp of day number is the whole week, so a chip has nowhere to go.
            height < 280.dp -> Geometry(36.dp, 18.sp, 28.dp, 12.sp, 16.dp, 0, 0.dp, 0.dp, 0.sp)
            height < 380.dp -> Geometry(40.dp, 20.sp, 30.dp, 13.sp, 17.dp, 1, 14.dp, 1.dp, 10.sp)
            height < 440.dp -> Geometry(44.dp, 22.sp, 32.dp, 13.sp, 18.dp, 2, 14.dp, 1.dp, 10.sp)
            else -> Geometry(48.dp, 24.sp, 36.dp, 15.sp, 21.dp, 2, 16.dp, 2.dp, 11.sp)
        }
    }
}

@Composable
private fun MonthBody(
    month: YearMonth,
    today: LocalDate,
    days: List<LocalDate>,
    firstDayOfWeek: DayOfWeek,
    eventsByDate: Map<LocalDate, List<EventInstance>>,
    overrides: Map<Long, Int>,
    zone: ZoneId,
    onStep: (Int) -> Unit,
    onBackToThisMonth: () -> Unit,
    context: Context,
) {
    val size = LocalSize.current
    val geo = Geometry.forHeight(size.height)
    // Floored so a degenerate size reported before layout settles can't hand columnEdges nothing.
    val gridWidth = (size.width - CARD_PADDING_H * 2).coerceAtLeast(MIN_GRID_WIDTH)
    val edges = MonthGrid.columnEdges(gridWidth.value).map { it.dp }

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(ImageProvider(R.drawable.widget_month_background))
            // Clips the grid's bottom corners to the card's on 31+; below that the drawable's own
            // corners are the card's shape and nothing inside reaches them.
            .cornerRadius(CARD_RADIUS)
            .padding(horizontal = CARD_PADDING_H, vertical = CARD_PADDING_V),
    ) {
        Header(
            month = month,
            today = today,
            geo = geo,
            onStep = onStep,
            onBackToThisMonth = onBackToThisMonth,
            context = context,
        )
        WeekdayRow(firstDayOfWeek, edges)
        // Six children, bringing the outer column to 8 of its 10. Nothing else may be added here.
        days.chunked(MonthGrid.DAYS_PER_WEEK).forEach { week ->
            WeekBlock(
                modifier = GlanceModifier.defaultWeight(),
                week = week,
                month = month,
                today = today,
                eventsByDate = eventsByDate,
                overrides = overrides,
                zone = zone,
                geo = geo,
                edges = edges,
                context = context,
            )
        }
    }
}

@Composable
private fun Header(
    month: YearMonth,
    today: LocalDate,
    geo: Geometry,
    onStep: (Int) -> Unit,
    onBackToThisMonth: () -> Unit,
    context: Context,
) {
    val awayFromThisMonth = month != YearMonth.from(today)
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(geo.headerHeight)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            // The month alone, as the reference has it; the year only once it isn't this one, since
            // "January" browsed to from December is otherwise ambiguous.
            text = month.atDay(1).format(
                if (month.year == today.year) MONTH_ONLY else MONTH_AND_YEAR,
            ),
            maxLines = 1,
            style = TextStyle(
                color = MonthWidgetColors.text,
                fontSize = geo.titleSize,
                fontWeight = FontWeight.Medium,
                fontFamily = MonthWidgetColors.sans,
            ),
            // Only clickable while browsing: on this month a tap would do nothing, and a clickable
            // view that does nothing still eats the touch. No new child, so the 10-child budget of
            // this row is untouched.
            modifier = GlanceModifier.defaultWeight().let { m ->
                if (awayFromThisMonth) {
                    m.semantics { contentDescription = "Back to this month" }.clickable(onBackToThisMonth)
                } else {
                    m
                }
            },
        )
        ChevronButton(R.drawable.ic_widget_chevron_left, "Previous month", geo) { onStep(-1) }
        ChevronButton(R.drawable.ic_widget_chevron_right, "Next month", geo) { onStep(+1) }
        Box(
            modifier = GlanceModifier
                .padding(start = 8.dp)
                .width(geo.pillHeight * PILL_ASPECT)
                .height(geo.pillHeight)
                .background(ImageProvider(R.drawable.widget_month_add_pill))
                .clickable(
                    actionStartActivity(WidgetLaunchIntents.addEvent(context, month.pickAddDate(today))),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_add),
                contentDescription = "Add event",
                modifier = GlanceModifier.size(geo.pillHeight * 0.7f),
            )
        }
    }
}

/**
 * `‹`/`›` change the month in place — no activity launch, which is the whole point of putting them
 * on the widget rather than making the user open the app to look at next month. A lambda action, so
 * the tap runs inside the live session instead of going out through a broadcast receiver.
 */
@Composable
private fun ChevronButton(
    icon: Int,
    description: String,
    geo: Geometry,
    onClick: () -> Unit,
) {
    Box(
        modifier = GlanceModifier
            .size(geo.pillHeight + 8.dp)
            .semantics { contentDescription = description }
            .clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(icon),
            // Already said once by the box's semantics; twice would be read out twice.
            contentDescription = null,
            modifier = GlanceModifier.size(geo.pillHeight * 0.8f),
        )
    }
}

@Composable
private fun WeekdayRow(firstDayOfWeek: DayOfWeek, edges: List<Dp>) {
    Row(modifier = GlanceModifier.fillMaxWidth().height(WEEKDAY_ROW)) {
        MonthGrid.weekdayOrder(firstDayOfWeek).forEachIndexed { index, day ->
            Box(
                modifier = columnsWidth(index, index, edges)
                    .fillMaxHeight()
                    .let { if (index > 0) it.background(ImageProvider(R.drawable.widget_month_weekday_rule)) else it },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = day.getDisplayName(JavaTextStyle.NARROW, Locale.getDefault()),
                    maxLines = 1,
                    style = TextStyle(
                        color = MonthWidgetColors.muted,
                        fontSize = WEEKDAY_TEXT,
                        fontFamily = MonthWidgetColors.sans,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        }
    }
}

/**
 * One week, as two layers.
 *
 * Behind: seven day columns, each carrying the rule down its start edge and the tap that opens that
 * day — so the whole cell is the target, chips included, since nothing in front is clickable and an
 * unclickable view passes the touch through. In front: the rule across the top, the day numbers,
 * then up to [Geometry.chipRows] rows of bands. Separate rows rather than chips inside each day
 * cell, because that is the only way a band can cross columns — see the class doc on [MonthWidget].
 */
@Composable
private fun WeekBlock(
    modifier: GlanceModifier,
    week: List<LocalDate>,
    month: YearMonth,
    today: LocalDate,
    eventsByDate: Map<LocalDate, List<EventInstance>>,
    overrides: Map<Long, Int>,
    zone: ZoneId,
    geo: Geometry,
    edges: List<Dp>,
    context: Context,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        Row(modifier = GlanceModifier.fillMaxSize()) {
            week.forEachIndexed { index, date ->
                Box(
                    modifier = columnsWidth(index, index, edges)
                        .fillMaxHeight()
                        .let { if (index > 0) it.background(ImageProvider(R.drawable.widget_month_column_rule)) else it }
                        // A launcher keeps long-press for itself (move, resize, remove), so a widget
                        // cannot have one: the tap is what opens the day's agenda pop-up, and the
                        // pop-up's "Open day" goes on to Today. See DayAgendaActivity.
                        .clickable(actionStartActivity(WidgetLaunchIntents.dayAgenda(context, date))),
                ) {}
            }
        }

        Column(modifier = GlanceModifier.fillMaxSize()) {
            Box(GlanceModifier.fillMaxWidth().height(1.dp).background(GRID_RULE)) {}

            Row(modifier = GlanceModifier.fillMaxWidth()) {
                week.forEachIndexed { index, date ->
                    DayNumber(
                        date = date,
                        inMonth = YearMonth.from(date) == month,
                        isToday = date == today,
                        hasEvents = geo.chipRows == 0 && !eventsByDate[date].isNullOrEmpty(),
                        geo = geo,
                        modifier = columnsWidth(index, index, edges),
                    )
                }
            }

            if (geo.chipRows > 0) {
                // Named bands for all-day and multi-day events, dots for timed ones — the Month
                // page's rule. See MonthChips.layoutWithDots.
                val layout = MonthChips.layoutWithDots(week, eventsByDate, zone, geo.chipRows, MAX_DOTS)
                val lastRow = geo.chipRows - 1
                for (rowIndex in 0 until geo.chipRows) {
                    val chips = layout.bands.chips.filter { it.row == rowIndex }
                    // With one row there is no row to give up, so nothing is marked; see
                    // layoutWithMoreRow.
                    val more = if (rowIndex == lastRow && geo.chipRows > 1) layout.bands.hidden.keys else emptySet()
                    val dots = layout.dots.filterValues { it.row == rowIndex }
                    if (chips.isEmpty() && more.isEmpty() && dots.isEmpty()) continue
                    ChipRow(
                        chips = chips,
                        moreColumns = more,
                        dots = dots,
                        overrides = overrides,
                        geo = geo,
                        edges = edges,
                    )
                }
            }
        }
    }
}

@Composable
private fun DayNumber(
    date: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    hasEvents: Boolean,
    geo: Geometry,
    modifier: GlanceModifier,
) {
    val color = when {
        isToday -> MonthWidgetColors.onPill
        !inMonth -> MonthWidgetColors.outOfMonth
        else -> MonthWidgetColors.text
    }
    Box(modifier = modifier.height(geo.dayRow), contentAlignment = Alignment.Center) {
        // The disc is a sibling behind the number, not a box around it. Around it, the number would
        // be laid out inside a square as wide as the row is tall — and "27·" at 12sp is wider than
        // the 16dp that leaves at the smallest size, so the day carrying the mark is exactly the one
        // that would lose it.
        if (isToday) {
            Box(
                GlanceModifier
                    .size(geo.dayRow)
                    .background(ImageProvider(R.drawable.widget_month_today)),
            ) {}
        }
        Text(
            // At the smallest size there is no room for a chip, so a day carrying something says so
            // with a mark against the number.
            text = if (hasEvents) "${date.dayOfMonth}·" else date.dayOfMonth.toString(),
            maxLines = 1,
            style = TextStyle(
                color = color,
                fontSize = geo.daySize,
                fontWeight = if (isToday) FontWeight.Medium else FontWeight.Normal,
                fontFamily = MonthWidgetColors.sans,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

/**
 * The width spanning columns [startColumn]..[lastColumn] of a row, taken from [edges] so every row
 * lines up with every other. A run reaching the last column keeps `defaultWeight()` instead — the
 * safety net for a `LocalSize` that under-reports (see the class doc). On [RowScope] because
 * `defaultWeight()` is only callable there, and every call site is inside a `Row`.
 */
private fun RowScope.columnsWidth(startColumn: Int, lastColumn: Int, edges: List<Dp>): GlanceModifier =
    if (lastColumn == MonthGrid.DAYS_PER_WEEK - 1) {
        GlanceModifier.defaultWeight()
    } else {
        GlanceModifier.width(edges[lastColumn + 1] - edges[startColumn])
    }

/**
 * One row of bands across a week, the `•••` marker in the columns named by [moreColumns], and the
 * timed events' dots in the columns named by [dots].
 *
 * A run is a single box as wide as the columns it covers, so its title has the whole band to sit in.
 * A column no run covers gets a box of that column's width, carrying the marker or the dots when it
 * has them, so the row stays aligned with the day numbers above it.
 */
@Composable
private fun ChipRow(
    chips: List<MonthChip>,
    moreColumns: Set<Int>,
    dots: Map<Int, DayDots>,
    overrides: Map<Long, Int>,
    geo: Geometry,
    edges: List<Dp>,
) {
    val byStart = chips.associateBy { it.startColumn }

    Row(modifier = GlanceModifier.fillMaxWidth().padding(top = geo.chipGap)) {
        var column = 0
        while (column < MonthGrid.DAYS_PER_WEEK) {
            val chip = byStart[column]
            if (chip == null) {
                val dayDots = dots[column]
                if (dayDots != null && column !in moreColumns) {
                    Box(
                        modifier = columnsWidth(column, column, edges).height(geo.chipHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        DotCluster(dayDots, overrides, geo)
                    }
                    column++
                    continue
                }
                Box(
                    modifier = columnsWidth(column, column, edges)
                        .height(geo.chipHeight)
                        .padding(start = CHIP_TEXT_PADDING + CHIP_INSET),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (column in moreColumns) {
                        Text(
                            text = "•••",
                            maxLines = 1,
                            style = TextStyle(
                                color = MonthWidgetColors.muted,
                                fontSize = geo.chipTextSize,
                                fontWeight = FontWeight.Bold,
                                fontFamily = MonthWidgetColors.sans,
                            ),
                        )
                    }
                }
                column++
                continue
            }

            val lastColumn = chip.startColumn + chip.span - 1
            // A real edge, as opposed to one the week boundary cut. A band running on into the next
            // week keeps its square end and its full width, so it reads as one event carrying on
            // rather than as two events touching.
            val realStart = !chip.continuesBefore
            val realEnd = !chip.continuesAfter
            val fill = EventColorResolver.argbFor(chip.event, overrides).orBrand()
            val onFill = onChipColor(fill)

            Box(
                modifier = columnsWidth(chip.startColumn, lastColumn, edges)
                    .height(geo.chipHeight)
                    .padding(
                        start = if (realStart) CHIP_INSET else 0.dp,
                        end = if (realEnd) CHIP_INSET else 0.dp,
                    ),
            ) {
                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(ColorProvider(day = fill, night = fill))
                        // One view, no internal seam: rounded whenever both ends are real.
                        .let { m -> if (realStart && realEnd) m.cornerRadius(4.dp) else m },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = chip.event.title,
                        maxLines = 1,
                        style = TextStyle(
                            color = ColorProvider(day = onFill, night = onFill),
                            fontSize = geo.chipTextSize,
                            fontFamily = MonthWidgetColors.sans,
                        ),
                        modifier = GlanceModifier.padding(horizontal = CHIP_TEXT_PADDING),
                    )
                }
            }
            column = lastColumn + 1
        }
    }
}

/**
 * A day's timed events as dots in their own colours, then "+N" when there are more than fit.
 *
 * Each dot is an outer box whose padding is the gap and an inner box carrying the fill — Glance
 * paints a background across a view's own padding, so a padded filled box would just be a bigger
 * dot. At most [MAX_DOTS] dots plus the count: well under the 10-child limit of a `Row`. Rounded
 * only on 31+ (`cornerRadius`); on 29 and 30 a dot is a small square, which still reads as a mark.
 */
@Composable
private fun DotCluster(day: DayDots, overrides: Map<Long, Int>, geo: Geometry) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        day.shown.forEach { event ->
            val fill = EventColorResolver.argbFor(event, overrides).orBrand()
            Box(GlanceModifier.padding(horizontal = DOT_GAP / 2)) {
                Box(
                    GlanceModifier
                        .size(DOT_SIZE)
                        .background(ColorProvider(day = fill, night = fill))
                        .cornerRadius(DOT_SIZE / 2),
                ) {}
            }
        }
        if (day.extra > 0) {
            Text(
                text = "+${day.extra}",
                maxLines = 1,
                style = TextStyle(
                    color = MonthWidgetColors.muted,
                    fontSize = geo.chipTextSize,
                    fontFamily = MonthWidgetColors.sans,
                ),
                modifier = GlanceModifier.padding(start = DOT_GAP / 2),
            )
        }
    }
}

/**
 * Colour a provider or the user gave us, guarding against the 0 that unset calendar colours come
 * back as. An event with no colour of its own falls back to the brand green, as `asCalendarColor`
 * does on every in-app surface — a band with no background would be a title floating on the card.
 */
private fun Int?.orBrand(): Color =
    if (this == null || this == 0) WidgetTokens.DarkGreen else Color(this or ALPHA_OPAQUE)

/**
 * Black or white on a fill, whichever contrasts better.
 *
 * The crossover is a relative luminance of 0.179 — the point where `(L+0.05)/0.05` and
 * `1.05/(L+0.05)` meet, and the same number `onChipColor` uses on the Month page. Event colours come
 * from the user's account and a free colour picker, so they span the whole wheel and one fixed text
 * colour is unreadable on some of them however carefully it is picked.
 */
private fun onChipColor(fill: Color): Color =
    if (fill.luminance() > 0.179f) WidgetTokens.OnLightFill else WidgetTokens.OnDarkFill

/** Tapping `+` on a month that isn't this one should land on that month, not on today. */
private fun YearMonth.pickAddDate(today: LocalDate): LocalDate =
    if (YearMonth.from(today) == this) today else atDay(1)

private val CARD_RADIUS = 24.dp
private val CARD_PADDING_H = 4.dp
private val CARD_PADDING_V = 8.dp
/** 7 columns × 20dp — a floor so a degenerate `LocalSize` can never produce zero-width columns. */
private val MIN_GRID_WIDTH = 140.dp
/** The pill is a little under twice as wide as it is tall, as in the reference. */
private const val PILL_ASPECT = 1.75f
private val WEEKDAY_ROW = 20.dp
private val WEEKDAY_TEXT = 12.sp
/** Breathing room either side of a chip, so two single-day chips don't fuse into one band. */
private val CHIP_INSET = 2.dp
private val CHIP_TEXT_PADDING = 3.dp
/** Beyond this a day shows [MAX_DOTS] - 1 dots and a "+N", as the Month page does. */
private const val MAX_DOTS = 4
private val DOT_SIZE = 5.dp
private val DOT_GAP = 3.dp
/** The rule across the top of each week: `widget_month_grid`, which the column rules are drawn in. */
private val GRID_RULE = ColorProvider(day = Color(0xFF564D60), night = Color(0xFF564D60))
private const val ALPHA_OPAQUE = 0xFF000000.toInt()

class MonthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MonthWidget()
}
