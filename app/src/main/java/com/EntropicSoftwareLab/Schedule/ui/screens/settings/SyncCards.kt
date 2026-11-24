package com.EntropicSoftwareLab.Schedule.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.Schedule.R
import com.EntropicSoftwareLab.Schedule.data.accounts.AccountPrefs
import com.EntropicSoftwareLab.Schedule.data.accounts.CalendarFilter
import com.EntropicSoftwareLab.Schedule.data.calendar.AccountSyncStatus
import com.EntropicSoftwareLab.Schedule.data.calendar.CalendarAccount
import com.EntropicSoftwareLab.Schedule.data.calendar.DurationUnit
import com.EntropicSoftwareLab.Schedule.data.calendar.SLOW_UPLOAD_MS
import com.EntropicSoftwareLab.Schedule.data.calendar.SyncHealth
import com.EntropicSoftwareLab.Schedule.data.calendar.SyncVerdict
import com.EntropicSoftwareLab.Schedule.data.calendar.UploadTiming
import com.EntropicSoftwareLab.Schedule.data.calendar.UploadTimingEntry
import com.EntropicSoftwareLab.Schedule.data.calendar.syncHealth
import com.EntropicSoftwareLab.Schedule.ui.components.AppCard
import com.EntropicSoftwareLab.Schedule.ui.components.AppTintedButton
import com.EntropicSoftwareLab.Schedule.ui.components.AppTonalButton
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens
import kotlinx.coroutines.delay

/** Everything the sync card needs. */
internal class SyncCardInputs(
    val statuses: List<AccountSyncStatus>,
    val timings: Map<CalendarAccount, UploadTimingEntry>,
    val prefs: AccountPrefs,
    val onSyncNow: () -> Unit,
    val onOpenPhoneSyncSettings: () -> Unit,
    val onLetSyncDirectly: (AccountSyncStatus) -> Unit,
)

/** The derived health plus the clock reading it was derived at. */
private class SyncSnapshot(val health: SyncHealth, val nowMs: Long)

/**
 * Derives the health, ticking once a second while the card is [open] (so "... ago" keeps moving) or
 * an upload clock is running. Otherwise the time doesn't matter, so it is read once and no loop runs.
 */
@Composable
private fun rememberSyncSnapshot(inputs: SyncCardInputs, open: Boolean): SyncSnapshot {
    val ticking = open || inputs.statuses.any {
        inputs.timings[CalendarAccount(it.accountName, it.accountType)]?.pendingSinceMs != null
    }
    val nowMs by produceState(System.currentTimeMillis(), ticking) {
        value = System.currentTimeMillis()
        while (ticking) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }
    val health = remember(inputs.statuses, inputs.timings, nowMs) {
        syncHealth(inputs.statuses, inputs.timings, nowMs)
    }
    return SyncSnapshot(health, nowMs)
}

/** [ms] as "12 s", "3 min 4 s" or "2 h 5 min", from string resources so the units can be translated. */
@Composable
private fun uploadDuration(ms: Long): String {
    val parts = UploadTiming.durationParts(ms)
    return when (parts.unit) {
        DurationUnit.Seconds -> stringResource(R.string.settings_duration_seconds, parts.first)
        DurationUnit.MinutesSeconds -> stringResource(R.string.settings_duration_min_sec, parts.first, parts.second)
        DurationUnit.HoursMinutes -> stringResource(R.string.settings_duration_hr_min, parts.first, parts.second)
    }
}

