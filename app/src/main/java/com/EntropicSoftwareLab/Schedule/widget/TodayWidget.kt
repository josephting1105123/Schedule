package com.EntropicSoftwareLab.Schedule.widget

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
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
import com.EntropicSoftwareLab.Schedule.data.calendar.WeekPaging
import com.EntropicSoftwareLab.Schedule.ui.components.clockFormatter
import com.EntropicSoftwareLab.Schedule.ui.theme.EventColorPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

private val CARD_RADIUS = 22.dp
private val CARD_PADDING = 14.dp
private val HEADER_HEIGHT = 24.dp

/** Same order the picker shows — see [EventColorPalette.assignmentOrder]. */
private val BRAND_PALETTE: List<Int> = EventColorPalette.assignmentOrder.map { it.argb }

/** Everything the widget draws for [date], read in one pass so the seed and every live reload agree. */
private data class TodayData(
    val date: LocalDate,
    val allDay: List<EventInstance>,
    val timed: List<EventInstance>,
    val colors: Map<Long, Int>,
    val zone: ZoneId,
    val is24Hour: Boolean,
)

/**
 * Reads [date]'s events and their colours. An unreadable calendar or colour store (permission not
 * granted yet, or revoked) shows the empty state rather than crashing the host launcher;
 * cancellation still propagates.
 */
private suspend fun loadToday(context: Context, date: LocalDate): TodayData {
    val zone = ZoneId.systemDefault()

    val byDate: Map<LocalDate, List<EventInstance>> = try {
        // The month widget's cache, so a birthday the user hides is already gone — all-day rows
        // are shown here, and a birthday is one.
        MonthWidgetCache.load(context, YearMonth.from(date), zone, WeekPaging.FIRST_DAY)
    } catch (e: SecurityException) {
        emptyMap()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyMap()
    }

    val todays = byDate[date].orEmpty()
    val allDay = todays.filter { it.allDay }.sortedBy { it.title }
    val timed = todays.filterNot { it.allDay }.sortedBy { it.startMillis }

    // The user's picks, then a brand colour for anything nobody coloured — the same rule the app's
    // Today and Month pages apply, over the whole 42 days so a multi-day event keeps one colour.
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

    return TodayData(
        date = date,
        allDay = allDay,
        timed = timed,
        colors = colors,
        zone = zone,
        // Read per load so a changed 12/24-hour setting shows on the next refresh.
        is24Hour = DateFormat.is24HourFormat(context),
    )
}

class TodayWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Generation first: a refresh landing during the load below leaves this behind, so the
        // effect in provideContent reloads instead of trusting a seed that may predate it.
        val seedGeneration = MonthWidgetCache.generation.value
        // Read before provideContent so the first frame already has data.
        val seed = loadToday(context, LocalDate.now())

        provideContent {
            val today = LocalDate.now()
            var offset by remember { mutableStateOf(0) }
            var offsetDay by remember { mutableStateOf(today.toEpochDay()) }
            val shown = DayWidgetPaging.dayFor(today, offset, offsetDay)
            val loaded = remember {
                mutableStateMapOf<LocalDate, TodayData>().apply { put(seed.date, seed) }
            }
            var loadedGeneration by remember { mutableStateOf(seedGeneration) }
            // Glance 1.1.1's update/updateAll don't restart provideGlance while a session is alive,
            // so a refresh bumps this and the live session reloads itself (same as MonthWidget).
            val generation by MonthWidgetCache.generation.collectAsState()
            LaunchedEffect(shown, generation) {
                val stale = generation != loadedGeneration
                for (day in listOf(shown, shown.plusDays(1), shown.minusDays(1))) {
                    if (stale || day !in loaded) loaded[day] = loadToday(context, day)
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
                WidgetBody(
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
private fun WidgetBody(
    context: Context,
    shown: LocalDate,
    today: LocalDate,
    data: TodayData?,
    onStep: (Int) -> Unit,
    onBackToToday: () -> Unit,
) {
    // 3 children (limit 10): header, 6dp spacer, body.
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            // Ignored on API 29/30; the card is square there.
            .cornerRadius(CARD_RADIUS)
            .padding(CARD_PADDING),
    ) {
        Header(
            context = context,
            shown = shown,
            today = today,
            onStep = onStep,
            onBackToToday = onBackToToday,
        )
        Spacer(GlanceModifier.height(6.dp))

        if (data == null) {
            Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {}
        } else {
            val timeFormat = remember(data.is24Hour) { clockFormatter(data.is24Hour) }
            val now = System.currentTimeMillis()
            val remaining = if (shown == today) data.timed.filter { it.endMillis > now } else data.timed
            val hadTimed = data.timed.isNotEmpty()

            if (data.allDay.isEmpty() && remaining.isEmpty()) {
                Box(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .defaultWeight()
                        .clickable(actionStartActivity(WidgetLaunchIntents.openDay(context, shown))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (shown == today && hadTimed) "Nothing left today" else "Nothing scheduled",
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.SansSerif,
                        ),
                    )
                }
            } else {
                LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                    // instanceId, not eventId: recurring instances share an eventId, and a repeated item
                    // id makes the list reuse one row for both.
                    items(data.allDay, itemId = { it.instanceId }) { event ->
                        AgendaRow(
                            context = context,
                            shown = shown,
                            event = event,
                            colors = data.colors,
                            zone = data.zone,
                            is24Hour = data.is24Hour,
                            timeFormat = timeFormat,
                        )
                    }
                    items(remaining, itemId = { it.instanceId }) { event ->
                        AgendaRow(
                            context = context,
                            shown = shown,
                            event = event,
                            colors = data.colors,
                            zone = data.zone,
                            is24Hour = data.is24Hour,
                            timeFormat = timeFormat,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(
    context: Context,
    shown: LocalDate,
    today: LocalDate,
    onStep: (Int) -> Unit,
    onBackToToday: () -> Unit,
) {
    // 4 children (limit 10): title, previous day, next day, and "+" button.
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(HEADER_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = DayWidgetPaging.agendaTitle(shown, today),
            maxLines = 1,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 13.sp,
                fontFamily = FontFamily.SansSerif,
            ),
            modifier = if (shown == today) {
                GlanceModifier
                    .defaultWeight()
                    .clickable(actionStartActivity(WidgetLaunchIntents.openDay(context, shown)))
            } else {
                GlanceModifier
                    .defaultWeight()
                    .semantics { contentDescription = "Back to today" }
                    .clickable(onBackToToday)
            },
        )
        DayChevron(R.drawable.ic_widget_chevron_left, "Previous day") { onStep(-1) }
        DayChevron(R.drawable.ic_widget_chevron_right, "Next day") { onStep(+1) }
        Box(
            modifier = GlanceModifier
                .width(28.dp)
                .height(24.dp)
                .clickable(actionStartActivity(WidgetLaunchIntents.addEvent(context, shown))),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_add),
                contentDescription = "Add event",
                modifier = GlanceModifier.size(20.dp),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
            )
        }
    }
}

@Composable
private fun AgendaRow(
    context: Context,
    shown: LocalDate,
    event: EventInstance,
    colors: Map<Long, Int>,
    zone: ZoneId,
    is24Hour: Boolean,
    timeFormat: DateTimeFormatter,
) {
    val fill = EventColorResolver.argbFor(event, colors).orBrand()
    // 5 children (limit 10): colour bar, spacer, time column, spacer, title.
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clickable(actionStartActivity(WidgetLaunchIntents.dayAgenda(context, shown))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier
                .width(3.dp)
                .height(32.dp)
                .background(fill)
                .cornerRadius(2.dp),
        ) {}
        Spacer(GlanceModifier.width(8.dp))
        // 1 child (all-day) or 2 children (timed); limit 10.
        Column(modifier = GlanceModifier.width(if (is24Hour) 40.dp else 64.dp)) {
            if (event.allDay) {
                Text(
                    text = "All day",
                    maxLines = 1,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.SansSerif,
                    ),
                )
            } else {
                val start = maxOf(event.localStart(zone), shown.atStartOfDay())
                val end = minOf(event.localEnd(zone), shown.plusDays(1).atStartOfDay())
                Text(
                    text = start.format(timeFormat),
                    maxLines = 1,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.SansSerif,
                    ),
                )
                Text(
                    text = end.format(timeFormat),
                    maxLines = 1,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.SansSerif,
                    ),
                )
            }
        }
        Spacer(GlanceModifier.width(8.dp))
        Text(
            text = event.title,
            modifier = GlanceModifier.defaultWeight(),
            maxLines = 1,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 13.sp,
                fontFamily = FontFamily.SansSerif,
            ),
        )
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}
