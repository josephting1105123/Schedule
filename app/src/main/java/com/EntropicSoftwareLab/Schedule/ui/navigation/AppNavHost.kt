package com.EntropicSoftwareLab.Schedule.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.ViewWeek
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.navArgument
import com.EntropicSoftwareLab.Schedule.data.birthday.Birthday
import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance
import com.EntropicSoftwareLab.Schedule.data.task.Task
import com.EntropicSoftwareLab.Schedule.di.LocalAppContainer
import com.EntropicSoftwareLab.Schedule.ui.screens.birthday.BirthdayEditorScreen
import com.EntropicSoftwareLab.Schedule.ui.screens.dashboard.DashboardScreen
import com.EntropicSoftwareLab.Schedule.ui.screens.eventeditor.EventEditorScreen
import com.EntropicSoftwareLab.Schedule.ui.screens.month.MonthScreen
import com.EntropicSoftwareLab.Schedule.ui.screens.settings.SettingsScreen
import com.EntropicSoftwareLab.Schedule.ui.screens.task.TaskEditorScreen
import com.EntropicSoftwareLab.Schedule.ui.screens.week.WeekScreen
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens
import java.time.LocalDate
import java.time.LocalTime

/**
 * The bottom bar, in order: the calendar from its narrowest view to its widest, then Settings.
 * There is no Assistant tab — removed at the user's request; its screen and the `ai/` layer are
 * still in the build but nothing routes to them.
 */
enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    Today("today", "Today", Icons.Outlined.Today),
    Week("week", "Week", Icons.Outlined.ViewWeek),
    Month("month", "Month", Icons.Outlined.CalendarMonth),
    Settings("settings", "Settings", Icons.Outlined.Settings),
}

const val ROUTE_ADD_EVENT = "addEvent"
const val ROUTE_EDIT_EVENT = "editEvent"
const val ROUTE_ADD_BIRTHDAY = "addBirthday"
const val ROUTE_EDIT_BIRTHDAY = "editBirthday"
const val ROUTE_ADD_TASK = "addTask"
const val ROUTE_EDIT_TASK = "editTask"

/**
 * The registered pattern. Both seed arguments are optional, so the plain [ROUTE_ADD_EVENT] the FAB
 * and the assistant navigate to still matches and lands on today's defaults.
 */
private const val ROUTE_ADD_EVENT_PATTERN = "$ROUTE_ADD_EVENT?seedDate={seedDate}&seedHour={seedHour}"
private const val ARG_SEED_DATE = "seedDate"
private const val ARG_SEED_HOUR = "seedHour"

/**
 * Editing carries the occurrence, not just the event.
 *
 * A recurring series has one row and many occurrences, so the event id alone can't say which Tuesday
 * the user tapped — and "this event" / "this and following" are meaningless without it. The instance
 * bounds travel with the id for that reason.
 */
private const val ROUTE_EDIT_EVENT_PATTERN =
    "$ROUTE_EDIT_EVENT/{eventId}/{instanceStart}/{instanceEnd}"
private const val ARG_EVENT_ID = "eventId"
private const val ARG_INSTANCE_START = "instanceStart"
private const val ARG_INSTANCE_END = "instanceEnd"

/** Mirrors [ROUTE_ADD_EVENT_PATTERN] — a birthday only ever seeds a date, never an hour. */
private const val ROUTE_ADD_BIRTHDAY_PATTERN = "$ROUTE_ADD_BIRTHDAY?seedDate={seedDate}"
private const val ROUTE_EDIT_BIRTHDAY_PATTERN = "$ROUTE_EDIT_BIRTHDAY/{birthdayId}"
private const val ARG_BIRTHDAY_ID = "birthdayId"

/**
 * Adding takes no arguments — the "+" chooser opens it on today, the same as Event and Birthday do.
 * Editing needs only the id: a task has one occurrence, so there is no instance to carry the way
 * [ROUTE_EDIT_EVENT_PATTERN] has to.
 */
private const val ROUTE_EDIT_TASK_PATTERN = "$ROUTE_EDIT_TASK/{taskId}"
private const val ARG_TASK_ID = "taskId"

