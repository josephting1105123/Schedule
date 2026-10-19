package com.EntropicSoftwareLab.Schedule.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
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
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.EntropicSoftwareLab.Schedule.MainActivity
import com.EntropicSoftwareLab.Schedule.data.appearance.BrandColorAssignment
import com.EntropicSoftwareLab.Schedule.data.appearance.EventColorRepository
import com.EntropicSoftwareLab.Schedule.data.appearance.EventColorResolver
import com.EntropicSoftwareLab.Schedule.data.calendar.CalendarRepository
import com.EntropicSoftwareLab.Schedule.data.calendar.EventInstance
import com.EntropicSoftwareLab.Schedule.ui.theme.EventColorPalette
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HEADER_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** How many events fit before the widget becomes a wall of text. */
private const val MAX_ROWS = 5

/** Same order the picker shows — see [EventColorPalette.assignmentOrder]. */
private val BRAND_PALETTE: List<Int> = EventColorPalette.assignmentOrder.map { it.argb }

class TodayWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()

        // Read before provideContent so the first frame already has data — Glance has no loading
        // state worth showing at this size.
        val events = try {
            CalendarRepository(context.contentResolver)
                .eventsOnDay(today, zone)
                .filterNot { it.allDay }
                .sortedBy { it.startMillis }
        } catch (e: SecurityException) {
            // Permission not granted yet, or revoked. Show the empty state rather than crashing the
            // host launcher.
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        // The user's picks, then a brand colour for anything nobody coloured — the same rule the app
        // and the month widget apply, so one event is never two colours across three surfaces.
        val colors = try {
            val picks = EventColorRepository(context).overrides.first()
            BrandColorAssignment.forDay(events, picks, BRAND_PALETTE)
        } catch (e: Exception) {
            emptyMap()
        }

        provideContent {
            GlanceTheme {
                WidgetBody(today = today, events = events, colors = colors, zone = zone)
            }
        }
    }
}

@Composable
private fun WidgetBody(
    today: LocalDate,
    events: List<EventInstance>,
    colors: Map<Long, Int>,
    zone: ZoneId,
) {
    val now = System.currentTimeMillis()
    val upcoming = events.filter { it.endMillis > now }.take(MAX_ROWS)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetTokens.card)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Text(
            text = today.format(HEADER_FORMAT),
            style = TextStyle(
                color = WidgetTokens.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        Spacer(GlanceModifier.height(6.dp))

        if (upcoming.isEmpty()) {
            Text(
                text = if (events.isEmpty()) "Nothing scheduled" else "Nothing left today",
                style = TextStyle(color = WidgetTokens.greenText, fontSize = 14.sp),
            )
        } else {
            upcoming.forEach { event ->
                EventLine(event = event, colors = colors, zone = zone)
                Spacer(GlanceModifier.height(4.dp))
            }
            val hidden = events.count { it.endMillis > now } - upcoming.size
            if (hidden > 0) {
                Text(
                    text = "+$hidden more",
                    style = TextStyle(color = WidgetTokens.textSecondary, fontSize = 12.sp),
                )
            }
        }
    }
}

@Composable
private fun EventLine(event: EventInstance, colors: Map<Long, Int>, zone: ZoneId) {
    val argb = EventColorResolver.argbFor(event, colors)
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A 3dp rule rather than a dot: at this size a dot reads as a bullet, and the rule lines up
        // down the list so two events sharing a colour are obvious at a glance.
        Box(
            GlanceModifier
                .width(3.dp)
                .height(14.dp)
                .let { m -> if (argb == null) m.background(WidgetTokens.green) else m.background(Color(argb)) },
        ) {}
        Spacer(GlanceModifier.width(6.dp))
        Text(
            text = event.localStart(zone).format(TIME_FORMAT),
            style = TextStyle(color = WidgetTokens.greenText, fontSize = 13.sp),
            modifier = GlanceModifier.width(46.dp),
        )
        Text(
            text = event.title,
            maxLines = 1,
            style = TextStyle(color = WidgetTokens.textPrimary, fontSize = 13.sp),
        )
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}