@Composable
private fun verdictText(health: SyncHealth, prefs: AccountPrefs): String = when (health.verdict) {
    SyncVerdict.NoAccounts -> stringResource(R.string.settings_no_synced_accounts_long)
    SyncVerdict.PhoneSyncOff -> stringResource(R.string.settings_sync_verdict_phone_off)
    SyncVerdict.CalendarSyncOff -> stringResource(
        R.string.settings_sync_verdict_calendar_off,
        health.calendarSyncOff.firstOrNull()?.let { CalendarFilter.accountLabel(it.accountName, prefs) }.orEmpty(),
    )
    SyncVerdict.SlowWaiting -> pluralStringResource(
        R.plurals.settings_sync_verdict_slow,
        health.stuckCount,
        health.stuckCount,
        uploadDuration(health.oldestPendingMs ?: 0L),
    )
    SyncVerdict.Syncing ->
        if (health.waiting > 0) {
            pluralStringResource(R.plurals.settings_sync_verdict_waiting, health.waiting, health.waiting)
        } else {
            stringResource(R.string.settings_syncing_now)
        }
    SyncVerdict.Waiting -> pluralStringResource(R.plurals.settings_sync_verdict_waiting, health.waiting, health.waiting)
    SyncVerdict.Unknown -> stringResource(R.string.settings_sync_state_unknown)
    SyncVerdict.UpToDate -> stringResource(R.string.settings_sync_verdict_up_to_date)
}

/** What the phone says about one account's upload queue. */
private class AccountUpload(
    val rows: Int,
    /** How long the oldest waiting change has been waiting; null with nothing waiting or no clock. */
    val elapsedMs: Long?,
) {
    val slow: Boolean get() = elapsedMs != null && elapsedMs > SLOW_UPLOAD_MS
}

private fun accountUpload(account: AccountSyncStatus, timing: UploadTimingEntry?, nowMs: Long): AccountUpload {
    val rows = account.pendingUploads ?: 0
    val elapsed = if (rows > 0) timing?.pendingSinceMs?.let { (nowMs - it).coerceAtLeast(0L) } else null
    return AccountUpload(rows, elapsed)
}

/**
 * The "Calendar sync" card. Folded, it is a single header row showing a verdict icon, title and
 * verdict subtitle, a Sync now icon button, and an expand chevron (when accounts exist). Open, it
 * reveals one bordered card per account with its sync chip, status lines, and at most one fix
 * button, followed by the battery-saving hint when uploads are stuck.
 */
