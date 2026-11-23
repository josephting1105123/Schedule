package com.EntropicSoftwareLab.Schedule.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.EntropicSoftwareLab.Schedule.R
import com.EntropicSoftwareLab.Schedule.data.accounts.AccountPrefs
import com.EntropicSoftwareLab.Schedule.data.accounts.CalendarFilter
import com.EntropicSoftwareLab.Schedule.data.calendar.AccountSyncStatus
import com.EntropicSoftwareLab.Schedule.data.calendar.CalendarAccount
import com.EntropicSoftwareLab.Schedule.data.calendar.DurationUnit
import com.EntropicSoftwareLab.Schedule.data.calendar.SLOW_UPLOAD_MS
import com.EntropicSoftwareLab.Schedule.data.calendar.SyncAdvice
import com.EntropicSoftwareLab.Schedule.data.calendar.SyncHeadline
import com.EntropicSoftwareLab.Schedule.data.calendar.SyncHealth
import com.EntropicSoftwareLab.Schedule.data.calendar.SyncVerdict
import com.EntropicSoftwareLab.Schedule.data.calendar.UploadTiming
import com.EntropicSoftwareLab.Schedule.data.calendar.UploadTimingEntry
import com.EntropicSoftwareLab.Schedule.data.calendar.syncHealth
import com.EntropicSoftwareLab.Schedule.ui.components.AppDivider
import com.EntropicSoftwareLab.Schedule.ui.components.AppTextButton
import com.EntropicSoftwareLab.Schedule.ui.components.AppTonalButton
import com.EntropicSoftwareLab.Schedule.ui.components.AutostartTip
import com.EntropicSoftwareLab.Schedule.ui.components.SectionCard
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

