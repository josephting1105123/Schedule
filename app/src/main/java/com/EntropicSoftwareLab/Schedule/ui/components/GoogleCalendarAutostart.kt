package com.EntropicSoftwareLab.Schedule.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.EntropicSoftwareLab.Schedule.R
import com.EntropicSoftwareLab.Schedule.data.calendar.SyncAdvice
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens

fun Context.isGoogleCalendarInstalled(): Boolean =
    runCatching { packageManager.getApplicationInfo(SyncAdvice.GOOGLE_CALENDAR_PACKAGE, 0); true }
        .getOrDefault(false)

/**
 * Google Calendar owns the calendar sync adapter, and Xiaomi's HyperOS/MIUI denies it Autostart by
 * default, so uploads wait until it happens to run. Worth saying only where that can happen.
 */
fun Context.shouldShowAutostartTip(): Boolean =
    SyncAdvice.isXiaomiFamily(Build.MANUFACTURER, Build.BRAND) && isGoogleCalendarInstalled()

/**
 * HyperOS/MIUI's own Autostart list, where Google Calendar shows as "Calendar" with the blue 31
 * icon. Google Calendar's app-info page has no Autostart switch on current HyperOS (checked on a
 * Xiaomi phone, 2026-11-23), so that page is only the fallback for builds without the list.
 */
fun Context.openAutostartSettings() {
    val opened = runCatching {
        startActivity(Intent(ACTION_MIUI_AUTOSTART).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (!opened) {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", SyncAdvice.GOOGLE_CALENDAR_PACKAGE, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

private const val ACTION_MIUI_AUTOSTART = "miui.intent.action.OP_AUTO_START"

/** The Autostart advice and a button to the page that has the switch. Nothing off Xiaomi phones. */
@Composable
fun AutostartTip(highlight: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val show = remember { context.shouldShowAutostartTip() }
    if (!show) return
    val colors = AppThemeTokens.colors
    Column(modifier) {
        Text(
            text = stringResource(R.string.autostart_tip_body),
            style = MaterialTheme.typography.bodyMedium,
            color = if (highlight) colors.warning else colors.textSecondary,
        )
        AppTextButton(
            text = stringResource(R.string.autostart_open_settings),
            onClick = { context.openAutostartSettings() },
        )
    }
}
