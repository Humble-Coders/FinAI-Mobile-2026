package com.humblesolutions.finai.ui.plan

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.DebtKind
import com.humblesolutions.finai.model.PlanStrategy
import com.humblesolutions.finai.ui.components.AmountField
import com.humblesolutions.finai.ui.components.PickerField
import com.humblesolutions.finai.ui.components.WizardField
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.usecase.DebtBlock
import com.humblesolutions.finai.usecase.DebtDraft
import com.humblesolutions.finai.usecase.DebtEdit
import com.humblesolutions.finai.usecase.PlanChoiceBlock
import com.humblesolutions.finai.usecase.PlanChoicesDraft
import com.humblesolutions.finai.usecase.YourPlan
import com.humblesolutions.finai.util.Dates
import kotlinx.datetime.LocalDate

// ── The choices ─────────────────────────────────────────────────────────

internal data class ChoicesActions(
    val onDraftChange: (PlanChoicesDraft) -> Unit,
    val onSave: () -> Unit,
    val onCancel: () -> Unit,
)

private val BufferBlocks = setOf(
    PlanChoiceBlock.NO_BUFFER_AMOUNT,
    PlanChoiceBlock.BUFFER_NOT_MONEY,
    PlanChoiceBlock.BUFFER_NEGATIVE,
    PlanChoiceBlock.BUFFER_TOO_PRECISE,
    PlanChoiceBlock.BUFFER_ZERO,
)

