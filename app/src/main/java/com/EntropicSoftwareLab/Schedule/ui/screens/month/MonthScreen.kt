package com.EntropicSoftwareLab.Schedule.ui.screens.month

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CalendarViewMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.EntropicSoftwareLab.Schedule.data.birthday.Birthday
import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance
import com.EntropicSoftwareLab.Schedule.data.appearance.EventColorResolver
import com.EntropicSoftwareLab.Schedule.data.calendar.MonthGrid
import com.EntropicSoftwareLab.Schedule.data.calendar.WeekPaging
import com.EntropicSoftwareLab.Schedule.di.LocalAppContainer
import com.EntropicSoftwareLab.Schedule.ui.components.AppCard
import com.EntropicSoftwareLab.Schedule.ui.components.AppDivider
import com.EntropicSoftwareLab.Schedule.ui.components.BirthdayRow
import com.EntropicSoftwareLab.Schedule.ui.components.EmptyState
import com.EntropicSoftwareLab.Schedule.ui.components.DayAgendaDialog
import com.EntropicSoftwareLab.Schedule.ui.components.EventRow
import com.EntropicSoftwareLab.Schedule.ui.components.ModeSlider
import com.EntropicSoftwareLab.Schedule.ui.components.ScreenHeader
import com.EntropicSoftwareLab.Schedule.ui.components.TodayPill
import com.EntropicSoftwareLab.Schedule.ui.components.asCalendarColor
import com.EntropicSoftwareLab.Schedule.ui.components.sortForAgenda
import com.EntropicSoftwareLab.Schedule.ui.components.pressScale
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens
import com.EntropicSoftwareLab.Schedule.ui.theme.TabularFigures
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

private val MONTH_TITLE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")
private val GUTTER_WEEKDAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE")

internal const val DAYS_PER_WEEK = 7
/** Below this a two-digit day number starts clipping, so the grid stops shrinking here. */
private val MIN_CELL_SIZE = 40.dp
/**
 * Under this the pinned grid would leave the agenda no usable height; it scrolls instead.
 *
 * Higher than it was, because a cell now carries named events rather than three dots: six rows of
 * [CELL_HEIGHT] is most of a short screen on its own.
 */
private val MIN_HEIGHT_FOR_PINNED_GRID = 740.dp
/** Header, calendar and spacer, ahead of the day items when the grid isn't pinned. */
private const val LEADING_ITEM_COUNT = 3

// --- Day cell geometry -------------------------------------------------------------------------
// The whole grid — numbers, dots, chip bands, the "+N" overflow line — is one Canvas pass in
// MonthGridCanvas rather than a composable per cell, so these constants are its only layout: every
// position is `cellWidth * column` and one of these dp values added on, computed once per month
// rather than measured. `internal` because that drawing lives in a different file of this package.

/**
 * The strip at the top of a cell holding the day number, clear of the rule above it.
 *
 * `internal`, along with the rest of this block, because [MonthGridCanvas] draws all of this
 * geometry itself now and needs the same constants — one definition, shared across the file that
 * lays the grid out and the file that draws it.
 */
internal val DAY_NUMBER_ROW = 23.dp
internal val DAY_NUMBER_DIAMETER = 21.dp
internal val CHIP_HEIGHT = 14.dp
internal val CHIP_GAP = 2.dp
/** Breathing room either side of a chip, so two single-day chips don't fuse into one band. */
internal val CHIP_INSET = 1.dp
/** Two all-day/multi-day bands, then the rest become dots. See MonthGridCanvas. */
internal const val MAX_CHIP_ROWS = 2
/** The row of dots for timed events (and any band that didn't fit) under the bands. */
internal val DOT_ROW = 11.dp
internal val CELL_HEIGHT =
    DAY_NUMBER_ROW + (CHIP_HEIGHT + CHIP_GAP) * MAX_CHIP_ROWS + DOT_ROW
/**
 * How far a drag has to keep going, once the agenda has nowhere left of its own to scroll, before
 * it carries on into the next or previous month.
 *
 * This is what "a long scroll" means here — a real, sustained push past the edge, not the small
 * bounce of a normal fling landing on the last day.
 */
