package com.EntropicSoftwareLab.Schedule.ui.screens.settings

import android.content.Intent
import android.provider.CalendarContract
import android.provider.Settings
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.EntropicSoftwareLab.Schedule.data.accounts.AccountPrefs
import com.EntropicSoftwareLab.Schedule.data.accounts.AddedAccount
import com.EntropicSoftwareLab.Schedule.data.accounts.CalendarFilter
import com.EntropicSoftwareLab.Schedule.data.calendar.AccountSyncStatus
import com.EntropicSoftwareLab.Schedule.data.calendar.CalendarAccount
import com.EntropicSoftwareLab.Schedule.di.LocalAppContainer
import com.EntropicSoftwareLab.Schedule.ui.components.AppDivider
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

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = d.spaceLg, end = d.spaceLg, top = d.spaceXxl, bottom = d.spaceHuge,
        ),
        verticalArrangement = Arrangement.spacedBy(d.spaceLg),
    ) {
        item { ScreenHeader(title = "Settings") }

        item {
            SectionCard(title = "Accounts", icon = Icons.Outlined.People) {
                if (state.prefs.added.isEmpty()) {
                    Text(
                        text = "Every account on this phone is shown. Add the ones you want and the rest are left out.",
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
                AppTextButton(text = "Add account", onClick = { addingAccount = true })
                if (state.prefs.hiddenBirthdayKeys.isNotEmpty()) {
                    val n = state.prefs.hiddenBirthdayKeys.size
                    Spacer(Modifier.height(d.spaceSm))
                    AppDivider()
                    Spacer(Modifier.height(d.spaceSm))
                    Text(
                        text = if (n == 1) "1 hidden birthday" else "$n hidden birthdays",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                    )
                    AppTextButton(text = "Show them again", onClick = vm::showHiddenBirthdays)
                }
            }
        }

        item {
            SectionCard(title = "Calendar", icon = Icons.Outlined.CalendarMonth) {
                Box {
                    SettingRow(
                        label = "Default calendar",
                        value = if (state.calendars.isEmpty()) "None available"
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
                    text = "New events are added here and sync through that account.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }

        item {
            val context = LocalContext.current
            SectionCard(
                title = "Sync with Google",
                icon = Icons.Outlined.Sync,
                trailing = {
                    IconButton(onClick = { syncOpen = !syncOpen }) {
                        Icon(
                            imageVector = if (syncOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = if (syncOpen) "Hide sync details" else "Show sync details",
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
                            accounts == 0 -> "No synced accounts"
                            waiting > 0 -> "${accountCount(accounts)} · $waiting changes waiting to upload"
                            else -> "${accountCount(accounts)} · nothing waiting to upload"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (waiting > 0) colors.warning else colors.textSecondary,
                    )
                    AppTextButton(text = "Sync now", onClick = vm::syncNow)
                }
                AnimatedVisibility(visible = syncOpen) {
                    Column {
                        if (state.syncStatus.isEmpty()) {
                            Text(
                                text = "No synced accounts — events here stay on this phone.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.textSecondary,
                            )
                        }
                        state.syncStatus.forEach { account ->
                            SyncAccountRows(account, state.prefs)
                            Spacer(Modifier.height(d.spaceSm))
                        }
                        Text(
                            text = "Changes you make here are uploaded by the phone's own Google sync. If " +
                                "\"Waiting to upload\" stays above zero, check that auto-sync is on for " +
                                "Calendar and that battery saving isn't stopping background sync.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(d.spaceSm)) {
                            AppTextButton(text = "Sync now", onClick = vm::syncNow)
                            AppTextButton(
                                text = "Phone sync settings",
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
            SectionCard(title = "About") {
                Text(
                    text = "Schedule reads the calendars already synced to this device. No account, " +
                        "no server, no analytics — nothing typed or scheduled here leaves the phone.",
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
                        text = "Version $version",
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
        val context = LocalContext.current
        val addable = state.phoneAccounts.filter { phone -> state.prefs.added.none { it.name == phone.name } }
        AddAccountDialog(
            addable = addable,
            onDismiss = { addingAccount = false },
            onPick = { vm.addAccount(it); addingAccount = false },
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

/** One account's lines in the sync card: its name, then each thing the phone would say about it. */
@Composable
private fun SyncAccountRows(account: AccountSyncStatus, prefs: AccountPrefs) {
    val colors = AppThemeTokens.colors
    fun onOff(value: Boolean?): String = when (value) {
        true -> "on"
        false -> "off"
        null -> "unknown"
    }
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
    val lines = buildList {
        // The phone-wide switch overrides the account's own, so an "on" under an "off" is still off.
        if (account.masterAutoSync == false) add("Phone auto-sync: off" to true)
        add("Calendar auto-sync: ${onOff(account.autoSync)}" to (account.autoSync == false))
        account.pendingUploads?.let { add("Waiting to upload: $it" to (it > 0)) }
        if (account.syncing == true) add("Syncing now…" to false)
    }
    lines.forEach { (line, warn) ->
        Text(
            text = line,
            style = MaterialTheme.typography.bodyMedium,
            color = if (warn) colors.warning else colors.textSecondary,
        )
    }
}

private fun accountCount(n: Int): String = if (n == 1) "1 account" else "$n accounts"

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
                text = if (named) account.nickname else "Add a name",
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
        title = { Text("Name this account", color = colors.textPrimary) },
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
                TextButton(onClick = onRemove) { Text("Remove", color = colors.error) }
                TextButton(onClick = { onSave(text) }) { Text("Save", color = colors.greenText) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textSecondary) }
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
        title = { Text("Add an account", color = colors.textPrimary) },
        text = {
            Column {
                if (addable.isEmpty()) {
                    Text(
                        text = "Every account on this phone is already added.",
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
                    Text("Sign in to another account", color = colors.greenText)
                }
                Text(
                    text = "Android adds accounts to the phone; the app can then show them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = colors.textSecondary) }
        },
    )
}
