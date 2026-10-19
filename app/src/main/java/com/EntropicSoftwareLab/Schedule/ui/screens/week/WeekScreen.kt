package com.EntropicSoftwareLab.Schedule.ui.screens.week

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.EntropicSoftwareLab.Schedule.data.appearance.EventColorResolver
import com.EntropicSoftwareLab.Schedule.data.birthday.Birthday
import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance
import com.EntropicSoftwareLab.Schedule.data.calendar.MonthChips
import com.EntropicSoftwareLab.Schedule.data.calendar.WeekGrid
import com.EntropicSoftwareLab.Schedule.data.calendar.WeekPaging
import com.EntropicSoftwareLab.Schedule.di.LocalAppContainer
import com.EntropicSoftwareLab.Schedule.ui.components.AppCard
import com.EntropicSoftwareLab.Schedule.ui.components.BirthdayRow
import com.EntropicSoftwareLab.Schedule.ui.components.EventRow
import com.EntropicSoftwareLab.Schedule.ui.components.ScreenHeader
import com.EntropicSoftwareLab.Schedule.ui.components.SectionCard
import com.EntropicSoftwareLab.Schedule.ui.components.TodayPill
import com.EntropicSoftwareLab.Schedule.ui.components.asCalendarColor
import com.EntropicSoftwareLab.Schedule.ui.components.onChipColor
import com.EntropicSoftwareLab.Schedule.ui.screens.dashboard.EventsSheet
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens
import com.EntropicSoftwareLab.Schedule.ui.theme.TabularFigures
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import kotlinx.coroutines.launch

private const val DAYS_PER_WEEK = WeekPaging.DAYS_PER_WEEK
/** The hour labels' column. Fits "0800" at the timetable's small size. */
private val GUTTER_WIDTH = 36.dp
/**
 * One hour of the timetable: a one-hour block holds a two-line title, its start, dash and end on
 * three lines and a line of place — about 70dp of 10sp/9sp text and padding.
 */
private val HOUR_HEIGHT = 84.dp
/** The pinned row of dates, subtracted when opening on an hour so it doesn't cover that hour. */
private val HEADINGS_HEIGHT = 72.dp
/** Where the hours open when the week's first event is later than this, or there is none. */
private const val DEFAULT_FOCUS_HOUR = 8
/** A 15-minute event still has to be seen and tapped. */
private val MIN_BLOCK_HEIGHT = 18.dp
private val LIST_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM")
private val BLOCK_TITLE_STYLE = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold)
private val BLOCK_TEXT_STYLE = TextStyle(fontSize = 9.sp, lineHeight = 10.sp)
private val BLOCK_VERTICAL_PADDING = 3.dp

/**
 * A week, Sunday to Saturday, as a timetable — see [TimetableGrid].
 *
 * Swipe for the week before or after. The weeks either side are composed off screen and their data
 * kept loaded (see [WeekViewModel]), so a swipe lands on a finished page. Tasks stay on Today: they
 * are a Today-only feature by design, and a week of deadlines here would be the one place a task
 * could be seen and not ticked off.
 */