private val LONG_SCROLL_THRESHOLD = 120.dp
/** How far a horizontal drag outside the grid has to travel before it changes month. */
private val MONTH_SWIPE_THRESHOLD = 56.dp
/** The month pager reaches two years either side of this month, as the library grid did. */
private const val MONTH_PAGE_RADIUS = 24
private const val MONTH_PAGE_COUNT = MONTH_PAGE_RADIUS * 2 + 1
/** The weekday initials above the full-page grid. */
private val FULL_WEEKDAY_ROW = 24.dp
private const val GRID_LIST_FADE_MS = 180

@Composable
fun MonthScreen(
    modifier: Modifier = Modifier,
    onEditEvent: (EventInstance) -> Unit = {},
    onEditBirthday: (Birthday) -> Unit = {},
    /** A day was tapped — opens the Today page at that date. Tapping never moves this screen. */
    onOpenDay: (LocalDate) -> Unit = {},
    /** "Add event" in a day's pop-up — the editor, seeded with that date. */
    onAddEvent: (LocalDate) -> Unit = {},
) {
    val container = LocalAppContainer.current
    val vm: MonthViewModel = viewModel(factory = MonthViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val zone = container.zone
    val d = AppThemeTokens.dimens

    val today = remember { LocalDate.now() }
    // The pager's page MONTH_PAGE_RADIUS is this month, fixed for the screen's life, so page maths
    // never shifts under a swipe. Always six rows per month (MonthGrid): a month that renders five
    // leaves the pinned grid a different height from its neighbours, so every swipe would resize the
    // agenda below it.
    val baseMonth = remember { YearMonth.from(today) }
    // Sunday first, as the Week page and the widget are — not the locale's choice. See WeekPaging.
    val firstDayOfWeek = WeekPaging.FIRST_DAY
    val listState = rememberLazyListState()

    // A pinned month grid plus a usable agenda needs real vertical room. Landscape on a phone hasn't
    // got it, so below this the calendar goes back to scrolling away with the content — which is
    // what this screen did before the agenda existed.
    val configuration = LocalConfiguration.current
    val pinCalendar = configuration.screenHeightDp.dp >= MIN_HEIGHT_FOR_PINNED_GRID
    // When the grid scrolls, it and the header occupy list slots ahead of the days, so every
    // agenda index the scroll helpers use has to shift past them.
    val leadingItems = if (pinCalendar) 0 else LEADING_ITEM_COUNT

    // The day whose agenda is floating over the page after a long-press, or null. Saveable, so a
    // rotation keeps it open.
    var agendaDay by rememberSaveable { mutableStateOf<LocalDate?>(null) }

    // Open on today, once. Saveable so a rotation doesn't yank the user back here.
    var didInitialScroll by rememberSaveable { mutableStateOf(false) }

    // Grid: the month fills the page, Google Calendar's way — tall cells naming every event. List:
    // the month's days and their events, one under another. Only the header button switches, and
    // the choice is saveable so a rotation keeps it. (2026-10-14; this replaced a small grid card
    // floating over the list, whose cells were too short to name anything.)
    var gridMode by rememberSaveable { mutableStateOf(true) }

    // Net drag distance since the agenda first ran out of its own room to scroll in the current
    // direction. Reset the instant that stops being true — see onPreScroll — so lifting a finger,
    // or the list becoming scrollable again after a month change, never leaves a stale head start.
    val dragPastEdge = remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val longScrollThresholdPx = with(density) { LONG_SCROLL_THRESHOLD.toPx() }
    val monthSwipeThresholdPx = with(density) { MONTH_SWIPE_THRESHOLD.toPx() }
    // Observes the agenda's drag without consuming any of it — the list still scrolls exactly as
    // it did before this existed. NestedScrollSource.UserInput is a finger on the glass; anything
    // else is the screen's own scrollToItem, correcting position after a grid swipe or the opening
    // jump to today, and reacting to those would change the month without the user asking for it.
    val gridScrollObserver = remember(longScrollThresholdPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero

                // Checked against the state as of just before this delta, which is what the list is
                // about to try to consume it against — a drag that lands exactly on the edge is
                // caught on the very next one, not missed.
                val atForwardEdge = available.y < 0f && !listState.canScrollForward
                val atBackwardEdge = available.y > 0f && !listState.canScrollBackward
                if (!atForwardEdge && !atBackwardEdge) {
                    dragPastEdge.floatValue = 0f
                    return Offset.Zero
                }
                dragPastEdge.floatValue += available.y
                when {
                    dragPastEdge.floatValue <= -longScrollThresholdPx -> {
                        vm.continueAgenda(forward = true)
                        dragPastEdge.floatValue = 0f
                    }
                    dragPastEdge.floatValue >= longScrollThresholdPx -> {
                        vm.continueAgenda(forward = false)
                        dragPastEdge.floatValue = 0f
                    }
                }
                return Offset.Zero
            }
        }
    }

    // Selected date -> agenda, but only for grid-originated changes. See agendaScrollRequests.
    LaunchedEffect(Unit) {
        vm.agendaScrollRequests.collect { date ->
            // vm.state.value, not the composed snapshot: the request is emitted synchronously after
            // the state update, so the snapshot here may still be a recomposition behind.
            val index = vm.state.value.agenda.indexOfFirst { it.date == date }
            if (index >= 0) listState.scrollToItem(index + leadingItems)
        }
    }

    // Opening the list lands on the selected day, the one the grid was last showing.
    LaunchedEffect(gridMode) {
        if (!gridMode) {
            val index = vm.state.value.agenda.indexOfFirst { it.date == vm.state.value.selectedDate }
            if (index >= 0) listState.scrollToItem(index + leadingItems)
        }
    }

    LaunchedEffect(state.agenda.isNotEmpty()) {
        if (!didInitialScroll && state.agenda.isNotEmpty()) {
            val index = state.agenda.indexOfFirst { it.date == today }
            if (index >= 0) {
                listState.scrollToItem(index + leadingItems)
                didInitialScroll = true
            }
        }
    }

    // Memoised: in birthday mode the getter builds a fresh map on every read, and a fresh map is a
    // new key for every week's chip layout on every composed month.
    val gridEventsByDate = remember(state.mode, state.eventsByDate, state.birthdaysByDate) {
        state.gridEventsByDate
    }

    Box(modifier.fillMaxSize()) {
        val gridWidth = configuration.screenWidthDp.dp - d.spaceLg * 2 - d.spaceMd * 2
        val cellSize = (gridWidth / DAYS_PER_WEEK).coerceAtLeast(MIN_CELL_SIZE)

        val header: @Composable () -> Unit = {
            ScreenHeader(
                title = state.visibleMonth.format(MONTH_TITLE),
                subtitle = monthSubtitle(state),
                // Back to this month, landing on today. Only while browsing — on this month there is
                // nowhere to go back to, and a title that reacts to a tap by doing nothing reads as
                // broken.
                onTitleClick = if (state.visibleMonth != baseMonth) {
                    { vm.selectDate(today) }
                } else {
                    null
                },
                titleClickLabel = "Back to this month",
                modifier = Modifier.padding(top = d.spaceLg),
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
                    ) {
                        ModeSlider(
                            selectedFirst = state.mode == CalendarMode.Events,
                            firstIcon = Icons.Outlined.CalendarMonth,
                            firstDescription = "Events calendar",
                            secondIcon = Icons.Outlined.Cake,
                            secondDescription = "Birthday calendar",
                            onSelect = { first ->
                                vm.setMode(if (first) CalendarMode.Events else CalendarMode.Birthdays)
                            },
                        )
                        // Only where there is room for a full-page grid — the landscape branch below
                        // keeps its small calendar above the list instead.
                        if (pinCalendar) {
                            ViewToggleButton(
                                gridShown = gridMode,
                                onClick = { gridMode = !gridMode },
                            )
                        }
                    }
                },
            )
        }
        val describeCount: (Int) -> String = { count ->
            when (state.mode) {
                CalendarMode.Events -> if (count == 1) "1 event" else "$count events"
                CalendarMode.Birthdays -> if (count == 1) "1 birthday" else "$count birthdays"
            }
        }
        val calendar: @Composable (Modifier) -> Unit = { cardModifier ->
            CalendarCard(
                modifier = cardModifier,
                baseMonth = baseMonth,
                visibleMonth = state.visibleMonth,
                onMonthSettled = vm::onGridSwipedTo,
                firstDayOfWeek = firstDayOfWeek,
                cellWidth = cellSize,
                today = today,
                eventsByDate = gridEventsByDate,
                zone = zone,
                colorOverrides = state.chipColors,
                onDayClick = onOpenDay,
                onDayLongPress = { agendaDay = it },
                describeCount = describeCount,
            )
        }

        if (pinCalendar) {
            Column(Modifier.fillMaxSize()) {
                header()
                Crossfade(
                    targetState = gridMode,
                    modifier = Modifier.weight(1f),
                    animationSpec = tween(AppThemeTokens.motion.dur(GRID_LIST_FADE_MS)),
                    label = "monthGridOrList",
                ) { showGrid ->
                    if (showGrid) {
                        FullMonthGrid(
                            baseMonth = baseMonth,
                            visibleMonth = state.visibleMonth,
                            onMonthSettled = vm::onGridSwipedTo,
                            firstDayOfWeek = firstDayOfWeek,
                            today = today,
                            eventsByDate = gridEventsByDate,
                            zone = zone,
                            colorOverrides = state.chipColors,
                            onDayClick = onOpenDay,
                            onDayLongPress = { agendaDay = it },
                            describeCount = describeCount,
                        )
                    } else {
                        AgendaList(
                            agenda = state.agenda,
                            today = today,
                            zone = zone,
                            mode = state.mode,
                            colorOverrides = state.chipColors,
                            listState = listState,
                            onEditEvent = onEditEvent,
                            onEditBirthday = onEditBirthday,
                            contentPadding = PaddingValues(
                                start = d.spaceLg,
                                end = d.spaceLg,
                                top = d.spaceSm,
                                bottom = d.spaceHuge,
                            ),
                            modifier = Modifier
                                .nestedScroll(gridScrollObserver)
                                // A sideways swipe over the list changes month, as swiping the grid
                                // does. detectHorizontalDragGestures only claims a gesture past its
                                // own touch slop, so ordinary vertical scrolling is untouched. Straight
                                // to the ViewModel: there is no pager on screen to drive.
                                .pointerInput(monthSwipeThresholdPx) {
                                    var accumulated = 0f
                                    detectHorizontalDragGestures(
                                        onDragStart = { accumulated = 0f },
                                        onDragEnd = { accumulated = 0f },
                                        onDragCancel = { accumulated = 0f },
                                    ) { _, dragAmount ->
                                        accumulated += dragAmount
                                        if (abs(accumulated) >= monthSwipeThresholdPx) {
                                            val shown = vm.state.value.visibleMonth
                                            vm.onGridSwipedTo(shown.plusMonths(if (accumulated < 0f) 1 else -1))
                                            accumulated = 0f
                                        }
                                    }
                                },
                        )
                    }
                }
            }
        } else {
            // No separate scrolling region to drag against here — the calendar is one more item in
            // the same list as the agenda, so it never shrinks.
            AgendaList(
                agenda = state.agenda,
                today = today,
                zone = zone,
                mode = state.mode,
                colorOverrides = state.chipColors,
                listState = listState,
                onEditEvent = onEditEvent,
                onEditBirthday = onEditBirthday,
                contentPadding = PaddingValues(
                    start = d.spaceLg, end = d.spaceLg, bottom = d.spaceHuge,
                ),
                leading = {
                    item { header() }
                    item { calendar(Modifier) }
                    item { Spacer(Modifier.height(d.spaceMd)) }
                },
            )
        }

        // Back to this month, as Today and Week have. The month's title does the same on a tap, but
        // nothing on screen said so. Only while browsing — on this month it would go nowhere.
        if (state.visibleMonth != baseMonth) {
            TodayPill(
                today = today,
                onClick = { vm.selectDate(today) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = d.spaceLg),
            )
        }

        agendaDay?.let { date ->
            DayAgendaPopup(
                date = date,
                today = today,
                state = state,
                zone = zone,
                onDismiss = { agendaDay = null },
                onOpenDay = {
                    agendaDay = null
                    onOpenDay(date)
                },
                onAddEvent = {
                    agendaDay = null
                    onAddEvent(date)
                },
                onEditEvent = {
                    agendaDay = null
                    onEditEvent(it)
                },
                onEditBirthday = {
                    agendaDay = null
                    onEditBirthday(it)
                },
            )
        }
    }
}

