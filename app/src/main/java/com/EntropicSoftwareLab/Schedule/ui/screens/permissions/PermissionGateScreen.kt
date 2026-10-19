package com.EntropicSoftwareLab.Schedule.ui.screens.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.EntropicSoftwareLab.Schedule.ui.components.AppButton
import com.EntropicSoftwareLab.Schedule.ui.components.AppCard
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens

val CALENDAR_PERMISSIONS = arrayOf(
    Manifest.permission.READ_CALENDAR,
    Manifest.permission.WRITE_CALENDAR,
)

fun Context.hasCalendarPermissions(): Boolean = CALENDAR_PERMISSIONS.all {
    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
}

/**
 * Shown until calendar access is granted. Explains what the app does with it before the system
 * dialog appears, and falls back to a Settings deep link once Android stops showing that dialog.
 */
@Composable
fun PermissionGateScreen(onGranted: () -> Unit) {
    val context = LocalContext.current
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors

    // Android silently denies once the user has picked "don't ask again"; when that happens the
    // only route left is the app's settings page.
    var permanentlyDenied by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            onGranted()
        } else {
            val activity = context as? Activity
            val canAskAgain = activity != null && CALENDAR_PERMISSIONS.any {
                activity.shouldShowRequestPermissionRationale(it)
            }
            permanentlyDenied = !canAskAgain
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(d.spaceLg),
        contentAlignment = Alignment.Center,
    ) {
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(d.spaceXl)) {
                Icon(
                    imageVector = Icons.Outlined.CalendarMonth,
                    contentDescription = null,
                    tint = colors.greenText,
                    modifier = Modifier.size(d.iconLg),
                )
                Spacer(Modifier.height(d.spaceLg))
                Text(
                    text = "Calendar access",
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(d.spaceSm))
                Text(
                    text = "Schedule reads the calendars already synced to this device so it can " +
                        "show your day and tell you when you're free. Events you add here sync " +
                        "back through your Google account. Nothing is sent anywhere else.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(d.spaceXl))

                if (permanentlyDenied) {
                    Text(
                        text = "Access was declined. Turn on Calendar in system settings to continue.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.warning,
                    )
                    Spacer(Modifier.height(d.spaceMd))
                    AppButton(text = "Open settings", onClick = { context.openAppSettings() })
                } else {
                    AppButton(text = "Grant access", onClick = { launcher.launch(CALENDAR_PERMISSIONS) })
                }
            }
        }
    }
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
