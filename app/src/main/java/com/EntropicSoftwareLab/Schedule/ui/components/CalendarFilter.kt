package com.EntropicSoftwareLab.Schedule.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.selection.toggleable
import com.EntropicSoftwareLab.Schedule.data.accounts.CalendarChip
import com.EntropicSoftwareLab.Schedule.data.accounts.CalendarFilter
import com.EntropicSoftwareLab.Schedule.di.AppContainer
import com.EntropicSoftwareLab.Schedule.di.LocalAppContainer
import com.EntropicSoftwareLab.Schedule.ui.theme.AppThemeTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.EntropicSoftwareLab.Schedule.R
import androidx.compose.ui.res.stringResource

/**
 * Backs [CalendarFilterButton]. Self-contained so Today, Week and Month can each drop the button in
 * without threading state through their own ViewModels.
 */
class CalendarFilterViewModel(private val container: AppContainer) : ViewModel() {

    private val _chips = MutableStateFlow<List<CalendarChip>>(emptyList())
    val chips: StateFlow<List<CalendarChip>> = _chips.asStateFlow()

    init {
        val prefsFlow = container.accountPrefsRepository.prefs
        // observeChanges() emits once straight away, then on provider and filter-pref changes. It is
        // combined with prefs anyway because the chips need the prefs value itself, and because a
        // nickname rename changes the labels without changing which events are shown.
        val changes = container.calendarRepository.observeChanges()
        viewModelScope.launch {
            combine(prefsFlow, changes) { prefs, _ -> prefs }.collect { prefs ->
                val calendars = runCatching { container.calendarRepository.listCalendars() }
                    .getOrDefault(emptyList())
                // Equal lists are not written, so one toggle reaching here twice redraws once.
                _chips.value = CalendarFilter.chips(calendars, prefs)
            }
        }
    }

    fun toggle(chip: CalendarChip) {
        viewModelScope.launch {
            val prefs = container.accountPrefsRepository
            when (chip) {
                is CalendarChip.Account -> prefs.setAccountShown(chip.accountName, !chip.shown)
                is CalendarChip.Calendar -> prefs.setCalendarShown(chip.calendarId, !chip.shown)
                is CalendarChip.Birthdays -> prefs.setBirthdaysShown(!chip.shown)
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CalendarFilterViewModel(container) as T
        }
    }
}

/**
 * The round three-dots button in a page header. Opens a sheet where each account, extra calendar and
 * Birthday can be ticked off, which hides it from the pages and the widgets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarFilterButton(modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val vm: CalendarFilterViewModel = viewModel(factory = CalendarFilterViewModel.factory(container))
    val chips by vm.chips.collectAsStateWithLifecycle()
    val colors = AppThemeTokens.colors
    val d = AppThemeTokens.dimens
    val interaction = remember { MutableInteractionSource() }
    var open by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .pressScale(interaction)
            .size(d.touchTarget)
            .clip(CircleShape)
            .background(colors.card)
            .border(d.hairlineWidth, colors.hairline, CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                role = Role.Button,
                onClick = { open = true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.MoreHoriz,
            contentDescription = stringResource(R.string.cmp_choose_calendars),
            tint = colors.textPrimary,
            modifier = Modifier.size(d.iconSm),
        )
    }

    if (open) {
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            CalendarFilterSheetBody(chips = chips, onToggle = vm::toggle)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarFilterSheetBody(chips: List<CalendarChip>, onToggle: (CalendarChip) -> Unit) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        modifier = Modifier.padding(
            start = d.spaceLg, end = d.spaceLg, top = d.spaceSm, bottom = d.spaceXl + navBottom,
        ),
    ) {
        Text(
            text = stringResource(R.string.cmp_show_calendars),
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(d.spaceMd))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
            verticalArrangement = Arrangement.spacedBy(d.spaceSm),
        ) {
            chips.forEach { chip ->
                FilterPill(chip = chip, onClick = { onToggle(chip) })
            }
        }
        Spacer(Modifier.height(d.spaceMd))
        Text(
            text = stringResource(R.string.cmp_calendars_hint),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
    }
}

/** One pill: a small square tick box in the chip's own colour, then its label. The whole pill toggles. */
@Composable
private fun FilterPill(chip: CalendarChip, onClick: () -> Unit) {
    val d = AppThemeTokens.dimens
    val colors = AppThemeTokens.colors
    val tint = chip.color.asCalendarColor(colors.textSecondary)
    val shape = RoundedCornerShape(d.radiusPill)
    val boxShape = RoundedCornerShape(4.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(colors.card)
            .border(d.hairlineWidth, colors.hairline, shape)
            .toggleable(
                value = chip.shown,
                role = Role.Checkbox,
                onValueChange = { onClick() },
            )
            .padding(horizontal = d.spaceMd, vertical = d.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(boxShape)
                .background(if (chip.shown) tint else Color.Transparent)
                .then(if (chip.shown) Modifier else Modifier.border(BorderStroke(1.5.dp, tint), boxShape)),
            contentAlignment = Alignment.Center,
        ) {
            if (chip.shown) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.size(d.spaceSm))
        Text(
            text = chip.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (chip.shown) colors.textPrimary else colors.textSecondary,
        )
    }
}
