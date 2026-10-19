package com.EntropicSoftwareLab.Schedule.ui.screens.settings

import android.content.Intent
import android.provider.CalendarContract
import android.provider.Settings
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.EntropicSoftwareLab.Schedule.data.calendar.AccountSyncStatus
import com.EntropicSoftwareLab.Schedule.di.LocalAppContainer
import com.EntropicSoftwareLab.Schedule.ui.components.AppDivider
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

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = d.spaceLg, end = d.spaceLg, top = d.spaceXxl, bottom = d.spaceHuge,
        ),
        verticalArrangement = Arrangement.spacedBy(d.spaceLg),
    ) {
        item { ScreenHeader(title = "Settings") }

        item {
            SectionCard(title = "Calendar", icon = Icons.Outlined.CalendarMonth) {
                Box {
                    SettingRow(
                        label = "Default calendar",
                        value = state.selectedCalendar?.displayName ?: "None available",
                        onClick = { showCalendarMenu = true },
                    )
                    DropdownMenu(
                        expanded = showCalendarMenu,
                        onDismissRequest = { showCalendarMenu = false },
                    ) {
                        state.calendars.forEach { calendar ->
                            DropdownMenuItem(
                                text = { Text("${calendar.displayName} · ${calendar.accountName}") },
                                onClick = {
                                    vm.setCalendar(calendar.id)
                                    showCalendarMenu = false
                                },
                            )
                        }
                    }
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
            SectionCard(title = "Sync with Google", icon = Icons.Outlined.Sync) {
                if (state.syncStatus.isEmpty()) {
                    Text(
                        text = "No synced accounts — events here stay on this phone.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textSecondary,
                    )
                }
                state.syncStatus.forEach { account ->
                    SyncAccountRows(account)
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
        Text(value, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
    }
}

/** One account's lines in the sync card: its name, then each thing the phone would say about it. */
@Composable
private fun SyncAccountRows(account: AccountSyncStatus) {
    val colors = AppThemeTokens.colors
    fun onOff(value: Boolean?): String = when (value) {
        true -> "on"
        false -> "off"
        null -> "unknown"
    }
    Text(
        text = account.accountName,
        style = MaterialTheme.typography.bodyLarge,
        color = colors.textPrimary,
    )
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

