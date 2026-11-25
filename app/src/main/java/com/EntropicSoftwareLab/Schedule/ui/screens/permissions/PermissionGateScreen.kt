package com.EntropicSoftwareLab.Schedule.ui.screens.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import android.os.Build
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.EntropicSoftwareLab.Schedule.R
import com.EntropicSoftwareLab.Schedule.data.calendar.SyncAdvice
import com.EntropicSoftwareLab.Schedule.ui.components.AppButton
import com.EntropicSoftwareLab.Schedule.ui.components.AppCard
import com.EntropicSoftwareLab.Schedule.ui.components.AppDivider
import com.EntropicSoftwareLab.Schedule.ui.components.AppTextButton
import com.EntropicSoftwareLab.Schedule.ui.components.AppTonalButton
import com.EntropicSoftwareLab.Schedule.ui.components.isGoogleCalendarInstalled
import com.EntropicSoftwareLab.Schedule.ui.components.openAutostartSettings
import com.EntropicSoftwareLab.Schedule.ui.components.openGoogleCalendarAppInfo
import com.EntropicSoftwareLab.Schedule.ui.components.shouldShowAutostartTip
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens

val CALENDAR_PERMISSIONS = arrayOf(
    Manifest.permission.READ_CALENDAR,
    Manifest.permission.WRITE_CALENDAR,
)

fun Context.hasCalendarPermissions(): Boolean = CALENDAR_PERMISSIONS.all {
    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
}

private const val PRIVACY_POLICY_URL = "https://entropicsoftwarelabs.com/schedule/privacy/"

/**
 * First-open onboarding flow: privacy acknowledgement (step 1) followed by required/recommended
 * permissions (step 2).
 */
@Composable
fun OnboardingScreen(
    calendarGranted: Boolean,
    onGranted: () -> Unit,
    onFinish: () -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var agreed by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = step == 1) { step = 0 }

    if (step == 0) {
        PrivacyStep(
            agreed = agreed,
            onAgreedChange = { agreed = it },
            onContinue = { step = 1 },
        )
    } else {
        PermissionsStep(
            stepLabel = stringResource(R.string.onboarding_step, 2, 2),
            calendarGranted = calendarGranted,
            onGranted = onGranted,
            finishText = stringResource(R.string.onboarding_finish),
            onFinish = onFinish,
        )
    }
}

@Composable
private fun PrivacyStep(
    agreed: Boolean,
    onAgreedChange: (Boolean) -> Unit,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 28.dp,
                    end = 28.dp,
                    top = d.spaceXxl,
                    bottom = d.spaceLg,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.onboarding_step, 1, 2),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 14.sp),
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(d.spaceLg))
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(d.radiusXl))
                    .background(colors.accentMuted),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = colors.greenText,
                    modifier = Modifier.size(38.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.onboarding_privacy_title),
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 30.sp),
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(d.spaceXs))
            Text(
                text = stringResource(R.string.onboarding_privacy_subtitle),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp),
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(d.spaceXl))

            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = d.spaceXs)) {
                    PrivacyPoint(
                        icon = Icons.Outlined.WifiOff,
                        title = stringResource(R.string.onboarding_point_offline_title),
                        body = stringResource(R.string.onboarding_point_offline_body),
                    )
                    AppDivider()
                    PrivacyPoint(
                        icon = Icons.Outlined.PhoneAndroid,
                        title = stringResource(R.string.onboarding_point_local_title),
                        body = stringResource(R.string.onboarding_point_local_body),
                    )
                    AppDivider()
                    PrivacyPoint(
                        icon = Icons.Outlined.BarChart,
                        title = stringResource(R.string.onboarding_point_nothing_title),
                        body = stringResource(R.string.onboarding_point_nothing_body),
                    )
                    AppDivider()
                    PrivacyPoint(
                        icon = Icons.Outlined.Sync,
                        title = stringResource(R.string.onboarding_point_google_title),
                        body = stringResource(R.string.onboarding_point_google_body),
                    )
                }
            }

            Spacer(Modifier.height(d.spaceLg))
            AppTextButton(
                text = stringResource(R.string.onboarding_read_policy),
                onClick = { context.openPrivacyPolicy() },
            )

        }
        AppDivider()
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = d.spaceLg),
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = d.touchTarget)
                    .toggleable(
                        value = agreed,
                        role = Role.Checkbox,
                        onValueChange = onAgreedChange,
                    ),
            ) {
                Checkbox(
                    checked = agreed,
                    onCheckedChange = null,
                )
                Spacer(Modifier.width(d.spaceMd))
                Text(
                    text = stringResource(R.string.onboarding_agree),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                    color = colors.textPrimary,
                )
            }

            Spacer(Modifier.height(d.spaceMd))
            AppButton(
                text = stringResource(R.string.onboarding_continue),
                onClick = onContinue,
                enabled = agreed,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PrivacyPoint(
    icon: ImageVector,
    title: String,
    body: String,
) {
    val colors = AppThemeTokens.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.greenText,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(24.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium),
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 21.sp),
                color = colors.textSecondary,
            )
        }
    }
}

