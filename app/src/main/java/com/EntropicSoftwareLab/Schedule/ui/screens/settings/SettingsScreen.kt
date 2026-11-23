package com.EntropicSoftwareLab.Schedule.ui.screens.settings

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Intent
import android.provider.CalendarContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import com.EntropicSoftwareLab.Schedule.R
import com.EntropicSoftwareLab.Schedule.data.accounts.AccountPrefs
import com.EntropicSoftwareLab.Schedule.data.accounts.AddedAccount
import com.EntropicSoftwareLab.Schedule.data.accounts.CalendarFilter
import com.EntropicSoftwareLab.Schedule.data.calendar.AccountSyncStatus
import com.EntropicSoftwareLab.Schedule.data.calendar.CalendarAccount
import com.EntropicSoftwareLab.Schedule.data.calendar.DurationUnit
import com.EntropicSoftwareLab.Schedule.data.calendar.SyncAdvice
import com.EntropicSoftwareLab.Schedule.data.calendar.SyncHeadline
import com.EntropicSoftwareLab.Schedule.data.calendar.UploadTiming
import com.EntropicSoftwareLab.Schedule.data.calendar.UploadTimingEntry
import com.EntropicSoftwareLab.Schedule.di.LocalAppContainer
import com.EntropicSoftwareLab.Schedule.ui.components.AppDivider
import com.EntropicSoftwareLab.Schedule.ui.components.AutostartTip
import com.EntropicSoftwareLab.Schedule.ui.components.CalendarPickerMenu
import com.EntropicSoftwareLab.Schedule.ui.components.asCalendarColor
import com.EntropicSoftwareLab.Schedule.ui.components.calendarLabel
import com.EntropicSoftwareLab.Schedule.ui.components.AppTextButton
import com.EntropicSoftwareLab.Schedule.ui.components.ScreenHeader
import com.EntropicSoftwareLab.Schedule.ui.components.SectionCard
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors

    var showCalendarMenu by remember { mutableStateOf(false) }
    var syncOpen by rememberSaveable { mutableStateOf(false) }
    /** The account whose name is being edited, or null. */
    var renaming by remember { mutableStateOf<AddedAccount?>(null) }
    var addingAccount by remember { mutableStateOf(false) }

    // Android's own account chooser, which is the only thing that makes an account visible to this
    // app for good; the card re-reads when it closes, whichever way.
    val context = LocalContext.current
    val chooserLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.refreshSyncStatus()
    }
    val launchChooser: (String, String) -> Unit = { name, type ->
        runCatching { chooserLauncher.launch(chooseAccountIntent(name, type)) }
    }

    // Re-reads the card every few seconds while it is open, so it never shows a stale answer.
    // Only while the app is on screen: the poll stops in the background and resumes on return.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(syncOpen, lifecycleOwner) {
        if (syncOpen) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.watchSyncStatus() }
        }
    }
    // One clock for the card, so "... ago" and the Autostart highlight keep moving while it is open.
    val now by produceState(System.currentTimeMillis(), syncOpen) {
        while (syncOpen) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = d.spaceLg, end = d.spaceLg, top = d.spaceXxl, bottom = d.spaceHuge,
        ),
        verticalArrangement = Arrangement.spacedBy(d.spaceLg),
    ) {
        item { ScreenHeader(title = stringResource(R.string.settings_title)) }

        item {
            SectionCard(title = stringResource(R.string.settings_accounts), icon = Icons.Outlined.People) {
                if (state.prefs.added.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_accounts_all_shown),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                    )
                }
                state.prefs.added.forEach { account ->
                    val dot = CalendarFilter
                        .primaryOf(state.allCalendars.filter { it.accountName == account.name })
                        ?.color
                    AccountRow(
                        account = account,
                        dotColor = dot,
                        onClick = { renaming = account },
                    )
                }
                AppTextButton(text = stringResource(R.string.settings_add_account), onClick = { addingAccount = true })
                if (state.prefs.hiddenBirthdayKeys.isNotEmpty()) {
                    val n = state.prefs.hiddenBirthdayKeys.size
                    Spacer(Modifier.height(d.spaceSm))
                    AppDivider()
                    Spacer(Modifier.height(d.spaceSm))
                    Text(
                        text = pluralStringResource(R.plurals.settings_hidden_birthdays, n, n),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                    )
                    AppTextButton(text = stringResource(R.string.settings_show_them_again), onClick = vm::showHiddenBirthdays)
                }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.settings_calendar), icon = Icons.Outlined.CalendarMonth) {
                Box {
                    SettingRow(
                        label = stringResource(R.string.settings_default_calendar),
                        value = if (state.calendars.isEmpty()) stringResource(R.string.settings_none_available)
                        else calendarLabel(state.selectedCalendar, state.calendars, state.prefs),
                        onClick = { showCalendarMenu = true },
                    )
                    CalendarPickerMenu(
                        expanded = showCalendarMenu,
                        calendars = state.calendars,
                        prefs = state.prefs,
                        selectedId = state.selectedCalendarId,
                        onDismiss = { showCalendarMenu = false },
                        onPick = { vm.setCalendar(it.id); showCalendarMenu = false },
                    )
                }
                Text(
                    text = stringResource(R.string.settings_new_events_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }

        item {
            SectionCard(
                title = stringResource(R.string.settings_sync_title),
                icon = Icons.Outlined.Sync,
                trailing = {
                    IconButton(onClick = { syncOpen = !syncOpen }) {
                        Icon(
                            imageVector = if (syncOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = if (syncOpen) stringResource(R.string.settings_hide_sync_details) else stringResource(R.string.settings_show_sync_details),
                            tint = colors.textSecondary,
                        )
                    }
                },
            ) {
                if (!syncOpen) {
                    val waiting = state.syncStatus.sumOf { it.pendingUploads ?: 0 }
                    val accounts = state.syncStatus.size
                    Text(
                        text = when {
                            accounts == 0 -> stringResource(R.string.settings_no_synced_accounts)
                            waiting > 0 -> stringResource(R.string.settings_sync_summary_waiting, accountCount(accounts), waiting)
                            else -> stringResource(R.string.settings_sync_summary_idle, accountCount(accounts))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (waiting > 0) colors.warning else colors.textSecondary,
                    )
                    AppTextButton(text = stringResource(R.string.settings_sync_now), onClick = vm::syncNow)
                }
                AnimatedVisibility(visible = syncOpen) {
                    Column {
                        if (state.syncStatus.isEmpty()) {
                            Text(
                                text = stringResource(R.string.settings_no_synced_accounts_long),
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.textSecondary,
                            )
                        }
                        state.syncStatus.forEach { account ->
                            SyncAccountRows(
                                account = account,
                                prefs = state.prefs,
                                timing = state.uploadTimings[CalendarAccount(account.accountName, account.accountType)],
                                now = now,
                                onLetSyncDirectly = { launchChooser(account.accountName, account.accountType) },
                            )
                            Spacer(Modifier.height(d.spaceSm))
                        }
                        AutostartTip(
                            highlight = state.uploadTimings.values.any {
                                val since = it.pendingSinceMs
                                since != null && now - since > SLOW_UPLOAD_MS
                            },
                        )
                        Text(
                            text = stringResource(R.string.settings_sync_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(d.spaceSm)) {
                            AppTextButton(text = stringResource(R.string.settings_sync_now), onClick = vm::syncNow)
                            AppTextButton(
                                text = stringResource(R.string.settings_phone_sync_settings),
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_SYNC_SETTINGS)
                                                .putExtra(Settings.EXTRA_AUTHORITIES, arrayOf(CalendarContract.AUTHORITY))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.settings_about)) {
                Text(
                    text = stringResource(R.string.settings_about_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textSecondary,
                )
                // Which build this is, including the commit CI stamps on — the version name
                // alone stays the same across many builds.
                val context = LocalContext.current
                val version = remember {
                    runCatching {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull()
                }
                if (version != null) {
                    Text(
                        text = stringResource(R.string.settings_version, version),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                    )
                }
            }
        }
    }

    renaming?.let { account ->
        RenameAccountDialog(
            account = account,
            onDismiss = { renaming = null },
            onSave = { vm.rename(account.name, it); renaming = null },
            onRemove = { vm.removeAccount(account.name); renaming = null },
        )
    }

    if (addingAccount) {
        val addable = state.phoneAccounts.filter { phone -> state.prefs.added.none { it.name == phone.name } }
        AddAccountDialog(
            addable = addable,
            onDismiss = { addingAccount = false },
            onPick = {
                vm.addAccount(it)
                addingAccount = false
                // Only for an account the app can't see yet; the chooser would add nothing otherwise.
                val seen = runCatching {
                    Account(it.name, it.type) in AccountManager.get(context).accounts
                }.getOrDefault(false)
                if (it.type == GOOGLE_ACCOUNT_TYPE && !seen) launchChooser(it.name, it.type)
            },
            onSignIn = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_ADD_ACCOUNT)
                            .putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )
    }
}

@Composable
private fun SettingRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(d.touchTarget)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).padding(start = d.spaceMd),
        )
    }
}

private const val GOOGLE_ACCOUNT_TYPE = "com.google"

/** The chooser's own screen for [name], so choosing it makes the account visible to this app. */
private fun chooseAccountIntent(name: String, type: String): Intent =
    AccountManager.newChooseAccountIntent(
        Account(name, type),
        arrayListOf(Account(name, type)),
        arrayOf(type),
        null,
        null,
        null,
        null,
    )

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

/** An upload slower than this is flagged as a warning on the card. */
private const val SLOW_UPLOAD_MS = 30_000L

/** One account's lines in the sync card: its name, then each thing the phone would say about it. */
@Composable
private fun SyncAccountRows(
    account: AccountSyncStatus,
    prefs: AccountPrefs,
    timing: UploadTimingEntry?,
    now: Long,
    onLetSyncDirectly: () -> Unit,
) {
    val colors = AppThemeTokens.colors
    val title = CalendarFilter.accountLabel(account.accountName, prefs)
    Text(
        text = title,
        style = MaterialTheme.typography.bodyLarge,
        color = colors.textPrimary,
    )
    if (title != account.accountName) {
        Text(
            text = account.accountName,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
    }

    val rows = account.pendingUploads ?: 0
    val headline = SyncAdvice.headline(account)
    val headlineText = when (headline) {
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
    val headlineColor = when (headline) {
        SyncHeadline.PhoneSyncOff, SyncHeadline.CalendarSyncOff, SyncHeadline.Waiting,
        SyncHeadline.WaitingQueued, SyncHeadline.WaitingNotQueued -> colors.warning
        SyncHeadline.UpToDate -> colors.success
        SyncHeadline.Uploading, SyncHeadline.Checking, SyncHeadline.Unknown -> colors.textSecondary
    }
    Text(text = headlineText, style = MaterialTheme.typography.bodyMedium, color = headlineColor)

    val pendingSince = timing?.pendingSinceMs
    if (pendingSince != null && rows > 0) {
        // Ticks once a second while the upload is outstanding, and only then.
        val elapsed by produceState(
            initialValue = (System.currentTimeMillis() - pendingSince).coerceAtLeast(0L),
            key1 = pendingSince,
        ) {
            while (true) {
                value = (System.currentTimeMillis() - pendingSince).coerceAtLeast(0L)
                delay(1_000)
            }
        }
        Text(
            text = stringResource(R.string.settings_upload_waiting_for, uploadDuration(elapsed)),
            style = MaterialTheme.typography.bodyMedium,
            color = if (elapsed > SLOW_UPLOAD_MS) colors.warning else colors.textSecondary,
        )
    }

    val took = timing?.lastDurationMs
    val finishedAt = timing?.lastFinishedAtMs
    if (took != null) {
        Text(
            text = if (finishedAt != null) {
                val ago = (now - finishedAt).coerceAtLeast(0L)
                stringResource(R.string.settings_upload_last_took_ago, uploadDuration(took), uploadDuration(ago))
            } else {
                stringResource(R.string.settings_upload_last_took, uploadDuration(took))
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (took > SLOW_UPLOAD_MS) colors.warning else colors.textSecondary,
        )
    }

    val stuck = account.notUploadable ?: 0
    if (stuck > 0) {
        Text(
            text = pluralStringResource(R.plurals.settings_not_uploadable, stuck, stuck),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
    }

    if (!account.visible) {
        Text(
            text = stringResource(R.string.settings_not_visible),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
        AppTextButton(text = stringResource(R.string.settings_let_sync_directly), onClick = onLetSyncDirectly)
    }
}

@Composable
private fun accountCount(n: Int): String = pluralStringResource(R.plurals.settings_account_count, n, n)

/** An added account: its nickname over its email, with a dot in its main calendar's colour. */
@Composable
private fun AccountRow(account: AddedAccount, dotColor: Int?, onClick: () -> Unit) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = d.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor?.asCalendarColor(colors.textSecondary) ?: colors.divider),
        )
        Spacer(Modifier.size(d.spaceMd))
        Column(Modifier.weight(1f)) {
            val named = account.nickname.isNotBlank()
            Text(
                text = if (named) account.nickname else stringResource(R.string.settings_add_a_name),
                style = MaterialTheme.typography.bodyLarge,
                color = if (named) colors.textPrimary else colors.textSecondary,
            )
            Text(
                text = account.name,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun RenameAccountDialog(
    account: AddedAccount,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = AppThemeTokens.colors
    var text by remember(account.name) { mutableStateOf(account.nickname) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        title = { Text(stringResource(R.string.settings_name_account), color = colors.textPrimary) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                supportingText = { Text(account.name) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Row {
                TextButton(onClick = onRemove) { Text(stringResource(R.string.settings_remove), color = colors.error) }
                TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.settings_save), color = colors.greenText) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel), color = colors.textSecondary) }
        },
    )
}

@Composable
private fun AddAccountDialog(
    addable: List<CalendarAccount>,
    onDismiss: () -> Unit,
    onPick: (CalendarAccount) -> Unit,
    onSignIn: () -> Unit,
) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        title = { Text(stringResource(R.string.settings_add_an_account), color = colors.textPrimary) },
        text = {
            Column {
                if (addable.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_all_added),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                    )
                }
                addable.forEach { account ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(account) }
                            .padding(vertical = d.spaceSm),
                    ) {
                        Text(
                            text = account.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textPrimary,
                        )
                        if (account.type != "com.google") {
                            Text(
                                text = account.type,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(d.spaceSm))
                TextButton(onClick = onSignIn) {
                    Text(stringResource(R.string.settings_sign_in_another), color = colors.greenText)
                }
                Text(
                    text = stringResource(R.string.settings_android_adds_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_close), color = colors.textSecondary) }
        },
    )
}