@Composable
fun WeekScreen(
    modifier: Modifier = Modifier,
    /** An empty stretch of a day was tapped — opens the add form at that day and hour. */
    onAddEventAt: (LocalDate, Int) -> Unit = { _, _ -> },
    onEditEvent: (EventInstance) -> Unit = {},
    onEditBirthday: (Birthday) -> Unit = {},
    /** A day's heading was tapped — opens the Today page at that date. */
    onOpenDay: (LocalDate) -> Unit = {},
) {
    val container = LocalAppContainer.current
    val vm: WeekViewModel = viewModel(factory = WeekViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val zone = container.zone
    val d = AppThemeTokens.dimens
    val scope = rememberCoroutineScope()

    val today = remember { LocalDate.now() }
    // Hoisted above the pager so folding the timetable on one week keeps it folded on the next.
    var timetableOpen by rememberSaveable { mutableStateOf(true) }
    // Page WEEK_WINDOW_RADIUS is this week, fixed for the screen's life so page maths never shifts.
    val thisWeek = remember { WeekPaging.weekStart(today) }
    val pagerState = rememberPagerState(
        // The ViewModel outlives this composable across tab switches, so open where it left off.
        initialPage = (WeekViewModel.WEEK_WINDOW_RADIUS + WeekPaging.weeksBetween(thisWeek, vm.state.value.visibleWeek))
            .coerceIn(0L, WeekViewModel.WEEK_WINDOW_RADIUS * 2L)
            .toInt(),
    ) { WeekViewModel.WEEK_WINDOW_RADIUS * 2 + 1 }
    val weekForPage: (Int) -> LocalDate = { page ->
        thisWeek.plusWeeks((page - WeekViewModel.WEEK_WINDOW_RADIUS).toLong())
    }

    // settledPage decides which week is *visible*; a fling across several must not make each week
    // it passes through briefly the current one.
    LaunchedEffect(pagerState.settledPage) {
        vm.showWeek(weekForPage(pagerState.settledPage))
    }
    // currentPage moves as soon as a drag commits to a target, so the fetch starts under the finger.
    LaunchedEffect(pagerState.currentPage) {
        vm.prefetch(weekForPage(pagerState.currentPage))
    }

    Box(modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // Compose the neighbours so a swipe reveals a built page; the ViewModel keeps their data
            // loaded to match.
            beyondViewportPageCount = 1,
            key = { it },
        ) { page ->
            WeekPage(
                weekStart = weekForPage(page),
                today = today,
                state = state,
                zone = zone,
                onAddEventAt = onAddEventAt,
                onEditEvent = onEditEvent,
                onEditBirthday = onEditBirthday,
                onOpenDay = onOpenDay,
                timetableOpen = timetableOpen,
                onToggleTimetable = { timetableOpen = !timetableOpen },
            )
        }

        // Only while away from this week — a pill announcing where you already are says nothing.
        if (state.visibleWeek != thisWeek) {
            TodayPill(
                today = today,
                onClick = {
                    scope.launch { pagerState.animateScrollToPage(WeekViewModel.WEEK_WINDOW_RADIUS) }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = d.spaceLg),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WeekPage(
    weekStart: LocalDate,
    today: LocalDate,
    state: WeekUiState,
    zone: ZoneId,
    onAddEventAt: (LocalDate, Int) -> Unit,
    onEditEvent: (EventInstance) -> Unit,
    onEditBirthday: (Birthday) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    timetableOpen: Boolean,
    onToggleTimetable: () -> Unit,
) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    val density = LocalDensity.current
    val loading = state.isLoading(weekStart)
    val birthdays = remember(state.birthdays, weekStart) { state.birthdaysIn(weekStart) }
    val colorFor = rememberColorAssignment(state.eventColors)
    val days = remember(weekStart) { WeekPaging.days(weekStart) }
    // A tapped block opens its details rather than the editor straight away: the block can only
    // show so much, and a glance at the full title and notes is what the tap is usually for.
    var selected by remember { mutableStateOf<EventInstance?>(null) }
    selected?.let { event ->
        EventsSheet(
            heading = event.localStart(zone).format(LIST_DAY),
            events = listOf(event),
            colorFor = colorFor,
            zone = zone,
            onDismiss = { selected = null },
            onEditEvent = {
                selected = null
                onEditEvent(it)
            },
        )
    }
    val eventsByDate = days.associateWith(state::eventsOn)
    val blocksByDate = remember(eventsByDate, zone) {
        days.associateWith { WeekGrid.blocks(eventsByDate.getValue(it), it, zone) }
    }

    val title = WeekPaging.title(weekStart, today)
    val subtitle = if (title == WeekPaging.range(weekStart)) {
        weekCount(state.eventsByWeek[weekStart]?.let { eventsByDate }, loading)
    } else {
        WeekPaging.range(weekStart)
    }

    // The whole page scrolls — title, the full 24-hour table, then the all-day list — with the row
    // of dates pinned at the top once it gets there, so a block at 19:00 is never read against a
    // column whose date has scrolled away.
    val listState = rememberLazyListState()
    val gridIndex = if (state.error != null) 3 else 2
    // Open on the first event of the week, once per page: later data or a return to the page must
    // not yank the table back under a user who has scrolled it. Keeps trying until the week's rows
    // have arrived, so a page opening before its data loads still lands on its first event.
    var didInitialScroll by rememberSaveable(weekStart) { mutableStateOf(false) }
    LaunchedEffect(blocksByDate, timetableOpen) {
        if (didInitialScroll || !timetableOpen) return@LaunchedEffect
        val firstHour = blocksByDate.values.flatten().minOfOrNull { it.startMinute / 60 }
        val focusHour = minOf(firstHour ?: DEFAULT_FOCUS_HOUR, DEFAULT_FOCUS_HOUR)
        // Less the pinned dates' height, which would otherwise sit over the hour being shown.
        val offset = with(density) { (HOUR_HEIGHT * focusHour - HEADINGS_HEIGHT).roundToPx() }
        listState.scrollToItem(gridIndex, offset.coerceAtLeast(0))
        if (firstHour != null) didInitialScroll = true
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = d.spaceLg, end = d.spaceLg, top = d.spaceXxl, bottom = d.spaceHuge,
        ),
    ) {
        item(key = "header") {
            ScreenHeader(
                title = title,
                subtitle = subtitle,
                trailing = { TimetableToggle(open = timetableOpen, onClick = onToggleTimetable) },
                modifier = Modifier.padding(bottom = d.spaceLg),
            )
        }

        state.error?.let { message ->
            item(key = "error") {
                AppCard(Modifier.fillMaxWidth().padding(bottom = d.spaceLg)) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.warning,
                        modifier = Modifier.padding(d.spaceLg),
                    )
                }
            }
        }

        stickyHeader(key = "days") {
            // On the page's own background, so hours scrolling up under the pinned row never show
            // through its rounded corners.
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(colors.background)
                    .padding(bottom = d.spaceXs),
            ) {
                AppCard(Modifier.fillMaxWidth()) {
                    DayHeadingsRow(days = days, today = today, onOpenDay = onOpenDay)
                }
            }
        }

        // Folded, the row of dates above is all that's left of the table.
        if (timetableOpen) {
            item(key = "grid") {
                AppCard(Modifier.fillMaxWidth()) {
                    TimetableGrid(
                        days = days,
                        today = today,
                        blocksByDate = blocksByDate,
                        colorFor = colorFor,
                        onBlockTap = { selected = it },
                        onEmptyTap = onAddEventAt,
                    )
                }
            }
        }

        item(key = "allday") {
            AllDayList(
                weekStart = weekStart,
                eventsOn = state::eventsOn,
                birthdays = birthdays,
                zone = zone,
                colorFor = colorFor,
                onEditEvent = onEditEvent,
                onEditBirthday = onEditBirthday,
                showWhenEmpty = !loading,
                modifier = Modifier.padding(top = d.spaceLg),
            )
        }
    }
}

