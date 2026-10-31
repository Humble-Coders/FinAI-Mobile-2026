package com.humblesolutions.finai.ui.plan

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.DebtKind
import com.humblesolutions.finai.model.PlanCategory
import com.humblesolutions.finai.ui.components.AmountField
import com.humblesolutions.finai.ui.components.PickerField
import com.humblesolutions.finai.ui.components.WizardField
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.usecase.DebtBlock
import com.humblesolutions.finai.usecase.DebtDraft
import com.humblesolutions.finai.usecase.DebtEdit
import com.humblesolutions.finai.usecase.YourPlan
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Percent
import kotlinx.datetime.LocalDate

// ── A row of the split, set by hand ─────────────────────────────────────

internal data class RowEditActions(
    val onAmountChange: (String) -> Unit,
    /** Save: asks first whether to put the amount where it belongs too. */
    val onSave: () -> Unit,
    val onCancel: () -> Unit,
    /** The alert's answer: true also updates the goal or the Budget. */
    val onApply: (Boolean) -> Unit,
    val onDismissApply: () -> Unit,
)

/**
 * One row's amount this month, at most what the server allows, the others
 * adjusting to keep the total. Save asks whether the amount should also go
 * where it belongs — a goal's monthly amount, the Budget's Debt line or its
 * savings target — and either answer saves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RowEditSheet(state: PlanUiState, actions: RowEditActions) {
    val draft = state.rowDraft ?: return
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val busy = state.savingRow
    val notice = state.rowNotice

    ModalBottomSheet(onDismissRequest = { if (!busy) actions.onCancel() }, sheetState = sheet) {
        SheetColumn {
            Title(strings(Strings.plan_edit_title, draft.label))
            AmountField(
                value = draft.amount,
                onValueChange = actions.onAmountChange,
                label = strings(Strings.plan_edit_amount),
                symbol = state.currencySymbol,
                placeholder = state.amountPlaceholder,
                isError = notice != null,
                large = false,
                imeAction = ImeAction.Done,
                enabled = !busy,
            )
            state.rowLimitLine?.let { Why(it) }
            state.rowRebalanceLine?.let { Why(it) }
            notice?.let { Problem(it) }
            state.rowErrorKey?.let { Problem(strings(it)) }
            Buttons(busy = busy, canSave = state.canSaveRow, savingLabel = strings(Strings.plan_saving), onCancel = actions.onCancel, onSave = actions.onSave)
        }
    }

    state.applyQuestion?.let { question ->
        AlertDialog(
            onDismissRequest = actions.onDismissApply,
            title = { Text(question.title) },
            text = { Text(question.body) },
            confirmButton = { TextButton(onClick = { actions.onApply(true) }) { Text(question.confirm) } },
            dismissButton = { TextButton(onClick = { actions.onApply(false) }) { Text(question.decline) } },
        )
    }
}

// ── The choices ─────────────────────────────────────────────────────────

internal data class ChoicesActions(
    val onMove: (PlanCategory, Boolean) -> Unit,
    val onStep: (PlanCategory, Boolean) -> Unit,
    val onPercent: (PlanCategory, String) -> Unit,
    val onSave: () -> Unit,
    val onCancel: () -> Unit,
)

/**
 * The choices in the order they are filled, each with its share: up and down
 * to reorder, − and + to move a share by five points, or a share typed. The
 * shares add up to 100; then "Recalculate allocation" saves, and the server
 * works the split out again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChoicesSheet(state: PlanUiState, actions: ChoicesActions) {
    if (!state.choosing) return
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val draft = state.weightsDraft
    val busy = state.savingChoices
    val focus = LocalFocusManager.current

    ModalBottomSheet(onDismissRequest = { if (!busy) actions.onCancel() }, sheetState = sheet) {
        SheetColumn {
            Title(strings(Strings.plan_choices_sheet_title))
            Why(strings(Strings.plan_choices_hint))

            draft.categories.forEachIndexed { index, category ->
                val label = YourPlan.categoryLabel(category, state.locale)
                val last = index == draft.categories.lastIndex
                if (index > 0) HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PriorityBadge(index + 1)
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { actions.onMove(category, true) }, enabled = !busy && index > 0) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = strings(Strings.plan_choices_move_up, label))
                        }
                        IconButton(onClick = { actions.onMove(category, false) }, enabled = !busy && !last) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = strings(Strings.plan_choices_move_down, label))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Spacer(Modifier.width(32.dp))
                        IconButton(onClick = { actions.onStep(category, false) }, enabled = !busy) {
                            Icon(Icons.Filled.Remove, contentDescription = strings(Strings.plan_choices_decrease, label))
                        }
                        OutlinedTextField(
                            value = draft.percents[category].orEmpty(),
                            onValueChange = { actions.onPercent(category, it) },
                            singleLine = true,
                            enabled = !busy,
                            isError = state.choicesNotice != null,
                            suffix = { Text(strings(Strings.plan_percent_sign)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = if (last) ImeAction.Done else ImeAction.Next),
                            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                            modifier = Modifier.width(112.dp).semantics { contentDescription = label },
                        )
                        IconButton(onClick = { actions.onStep(category, true) }, enabled = !busy) {
                            Icon(Icons.Filled.Add, contentDescription = strings(Strings.plan_choices_increase, label))
                        }
                    }
                }
            }

            state.choicesTotalLine?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            state.choicesNotice?.let { Problem(it) }
            state.choicesErrorKey?.let { Problem(strings(it)) }

            Buttons(
                busy = busy,
                canSave = state.canSaveChoices,
                savingLabel = strings(Strings.plan_saving),
                onCancel = actions.onCancel,
                onSave = actions.onSave,
                saveLabel = strings(Strings.plan_choices_recalculate),
            )
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
private fun Buttons(busy: Boolean, canSave: Boolean, savingLabel: String, onCancel: () -> Unit, onSave: () -> Unit, saveLabel: String? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onCancel, enabled = !busy) { Text(strings(Strings.action_cancel)) }
        Spacer(Modifier.weight(1f))
        Button(onClick = onSave, enabled = canSave) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text(savingLabel)
            } else {
                Text(saveLabel ?: strings(Strings.action_save), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