/**
 * A long-pressed day's agenda over the page, from the data the page already holds — the loaded
 * window reaches two months either side of the one on show, which covers every day the grid draws.
 * Events or birthdays to match the page's mode, as the agenda underneath does.
 */
@Composable
private fun DayAgendaPopup(
    date: LocalDate,
    today: LocalDate,
    state: MonthUiState,
    zone: ZoneId,
    onDismiss: () -> Unit,
    onOpenDay: () -> Unit,
    onAddEvent: () -> Unit,
    onEditEvent: (EventInstance) -> Unit,
    onEditBirthday: (Birthday) -> Unit,
) {
    val events = remember(date, state.eventsByDate) {
        sortForAgenda(state.eventsByDate[date].orEmpty().distinctBy { it.instanceId })
    }
    val birthdays = state.birthdaysByDate[date].orEmpty()
    val isEvents = state.mode == CalendarMode.Events

    DayAgendaDialog(
        date = date,
        today = today,
        isEmpty = if (isEvents) events.isEmpty() else birthdays.isEmpty(),
        emptyMessage = if (isEvents) "Nothing scheduled." else "No birthdays.",
        onDismiss = onDismiss,
        onOpenDay = onOpenDay,
        onAddEvent = onAddEvent,
    ) {
        if (isEvents) {
            items(events, key = { it.instanceId }) { event ->
                EventRow(
                    event = event,
                    zone = zone,
                    barColor = (EventColorResolver.argbFor(event, state.eventColors) ?: 0)
                        .asCalendarColor(AppThemeTokens.colors.green),
                    showNotes = true,
                    onClick = { onEditEvent(event) },
                )
            }
        } else {
            items(birthdays, key = { it.id }) { birthday ->
                BirthdayRow(birthday = birthday, on = date, onClick = { onEditBirthday(birthday) })
            }
        }
    }
}