/**
 * The week's all-day and multi-day events and its birthdays, as a list under the timetable.
 *
 * Each event is listed once, under the first day of this week it touches — a five-day trip under
 * Monday, not five times — with the day it runs until printed under it. Birthdays go under their
 * day.
 * On a week with neither it says so, rather than leaving the foot of the page blank.
 */
@Composable
private fun AllDayList(
    weekStart: LocalDate,
    eventsOn: (LocalDate) -> List<EventInstance>,
    birthdays: Map<LocalDate, List<Birthday>>,
    zone: ZoneId,
    colorFor: (EventInstance) -> Color,
    onEditEvent: (EventInstance) -> Unit,
    onEditBirthday: (Birthday) -> Unit,
    showWhenEmpty: Boolean,
    modifier: Modifier = Modifier,
) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    val days = remember(weekStart) { WeekPaging.days(weekStart) }
    val bandsByDate = days.associateWith { date ->
        eventsOn(date).filter { event ->
            MonthChips.isBand(event, zone) &&
                maxOf(MonthChips.daySpan(event, zone).first, weekStart) == date
        }
    }
    val hasAny = bandsByDate.values.any { it.isNotEmpty() } || birthdays.values.any { it.isNotEmpty() }
    if (!hasAny && !showWhenEmpty) return

    SectionCard(title = "All day", icon = Icons.Outlined.Event, modifier = modifier.fillMaxWidth()) {
        if (!hasAny) {
            Text(
                text = "No all-day events or birthdays this week.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textSecondary,
            )
        } else Column(verticalArrangement = Arrangement.spacedBy(d.spaceXs)) {
            days.filter { bandsByDate.getValue(it).isNotEmpty() || !birthdays[it].isNullOrEmpty() }.forEach { date ->
                val bands = bandsByDate.getValue(date)
                val dayBirthdays = birthdays[date].orEmpty()
                Text(
                    text = date.format(LIST_DAY),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = d.spaceXs),
                )
                bands.forEach { event ->
                    EventRow(event = event, zone = zone, barColor = colorFor(event), onClick = { onEditEvent(event) })
                    val last = MonthChips.daySpan(event, zone).last
                    if (last != MonthChips.daySpan(event, zone).first) {
                        Text(
                            text = "Until ${last.format(LIST_DAY)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(start = d.spaceLg),
                        )
                    }
                }
                dayBirthdays.forEach {
                    BirthdayRow(birthday = it, on = date, onClick = { onEditBirthday(it) })
                }
            }
        }
    }
}