// Sentinels for "not seeded". Long.MIN_VALUE rather than -1 so no real epoch day can collide.
private const val NO_SEED_DATE = Long.MIN_VALUE
private const val NO_SEED_HOUR = -1

/** Opens the form pre-filled from a tapped hour on the Today grid. Epoch day dodges date parsing. */
fun addEventRoute(date: LocalDate, hour: Int): String =
    "$ROUTE_ADD_EVENT?$ARG_SEED_DATE=${date.toEpochDay()}&$ARG_SEED_HOUR=$hour"

/** Opens the form on [date] with the form's default time — the "+" button from a day on Today. */
fun addEventOnRoute(date: LocalDate): String = "$ROUTE_ADD_EVENT?$ARG_SEED_DATE=${date.toEpochDay()}"

/** Opens an existing occurrence for editing. */
fun editEventRoute(event: EventInstance): String =
    "$ROUTE_EDIT_EVENT/${event.eventId}/${event.startMillis}/${event.endMillis}"

fun editBirthdayRoute(birthday: Birthday): String = "$ROUTE_EDIT_BIRTHDAY/${birthday.id}"

fun editTaskRoute(task: Task): String = "$ROUTE_EDIT_TASK/${task.id}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    val container = LocalAppContainer.current
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // The add-event form is a full screen, not a tab: hide the chrome while it's up.
    val isTabScreen = Destination.entries.any { it.route == currentRoute }
    var showAddChooser by remember { mutableStateOf(false) }

    // Navigation-Compose's default is a 700ms cross-fade on *every* destination change, which is
    // most of what "the app feels slow after I tap something" actually was: the screen is built and
    // its ViewModel is already fetching, but nothing is visible until the fade finishes.
    //
    // Tabs cross-fade fast — they are peers, and a slide would imply a hierarchy that isn't there.
    // The forms slide in from the trailing edge and back out the way they came, because they *are*
    // pushed on top of a tab and the direction is the only cue saying so. Both read their durations
    // through Motion.dur, so the system's reduce-motion setting collapses them to 0ms rather than
    // to something merely shorter.
    val motion = AppThemeTokens.motion
    val tabMs = motion.dur(motion.durFast)
    val formMs = motion.dur(motion.durNav)

    val formEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideIntoContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Start,
            animationSpec = tween(formMs, easing = motion.emphasized),
        ) + fadeIn(tween(formMs))
    }
    val formPopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutOfContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.End,
            animationSpec = tween(formMs, easing = motion.emphasized),
        ) + fadeOut(tween(formMs))
    }

    // A tap on the month widget, acted on here rather than in MainActivity — the NavHostController
    // only exists inside this composition, and on a cold start it may not exist yet.
    // Parking it on the container is what stops the tap being dropped; clearing
    // it here is what stops a configuration change replaying it. See AppContainer.pendingWidgetLaunch.
    val pendingLaunch by container.pendingWidgetLaunch.collectAsStateWithLifecycle()
    LaunchedEffect(pendingLaunch) {
        val launch = pendingLaunch ?: return@LaunchedEffect
        container.pendingWidgetLaunch.value = null
        container.dayFocus.tryEmit(launch.date)
        navController.navigate(Destination.Today.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        if (launch.addEvent) {
            // Seeded with the next whole hour rather than a fixed one: "+" from the widget means
            // "something now-ish", and midnight would be wrong for almost every use of it.
            navController.navigate(addEventRoute(launch.date, LocalTime.now().hour))
        }
    }

    // Week and Month both open a tapped day on Today. App-scoped one-shot: the Today tab is reached
    // with restoreState = true, which a route argument would fight, so the date rides a shared flow
    // instead — see AppContainer.dayFocus.
    val openDayOnToday: (LocalDate) -> Unit = { date ->
        container.dayFocus.tryEmit(date)
        navController.navigate(Destination.Today.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = AppThemeTokens.colors.background,
        floatingActionButton = {
            if (currentRoute == Destination.Today.route ||
                currentRoute == Destination.Week.route ||
                currentRoute == Destination.Month.route
            ) {
                FloatingActionButton(
                    onClick = { showAddChooser = true },
                    containerColor = AppThemeTokens.colors.green,
                    contentColor = Color.White,
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "Add")
                }
            }
        },
        bottomBar = {
            if (!isTabScreen) return@Scaffold
            NavigationBar(containerColor = AppThemeTokens.colors.navBar) {
                Destination.entries.forEach { dest ->
                    NavigationBarItem(
                        selected = currentRoute == dest.route,
                        onClick = {
                            navController.navigate(dest.route) {
                                // Tabs are peers: keep one entry each rather than stacking history.
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = null) },
                        label = { Text(dest.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AppThemeTokens.colors.greenText,
                            selectedTextColor = AppThemeTokens.colors.greenText,
                            indicatorColor = AppThemeTokens.colors.accentMuted,
                            unselectedIconColor = AppThemeTokens.colors.navIcon,
                            unselectedTextColor = AppThemeTokens.colors.navIcon,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Today.route,
            // The tab default. Every form route below overrides the enter/pop-exit half of it.
            enterTransition = { fadeIn(tween(tabMs)) },
            exitTransition = { fadeOut(tween(tabMs)) },
            popEnterTransition = { fadeIn(tween(tabMs)) },
            popExitTransition = { fadeOut(tween(tabMs)) },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Says "these insets are already paid for". Without it a screen that also calls
                // imePadding() counts the bottom inset twice: the Scaffold has already reserved the
                // navigation bar, and the child then subtracts the keyboard measured from the window
                // edge — which includes that same bar. On a tall keyboard the two together exceed
                // the space available and the column collapses to its own content, which is what
                // threw the assistant's composer to the top of the screen.
                .consumeWindowInsets(padding),
        ) {
            composable(Destination.Today.route) {
                DashboardScreen(
                    onAddEventAt = { date, hour ->
                        navController.navigate(addEventRoute(date, hour))
                    },
                    onEditEvent = { navController.navigate(editEventRoute(it)) },
                    onEditBirthday = { navController.navigate(editBirthdayRoute(it)) },
                    onEditTask = { navController.navigate(editTaskRoute(it)) },
                )
            }
            composable(Destination.Week.route) {
                WeekScreen(
                    onAddEventAt = { date, hour ->
                        navController.navigate(addEventRoute(date, hour))
                    },
                    onEditEvent = { navController.navigate(editEventRoute(it)) },
                    onEditBirthday = { navController.navigate(editBirthdayRoute(it)) },
                    onOpenDay = openDayOnToday,
                )
            }
            composable(Destination.Month.route) {
                MonthScreen(
                    onEditEvent = { navController.navigate(editEventRoute(it)) },
                    onEditBirthday = { navController.navigate(editBirthdayRoute(it)) },
                    onOpenDay = openDayOnToday,
                    // The next whole hour, as the widget's "+" seeds it: a fixed hour would be wrong
                    // for most of the day.
                    onAddEvent = { date -> navController.navigate(addEventRoute(date, LocalTime.now().hour)) },
                )
            }
            composable(Destination.Settings.route) { SettingsScreen() }
            composable(
                route = ROUTE_ADD_EVENT_PATTERN,
                arguments = listOf(
                    navArgument(ARG_SEED_DATE) {
                        type = NavType.LongType
                        defaultValue = NO_SEED_DATE
                    },
                    navArgument(ARG_SEED_HOUR) {
                        type = NavType.IntType
                        defaultValue = NO_SEED_HOUR
                    },
                ),
                enterTransition = formEnter,
                popExitTransition = formPopExit,
            ) { entry ->
                val args = entry.arguments
                EventEditorScreen(
                    seedDate = args?.getLong(ARG_SEED_DATE)
                        ?.takeIf { it != NO_SEED_DATE }
                        ?.let(LocalDate::ofEpochDay),
                    seedHour = args?.getInt(ARG_SEED_HOUR)?.takeIf { it in 0..23 },
                    onFinished = { navController.popBackStack() },
                    onCancel = { navController.popBackStack() },
                )
            }
            composable(
                route = ROUTE_EDIT_EVENT_PATTERN,
                arguments = listOf(
                    navArgument(ARG_EVENT_ID) { type = NavType.LongType },
                    navArgument(ARG_INSTANCE_START) { type = NavType.LongType },
                    navArgument(ARG_INSTANCE_END) { type = NavType.LongType },
                ),
                enterTransition = formEnter,
                popExitTransition = formPopExit,
            ) { entry ->
                val args = entry.arguments
                EventEditorScreen(
                    editEventId = args?.getLong(ARG_EVENT_ID),
                    editInstanceStart = args?.getLong(ARG_INSTANCE_START) ?: 0L,
                    editInstanceEnd = args?.getLong(ARG_INSTANCE_END) ?: 0L,
                    onFinished = { navController.popBackStack() },
                    onCancel = { navController.popBackStack() },
                )
            }
            composable(
                route = ROUTE_ADD_BIRTHDAY_PATTERN,
                arguments = listOf(
                    navArgument(ARG_SEED_DATE) {
                        type = NavType.LongType
                        defaultValue = NO_SEED_DATE
                    },
                ),
                enterTransition = formEnter,
                popExitTransition = formPopExit,
            ) { entry ->
                BirthdayEditorScreen(
                    seedDate = entry.arguments?.getLong(ARG_SEED_DATE)
                        ?.takeIf { it != NO_SEED_DATE }
                        ?.let(LocalDate::ofEpochDay),
                    onFinished = { navController.popBackStack() },
                    onCancel = { navController.popBackStack() },
                )
            }
            composable(
                route = ROUTE_EDIT_BIRTHDAY_PATTERN,
                arguments = listOf(navArgument(ARG_BIRTHDAY_ID) { type = NavType.LongType }),
                enterTransition = formEnter,
                popExitTransition = formPopExit,
            ) { entry ->
                BirthdayEditorScreen(
                    editBirthdayId = entry.arguments?.getLong(ARG_BIRTHDAY_ID),
                    onFinished = { navController.popBackStack() },
                    onCancel = { navController.popBackStack() },
                )
            }
            composable(
                route = ROUTE_ADD_TASK,
                enterTransition = formEnter,
                popExitTransition = formPopExit,
            ) {
                TaskEditorScreen(
                    onFinished = { navController.popBackStack() },
                    onCancel = { navController.popBackStack() },
                )
            }
            composable(
                route = ROUTE_EDIT_TASK_PATTERN,
                arguments = listOf(navArgument(ARG_TASK_ID) { type = NavType.LongType }),
                enterTransition = formEnter,
                popExitTransition = formPopExit,
            ) { entry ->
                TaskEditorScreen(
                    editTaskId = entry.arguments?.getLong(ARG_TASK_ID),
                    onFinished = { navController.popBackStack() },
                    onCancel = { navController.popBackStack() },
                )
            }
        }
    }

    // The "+" button asks which kind of thing before opening a form — a birthday only ever needs a
    // name and a date, and a task a title and a deadline, so folding either into the event form
    // would mean carrying a "which mode" flag through a screen most of whose fields don't apply.
    if (showAddChooser) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = { showAddChooser = false }, sheetState = sheetState) {
            AddChooserRow(
                icon = Icons.Outlined.Event,
                label = "Event",
                onClick = {
                    showAddChooser = false
                    // From Today, the event goes on the day the pager shows, not the real today:
                    // "+" while looking at 3 May means 3 May. The time stays the form's default.
                    val shown = container.todayShownDate.value
                    if (currentRoute == Destination.Today.route && shown != null) {
                        navController.navigate(addEventOnRoute(shown))
                    } else {
                        navController.navigate(ROUTE_ADD_EVENT)
                    }
                },
            )
            // Offered from Today only, because Today is the only page a task appears on. From Week or
            // Month the whole interaction would end in nothing visibly happening, which reads as the save
            // having failed rather than as the task being somewhere else.
            if (currentRoute == Destination.Today.route) {
                AddChooserRow(
                    icon = Icons.Outlined.TaskAlt,
                    label = "Task",
                    onClick = {
                        showAddChooser = false
                        navController.navigate(ROUTE_ADD_TASK)
                    },
                )
            }
            AddChooserRow(
                icon = Icons.Outlined.Cake,
                label = "Birthday",
                onClick = {
                    showAddChooser = false
                    navController.navigate(ROUTE_ADD_BIRTHDAY)
                },
            )
        }
    }
}

@Composable
private fun AddChooserRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(d.buttonHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = d.spaceLg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.textPrimary)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textPrimary,
            modifier = Modifier.padding(start = d.spaceMd),
        )
    }
}