@Composable
private fun CalendarCard(
    baseMonth: YearMonth,
    visibleMonth: YearMonth,
    onMonthSettled: (YearMonth) -> Unit,
    firstDayOfWeek: DayOfWeek,
    cellWidth: Dp,
    today: LocalDate,
    eventsByDate: Map<LocalDate, List<EventInstance>>,
    zone: ZoneId,
    colorOverrides: Map<Long, Int>,
    onDayClick: (LocalDate) -> Unit,
    onDayLongPress: (LocalDate) -> Unit,
    describeCount: (Int) -> String,
    modifier: Modifier = Modifier,
) {
    val d = AppThemeTokens.dimens

    AppCard(
        modifier
            .fillMaxWidth()
            .padding(horizontal = d.spaceLg),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(d.spaceMd)
                .width(cellWidth * DAYS_PER_WEEK),
        ) {
            WeekdayHeader(firstDayOfWeek)
            // A pager rather than the calendar library's LazyRow, for one reason: it can keep the
            // months either side composed and laid out while they're off screen. A swipe then slides
            // in a month that is already built, instead of building 42 cells and six weeks of chips
            // in the frames the swipe is trying to animate — which is what the lag on changing month
            // was. The ViewModel keeps their data loaded to match; see MonthViewModel.publish.
            HorizontalPager(
                state = rememberMonthPager(baseMonth, visibleMonth, onMonthSettled),
                modifier = Modifier.fillMaxWidth(),
                beyondViewportPageCount = 1,
                verticalAlignment = Alignment.Top,
                key = { it },
            ) { page ->
                val month = monthForPage(baseMonth, page)
                val weeks = remember(month, firstDayOfWeek) { MonthGrid.weeks(month, firstDayOfWeek) }
                MonthGridCanvas(
                    month = month,
                    weeks = weeks,
                    cellWidth = cellWidth,
                    today = today,
                    eventsByDate = eventsByDate,
                    zone = zone,
                    colorOverrides = colorOverrides,
                    onDayClick = onDayClick,
                    onDayLongPress = onDayLongPress,
                    describeCount = describeCount,
                )
            }
        }
    }
}

