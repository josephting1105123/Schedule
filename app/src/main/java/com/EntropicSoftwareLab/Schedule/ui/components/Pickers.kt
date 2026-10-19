package com.EntropicSoftwareLab.Schedule.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The Material date picker, converted through UTC in both directions.
 *
 * The picker speaks UTC millis while the app speaks [LocalDate]. Converting through the device zone
 * instead — the obvious-looking thing — shifts the selection by a day for anyone west of UTC, which
 * is the same class of bug the all-day storage rule exists to prevent. One shared copy, so the
 * conversion is right in one place rather than repeated at each call site.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerSheet(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
    /** Days before this are greyed out rather than accepted and silently moved. */
    minDate: LocalDate? = null,
) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = (minDate?.let { maxOf(initial, it) } ?: initial)
            .atStartOfDay(UTC).toInstant().toEpochMilli(),
        selectableDates = if (minDate == null) {
            object : SelectableDates {}
        } else {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(UTC).toLocalDate().isBefore(minDate)

                override fun isSelectableYear(year: Int): Boolean = year >= minDate.year
            }
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let {
                    onPick(Instant.ofEpochMilli(it).atZone(UTC).toLocalDate())
                }
            }) { Text("Select") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = pickerState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerSheet(
    initial: LocalTime,
    onDismiss: () -> Unit,
    onPick: (LocalTime) -> Unit,
) {
    val pickerState = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true,
    )
    Dialog(onDismissRequest = onDismiss) {
        AppCard {
            Column(
                Modifier.padding(AppThemeTokens.dimens.spaceLg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TimePicker(state = pickerState)
                Spacer(Modifier.height(AppThemeTokens.dimens.spaceMd))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(AppThemeTokens.dimens.spaceSm))
                    TextButton(onClick = {
                        onPick(LocalTime.of(pickerState.hour, pickerState.minute))
                    }) { Text("Select") }
                }
            }
        }
    }
}

private val UTC: ZoneId = ZoneId.of("UTC")
