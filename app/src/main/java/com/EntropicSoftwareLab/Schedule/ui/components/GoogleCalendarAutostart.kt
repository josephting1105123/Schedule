package com.EntropicSoftwareLab.Schedule.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.EntropicSoftwareLab.Schedule.data.calendar.SyncAdvice

fun Context.isGoogleCalendarInstalled(): Boolean =
    runCatching { packageManager.getApplicationInfo(SyncAdvice.GOOGLE_CALENDAR_PACKAGE, 0); true }
        .getOrDefault(false)

/**
 * Google Calendar owns the calendar sync adapter, and Xiaomi's HyperOS/MIUI denies it Autostart by
 * default, so uploads wait until it happens to run. Worth saying only where that can happen.
 */
fun Context.shouldShowAutostartTip(): Boolean =
    SyncAdvice.isXiaomiFamily(Build.MANUFACTURER, Build.BRAND) && isGoogleCalendarInstalled()

/** Google Calendar's app-info page, where its battery use (and on older HyperOS, Autostart) is set. */
fun Context.openGoogleCalendarAppInfo() {
    runCatching {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", SyncAdvice.GOOGLE_CALENDAR_PACKAGE, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * HyperOS/MIUI's own Autostart list, where Google Calendar shows as "Calendar" with the blue 31
 * icon. Google Calendar's app-info page has no Autostart switch on current HyperOS (checked on a
 * Xiaomi phone, 2026-11-23), so that page is only the fallback for builds without the list.
 */
fun Context.openAutostartSettings() {
    val opened = runCatching {
        startActivity(Intent(ACTION_MIUI_AUTOSTART).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (!opened) openGoogleCalendarAppInfo()
}

private const val ACTION_MIUI_AUTOSTART = "miui.intent.action.OP_AUTO_START"
