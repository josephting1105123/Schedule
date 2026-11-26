package com.EntropicSoftwareLab.Schedule.widget

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
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
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
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

private val CARD_RADIUS = 22.dp
private val CARD_PADDING = 14.dp
private val HEADER_HEIGHT = 32.dp
private val DAY_HEADER_HEIGHT = 40.dp
private val ROW_HEIGHT = 42.dp

private const val DAY_ID_STRIDE = 100_000L
private const val FILLER_SLOT = 99_999L
private const val MAX_ROWS_PER_DAY = 99_000

/** Same order the picker shows — see [EventColorPalette.assignmentOrder]. */
private val BRAND_PALETTE: List<Int> = EventColorPalette.assignmentOrder.map { it.argb }

private data class AgendaDay(
    val date: LocalDate,
    val allDay: List<EventInstance>,
    val timed: List<EventInstance>,
    val colors: Map<Long, Int>,
)

private data class AgendaData(
    val days: List<AgendaDay>,
    val zone: ZoneId,
    val is24Hour: Boolean,
)

private sealed interface AgendaItem {
    val id: Long

    data class Header(
        override val id: Long,
        val date: LocalDate,
        val title: String,
        val isToday: Boolean,
    ) : AgendaItem

    data class Event(
        override val id: Long,
        val date: LocalDate,
        val event: EventInstance,
        val colors: Map<Long, Int>,
    ) : AgendaItem

    data class Empty(
        override val id: Long,
        val date: LocalDate,
        val message: String,
    ) : AgendaItem

    data class Filler(
        override val id: Long,
        val date: LocalDate,
        val heightDp: Float,
    ) : AgendaItem
}

/**
 * Reads [DayWidgetPaging.AGENDA_DAYS] days starting at [from], loading each distinct month once so
 * every day's events and brand colours match the Month widget for that month. An unreadable
 * calendar or colour store (permission not granted yet, or revoked) shows the empty state rather
 * than crashing the host launcher; cancellation still propagates.
 */
private suspend fun loadAgenda(context: Context, from: LocalDate): AgendaData {
    val zone = ZoneId.systemDefault()
    val dates = (0 until DayWidgetPaging.AGENDA_DAYS).map { from.plusDays(it.toLong()) }
    val months = dates.map { YearMonth.from(it) }.distinct()

    val byMonth: Map<YearMonth, Map<LocalDate, List<EventInstance>>> = months.associateWith { month ->
        try {
            // The month widget's cache, so a birthday the user hides is already gone — all-day rows
            // are shown here, and a birthday is one.
            MonthWidgetCache.load(context, month, zone, WeekPaging.FIRST_DAY)
        } catch (e: SecurityException) {
            emptyMap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // The user's picks, then a brand colour for anything nobody coloured — computed per month rather
    // than over a merged map so a day's colours match the Month widget's for that day.
    val colorsByMonth: Map<YearMonth, Map<Long, Int>> = try {
        val picks = EventColorRepository(context).overrides.first()
        byMonth.mapValues { (_, monthEvents) ->
            BrandColorAssignment.forDates(monthEvents, picks, BRAND_PALETTE)
        }
    } catch (e: SecurityException) {
        emptyMap()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyMap()
    }

    val days = dates.map { date ->
        val month = YearMonth.from(date)
        val events = byMonth[month]?.get(date).orEmpty()
        AgendaDay(
            date = date,
            allDay = events.filter { it.allDay }.sortedBy { it.title },
            timed = events.filterNot { it.allDay }.sortedBy { it.startMillis },
            colors = colorsByMonth[month].orEmpty(),
        )
    }

    return AgendaData(
        days = days,
        zone = zone,
        // Read per load so a changed 12/24-hour setting shows on the next refresh.
        is24Hour = DateFormat.is24HourFormat(context),
    )
}

private fun buildAgendaItems(
    days: List<AgendaDay>,
    today: LocalDate,
    viewportDp: Float,
    nowMillis: Long,
): List<AgendaItem> = buildList {
    for ((dayIndex, day) in days.withIndex()) {
        val baseId = dayIndex.toLong() * DAY_ID_STRIDE
        val isToday = day.date == today
        add(
            AgendaItem.Header(
                id = baseId,
                date = day.date,
                title = DayWidgetPaging.agendaDayHeader(day.date, today),
                isToday = isToday,
            ),
        )

        val remainingTimed = if (isToday) day.timed.filter { it.endMillis > nowMillis } else day.timed
        val events = (day.allDay + remainingTimed).take(MAX_ROWS_PER_DAY)
        val rows: Int
        if (events.isEmpty()) {
            val message = if (isToday && day.timed.isNotEmpty()) {
                "Nothing left today"
            } else {
                "Nothing scheduled"
            }
            add(
                AgendaItem.Empty(
                    id = baseId + 1L,
                    date = day.date,
                    message = message,
                ),
            )
            rows = 1
        } else {
            for ((rowIndex, event) in events.withIndex()) {
                add(
                    AgendaItem.Event(
                        id = baseId + rowIndex + 1L,
                        date = day.date,
                        event = event,
                        colors = day.colors,
                    ),
                )
            }
            rows = events.size
        }

        val fillerDp = DayWidgetPaging.fillerHeight(
            viewportDp = viewportDp,
            headerDp = DAY_HEADER_HEIGHT.value,
            rowDp = ROW_HEIGHT.value,
            rows = rows,
        )
        if (fillerDp > 0f) {
            add(
                AgendaItem.Filler(
                    id = baseId + FILLER_SLOT,
                    date = day.date,
                    heightDp = fillerDp,
                ),
            )
        }
    }
}

class TodayWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // No load before provideContent: the header and an empty body draw at once, and the 14-day
        // range arrives afterwards in one assignment (the LaunchedEffect below), like MonthWidget.
        val startGeneration = MonthWidgetCache.generation.value

        provideContent {
            val today = LocalDate.now()
            var data by remember { mutableStateOf<AgendaData?>(null) }
            // Glance 1.1.1's update/updateAll don't restart provideGlance while a session is alive,
            // so a refresh bumps this and the live session reloads itself (same as MonthWidget).
            val generation by MonthWidgetCache.generation.collectAsState(initial = startGeneration)
            LaunchedEffect(today, generation) {
                data = loadAgenda(context, today)
            }
            val currentData = data?.takeIf { it.days.firstOrNull()?.date == today }

            GlanceTheme {
                WidgetBody(
                    context = context,
                    today = today,
                    data = currentData,
                )
            }
        }
    }
}

