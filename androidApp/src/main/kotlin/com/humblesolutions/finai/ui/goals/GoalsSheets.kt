package com.humblesolutions.finai.ui.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.GoalHorizon
import com.humblesolutions.finai.model.GoalKind
import com.humblesolutions.finai.ui.components.AmountField
import com.humblesolutions.finai.ui.components.Mint
import com.humblesolutions.finai.ui.components.PickerField
import com.humblesolutions.finai.ui.components.WizardField
import com.humblesolutions.finai.ui.components.tint
import com.humblesolutions.finai.ui.components.vector
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.usecase.GoalBlock
import com.humblesolutions.finai.usecase.GoalDraft
import com.humblesolutions.finai.usecase.GoalEdit
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money
import kotlinx.datetime.LocalDate

/** What the editor can do, passed down rather than reached for. */
internal data class GoalEditorActions(
    val onDraftChange: (GoalDraft) -> Unit,
    val onSave: () -> Unit,
    val onCancel: () -> Unit,
    val onDelete: () -> Unit,
)

private val TargetBlocks = setOf(GoalBlock.NO_TARGET, GoalBlock.TARGET_NOT_MONEY, GoalBlock.TARGET_NEGATIVE, GoalBlock.TARGET_TOO_PRECISE, GoalBlock.TARGET_ZERO)
private val SavedBlocks = setOf(GoalBlock.NO_SAVED, GoalBlock.SAVED_NOT_MONEY, GoalBlock.SAVED_NEGATIVE, GoalBlock.SAVED_TOO_PRECISE)
private val ContributionBlocks = setOf(GoalBlock.CONTRIBUTION_NOT_MONEY, GoalBlock.CONTRIBUTION_NEGATIVE, GoalBlock.CONTRIBUTION_TOO_PRECISE)
private val NameBlocks = setOf(GoalBlock.NO_NAME, GoalBlock.NAME_TOO_LONG)