/** The verdicts that mean changes can't or won't leave the phone, and so get a way out. */
private fun SyncVerdict.needsAttention(): Boolean =
    this == SyncVerdict.PhoneSyncOff || this == SyncVerdict.CalendarSyncOff || this == SyncVerdict.SlowWaiting

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
private fun verdictTone(verdict: SyncVerdict): Color {
    val colors = AppThemeTokens.colors
    return when (verdict) {
        SyncVerdict.PhoneSyncOff, SyncVerdict.CalendarSyncOff, SyncVerdict.SlowWaiting -> colors.warning
        SyncVerdict.UpToDate -> colors.success
        SyncVerdict.Syncing, SyncVerdict.Waiting -> colors.greenText
        SyncVerdict.NoAccounts, SyncVerdict.Unknown -> colors.textSecondary
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

private class StatusWord(val text: String, val color: Color)

/** What the phone says about one account's upload queue, worked out once for the header and the row. */
private class AccountUpload(
    val headline: SyncHeadline,
    val rows: Int,
    /** How long the oldest waiting change has been waiting; null with nothing waiting or no clock. */
    val elapsedMs: Long?,
) {
    val slow: Boolean get() = elapsedMs != null && elapsedMs > SLOW_UPLOAD_MS
}

private fun accountUpload(account: AccountSyncStatus, timing: UploadTimingEntry?, nowMs: Long): AccountUpload {
    val rows = account.pendingUploads ?: 0
    val elapsed = if (rows > 0) timing?.pendingSinceMs?.let { (nowMs - it).coerceAtLeast(0L) } else null
    return AccountUpload(SyncAdvice.headline(account), rows, elapsed)
}

/** One account's status in a word, from the same headline the Uploads row spells out. */
@Composable
private fun accountStatusWord(upload: AccountUpload): StatusWord {
    val colors = AppThemeTokens.colors
    val waiting = StatusWord(
        stringResource(R.string.settings_sync_status_waiting, upload.rows),
        if (upload.slow) colors.warning else colors.greenText,
    )
    return when (upload.headline) {
        SyncHeadline.PhoneSyncOff, SyncHeadline.CalendarSyncOff ->
            StatusWord(stringResource(R.string.settings_sync_status_off), colors.warning)
        SyncHeadline.Uploading -> StatusWord(
            stringResource(R.string.settings_sync_status_uploading),
            if (upload.slow) colors.warning else colors.greenText,
        )
        SyncHeadline.Waiting, SyncHeadline.WaitingQueued, SyncHeadline.WaitingNotQueued -> waiting
        SyncHeadline.Checking -> StatusWord(stringResource(R.string.settings_sync_status_syncing), colors.greenText)
        // An account the app can't ask by name is still up to date; the reach row says what's off.
        SyncHeadline.UpToDate -> StatusWord(stringResource(R.string.settings_sync_status_up_to_date), colors.success)
        SyncHeadline.Unknown ->
            StatusWord(stringResource(R.string.settings_sync_check_unknown), colors.textSecondary)
    }
}

/** The headline as a sentence: what the Uploads row says. */
@Composable
private fun headlineText(headline: SyncHeadline, rows: Int): String = when (headline) {
    SyncHeadline.PhoneSyncOff -> stringResource(R.string.settings_sync_state_phone_off)
    SyncHeadline.CalendarSyncOff -> stringResource(R.string.settings_sync_state_calendar_off)
    SyncHeadline.Uploading -> pluralStringResource(R.plurals.settings_sync_state_uploading, rows, rows)
    SyncHeadline.Waiting -> pluralStringResource(R.plurals.settings_sync_state_waiting, rows, rows)
    SyncHeadline.WaitingQueued -> pluralStringResource(R.plurals.settings_sync_state_waiting_queued, rows, rows)
    SyncHeadline.WaitingNotQueued -> pluralStringResource(R.plurals.settings_sync_state_waiting_not_queued, rows, rows)
    SyncHeadline.Checking -> stringResource(R.string.settings_sync_state_checking)
    SyncHeadline.UpToDate -> stringResource(R.string.settings_sync_state_up_to_date)
    SyncHeadline.Unknown -> stringResource(R.string.settings_sync_state_unknown)
}

private enum class CheckState { Ok, Fail, Unknown, Active }

/**
 * The "Sync with Google" card. Folded, it is the verdict plus Sync now; open, it is the checklist:
 * phone auto-sync once, then each account with its own switches, queue and last upload, and a fix
 * button beside whatever is off.
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
    SectionCard(
        title = stringResource(R.string.settings_sync_title),
        modifier = modifier,
        icon = Icons.Outlined.Sync,
        iconTint = if (verdict.needsAttention()) colors.warning else colors.greenText,
        trailing = if (hasAccounts) {
            {
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
        } else {
            null
        },
    ) {
        if (hasAccounts) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(verdictTone(verdict)))
                Spacer(Modifier.size(d.spaceSm))
                Text(
                    text = verdictText(health, inputs.prefs),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Text(
                text = verdictText(health, inputs.prefs),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textSecondary,
            )
        }

        AnimatedVisibility(visible = !expanded) {
            Row(
                modifier = Modifier.padding(top = d.spaceSm),
                horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppTextButton(text = stringResource(R.string.settings_sync_now), onClick = inputs.onSyncNow)
                if (verdict.needsAttention()) {
                    AppTextButton(
                        text = stringResource(R.string.settings_phone_sync_settings),
                        onClick = inputs.onOpenPhoneSyncSettings,
                    )
                }
            }
        }

        AnimatedVisibility(visible = expanded) {
            SyncDetails(inputs, snapshot)
        }
    }
}

/** The open card below the verdict line. */
@Composable
private fun SyncDetails(inputs: SyncCardInputs, snapshot: SyncSnapshot) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    Column {
        Spacer(Modifier.height(d.spaceSm))
        AppDivider()
        val phoneOff = inputs.statuses.any { it.masterAutoSync == false }
        val phoneUnknown = inputs.statuses.all { it.masterAutoSync == null }
        CheckRow(
            state = when {
                phoneOff -> CheckState.Fail
                phoneUnknown -> CheckState.Unknown
                else -> CheckState.Ok
            },
            label = stringResource(R.string.settings_sync_check_phone),
            value = when {
                phoneOff -> null
                phoneUnknown -> stringResource(R.string.settings_sync_check_unknown)
                else -> stringResource(R.string.settings_sync_check_on)
            },
            fixText = if (phoneOff) stringResource(R.string.settings_sync_turn_on) else null,
            onFix = inputs.onOpenPhoneSyncSettings,
        )

        inputs.statuses.forEach { account ->
            Spacer(Modifier.height(d.spaceMd))
            AppDivider()
            AccountGroup(account, inputs, snapshot.nowMs)
        }

        // Only on Xiaomi-family phones; it draws nothing elsewhere.
        AutostartTip(
            highlight = snapshot.health.stuckCount > 0,
            modifier = Modifier.padding(top = d.spaceMd),
        )

        if (snapshot.health.verdict == SyncVerdict.SlowWaiting) {
            Spacer(Modifier.height(d.spaceMd))
            Text(
                text = stringResource(R.string.settings_sync_fix_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
        Spacer(Modifier.height(d.spaceMd))
        Text(
            text = stringResource(R.string.settings_sync_times_caveat),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(d.spaceSm))
        Row(horizontalArrangement = Arrangement.spacedBy(d.spaceSm), verticalAlignment = Alignment.CenterVertically) {
            AppTonalButton(text = stringResource(R.string.settings_sync_now), onClick = inputs.onSyncNow)
            AppTextButton(
                text = stringResource(R.string.settings_phone_sync_settings),
                onClick = inputs.onOpenPhoneSyncSettings,
            )
        }
    }
}

/** One account: its name and status word, then the checks that apply to it. */
@Composable
private fun AccountGroup(account: AccountSyncStatus, inputs: SyncCardInputs, nowMs: Long) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    val timing = inputs.timings[CalendarAccount(account.accountName, account.accountType)]
    val label = CalendarFilter.accountLabel(account.accountName, inputs.prefs)
    val upload = accountUpload(account, timing, nowMs)
    val status = accountStatusWord(upload)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
            if (label != account.accountName) {
                Text(
                    text = account.accountName,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
        Text(text = status.text, style = MaterialTheme.typography.labelMedium, color = status.color)
    }

    AppDivider()
    CheckRow(
        state = when (account.autoSync) {
            true -> CheckState.Ok
            false -> CheckState.Fail
            null -> CheckState.Unknown
        },
        label = stringResource(R.string.settings_sync_check_calendar_plain),
        value = when (account.autoSync) {
            true -> stringResource(R.string.settings_sync_check_on)
            false -> null
            null -> stringResource(R.string.settings_sync_check_unknown)
        },
        fixText = if (account.autoSync == false) stringResource(R.string.settings_sync_turn_on) else null,
        onFix = inputs.onOpenPhoneSyncSettings,
    )

    if (!account.visible) {
        AppDivider()
        CheckRow(
            state = CheckState.Fail,
            label = stringResource(R.string.settings_sync_check_reach_plain),
            fixText = stringResource(R.string.settings_sync_allow),
            onFix = { inputs.onLetSyncDirectly(account) },
        )
        Text(
            text = stringResource(R.string.settings_not_visible),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.padding(start = 20.dp + d.spaceMd, bottom = d.spaceSm),
        )
    }

    AppDivider()
    val sentence = headlineText(upload.headline, upload.rows)
    val elapsed = upload.elapsedMs
    val uploadsState = when (upload.headline) {
        SyncHeadline.PhoneSyncOff, SyncHeadline.CalendarSyncOff -> CheckState.Fail
        SyncHeadline.Unknown -> CheckState.Unknown
        SyncHeadline.UpToDate -> CheckState.Ok
        SyncHeadline.Checking -> CheckState.Active
        SyncHeadline.Uploading, SyncHeadline.Waiting, SyncHeadline.WaitingQueued, SyncHeadline.WaitingNotQueued ->
            if (upload.slow) CheckState.Fail else CheckState.Active
    }
    CheckRow(
        state = uploadsState,
        label = stringResource(R.string.settings_sync_check_uploads),
        value = if (elapsed != null) {
            stringResource(R.string.settings_sync_text_elapsed, sentence, uploadDuration(elapsed))
        } else {
            sentence
        },
        valueColor = when (uploadsState) {
            CheckState.Fail -> colors.warning
            CheckState.Active -> colors.greenText
            else -> null
        },
    )

    val took = timing?.lastDurationMs
    if (took != null) {
        AppDivider()
        val slow = took > SLOW_UPLOAD_MS
        val finishedAt = timing.lastFinishedAtMs
        CheckRow(
            // Never a failure icon: a slow past upload isn't something to fix, only worth noticing.
            state = CheckState.Ok,
            label = stringResource(R.string.settings_sync_check_last_upload),
            value = if (finishedAt != null) {
                stringResource(
                    R.string.settings_sync_last_took_ago,
                    uploadDuration(took),
                    uploadDuration((nowMs - finishedAt).coerceAtLeast(0L)),
                )
            } else {
                stringResource(R.string.settings_sync_last_took, uploadDuration(took))
            },
            valueColor = if (slow) colors.warning else null,
        )
    }

    val stuck = account.notUploadable ?: 0
    if (stuck > 0) {
        AppDivider()
        CheckRow(
            state = CheckState.Unknown,
            label = stringResource(R.string.settings_sync_check_not_uploadable),
            value = stuck.toString(),
        )
        Text(
            text = pluralStringResource(R.plurals.settings_not_uploadable, stuck, stuck),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            modifier = Modifier.padding(start = 20.dp + d.spaceMd, bottom = d.spaceSm),
        )
    }
}

@Composable
private fun CheckRow(
    state: CheckState,
    label: String,
    value: String? = null,
    valueColor: Color? = null,
    fixText: String? = null,
    onFix: () -> Unit = {},
) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    val icon: ImageVector
    val tint: Color
    val stateDescription: Int
    when (state) {
        CheckState.Ok -> {
            icon = Icons.Outlined.CheckCircle; tint = colors.success
            stateDescription = R.string.settings_sync_a11y_ok
        }
        CheckState.Fail -> {
            icon = Icons.Outlined.ErrorOutline; tint = colors.warning
            stateDescription = R.string.settings_sync_a11y_attention
        }
        CheckState.Unknown -> {
            icon = Icons.Outlined.RadioButtonUnchecked; tint = colors.textSecondary
            stateDescription = R.string.settings_sync_check_unknown
        }
        CheckState.Active -> {
            icon = Icons.Outlined.CloudUpload; tint = colors.greenText
            stateDescription = R.string.settings_sync_a11y_active
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(stateDescription),
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(d.spaceMd))
        // The label keeps its natural width (it only wraps between words); the value takes what is
        // left, so a long headline sentence wraps there instead of squeezing the label.
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textPrimary,
            modifier = if (fixText != null) Modifier.weight(1f) else Modifier,
        )
        if (fixText != null) {
            AppTextButton(text = fixText, onClick = onFix)
        } else if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor ?: colors.textSecondary,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f).padding(start = d.spaceSm, top = d.spaceXs, bottom = d.spaceXs),
            )
        }
    }
}