/** The month a pager page shows. Page [MONTH_PAGE_RADIUS] is [baseMonth]. */
private fun monthForPage(baseMonth: YearMonth, page: Int): YearMonth =
    baseMonth.plusMonths((page - MONTH_PAGE_RADIUS).toLong())

/** The pager page showing [month], clamped to the pager's range. */
private fun pageForMonth(baseMonth: YearMonth, month: YearMonth): Int =
    (MONTH_PAGE_RADIUS + ChronoUnit.MONTHS.between(baseMonth, month))
        .coerceIn(0L, MONTH_PAGE_COUNT - 1L)
        .toInt()

/**
 * A pager for the month grid, opened on [visibleMonth] and kept in step with it both ways.
 *
 * Each grid makes its own, created when it appears, rather than sharing one with the whole screen:
 * a shared state sits detached while the list is showing, and coming back it would either animate
 * from a stale month or report that stale month as settled and drag the page back to it.
 *
 * Month changes are reported on *settle* only. The library grid this replaced reported its first
 * visible month mid-swipe, so the agenda was rebuilt in the very frames the swipe was animating.
 * `onGridSwipedTo` ignores the month it already holds, which is what stops the two effects from
 * bouncing off each other.
 */
@Composable
private fun rememberMonthPager(
    baseMonth: YearMonth,
    visibleMonth: YearMonth,
    onMonthSettled: (YearMonth) -> Unit,
): PagerState {
    val pagerState = rememberPagerState(initialPage = pageForMonth(baseMonth, visibleMonth)) { MONTH_PAGE_COUNT }
    LaunchedEffect(visibleMonth) {
        val target = pageForMonth(baseMonth, visibleMonth)
        if (pagerState.settledPage != target) pagerState.animateScrollToPage(target)
    }
    val currentOnSettled by rememberUpdatedState(onMonthSettled)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            currentOnSettled(monthForPage(baseMonth, page))
        }
    }
    return pagerState
}

