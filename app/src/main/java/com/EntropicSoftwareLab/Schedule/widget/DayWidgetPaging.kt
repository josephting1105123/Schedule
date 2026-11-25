package com.EntropicSoftwareLab.Schedule.widget

import androidx.compose.runtime.Composable
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

/** Which day the Agenda and Day at a glance widgets show, and how ‹ › move it. Pure; tested. */
internal object DayWidgetPaging {
    /** A year either way is further than anyone taps. */
    const val MAX_OFFSET = 366

    /** The day to show for an [offset] set on [offsetDay] (epoch day): stale offsets mean today. */
    fun dayFor(today: LocalDate, offset: Int, offsetDay: Long): LocalDate =
        if (offsetDay == today.toEpochDay()) today.plusDays(offset.toLong()) else today

    /** The offset after a ‹ (-1) or › (+1); restarts from today when the stored one is stale. */
    fun step(today: LocalDate, offset: Int, offsetDay: Long, delta: Int): Int {
        val base = if (offsetDay == today.toEpochDay()) offset else 0
        return (base + delta).coerceIn(-MAX_OFFSET, MAX_OFFSET)
    }

    /** Agenda's title: "Agenda" today, "Tomorrow"/"Yesterday" either side, else e.g. "Thu 27 Nov". */
    fun agendaTitle(day: LocalDate, today: LocalDate): String = when (day) {
        today -> "Agenda"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern("EEE d MMM"))
    }
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
    onClick: () -> Unit,
) {
    Box(
        modifier = GlanceModifier
            .width(28.dp)
            .height(24.dp)
            .semantics { contentDescription = description }
            .clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = null,
            modifier = GlanceModifier.size(16.dp),
            colorFilter = ColorFilter.tint(tint),
        )
    }
}