/**
 * Permissions page: Calendar access (required), and when Google Calendar is installed, its battery
 * setting plus, on Xiaomi-family phones, its Autostart. Reused standalone when calendar access is
 * revoked after onboarding has already finished.
 */
@Composable
fun PermissionsStep(
    stepLabel: String?,
    calendarGranted: Boolean,
    onGranted: () -> Unit,
    finishText: String?,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors

    // Android silently denies once the user has picked "don't ask again"; when that happens the
    // only route left is the app's settings page.
    var permanentlyDenied by remember { mutableStateOf(false) }
    val showAutostartTip = remember { context.shouldShowAutostartTip() }
    val showBatteryTip = remember { context.isGoogleCalendarInstalled() }
    val isXiaomi = remember { SyncAdvice.isXiaomiFamily(Build.MANUFACTURER, Build.BRAND) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.isEmpty()) return@rememberLauncherForActivityResult
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

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = d.spaceXl,
                    end = d.spaceXl,
                    top = d.spaceXxl,
                    bottom = d.spaceLg,
                ),
        ) {
            if (stepLabel != null) {
                Text(
                    text = stepLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(d.spaceLg))
            }
            Text(
                text = stringResource(R.string.onboarding_permissions_title),
                style = MaterialTheme.typography.headlineSmall,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(d.spaceXs))
            Text(
                text = stringResource(R.string.onboarding_permissions_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(d.spaceXl))

            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(d.spaceLg)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.CalendarMonth,
                            contentDescription = null,
                            tint = colors.greenText,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(d.spaceSm))
                        Text(
                            text = stringResource(R.string.onboarding_perm_calendar_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(d.spaceSm))
                        PermissionBadge(
                            text = stringResource(R.string.onboarding_badge_required),
                            fg = colors.textSecondary,
                            bg = colors.textSecondary.copy(alpha = 0.12f),
                        )
                    }
                    Spacer(Modifier.height(d.spaceXs))
                    Text(
                        text = stringResource(R.string.onboarding_perm_calendar_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                    )
                    Spacer(Modifier.height(d.spaceMd))
                    when {
                        calendarGranted -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.CheckCircle,
                                    contentDescription = null,
                                    tint = colors.success,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(d.spaceSm))
                                Text(
                                    text = stringResource(R.string.perm_granted),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.success,
                                )
                            }
                        }
                        permanentlyDenied -> {
                            Text(
                                text = stringResource(R.string.perm_declined),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.warning,
                            )
                            Spacer(Modifier.height(d.spaceSm))
                            AppTonalButton(
                                text = stringResource(R.string.perm_open_settings),
                                onClick = { context.openAppSettings() },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        else -> {
                            AppTonalButton(
                                text = stringResource(R.string.perm_grant),
                                onClick = { launcher.launch(CALENDAR_PERMISSIONS) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            if (showAutostartTip) {
                Spacer(Modifier.height(d.spaceMd))
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(d.spaceLg)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.RocketLaunch,
                                contentDescription = null,
                                tint = colors.greenText,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(d.spaceSm))
                            Text(
                                text = stringResource(R.string.onboarding_perm_autostart_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.textPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(d.spaceSm))
                            PermissionBadge(
                                text = stringResource(R.string.onboarding_badge_xiaomi),
                                fg = colors.warning,
                                bg = colors.warning.copy(alpha = 0.14f),
                            )
                        }
                        Spacer(Modifier.height(d.spaceXs))
                        Text(
                            text = stringResource(R.string.perm_autostart_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                        )
                        Spacer(Modifier.height(d.spaceMd))
                        AppTonalButton(
                            text = stringResource(R.string.autostart_open_settings),
                            onClick = { context.openAutostartSettings() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            if (showBatteryTip) {
                Spacer(Modifier.height(d.spaceMd))
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(d.spaceLg)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.BatteryChargingFull,
                                contentDescription = null,
                                tint = colors.greenText,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(d.spaceSm))
                            Text(
                                text = stringResource(R.string.onboarding_perm_battery_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.textPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(d.spaceSm))
                            PermissionBadge(
                                text = stringResource(R.string.onboarding_badge_recommended),
                                fg = colors.textSecondary,
                                bg = colors.textSecondary.copy(alpha = 0.12f),
                            )
                        }
                        Spacer(Modifier.height(d.spaceXs))
                        Text(
                            text = stringResource(
                                if (isXiaomi) R.string.perm_battery_body_xiaomi else R.string.perm_battery_body,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                        )
                        Spacer(Modifier.height(d.spaceMd))
                        AppTonalButton(
                            text = stringResource(R.string.battery_open_settings),
                            onClick = { context.openGoogleCalendarAppInfo() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

        }
        if (finishText != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = d.spaceXl, vertical = d.spaceLg),
            ) {
                AppButton(
                    text = finishText,
                    onClick = onFinish,
                    enabled = calendarGranted,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun PermissionBadge(text: String, fg: Color, bg: Color) {
    val d = AppThemeTokens.dimens
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = d.spaceSm, vertical = 2.dp),
    )
}

private fun Context.openPrivacyPolicy() {
    runCatching {
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