/**
 * The month as the whole page, after Google Calendar's month view (2026-10-14, from a screenshot the
 * user supplied): six weeks sharing the height available, every event named — timed ones too — in
 * as many rows as a cell can hold, then `•••`. The small grid card named only all-day events and
 * drew the rest as dots because its cells were 66dp tall; at a full page a cell holds five or six
 * names, which is what makes naming them worth it.
 */
@Composable
private fun FullMonthGrid(
    baseMonth: YearMonth,
    visibleMonth: YearMonth,
    onMonthSettled: (YearMonth) -> Unit,
    firstDayOfWeek: DayOfWeek,
    today: LocalDate,
    eventsByDate: Map<LocalDate, List<EventInstance>>,
    zone: ZoneId,
    colorOverrides: Map<Long, Int>,
    onDayClick: (LocalDate) -> Unit,
    onDayLongPress: (LocalDate) -> Unit,
    describeCount: (Int) -> String,
) {
    val d = AppThemeTokens.dimens
    val pagerState = rememberMonthPager(baseMonth, visibleMonth, onMonthSettled)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(start = d.spaceXs, end = d.spaceXs, bottom = d.spaceXs),
    ) {
        val cellWidth = maxWidth / DAYS_PER_WEEK
        // Never shorter than the small grid's cell: on a cramped window the page scrolls no worse
        // than before, rather than squeezing a week below one row of names.
        val cellHeight = ((maxHeight - FULL_WEEKDAY_ROW) / MonthGrid.WEEKS).coerceAtLeast(CELL_HEIGHT)
        Column {
            WeekdayHeader(firstDayOfWeek, Modifier.height(FULL_WEEKDAY_ROW))
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth(),
                beyondViewportPageCount = 1,
                verticalAlignment = Alignment.Top,
                key = { it },
            ) { page ->
                val month = monthForPage(baseMonth, page)
                val weeks = remember(month, firstDayOfWeek) { MonthGrid.weeks(month, firstDayOfWeek) }
                MonthGridCanvas(
                    month = month,
                    weeks = weeks,
                    cellWidth = cellWidth,
                    cellHeight = cellHeight,
                    nameEveryEvent = true,
                    today = today,
                    eventsByDate = eventsByDate,
                    zone = zone,
                    colorOverrides = colorOverrides,
                    onDayClick = onDayClick,
                    onDayLongPress = onDayLongPress,
                    describeCount = describeCount,
                )
            }
        }
    }
}

private fun monthSubtitle(state: MonthUiState): String = when (state.mode) {
    CalendarMode.Events -> {
        // eventsByDate spans three months, so the header counts only the visible one — and counts
        // each occurrence once, because the map repeats a multi-day event on every day it covers and
        // summing the lists would report a three-week trip as twenty-one events.
        val count = state.eventsByDate
            .filterKeys { YearMonth.from(it) == state.visibleMonth }
            .values.flatten()
            .distinctBy { it.instanceId }
            .size
        if (count == 1) "1 event this month" else "$count events this month"
    }
    CalendarMode.Birthdays -> {
        val count = state.birthdaysByDate
            .filterKeys { YearMonth.from(it) == state.visibleMonth }
            .values.flatten()
            .distinctBy { it.id }
            .size
        if (count == 1) "1 birthday this month" else "$count birthdays this month"
    }
}