/**
 * Folds the timetable down to its row of dates and back. Filled with the accent while folded, so
 * where the hours went is obvious — the Month page's button, in the Week page's header.
 */
@Composable
private fun TimetableToggle(open: Boolean, onClick: () -> Unit) {
    val colors = AppThemeTokens.colors
    val d = AppThemeTokens.dimens
    Box(
        modifier = Modifier
            .size(d.touchTarget)
            .clip(CircleShape)
            .background(if (open) colors.card else colors.green)
            .border(d.hairlineWidth, if (open) colors.hairline else colors.green, CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (open) "Hide the timetable" else "Show the timetable",
            tint = if (open) colors.textPrimary else Color.White,
            modifier = Modifier.size(d.iconSm),
        )
    }
}

/** The seven dates across the top of the table, indented past the hour gutter; pinned while scrolling. */
@Composable
private fun DayHeadingsRow(days: List<LocalDate>, today: LocalDate, onOpenDay: (LocalDate) -> Unit) {
    val d = AppThemeTokens.dimens
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(vertical = d.spaceSm),
    ) {
        val columnWidth = (maxWidth - GUTTER_WIDTH - d.spaceXs) / DAYS_PER_WEEK
        Row(Modifier.padding(start = GUTTER_WIDTH)) {
            days.forEach { date ->
                DayHeading(
                    date = date,
                    isToday = date == today,
                    onClick = { onOpenDay(date) },
                    modifier = Modifier.width(columnWidth),
                )
            }
        }
    }
}

/**
 * The week as a timetable: a column per day, Sunday to Saturday, the clock running down the side,
 * and each timed event a block as tall as it is long.
 *
 * What a timetable can't place — all-day and multi-day events ([MonthChips.isBand]) and birthdays
 * — is listed under it by [AllDayList]. Events that overlap share their day's column side by side,
 * so a clash is visible as two narrow blocks.
 *
 * The whole day, 00:00 to 23:59, at [HOUR_HEIGHT] an hour, laid out in full: the page scrolls, not
 * the table.
 */