/**
 * The plan's four choices, each opening on what the plan runs on now — the
 * person's own, or the suggestion — with its one line of why. Saving with a
 * buffer keeps it as an Emergency fund goal, and the sheet says so first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChoicesSheet(state: PlanUiState, actions: ChoicesActions) {
    if (!state.choosing) return
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val draft = state.choicesDraft
    val busy = state.savingChoices
    val notice = state.choicesNotice

    ModalBottomSheet(onDismissRequest = { if (!busy) actions.onCancel() }, sheetState = sheet) {
        SheetColumn {
            Title(strings(Strings.plan_choices_sheet_title))

            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(value = draft.hasBuffer, enabled = !busy, role = Role.Switch) { actions.onDraftChange(draft.copy(hasBuffer = it)) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(strings(Strings.plan_field_keep_buffer), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = draft.hasBuffer, onCheckedChange = null, enabled = !busy)
            }
            if (draft.hasBuffer) {
                AmountField(
                    value = draft.buffer,
                    onValueChange = { actions.onDraftChange(draft.copy(buffer = it)) },
                    label = strings(Strings.plan_field_buffer),
                    symbol = state.currencySymbol,
                    placeholder = state.amountPlaceholder,
                    isError = notice in BufferBlocks,
                    large = false,
                    enabled = !busy,
                )
            }
            Why(strings(Strings.plan_choice_buffer_why))

            WizardField(
                value = draft.goalSharePercent,
                onValueChange = { actions.onDraftChange(draft.copy(goalSharePercent = it)) },
                label = strings(Strings.plan_field_goal_share),
                keyboardType = KeyboardType.Decimal,
                capitalization = KeyboardCapitalization.None,
                isError = notice == PlanChoiceBlock.GOAL_SHARE_INVALID,
                enabled = !busy,
            )
            Why(strings(Strings.plan_choice_goal_share_why))

            WizardField(
                value = draft.rateLinePercent,
                onValueChange = { actions.onDraftChange(draft.copy(rateLinePercent = it)) },
                label = strings(Strings.plan_field_rate_line),
                keyboardType = KeyboardType.Decimal,
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Done,
                isError = notice == PlanChoiceBlock.RATE_LINE_INVALID,
                enabled = !busy,
            )
            Why(strings(Strings.plan_choice_rate_line_why))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings(Strings.plan_choice_strategy), style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PlanStrategy.entries.forEachIndexed { index, strategy ->
                        SegmentedButton(
                            selected = draft.strategy == strategy,
                            onClick = { actions.onDraftChange(draft.copy(strategy = strategy)) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = PlanStrategy.entries.size),
                            enabled = !busy,
                        ) { Text(YourPlan.strategyLabel(strategy, state.locale), maxLines = 1) }
                    }
                }
                Why(strings(Strings.plan_choice_strategy_why))
            }

            state.goalNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            notice?.let { Problem(YourPlan.blockText(it, state.moneyCurrency, state.locale)) }
            state.choicesErrorKey?.let { Problem(strings(it)) }

            Buttons(busy = busy, canSave = state.canSaveChoices, savingLabel = strings(Strings.plan_saving), onCancel = actions.onCancel, onSave = actions.onSave)
        }
    }
}

// ── A debt's details ────────────────────────────────────────────────────

internal data class DebtEditorActions(
    val onDraftChange: (DebtDraft) -> Unit,
    val onSave: () -> Unit,
    val onCancel: () -> Unit,
)

/**
 * A debt's details: name, type, minimum, rate, a promotion and the account
 * its statements go into. The balance is not here — "Update balance" is,
 * because the server dates that one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DebtEditorSheet(state: PlanUiState, actions: DebtEditorActions) {
    val debt = state.editingDebt ?: return
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val draft = state.debtDraft
    val busy = state.savingDebt
    val notice = state.debtNotice
    var choosingDate by rememberSaveable { mutableStateOf(false) }
    var choosingAccount by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = { if (!busy) actions.onCancel() }, sheetState = sheet) {
        SheetColumn {
            Title(strings(Strings.debts_edit_title, debt.name))

            WizardField(
                value = draft.name,
                onValueChange = { actions.onDraftChange(draft.copy(name = it)) },
                label = strings(Strings.debts_field_name),
                isError = notice == DebtBlock.NO_NAME || notice == DebtBlock.NAME_TOO_LONG,
                enabled = !busy,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings(Strings.debts_field_kind), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val chosen = draft.kind ?: DebtKind.OTHER
                    DebtKind.choosable.forEach { kind ->
                        FilterChip(
                            selected = kind == chosen,
                            onClick = { actions.onDraftChange(draft.copy(kind = kind)) },
                            label = { Text(DebtEdit.kindLabel(kind, state.locale)) },
                            enabled = !busy,
                        )
                    }
                }
            }

            AmountField(
                value = draft.minimumPayment,
                onValueChange = { actions.onDraftChange(draft.copy(minimumPayment = it)) },
                label = strings(Strings.debts_field_minimum),
                symbol = state.currencySymbol,
                placeholder = state.amountPlaceholder,
                isError = notice == DebtBlock.MINIMUM_NOT_MONEY || notice == DebtBlock.MINIMUM_NEGATIVE || notice == DebtBlock.MINIMUM_TOO_PRECISE,
                large = false,
                enabled = !busy,
            )

            WizardField(
                value = draft.interestRatePercent,
                onValueChange = { actions.onDraftChange(draft.copy(interestRatePercent = it)) },
                label = strings(Strings.debts_field_rate),
                keyboardType = KeyboardType.Decimal,
                capitalization = KeyboardCapitalization.None,
                isError = notice == DebtBlock.RATE_INVALID,
                enabled = !busy,
            )

            WizardField(
                value = draft.promoRatePercent,
                onValueChange = { actions.onDraftChange(draft.copy(promoRatePercent = it)) },
                label = strings(Strings.debts_field_promo_rate),
                keyboardType = KeyboardType.Decimal,
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Done,
                isError = notice == DebtBlock.PROMO_RATE_INVALID || (notice == DebtBlock.PROMO_INCOMPLETE && draft.promoRatePercent.isBlank()),
                enabled = !busy,
            )
            PickerField(
                label = strings(Strings.debts_field_promo_ends),
                value = Dates.parse(draft.promoEndsOn)?.let(Dates::display),
                placeholder = strings(Strings.debts_field_promo_none),
                onClick = { choosingDate = true },
                isError = notice == DebtBlock.PROMO_INCOMPLETE && draft.promoEndsOn == null,
                enabled = !busy,
            )
            if (draft.promoRatePercent.isNotBlank() || draft.promoEndsOn != null) {
                TextButton(onClick = { actions.onDraftChange(draft.copy(promoRatePercent = "", promoEndsOn = null)) }, enabled = !busy) {
                    Text(strings(Strings.debts_field_promo_clear))
                }
            }

            Box {
                PickerField(
                    label = strings(Strings.debts_field_account),
                    value = state.linkableAccounts.firstOrNull { it.id == draft.accountId }?.name ?: state.accounts.firstOrNull { it.id == draft.accountId }?.name,
                    placeholder = strings(Strings.debts_field_account_none),
                    onClick = { choosingAccount = true },
                    enabled = !busy,
                )
                DropdownMenu(expanded = choosingAccount, onDismissRequest = { choosingAccount = false }) {
                    DropdownMenuItem(
                        text = { Text(strings(Strings.debts_field_account_none)) },
                        onClick = {
                            actions.onDraftChange(draft.copy(accountId = null))
                            choosingAccount = false
                        },
                    )
                    state.linkableAccounts.forEach { account ->
                        DropdownMenuItem(
                            text = { Text(account.name) },
                            onClick = {
                                actions.onDraftChange(draft.copy(accountId = account.id))
                                choosingAccount = false
                            },
                        )
                    }
                }
            }

            notice?.let { Problem(DebtEdit.blockText(it, state.moneyCurrency, state.locale)) }
            state.debtErrorKey?.let { Problem(strings(it)) }

            Buttons(busy = busy, canSave = state.canSaveDebt, savingLabel = strings(Strings.debts_saving), onCancel = actions.onCancel, onSave = actions.onSave)
        }
    }

    if (choosingDate) {
        PromotionEndDialog(
            chosen = Dates.parse(draft.promoEndsOn) ?: state.today,
            onChosen = {
                actions.onDraftChange(draft.copy(promoEndsOn = it.toString()))
                choosingDate = false
            },
            onDismiss = { choosingDate = false },
        )
    }
}

private const val MILLIS_PER_DAY = 86_400_000L

/** Any day: a promotion that has already ended is still a true fact about the debt. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PromotionEndDialog(chosen: LocalDate, onChosen: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val picker = rememberDatePickerState(initialSelectedDateMillis = chosen.toEpochDays() * MILLIS_PER_DAY)
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

// ── Update balance ──────────────────────────────────────────────────────

internal data class BalanceActions(
    val onAmountChange: (String) -> Unit,
    val onSave: () -> Unit,
    val onCancel: () -> Unit,
)

/** One amount: what is owed today. The server dates it and marks it typed by hand. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BalanceSheet(state: PlanUiState, actions: BalanceActions) {
    val debt = state.balanceDebt ?: return
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val busy = state.savingBalance

    ModalBottomSheet(onDismissRequest = { if (!busy) actions.onCancel() }, sheetState = sheet) {
        SheetColumn {
            Title(strings(Strings.debts_balance_title, debt.name))
            Why(DebtEdit.sourceLine(debt, state.locale))
            AmountField(
                value = state.balanceAmount,
                onValueChange = actions.onAmountChange,
                label = strings(Strings.debts_field_balance),
                symbol = state.currencySymbol,
                placeholder = state.amountPlaceholder,
                isError = state.balanceNotice != null,
                large = false,
                imeAction = ImeAction.Done,
                enabled = !busy,
            )
            state.balanceNotice?.let { Problem(DebtEdit.balanceBlockText(it, state.moneyCurrency, state.locale)) }
            state.balanceErrorKey?.let { Problem(strings(it)) }
            Buttons(busy = busy, canSave = state.canSaveBalance, savingLabel = strings(Strings.debts_saving), onCancel = actions.onCancel, onSave = actions.onSave)
        }
    }
}

// ── Shared pieces ───────────────────────────────────────────────────────

@Composable
private fun SheetColumn(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .imePadding()
            .navigationBarsPadding()
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) { content() }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Why(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Problem(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun Buttons(busy: Boolean, canSave: Boolean, savingLabel: String, onCancel: () -> Unit, onSave: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onCancel, enabled = !busy) { Text(strings(Strings.action_cancel)) }
        Spacer(Modifier.weight(1f))
        Button(onClick = onSave, enabled = canSave) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text(savingLabel)
            } else {
                Text(strings(Strings.action_save))
            }
        }
    }
}