@Composable
private fun AgendaList(
    agenda: List<AgendaDay>,
    today: LocalDate,
    zone: ZoneId,
    mode: CalendarMode,
    listState: LazyListState,
    colorOverrides: Map<Long, Int>,
    onEditEvent: (EventInstance) -> Unit,
    onEditBirthday: (Birthday) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    leading: (LazyListScope.() -> Unit)? = null,
) {
    val d = AppThemeTokens.dimens
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(d.spaceSm),
    ) {
        // Only set when the grid isn't pinned. These items carry no Long key, so the centre-line
        // reader skips them and day tracking still works.
        leading?.invoke(this)

        // The key is the anchor that holds scroll position when a reload rebuilds the list under a
        // user who is part-way down it.
        itemsIndexed(agenda, key = { _, day -> day.date.toEpochDay() }) { index, day ->
            AgendaDayBlock(
                day = day,
                isToday = day.date == today,
                // Every day is ruled off from the one before it, so a day's events read as that
                // day's rather than running into the next. Nothing above the first day to divide.
                showDivider = index > 0,
                zone = zone,
                mode = mode,
                colorOverrides = colorOverrides,
                onEditEvent = onEditEvent,
                onEditBirthday = onEditBirthday,
            )
        }
    }
}

@Composable
private fun AgendaDayBlock(
    day: AgendaDay,
    isToday: Boolean,
    showDivider: Boolean,
    zone: ZoneId,
    mode: CalendarMode,
    colorOverrides: Map<Long, Int>,
    onEditEvent: (EventInstance) -> Unit,
    onEditBirthday: (Birthday) -> Unit,
) {
    val colors = AppThemeTokens.colors
    val d = AppThemeTokens.dimens

    Column(Modifier.fillMaxWidth()) {
        if (showDivider) {
            AppDivider(Modifier.padding(bottom = d.spaceSm))
        }

        Row(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .width(44.dp)
                    .padding(top = d.spaceSm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = day.date.format(GUTTER_WEEKDAY),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isToday) colors.greenText else colors.textSecondary,
                )
                Text(
                    text = day.date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.headlineSmall.merge(TabularFigures),
                    color = if (isToday) colors.greenText else colors.textPrimary,
                )
            }
            Spacer(Modifier.width(d.spaceSm))

            Column(Modifier.weight(1f)) {
                when (mode) {
                    CalendarMode.Events -> if (day.events.isEmpty()) {
                        EmptyState("Nothing scheduled.")
                    } else {
                        day.events.forEach {
                            EventRow(
                                event = it,
                                zone = zone,
                                barColor = (EventColorResolver.argbFor(it, colorOverrides) ?: 0)
                                    .asCalendarColor(AppThemeTokens.colors.green),
                                onClick = { onEditEvent(it) },
                            )
                        }
                    }
                    CalendarMode.Birthdays -> if (day.birthdays.isEmpty()) {
                        EmptyState("No birthdays.")
                    } else {
                        day.birthdays.forEach { birthday ->
                            BirthdayRow(
                                birthday = birthday,
                                on = day.date,
                                onClick = { onEditBirthday(birthday) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekdayHeader(firstDayOfWeek: DayOfWeek, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        MonthGrid.weekdayOrder(firstDayOfWeek).forEach { dayOfWeek ->
            Text(
                text = dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                style = MaterialTheme.typography.bodySmall,
                color = AppThemeTokens.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Switches the page between the full month grid and the list of its days. The icon shows what the
 * *next* tap gives, as a toolbar toggle does: a list icon over the grid, a grid icon over the list.
 */
@Composable
private fun ViewToggleButton(gridShown: Boolean, onClick: () -> Unit) {
    val colors = AppThemeTokens.colors
    val d = AppThemeTokens.dimens
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .pressScale(interaction)
            .size(d.touchTarget)
            .clip(CircleShape)
            .background(colors.card)
            .border(d.hairlineWidth, colors.hairline, CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (gridShown) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.CalendarViewMonth,
            contentDescription = if (gridShown) "Show as a list" else "Show as a grid",
            tint = colors.textPrimary,
            modifier = Modifier.size(d.iconSm),
        )
    }
}