@Composable
private fun TimetableGrid(
    days: List<LocalDate>,
    today: LocalDate,
    blocksByDate: Map<LocalDate, List<WeekGrid.Block>>,
    colorFor: (EventInstance) -> Color,
    onBlockTap: (EventInstance) -> Unit,
    /** The day and hour under a tap that landed on no block. */
    onEmptyTap: (LocalDate, Int) -> Unit,
) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    val hours = 0 until 24
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(HOUR_HEIGHT * hours.count() + d.spaceSm * 2),
    ) {
        val columnWidth = (maxWidth - GUTTER_WIDTH - d.spaceXs) / DAYS_PER_WEEK
        val todayColumn = days.indexOf(today)

        // The empty grid is a target too: a tap that lands on no block adds an event at that day and
        // hour, the same as a free hour on Today. Underneath everything, so a block — drawn later and
        // clickable itself — always takes its own taps first.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(days, columnWidth) {
                    val gutterPx = GUTTER_WIDTH.toPx()
                    val columnPx = columnWidth.toPx()
                    val hourPx = HOUR_HEIGHT.toPx()
                    val topPx = d.spaceSm.toPx()
                    detectTapGestures { offset ->
                        val column = ((offset.x - gutterPx) / columnPx).toInt()
                        val hour = ((offset.y - topPx) / hourPx).toInt()
                        if (offset.x >= gutterPx && column in 0 until DAYS_PER_WEEK && hour in 0 until 24) {
                            onEmptyTap(days[column], hour)
                        }
                    }
                },
        )

        // Today's column, tinted behind everything else.
        if (todayColumn >= 0) {
            Box(
                Modifier
                    .offset(x = GUTTER_WIDTH + columnWidth * todayColumn)
                    .width(columnWidth)
                    .fillMaxHeight()
                    .background(colors.accentMuted.copy(alpha = 0.6f)),
            )
        }

        // Hour rules and labels, each label centred on the rule where its hour starts.
        hours.forEachIndexed { index, hour ->
            val y = HOUR_HEIGHT * index + d.spaceSm
            Box(
                Modifier
                    .offset(x = GUTTER_WIDTH, y = y)
                    .fillMaxWidth()
                    .height(d.hairlineWidth)
                    .background(colors.hairline),
            )
            Text(
                text = "%02d00".format(hour),
                style = MaterialTheme.typography.bodySmall.merge(BLOCK_TEXT_STYLE).merge(TabularFigures),
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .offset(y = y - 6.dp)
                    .width(GUTTER_WIDTH),
            )
        }

        // Day rules, so a narrow block reads as belonging to its day.
        (1 until DAYS_PER_WEEK).forEach { column ->
            Box(
                Modifier
                    .offset(x = GUTTER_WIDTH + columnWidth * column, y = d.spaceSm)
                    .width(d.hairlineWidth)
                    .fillMaxHeight()
                    .background(colors.hairline),
            )
        }

        days.forEachIndexed { column, date ->
            blocksByDate.getValue(date).forEach { block ->
                val laneWidth = columnWidth / block.lanes
                val top = HOUR_HEIGHT * (block.startMinute / 60f) + d.spaceSm
                val height = maxOf(HOUR_HEIGHT * ((block.endMinute - block.startMinute) / 60f), MIN_BLOCK_HEIGHT)
                EventBlock(
                    block = block,
                    fill = colorFor(block.event),
                    onClick = { onBlockTap(block.event) },
                    modifier = Modifier
                        .offset(
                            x = GUTTER_WIDTH + columnWidth * column + laneWidth * block.lane + 1.dp,
                            y = top + 1.dp,
                        )
                        .width(laneWidth - 2.dp)
                        .height(height - 2.dp),
                )
            }
        }

        // Now, across today's column.
        if (todayColumn >= 0) {
            val now = LocalTime.now()
            val y = HOUR_HEIGHT * ((now.hour * 60 + now.minute) / 60f) + d.spaceSm
            Box(
                Modifier
                    .offset(x = GUTTER_WIDTH + columnWidth * todayColumn, y = y - 1.dp)
                    .width(columnWidth)
                    .height(2.dp)
                    .background(colors.greenText),
            )
        }
    }
}

/**
 * One event on the timetable, every line centred and as much shown as the block's height allows.
 *
 * No fixed line cap: a six-hour block has room for its whole title and a half-hour one doesn't, and a
 * cap of two lines left the tall one showing "Orienta / tion E…" over empty colour. What gives way as
 * the block shrinks, in order: the place, then the end time and dash, then title lines — so a short
 * block still says what it is and when it starts. Titles hyphenate, so a word wider than the column
 * breaks as "Orien- / tation" rather than mid-syllable without a mark.
 *
 * Times read "1300 / - / 1400" on three lines: a column is too narrow for "13:00–14:00", and wrapped
 * it read as "13:00–14" over ":00". Text on the fill is black or white by [onChipColor].
 */
