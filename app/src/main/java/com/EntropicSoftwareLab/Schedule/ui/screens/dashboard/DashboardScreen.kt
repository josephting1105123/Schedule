package com.EntropicSoftwareLab.Schedule.ui.screens.dashboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.EntropicSoftwareLab.Schedule.data.appearance.EventColorResolver
import com.EntropicSoftwareLab.Schedule.data.birthday.Birthday
import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance
import com.EntropicSoftwareLab.Schedule.data.calendar.FreeBusy
import com.EntropicSoftwareLab.Schedule.data.calendar.HourGrid
import com.EntropicSoftwareLab.Schedule.data.task.Task
import com.EntropicSoftwareLab.Schedule.data.task.TaskGrid
import com.EntropicSoftwareLab.Schedule.di.LocalAppContainer
import com.EntropicSoftwareLab.Schedule.ui.components.AppCard
import com.EntropicSoftwareLab.Schedule.ui.components.AppDivider
import com.EntropicSoftwareLab.Schedule.ui.components.BirthdayRow
import com.EntropicSoftwareLab.Schedule.ui.components.DayHourGrid
import com.EntropicSoftwareLab.Schedule.ui.components.EmptyState
import com.EntropicSoftwareLab.Schedule.ui.components.EventRow
import com.EntropicSoftwareLab.Schedule.ui.components.ScreenHeader
import com.EntropicSoftwareLab.Schedule.ui.components.SectionCard
import com.EntropicSoftwareLab.Schedule.ui.components.TaskRow
import com.EntropicSoftwareLab.Schedule.ui.components.TodayPill
import com.EntropicSoftwareLab.Schedule.ui.components.asCalendarColor
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens
import com.EntropicSoftwareLab.Schedule.ui.theme.TabularFigures
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private val HEADER_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM")
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** "2h 1m left", "45m left", "1h left" — rounded up, so the last minute never reads "0m left". */
private fun timeLeft(millis: Long): String {
    val minutes = ((millis + 59_999L) / 60_000L).coerceAtLeast(1L)
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0L -> "${m}m left"
        m == 0L -> "${h}h left"
        else -> "${h}h ${m}m left"
    }
}
private val WEEKDAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    onAddEventAt: (LocalDate, Int) -> Unit = { _, _ -> },
    onEditEvent: (EventInstance) -> Unit = {},
    onEditBirthday: (Birthday) -> Unit = {},
    onEditTask: (Task) -> Unit = {},
) {
    val container = LocalAppContainer.current
    val vm: DashboardViewModel = viewModel(factory = DashboardViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val zone = container.zone
    val d = AppThemeTokens.dimens
    val today = remember { LocalDate.now() }

    val pagerState = rememberPagerState(initialPage = DashboardViewModel.DAY_WINDOW_RADIUS) {
        DashboardViewModel.DAY_WINDOW_RADIUS * 2 + 1
    }
    val dateForPage: (Int) -> LocalDate = { page ->
        state.anchorDate.plusDays((page - DashboardViewModel.DAY_WINDOW_RADIUS).toLong())
    }

    // settledPage decides which day the app considers *visible* — a fling across several days must
    // not make each page it passes through briefly "today".
    LaunchedEffect(pagerState.settledPage, state.anchorDate) {
        vm.showDate(dateForPage(pagerState.settledPage))
        container.todayShownDate.value = dateForPage(pagerState.settledPage)
    }

    // currentPage moves as soon as the drag commits to a target, well before it settles, so the
    // fetch starts while the finger is still down. Without this a fast fling lands on a page whose
    // data was only requested on arrival, and the user reads "Reading your calendar…" instead of
    // their day. ensureLoaded skips anything already cached or in flight, so this is nearly free.
    LaunchedEffect(pagerState.currentPage, state.anchorDate) {
        vm.prefetch(dateForPage(pagerState.currentPage))
    }

    // A day tapped on Month, Week or the month widget, read straight off the app-scoped state rather
    // than relayed through the ViewModel — see AppContainer.dayFocus for why. A jump, not a scroll:
    // arriving from another tab, the user wants the day they tapped, not an animation through every
    // day in between. Plain collectAsState, not the lifecycle-aware one: this must be seen on the
    // very first composition, before the entry's lifecycle has reached STARTED.
    val dayFocus by container.dayFocus.collectAsState()
    LaunchedEffect(dayFocus) {
        val date = dayFocus ?: return@LaunchedEffect
        val page = vm.pageFor(date)
        pagerState.scrollToPage(page)
        // Belt and braces: if anything moved the pager during the jump (a saved page being
        // restored, a transition's first layout), put it back where the tap asked for.
        if (pagerState.currentPage != page) pagerState.scrollToPage(page)
        container.dayFocus.compareAndSet(date, null)
    }

    // "Back to today" comes through here — see DashboardViewModel.pendingFocus and focusDate.
    val pendingFocus by vm.pendingFocus.collectAsStateWithLifecycle()
    LaunchedEffect(pendingFocus) {
        val request = pendingFocus ?: return@LaunchedEffect
        if (request.animate) pagerState.animateScrollToPage(request.page)
        else pagerState.scrollToPage(request.page)
        vm.consumeFocus(request)
    }

    // Pull down from the top of a day to sync. The box reads the day's own LazyColumn through nested
    // scroll, so the pull only starts once that list is already at its top; a horizontal swipe
    // still belongs to the pager.
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = vm::refresh,
        modifier = modifier.fillMaxSize(),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // Compose the neighbour so a swipe reveals a built page; the ViewModel prefetches its
            // data to match, so the day arrives populated rather than blank-then-filled.
            beyondViewportPageCount = 1,
        ) { page ->
            DayPage(
                date = dateForPage(page),
                state = state,
                zone = zone,
                onHourTap = { date, hour, occupied ->
                    if (occupied) vm.selectHour(date, hour) else onAddEventAt(date, hour)
                },
                onEditEvent = onEditEvent,
                onEditBirthday = onEditBirthday,
                onEditTask = onEditTask,
                onToggleTask = vm::setTaskDone,
            )
        }

        // Only while away from today — a pill announcing where you already are says nothing useful.
        if (state.visibleDate != today) {
            TodayPill(
                today = today,
                onClick = { vm.focusDate(today) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = d.spaceLg),
            )
        }
    }

    state.selectedHour?.let { selection ->
        val timed = state.timedEventsOn(selection.date)
        val cells = remember(timed, selection.date, zone) {
            HourGrid.bucketByHour(timed, selection.date, zone)
        }
        val hourEvents = cells.getOrElse(selection.hour) { emptyList() }
        // The sheet reads from live state, so an event vanishing under it (a sync, a delete in
        // another app) closes it rather than leaving an empty sheet up.
        LaunchedEffect(hourEvents.isEmpty()) {
            if (hourEvents.isEmpty()) vm.dismissHourSheet()
        }
        if (hourEvents.isNotEmpty()) {
            val colorFor = rememberColorAssignment(state.eventColors)
            HourDetailsSheet(
                hour = selection.hour,
                events = hourEvents,
                colorFor = colorFor,
                zone = zone,
                onDismiss = vm::dismissHourSheet,
                onEditEvent = { event ->
                    // Close first: the editor is a full screen, and leaving the sheet up behind it
                    // means coming back to a stale hour after the event has moved.
                    vm.dismissHourSheet()
                    onEditEvent(event)
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayPage(
    date: LocalDate,
    state: DashboardUiState,
    zone: ZoneId,
    onHourTap: (date: LocalDate, hour: Int, occupied: Boolean) -> Unit,
    onEditEvent: (EventInstance) -> Unit,
    onEditBirthday: (Birthday) -> Unit,
    onEditTask: (Task) -> Unit,
    onToggleTask: (id: Long, done: Boolean) -> Unit,
) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    val today = LocalDate.now()

    val timed = state.timedEventsOn(date)
    val allDay = state.allDayEventsOn(date)
    val birthdays = remember(state.birthdays, date) { state.birthdaysOn(date) }
    val tasks = remember(state.tasks, date) { state.tasksOn(date) }
    // Only the day's own tasks reach the grid. An overdue one is drawn nowhere: its deadline hour
    // belongs to a different date, and a tick at 09:00 today for something due last Tuesday marks
    // an hour on which nothing is due.
    val taskMarks = remember(tasks) { TaskGrid.byHour(tasks) }
    val overdue = remember(state.tasks, date, today) { state.overdueTasksOn(date, today) }
    val cells = remember(timed, date, zone) { HourGrid.bucketByHour(timed, date, zone) }
    val lanes = remember(timed, date, zone) { HourGrid.lanes(timed, date, zone) }
    val clashes = remember(timed, date, zone) { FreeBusy.clashesOnDay(timed, date, zone) }
    val colorFor = rememberColorAssignment(state.eventColors)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = d.spaceLg, end = d.spaceLg, top = d.spaceXxl, bottom = d.spaceHuge,
        ),
        verticalArrangement = Arrangement.spacedBy(d.spaceLg),
    ) {
        item {
            ScreenHeader(
                title = relativeDayTitle(date, today),
                subtitle = date.format(HEADER_DATE),
            )
        }

        state.error?.let { message ->
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.warning,
                        modifier = Modifier.padding(d.spaceLg),
                    )
                }
            }
        }

        // What's on now and what's next are statements about right now, so they only belong on the
        // actual current day.
        if (date == today) {
            item {
                // Re-read the clock on each minute boundary, or the card keeps calling an event "up
                // next" after it has started while the app sat open.
                val nowMillis by produceState(System.currentTimeMillis()) {
                    while (true) {
                        delay(60_000L - value % 60_000L)
                        value = System.currentTimeMillis()
                    }
                }
                val status = state.statusAt(nowMillis)
                // An empty list means "not fetched yet" until the day lands, and promising an empty
                // afternoon before reading the calendar is the one claim this card must not get
                // wrong.
                val stillLoading = state.isLoading(date)
                AppCard(Modifier.fillMaxWidth(), elevated = true) {
                    Column(Modifier.padding(d.spaceLg)) {
                        val label = when {
                            stillLoading -> "Today"
                            status.current.size > 1 -> "Current events"
                            status.current.isNotEmpty() -> "Current event"
                            status.next != null -> "Up next"
                            else -> "Nothing left today"
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                        Spacer(Modifier.height(d.spaceXs))
                        when {
                            stillLoading -> Text(
                                text = "Reading your calendar…",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.textSecondary,
                            )
                            status.current.isNotEmpty() -> {
                                // Two by name is what fits; a third clash is counted, not listed.
                                status.current.take(2).forEachIndexed { index, event ->
                                    if (index > 0) Spacer(Modifier.height(d.spaceSm))
                                    Text(
                                        text = event.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = colors.textPrimary,
                                    )
                                    Spacer(Modifier.height(d.spaceXxs))
                                    Text(
                                        text = "Until ${event.localEnd(zone).format(TIME_FORMAT)} · " +
                                            timeLeft(event.endMillis - nowMillis),
                                        style = MaterialTheme.typography.bodyLarge.merge(TabularFigures),
                                        color = colors.greenText,
                                    )
                                }
                                if (status.current.size > 2) {
                                    Spacer(Modifier.height(d.spaceXs))
                                    Text(
                                        text = "+${status.current.size - 2} more on now",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textSecondary,
                                    )
                                }
                                status.next?.let { next ->
                                    Spacer(Modifier.height(d.spaceSm))
                                    Text(
                                        text = "Then: ${next.title} at " +
                                            next.localStart(zone).format(TIME_FORMAT),
                                        style = MaterialTheme.typography.bodyMedium.merge(TabularFigures),
                                        color = colors.textSecondary,
                                    )
                                }
                            }
                            status.next != null -> status.next?.let { next ->
                                Text(
                                    text = next.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = colors.textPrimary,
                                )
                                Spacer(Modifier.height(d.spaceXxs))
                                Text(
                                    text = "${next.localStart(zone).format(TIME_FORMAT)} – " +
                                        next.localEnd(zone).format(TIME_FORMAT),
                                    style = MaterialTheme.typography.bodyLarge.merge(TabularFigures),
                                    color = colors.greenText,
                                )
                            }
                            // Only when nothing is on and nothing is left.
                            else -> Text(
                                text = "The rest of the day is yours.",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.greenText,
                            )
                        }
                    }
                }
            }
        }

        // Pinned: once scrolled to, the grid stays at the top while the sections under it scroll
        // past, so the day's shape is always in view beside whichever event is being read. On the
        // page's own background so rows passing underneath never show round its corners.
        stickyHeader(key = "glance") {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(colors.background)
                    .padding(vertical = d.spaceXs),
            ) {
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(d.spaceLg)) {
                        Text(
                            text = "Day at a glance",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                        Spacer(Modifier.height(d.spaceSm))
                        DayHourGrid(
                            lanes = lanes,
                            colorFor = colorFor,
                            onHourTap = { hour, cellIsFree ->
                                onHourTap(date, hour, !cellIsFree)
                            },
                            taskMarks = taskMarks,
                            currentHour = if (date == today) LocalTime.now().hour else null,
                        )
                        Spacer(Modifier.height(d.spaceSm))
                        Text(
                            text = if (taskMarks.any { it.isNotEmpty() }) {
                                "Tap a free hour to add an event. A line marks a task deadline."
                            } else {
                                "Tap a free hour to add an event."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                    }
                }
            }
        }

        // Sits directly under the grid, where a clash shows up as a stacked lane — the section says
        // in words what that second row means. Absent on a clean day rather than reassuring about
        // nothing, matching how the all-day section behaves.
        if (clashes.isNotEmpty()) {
            item {
                SectionCard(
                    title = if (clashes.size == 1) "Clash" else "Clashes",
                    icon = Icons.Outlined.WarningAmber,
                    iconTint = colors.warning,
                ) {
                    clashes.forEachIndexed { index, clash ->
                        if (index > 0) {
                            Spacer(Modifier.height(d.spaceMd))
                            AppDivider()
                            Spacer(Modifier.height(d.spaceMd))
                        }
                        // "Double-booked" describes the window, which is the claim that's exactly
                        // true: every instant in it is claimed twice over. "N events overlap" would
                        // imply all N run at once, and a chain — A over B, B over C — merges into
                        // one window without A and C ever meeting.
                        Text(
                            text = "Double-booked " +
                                "${clash.localStart(zone).format(TIME_FORMAT)}–" +
                                "${clash.localEnd(zone).format(TIME_FORMAT)} · " +
                                "${clash.events.size} events",
                            style = MaterialTheme.typography.bodySmall.merge(TabularFigures),
                            color = colors.warning,
                        )
                        clash.events.forEach {
                            EventRow(
                                event = it,
                                zone = zone,
                                barColor = colorFor(it),
                                onClick = { onEditEvent(it) },
                            )
                        }
                    }
                }
            }
        }

        // Deadlines, not appointments: no times to reserve, no clash to report, and a checkbox that
        // is the whole point of the section. Absent on a day with none, like Clashes and Birthdays.
        if (tasks.isNotEmpty() || overdue.isNotEmpty()) {
            item {
                // Overdue tasks are unfinished by construction, so they all count towards what's
                // left — carrying them onto today and then not counting them would be the section
                // saying "all done" over a list of things that aren't.
                val remaining = tasks.count { !it.done } + overdue.size
                SectionCard(
                    title = "Tasks",
                    icon = Icons.Outlined.TaskAlt,
                    trailing = {
                        Text(
                            text = if (remaining == 0) "all done" else "$remaining left",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (remaining == 0) colors.greenText else colors.textSecondary,
                        )
                    },
                ) {
                    if (overdue.isNotEmpty()) {
                        Text(
                            text = "Overdue",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.error,
                        )
                        overdue.forEach { task ->
                            TaskRow(
                                task = task,
                                onToggle = { done -> onToggleTask(task.id, done) },
                                onClick = { onEditTask(task) },
                                // Its deadline is another day's, so the date has to be on the row.
                                showDueDate = true,
                            )
                        }
                        if (tasks.isNotEmpty()) {
                            Spacer(Modifier.height(d.spaceMd))
                            AppDivider()
                            Spacer(Modifier.height(d.spaceMd))
                        }
                    }
                    tasks.forEach { task ->
                        TaskRow(
                            task = task,
                            onToggle = { done -> onToggleTask(task.id, done) },
                            onClick = { onEditTask(task) },
                        )
                    }
                }
            }
        }

        // Absent on a day with none, matching how Clashes and All day behave — a section reassuring
        // "no birthdays today" says nothing the user needed to hear.
        if (birthdays.isNotEmpty()) {
            item {
                SectionCard(title = "Birthdays", icon = Icons.Outlined.Cake) {
                    birthdays.forEach {
                        BirthdayRow(birthday = it, on = date, onClick = { onEditBirthday(it) })
                    }
                }
            }
        }

        if (allDay.isNotEmpty()) {
            item {
                SectionCard(title = "All day") {
                    allDay.forEach {
                        EventRow(event = it, zone = zone, showNotes = true, onClick = { onEditEvent(it) })
                    }
                }
            }
        }

        item {
            SectionCard(title = "Agenda", icon = Icons.Outlined.Schedule) {
                if (timed.isEmpty() && !state.isLoading(date)) {
                    EmptyState("Nothing scheduled.")
                } else {
                    // Same colours as the grid above, so a bar in the list points at a cell.
                    timed.forEach {
                        EventRow(
                            event = it,
                            zone = zone,
                            barColor = colorFor(it),
                            showNotes = true,
                            onClick = { onEditEvent(it) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Maps each of the day's events to its colour, memoised so the grid and the agenda below it agree.
 *
 * The order is [EventColorResolver]'s: this app's own colour first, then whatever the provider
 * resolved (the event's, else its calendar's), then the brand green for a row carrying neither.
 *
 * It used to take the day's events and the calendar colours and use neither — a leftover from a
 * palette-assignment design that was never wired up. The overrides map is the one input that
 * actually changes what comes out.
 */
@Composable
private fun rememberColorAssignment(
    colorOverrides: Map<Long, Int>,
): (EventInstance) -> Color {
    val fallback = AppThemeTokens.colors.green
    return remember(fallback, colorOverrides) {
        { event ->
            (EventColorResolver.argbFor(event, colorOverrides) ?: 0).asCalendarColor(fallback)
        }
    }
}

private fun relativeDayTitle(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    today.minusDays(1) -> "Yesterday"
    else -> date.format(WEEKDAY)
}
