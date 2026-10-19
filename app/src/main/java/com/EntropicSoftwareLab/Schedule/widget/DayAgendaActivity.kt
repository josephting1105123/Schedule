package com.EntropicSoftwareLab.Schedule.widget

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.EntropicSoftwareLab.Schedule.data.appearance.EventColorRepository
import com.EntropicSoftwareLab.Schedule.data.appearance.EventColorResolver
import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance
import com.EntropicSoftwareLab.Schedule.data.calendar.WeekPaging
import com.EntropicSoftwareLab.Schedule.ui.components.DayAgendaCard
import com.EntropicSoftwareLab.Schedule.ui.components.EventRow
import com.EntropicSoftwareLab.Schedule.ui.components.sortForAgenda
import com.EntropicSoftwareLab.Schedule.ui.components.asCalendarColor
import com.EntropicSoftwareLab.Schedule.ui.theme.AppTheme
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * The month widget's day pop-up: that day's agenda floating over the home screen, at about 70% of
 * its height.
 *
 * An activity because nothing else can draw over a launcher, and a transparent one
 * (`Theme.Schedule.Popup`) whose only content is the dialog, so what shows behind it is the home
 * screen the user tapped from rather than the app. It reads the same [MonthWidgetCache] the widget
 * just drew from and resolves colours the way the widget does, so a dot and its row agree.
 *
 * It shows nothing the widget itself doesn't already show on the home screen, and every way
 * onward ("Open day", "Add event", a row) goes through `MainActivity`, where the calendar
 * permission gate is.
 */
class DayAgendaActivity : ComponentActivity() {

    private var date by mutableStateOf<LocalDate?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        date = readDate(intent)
        if (date == null) {
            finish()
            return
        }
        setContent {
            AppTheme {
                date?.let { shown ->
                    DayAgendaContent(
                        date = shown,
                        onDismiss = { finishWithoutTransition() },
                        onOpenDay = { launchAndClose(WidgetLaunchIntents.openDay(this, shown)) },
                        onAddEvent = { launchAndClose(WidgetLaunchIntents.addEvent(this, shown)) },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readDate(intent)?.let { date = it }
    }

    private fun launchAndClose(intent: Intent) {
        startActivity(intent)
        finish()
    }

    /**
     * Closes once the card's own exit animation has run. The window transition is suppressed so
     * the system doesn't add a second animation (or, on some launchers, a flash) after it.
     */
    private fun finishWithoutTransition() {
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun readDate(intent: Intent?): LocalDate? {
        val epochDay = intent?.getLongExtra(WidgetLaunchIntents.EXTRA_FOCUS_DATE, Long.MIN_VALUE)
            ?: return null
        return if (epochDay == Long.MIN_VALUE) null else LocalDate.ofEpochDay(epochDay)
    }
}

/** What the pop-up is showing: still loading, the day's events, or why there are none to show. */
private sealed interface DayLoad {
    data object Loading : DayLoad
    data class Loaded(val events: List<EventInstance>, val colors: Map<Long, Int>) : DayLoad
    data class Failed(val message: String) : DayLoad
}

@Composable
private fun DayAgendaContent(
    date: LocalDate,
    onDismiss: () -> Unit,
    onOpenDay: () -> Unit,
    onAddEvent: () -> Unit,
) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    var load by remember(date) { mutableStateOf<DayLoad>(DayLoad.Loading) }

    LaunchedEffect(date) {
        load = try {
            // The month the widget built this day under may be a neighbour's (a spill-over day), but
            // the day's own month always covers it, and is the likelier one to be cached already.
            val byDate = MonthWidgetCache.load(context, YearMonth.from(date), zone, WeekPaging.FIRST_DAY)
            val colors = runCatching { EventColorRepository(context).overrides.first() }.getOrDefault(emptyMap())
            DayLoad.Loaded(sortForAgenda(byDate[date].orEmpty()), colors)
        } catch (e: SecurityException) {
            DayLoad.Failed("Calendar access is off. Open the app to allow it.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DayLoad.Failed("Couldn't read the calendar.")
        }
    }

    val current = load
    val motion = AppThemeTokens.motion

    // Not a Dialog: a dialog window is destroyed with the activity in the same frame, so closing
    // used to snap the card off the screen. Here the scrim and card are drawn in the activity's own
    // window and animated out first; the activity finishes only once the exit has settled.
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    val close = { visible.targetState = false }
    LaunchedEffect(visible.isIdle, visible.currentState) {
        if (visible.isIdle && !visible.currentState) onDismiss()
    }
    BackHandler(enabled = visible.targetState) { close() }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visibleState = visible,
            enter = fadeIn(tween(motion.dur(ENTER_MS))),
            exit = fadeOut(tween(motion.dur(EXIT_MS), easing = FastOutLinearInEasing)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = SCRIM_ALPHA))
                    // Outside the card: closes, as a dialog's outside tap does. No ripple on a scrim.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = close,
                    ),
            )
        }
        AnimatedVisibility(
            visibleState = visible,
            modifier = Modifier.align(Alignment.Center),
            // Grows out of, and settles back into, a slightly smaller card: enough movement to read
            // as the same card arriving and leaving, not so much that it reads as a zoom.
            enter = fadeIn(tween(motion.dur(ENTER_MS), easing = LinearOutSlowInEasing)) +
                scaleIn(tween(motion.dur(ENTER_MS), easing = LinearOutSlowInEasing), initialScale = CARD_SCALE),
            exit = fadeOut(tween(motion.dur(EXIT_MS), easing = FastOutLinearInEasing)) +
                scaleOut(tween(motion.dur(EXIT_MS), easing = FastOutLinearInEasing), targetScale = CARD_SCALE),
        ) {
            DayAgendaPopupCard(
                date = date,
                current = current,
                zone = zone,
                onClose = close,
                onOpenDay = onOpenDay,
                onAddEvent = onAddEvent,
            )
        }
    }
}

@Composable
private fun DayAgendaPopupCard(
    date: LocalDate,
    current: DayLoad,
    zone: ZoneId,
    onClose: () -> Unit,
    onOpenDay: () -> Unit,
    onAddEvent: () -> Unit,
) {
    DayAgendaCard(
        date = date,
        today = LocalDate.now(),
        isEmpty = current !is DayLoad.Loaded || current.events.isEmpty(),
        emptyMessage = when (current) {
            DayLoad.Loading -> "Loading…"
            is DayLoad.Failed -> current.message
            is DayLoad.Loaded -> "Nothing scheduled."
        },
        onDismiss = onClose,
        onOpenDay = onOpenDay,
        onAddEvent = onAddEvent,
    ) {
        if (current is DayLoad.Loaded) {
            items(current.events, key = { it.instanceId }) { event ->
                EventRow(
                    event = event,
                    zone = zone,
                    barColor = (EventColorResolver.argbFor(event, current.colors) ?: 0)
                        .asCalendarColor(AppThemeTokens.colors.green),
                    showNotes = true,
                    // No edit route reaches the app from outside it, so a row opens its day.
                    onClick = onOpenDay,
                )
            }
        }
    }
}

private const val ENTER_MS = 240
/** A little shorter than the way in: leaving should never feel like waiting. */
private const val EXIT_MS = 200
private const val CARD_SCALE = 0.92f
/** Roughly the platform's own dialog dim. */
private const val SCRIM_ALPHA = 0.5f