@Composable
internal fun SyncCard(inputs: SyncCardInputs, open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    val snapshot = rememberSyncSnapshot(inputs, open)
    val health = snapshot.health
    val verdict = health.verdict
    val hasAccounts = verdict != SyncVerdict.NoAccounts
    val expanded = open && hasAccounts

    val stateIcon: ImageVector
    val stateTint: Color
    when (verdict) {
        SyncVerdict.UpToDate -> {
            stateIcon = Icons.Outlined.CloudDone
            stateTint = colors.success
        }
        SyncVerdict.Syncing, SyncVerdict.Waiting -> {
            stateIcon = Icons.Outlined.CloudUpload
            stateTint = colors.greenText
        }
        SyncVerdict.SlowWaiting -> {
            stateIcon = Icons.Outlined.CloudUpload
            stateTint = colors.warning
        }
        SyncVerdict.PhoneSyncOff, SyncVerdict.CalendarSyncOff -> {
            stateIcon = Icons.Outlined.CloudOff
            stateTint = colors.warning
        }
        SyncVerdict.NoAccounts, SyncVerdict.Unknown -> {
            stateIcon = Icons.Outlined.Cloud
            stateTint = colors.textSecondary
        }
    }

    AppCard(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = d.spaceLg, vertical = d.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = stateIcon,
                contentDescription = null,
                tint = stateTint,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.size(d.spaceMd))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_sync_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )
                Text(
                    text = verdictText(health, inputs.prefs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
            }
            Spacer(Modifier.size(d.spaceSm))
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(d.radiusMd))
                    .background(colors.accentMuted)
                    .semantics { role = Role.Button }
                    .clickable(onClick = inputs.onSyncNow),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Sync,
                    contentDescription = stringResource(R.string.settings_sync_now),
                    tint = colors.greenText,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (hasAccounts) {
                IconButton(onClick = onToggle) {
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = stringResource(
                            if (expanded) R.string.settings_hide_sync_details else R.string.settings_show_sync_details,
                        ),
                        tint = colors.textSecondary,
                    )
                }
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(start = d.spaceLg, end = d.spaceLg, bottom = d.spaceLg),
                verticalArrangement = Arrangement.spacedBy(d.spaceSm),
            ) {
                inputs.statuses.forEach { account ->
                    AccountCard(account, inputs, snapshot.nowMs)
                }
                if (health.verdict == SyncVerdict.SlowWaiting) {
                    Text(
                        text = stringResource(R.string.settings_sync_fix_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountCard(account: AccountSyncStatus, inputs: SyncCardInputs, nowMs: Long) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    val timing = inputs.timings[CalendarAccount(account.accountName, account.accountType)]
    val label = CalendarFilter.accountLabel(account.accountName, inputs.prefs)
    val upload = accountUpload(account, timing, nowMs)
    val syncOff = account.masterAutoSync == false || account.autoSync == false
    val finishedAt = timing?.lastFinishedAtMs
    val stuck = account.notUploadable ?: 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(d.hairlineWidth, colors.hairline, RoundedCornerShape(d.radiusMd))
            .padding(horizontal = d.spaceMd, vertical = d.spaceSm),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            SyncChip(account)
        }
        Text(
            text = account.accountName,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )

        if (upload.rows > 0 || finishedAt != null || stuck > 0) {
            Column(
                modifier = Modifier.padding(top = d.spaceXs),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (upload.rows > 0) {
                    val base = pluralStringResource(R.plurals.settings_sync_waiting_line, upload.rows, upload.rows)
                    val text = upload.elapsedMs?.let {
                        stringResource(R.string.settings_sync_text_elapsed, base, uploadDuration(it))
                    } ?: base
                    AccountInfoLine(
                        icon = Icons.Outlined.CloudUpload,
                        text = text,
                        tint = if (upload.slow) colors.warning else colors.greenText,
                    )
                }
                if (finishedAt != null) {
                    AccountInfoLine(
                        icon = Icons.Outlined.History,
                        text = stringResource(
                            R.string.settings_sync_last_upload_ago,
                            uploadDuration((nowMs - finishedAt).coerceAtLeast(0L)),
                        ),
                        tint = colors.textSecondary,
                    )
                }
                if (stuck > 0) {
                    AccountInfoLine(
                        icon = Icons.Outlined.Block,
                        text = pluralStringResource(R.plurals.settings_not_uploadable, stuck, stuck),
                        tint = colors.textSecondary,
                    )
                }
            }
        }

        if (syncOff) {
            AppTintedButton(
                text = stringResource(R.string.settings_sync_turn_on),
                onClick = inputs.onOpenPhoneSyncSettings,
                tint = colors.warning,
                modifier = Modifier.fillMaxWidth().padding(top = d.spaceSm),
            )
        } else if (!account.visible) {
            AppTonalButton(
                text = stringResource(R.string.settings_sync_allow_faster),
                onClick = { inputs.onLetSyncDirectly(account) },
                modifier = Modifier.fillMaxWidth().padding(top = d.spaceSm),
            )
        }
    }
}

@Composable
private fun AccountInfoLine(icon: ImageVector, text: String, tint: Color) {
    val d = AppThemeTokens.dimens
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.size(d.spaceXs))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = tint,
        )
    }
}

@Composable
private fun SyncChip(account: AccountSyncStatus) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    val label: String
    val tint: Color
    val bg: Color
    when {
        account.masterAutoSync == false || account.autoSync == false -> {
            label = stringResource(R.string.settings_sync_chip_off)
            tint = colors.warning
            bg = colors.warning.copy(alpha = 0.14f)
        }
        account.autoSync == true -> {
            label = stringResource(R.string.settings_sync_chip_on)
            tint = colors.success
            bg = colors.success.copy(alpha = 0.14f)
        }
        else -> {
            label = stringResource(R.string.settings_sync_chip_unknown)
            tint = colors.textSecondary
            bg = colors.textSecondary.copy(alpha = 0.12f)
        }
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = tint,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = d.spaceSm, vertical = 2.dp),
    )
}
