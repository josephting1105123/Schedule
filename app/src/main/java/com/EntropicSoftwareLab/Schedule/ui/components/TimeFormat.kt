package com.EntropicSoftwareLab.Schedule.ui.components

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.time.format.DateTimeFormatter
import java.util.Locale

/** h:mm a or HH:mm, following the phone's own 12/24-hour setting. */
fun clockFormatter(is24Hour: Boolean, locale: Locale = Locale.getDefault()): DateTimeFormatter =
    DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", locale)

/** Reads android.text.format.DateFormat.is24HourFormat(LocalContext.current); remembered per that value. */
@Composable
fun rememberClockFormatter(): DateTimeFormatter {
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    return remember(is24Hour) { clockFormatter(is24Hour) }
}
