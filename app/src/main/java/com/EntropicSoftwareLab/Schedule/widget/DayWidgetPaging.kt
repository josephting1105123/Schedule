package com.EntropicSoftwareLab.Schedule.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.height
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.unit.ColorProvider
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Which day the Day at a glance widget shows, how ‹ › move it, and the Agenda list's day sections. Pure; tested. */
internal object DayWidgetPaging {
    /** A year either way is further than anyone taps. */
    const val MAX_OFFSET = 366

    /** How many days the Agenda list covers, today included. */
    const val AGENDA_DAYS = 14

    /** The day to show for an [offset] set on [offsetDay] (epoch day): stale offsets mean today. */
    fun dayFor(today: LocalDate, offset: Int, offsetDay: Long): LocalDate =
        if (offsetDay == today.toEpochDay()) today.plusDays(offset.toLong()) else today

    /** The offset after a ‹ (-1) or › (+1); restarts from today when the stored one is stale. */
    fun step(today: LocalDate, offset: Int, offsetDay: Long, delta: Int): Int {
        val base = if (offsetDay == today.toEpochDay()) offset else 0
        return (base + delta).coerceIn(-MAX_OFFSET, MAX_OFFSET)
    }

    /** An Agenda section header: "Today · 26 Nov", "Tomorrow · 27 Nov", else "Sat 28 Nov" (short enough for a 2-wide widget at 17sp). */
    fun agendaDayHeader(day: LocalDate, today: LocalDate): String {
        val date = day.format(DateTimeFormatter.ofPattern("d MMM"))
        return when (day) {
            today -> "Today · $date"
            today.plusDays(1) -> "Tomorrow · $date"
            else -> day.format(DateTimeFormatter.ofPattern("EEE d MMM"))
        }
    }

    /**
     * Height in dp of the blank item after a day's rows, so the day fills at least [viewportDp]:
     * viewport minus the header and [rows] rows, never negative.
     */
    fun fillerHeight(viewportDp: Float, headerDp: Float, rowDp: Float, rows: Int): Float =
        (viewportDp - headerDp - rowDp * rows).coerceAtLeast(0f)
}

/**
 * A ‹ or › on a Today widget: a Glance lambda action, so the tap runs inside the live session
 * (no broadcast, no provideGlance restart). The drawables are filled with the month widget's light
 * ink, so they MUST be tinted for the GlanceTheme card or they are near-invisible.
 */
@Composable
internal fun DayChevron(
    icon: Int,
    description: String,
    tint: ColorProvider = GlanceTheme.colors.onSurfaceVariant,
    boxWidth: Dp = 28.dp,
    boxHeight: Dp = 24.dp,
    iconSize: Dp = 16.dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = GlanceModifier
            .width(boxWidth)
            .height(boxHeight)
            .semantics { contentDescription = description }
            .clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = null,
            modifier = GlanceModifier.size(iconSize),
            colorFilter = ColorFilter.tint(tint),
        )
    }
}