/**
 * Creating or changing a goal, in the transaction editor's style.
 *
 * The disabled Save, the notice and the view model's refusal all read one
 * shared `GoalEdit` rule. Every field is locked while the goal is being
 * sent (#52 UI standards), so what is on screen is what the server answers about.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GoalEditorSheet(state: GoalsUiState, actions: GoalEditorActions) {
    if (!state.editorOpen) return
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val draft = state.draft
    val busy = state.saving
    val notice = state.editNotice
    var choosingDate by rememberSaveable { mutableStateOf(false) }
    val digits = Money.fractionDigits(state.currency)
    val placeholder = Money.normalize("0", digits).orEmpty()

    ModalBottomSheet(onDismissRequest = { if (!busy) actions.onCancel() }, sheetState = sheet) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .imePadding()
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                strings(if (state.creating) Strings.goals_edit_title_new else Strings.goals_edit_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )

            WizardField(
                value = draft.name,
                onValueChange = { actions.onDraftChange(draft.copy(name = it)) },
                label = strings(Strings.goals_field_name),
                isError = notice in NameBlocks,
                enabled = !busy,
            )

            KindPicker(draft.kind, enabled = !busy) { actions.onDraftChange(draft.copy(kind = it)) }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings(Strings.goals_field_horizon), style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(GoalHorizon.SHORT_TERM to Strings.goals_horizon_short, GoalHorizon.LONG_TERM to Strings.goals_horizon_long)
                        .forEachIndexed { i, (horizon, label) ->
                            SegmentedButton(
                                selected = draft.horizon == horizon,
                                onClick = { actions.onDraftChange(draft.copy(horizon = horizon)) },
                                shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                                enabled = !busy,
                            ) { Text(strings(label)) }
                        }
                }
            }

            AmountField(
                value = draft.target,
                onValueChange = { actions.onDraftChange(draft.copy(target = it)) },
                label = strings(Strings.goals_field_target),
                symbol = state.currencySymbol,
                placeholder = placeholder,
                isError = notice in TargetBlocks,
                large = false,
                enabled = !busy,
            )

            AmountField(
                value = draft.saved,
                onValueChange = { actions.onDraftChange(draft.copy(saved = it)) },
                label = strings(if (state.creating) Strings.goals_field_saved_new else Strings.goals_field_saved),
                symbol = state.currencySymbol,
                placeholder = placeholder,
                isError = notice in SavedBlocks,
                large = false,
                enabled = !busy,
            )

            PickerField(
                label = strings(Strings.goals_field_date),
                value = draft.targetDate?.let { Dates.parse(it) }?.let(Dates::display),
                placeholder = strings(Strings.goals_field_date_none),
                onClick = { choosingDate = true },
                isError = notice == GoalBlock.DATE_IN_PAST,
                enabled = !busy,
            )
            if (draft.targetDate != null) {
                TextButton(onClick = { actions.onDraftChange(draft.copy(targetDate = null)) }, enabled = !busy) {
                    Text(strings(Strings.goals_field_date_clear))
                }
            }

            AmountField(
                value = draft.monthlyContribution,
                onValueChange = { actions.onDraftChange(draft.copy(monthlyContribution = it)) },
                label = strings(Strings.goals_field_contribution),
                symbol = state.currencySymbol,
                placeholder = placeholder,
                isError = notice in ContributionBlocks,
                large = false,
                // The last field: Done closes the keyboard; Save submits.
                imeAction = ImeAction.Done,
                enabled = !busy,
            )

            notice?.let {
                Text(GoalEdit.blockText(it, state.currency, state.locale), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            state.editErrorKey?.let {
                Text(strings(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = actions.onCancel, enabled = !busy) { Text(strings(Strings.action_cancel)) }
                if (!state.creating) {
                    TextButton(onClick = actions.onDelete, enabled = !busy) {
                        Text(strings(Strings.goals_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = actions.onSave, enabled = state.canSave) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text(strings(Strings.goals_saving))
                    } else {
                        Text(strings(Strings.action_save))
                    }
                }
            }
        }
    }

    if (choosingDate) {
        GoalDateDialog(
            chosen = draft.targetDate?.let { Dates.parse(it) },
            today = state.today,
            onChosen = {
                actions.onDraftChange(draft.copy(targetDate = it.toString()))
                choosingDate = false
            },
            onDismiss = { choosingDate = false },
        )
    }
}

/** What the goal is for, as tiles — for its picture only. Tapping the chosen one clears it. */
@Composable
private fun KindPicker(chosen: GoalKind?, enabled: Boolean, onChosen: (GoalKind?) -> Unit) {
    val dark = isSystemInDarkTheme()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings(Strings.goals_field_kind), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GoalKind.choosable.forEach { kind ->
                val selected = kind == chosen
                val icon = GoalEdit.iconOf(kind)
                Column(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, if (selected) icon.tint(dark) else Color.Transparent, RoundedCornerShape(14.dp))
                        .clickable(enabled = enabled, role = Role.RadioButton) { onChosen(if (selected) null else kind) }
                        .semantics { this.selected = selected }
                        .heightIn(min = 48.dp)
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Mint.tile(icon.tint(dark))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(icon.vector(), contentDescription = null, tint = icon.tint(dark), modifier = Modifier.size(18.dp))
                    }
                    Text(GoalEdit.kindLabel(kind), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}

private const val MILLIS_PER_DAY = 86_400_000L

/** The calendar, from today on — the same limit the shared rule and the server hold a target date to. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalDateDialog(chosen: LocalDate?, today: LocalDate, onChosen: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val earliest = today.toEpochDays() * MILLIS_PER_DAY
    val limit = remember(today) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= earliest
            override fun isSelectableYear(year: Int) = year >= today.year
        }
    }
    val picker = rememberDatePickerState(
        initialSelectedDateMillis = chosen?.let { it.toEpochDays() * MILLIS_PER_DAY },
        selectableDates = limit,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { picker.selectedDateMillis?.let { onChosen(LocalDate.fromEpochDays(Math.floorDiv(it, MILLIS_PER_DAY))) } },
                enabled = picker.selectedDateMillis != null,
            ) { Text(strings(Strings.action_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings(Strings.action_cancel)) } },
    ) {
        DatePicker(state = picker)
    }
}

/** What "Add money" can do. */
internal data class AddToGoalActions(
    val onAmountChange: (String) -> Unit,
    val onAdd: () -> Unit,
    val onCancel: () -> Unit,
)

/** Adding to one goal: a single amount field, locked while it is sent. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddToGoalSheet(state: GoalsUiState, actions: AddToGoalActions) {
    val goal = state.addingTo ?: return
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val busy = state.adding

    ModalBottomSheet(onDismissRequest = { if (!busy) actions.onCancel() }, sheetState = sheet) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .imePadding()
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                strings(Strings.goals_add_title, goal.name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            AmountField(
                value = state.addAmount,
                onValueChange = actions.onAmountChange,
                label = strings(Strings.goals_field_add_amount),
                symbol = state.currencySymbol,
                placeholder = Money.normalize("0", Money.fractionDigits(state.currency)).orEmpty(),
                isError = state.addNotice != null,
                large = false,
                imeAction = ImeAction.Done,
                enabled = !busy,
            )
            state.addNotice?.let {
                Text(GoalEdit.addBlockText(it, state.currency, state.locale), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            state.addErrorKey?.let {
                Text(strings(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = actions.onCancel, enabled = !busy) { Text(strings(Strings.action_cancel)) }
                Spacer(Modifier.weight(1f))
                Button(onClick = actions.onAdd, enabled = state.canAdd) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(strings(Strings.goals_add_money))
                    }
                }
            }
        }
    }
}

/** Deleting cannot be undone, so it is asked first. */
@Composable
internal fun DeleteGoalDialog(state: GoalsUiState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val goal = state.confirmingDelete ?: return
    AlertDialog(
        onDismissRequest = { if (!state.deleting) onDismiss() },
        title = { Text(strings(Strings.goals_delete_title, goal.name)) },
        text = { Text(strings(Strings.goals_delete_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !state.deleting) {
                Text(strings(Strings.goals_delete_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.deleting) { Text(strings(Strings.action_cancel)) } },
    )
}