@Composable
private fun WidgetBody(
    context: Context,
    today: LocalDate,
    data: AgendaData?,
) {
    val viewportDp = LocalSize.current.height.value - 2 * CARD_PADDING.value - HEADER_HEIGHT.value - 6f

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
            today = today,
        )
        Spacer(GlanceModifier.height(6.dp))

        if (data == null) {
            Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {}
        } else {
            val timeFormat = remember(data.is24Hour) { clockFormatter(data.is24Hour) }
            val items = buildAgendaItems(
                days = data.days,
                today = today,
                viewportDp = viewportDp,
                nowMillis = System.currentTimeMillis(),
            )
            LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                // Multi-day events share an instanceId across days, so each item carries a composite
                // (dayIndex, slot) id unique across the whole 14-day list.
                items(items, itemId = { it.id }) { item ->
                    when (item) {
                        is AgendaItem.Header -> Box(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .height(DAY_HEADER_HEIGHT)
                                .padding(bottom = 4.dp)
                                .clickable(actionStartActivity(WidgetLaunchIntents.openDay(context, item.date))),
                            contentAlignment = Alignment.BottomStart,
                        ) {
                            Text(
                                text = item.title,
                                maxLines = 1,
                                style = TextStyle(
                                    color = if (item.isToday) {
                                        GlanceTheme.colors.primary
                                    } else {
                                        GlanceTheme.colors.onSurfaceVariant
                                    },
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                            )
                        }
                        is AgendaItem.Event -> AgendaRow(
                            context = context,
                            shown = item.date,
                            event = item.event,
                            colors = item.colors,
                            zone = data.zone,
                            is24Hour = data.is24Hour,
                            timeFormat = timeFormat,
                        )
                        is AgendaItem.Empty -> Box(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .height(ROW_HEIGHT)
                                .clickable(actionStartActivity(WidgetLaunchIntents.openDay(context, item.date))),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = item.message,
                                style = TextStyle(
                                    color = GlanceTheme.colors.onSurfaceVariant,
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily.SansSerif,
                                ),
                            )
                        }
                        is AgendaItem.Filler -> Box(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .height(item.heightDp.dp)
                                .clickable(actionStartActivity(WidgetLaunchIntents.openDay(context, item.date))),
                        ) {}
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(
    context: Context,
    today: LocalDate,
) {
    // 2 children (limit 10): title and "+" button.
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(HEADER_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Agenda",
            maxLines = 1,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
            ),
            modifier = GlanceModifier
                .defaultWeight()
                .clickable(actionStartActivity(WidgetLaunchIntents.openDay(context, today))),
        )
        Box(
            modifier = GlanceModifier
                .width(28.dp)
                .height(24.dp)
                .clickable(actionStartActivity(WidgetLaunchIntents.addEvent(context, today))),
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
            .height(ROW_HEIGHT)
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