@Composable
private fun EventBlock(
    block: WeekGrid.Block,
    fill: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val event = block.event
    val ink = onChipColor(fill)
    val start = clockLabel(block.startMinute)
    val end = clockLabel(block.endMinute)
    val titleStyle = MaterialTheme.typography.bodySmall.merge(BLOCK_TITLE_STYLE)
        .copy(hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph)
    val textStyle = MaterialTheme.typography.bodySmall.merge(BLOCK_TEXT_STYLE)
    val timeStyle = textStyle.merge(TabularFigures)
    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(fill)
            .clickable(onClickLabel = "Show ${event.title}", onClick = onClick)
            .semantics { contentDescription = "${event.title}, $start to $end" }
            .padding(horizontal = 2.dp, vertical = BLOCK_VERTICAL_PADDING),
    ) {
        val density = LocalDensity.current
        val titleLine = with(density) { BLOCK_TITLE_STYLE.lineHeight.toDp() }
        val textLine = with(density) { BLOCK_TEXT_STYLE.lineHeight.toDp() }
        val room = maxHeight
        // Lines of title that fit once [reserved] is set aside for what follows it — at least one.
        fun titleLines(reserved: Dp): Int = ((room - reserved) / titleLine).toInt().coerceAtLeast(1)
        val showFullTimes = room >= titleLine + textLine * 3
        val showStart = !showFullTimes && room >= titleLine + textLine

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = event.title,
                style = titleStyle,
                color = ink,
                textAlign = TextAlign.Center,
                maxLines = when {
                    showFullTimes -> titleLines(textLine * 3)
                    showStart -> titleLines(textLine)
                    else -> titleLines(0.dp)
                },
                overflow = TextOverflow.Ellipsis,
            )
            val times = when {
                showFullTimes -> listOf(start, "-", end)
                showStart -> listOf(start)
                else -> emptyList()
            }
            times.forEach { line ->
                Text(
                    text = line,
                    style = timeStyle,
                    color = ink.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
            // Whatever height the title and times left over, and nothing if they left none.
            if (showFullTimes) {
                event.location?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = textStyle,
                        color = ink.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
    }
}

/** "1300" for 780 minutes past midnight; "2400" for the end of an event running to midnight. */
private fun clockLabel(minuteOfDay: Int): String = "%02d%02d".format(minuteOfDay / 60, minuteOfDay % 60)

/** Narrow weekday over the date number; today's number on the accent disc. */
@Composable
private fun DayHeading(
    date: LocalDate,
    isToday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppThemeTokens.colors
    Column(
        modifier = modifier
            .clickable(role = Role.Button, onClickLabel = "Open this day", onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = date.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault()),
            style = MaterialTheme.typography.bodySmall,
            color = if (isToday) colors.greenText else colors.textSecondary,
        )
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (isToday) colors.green else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.titleSmall.merge(TabularFigures),
                color = if (isToday) Color.White else colors.textPrimary,
            )
        }
    }
}

/** The subtitle under a week titled by its dates: how many distinct occurrences it holds. */
private fun weekCount(byDate: Map<LocalDate, List<EventInstance>>?, loading: Boolean): String? {
    if (loading || byDate == null) return null
    // A multi-day event is listed on every day it touches; count it once.
    val count = byDate.values.flatten().distinctBy { it.instanceId }.size
    return if (count == 1) "1 event" else "$count events"
}

/** Event to its colour — the resolver's order, the same as the Today page's. */
@Composable
private fun rememberColorAssignment(colorOverrides: Map<Long, Int>): (EventInstance) -> Color {
    val fallback = AppThemeTokens.colors.green
    return remember(fallback, colorOverrides) {
        { event -> (EventColorResolver.argbFor(event, colorOverrides) ?: 0).asCalendarColor(fallback) }
    }
}
