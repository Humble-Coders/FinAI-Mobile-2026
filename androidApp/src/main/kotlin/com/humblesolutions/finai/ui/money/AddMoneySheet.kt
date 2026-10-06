package com.humblesolutions.finai.ui.money

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.TransactionDirection
import com.humblesolutions.finai.ui.components.AccountSheet
import com.humblesolutions.finai.ui.components.AmountField
import com.humblesolutions.finai.ui.components.ErrorText
import com.humblesolutions.finai.ui.components.GradientButton
import com.humblesolutions.finai.ui.components.Mint
import com.humblesolutions.finai.ui.components.NewAccountSheet
import com.humblesolutions.finai.ui.components.PickerField
import com.humblesolutions.finai.ui.components.WizardField
import com.humblesolutions.finai.ui.manualentry.CategorySheet
import com.humblesolutions.finai.ui.manualentry.DateDialog
import com.humblesolutions.finai.ui.manualentry.ManualEntryActions
import com.humblesolutions.finai.ui.manualentry.ManualEntryUiState
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.ui.theme.FinAiPalette
import com.humblesolutions.finai.usecase.ManualEntryBlock

/**
 * The quick entry from a money screen, as a sheet: "Add transaction" (money
 * out or in), or on Investments "Add investment transaction" (into savings, or
 * back out of it).
 *
 * The same entry, the same rules and the same model as the full manual entry
 * screen — only laid out as the design's sheet. Nothing here decides whether
 * an entry may be saved; [ManualEntryUiState] does, from shared.
 *
 * @param investment the Investments screen's variant: the category is savings
 *   and is not offered, and the two choices are Investment and Withdrawal.
 * @param markObligation "Mark as obligation", which the screen acts on after a
 *   successful save — the entry and the obligation are two writes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMoneySheet(
    state: ManualEntryUiState,
    actions: ManualEntryActions,
    investment: Boolean,
    markObligation: Boolean,
    onMarkObligation: (Boolean) -> Unit,
) {
    var choosingAccount by rememberSaveable { mutableStateOf(false) }
    var choosingDate by rememberSaveable { mutableStateOf(false) }
    var choosingCategory by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = actions.onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        AddMoneyForm(
            state = state,
            actions = actions,
            investment = investment,
            markObligation = markObligation,
            onMarkObligation = onMarkObligation,
            onPickDate = { choosingDate = true },
            onPickCategory = { choosingCategory = true },
            onPickAccount = { choosingAccount = true },
        )
    }

    if (choosingAccount) {
        AccountSheet(
            accounts = state.accounts,
            chosenId = state.draft.accountId,
            onChosen = {
                actions.onAccountChosen(it)
                choosingAccount = false
            },
            onAdd = {
                choosingAccount = false
                actions.onOpenNewAccount()
            },
            onDismiss = { choosingAccount = false },
        )
    }
    state.newAccount?.let { draft ->
        NewAccountSheet(
            draft = draft,
            notice = state.newAccountNotice,
            errorKey = state.newAccountErrorKey,
            canCreate = state.canCreateAccount,
            creating = state.creatingAccount,
            onName = actions.onNewAccountName,
            onKind = actions.onNewAccountKind,
            onCreate = actions.onCreateAccount,
            onCancel = actions.onCancelNewAccount,
        )
    }
    if (choosingDate) {
        DateDialog(
            chosen = state.draft.occurredOn,
            today = state.today,
            onChosen = {
                actions.onDateChosen(it)
                choosingDate = false
            },
            onDismiss = { choosingDate = false },
        )
    }
    if (choosingCategory) {
        CategorySheet(
            state = state,
            onChosen = {
                actions.onCategoryChosen(it)
                choosingCategory = false
            },
            onDismiss = { choosingCategory = false },
        )
    }
    state.duplicateMessage?.let { message ->
        AlertDialog(
            onDismissRequest = actions.onDismissDuplicate,
            title = { Text(strings(Strings.manual_entry_duplicate_title)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = actions.onKeepDuplicate) { Text(strings(Strings.manual_entry_duplicate_keep)) } },
            dismissButton = { TextButton(onClick = actions.onDismissDuplicate) { Text(strings(Strings.manual_entry_duplicate_cancel)) } },
        )
    }
}

/** The sheet's form, apart from the sheet: what it asks, in the design's order. */
@Composable
internal fun AddMoneyForm(
    state: ManualEntryUiState,
    actions: ManualEntryActions,
    investment: Boolean,
    markObligation: Boolean,
    onMarkObligation: (Boolean) -> Unit,
    onPickDate: () -> Unit,
    onPickCategory: () -> Unit,
    onPickAccount: () -> Unit,
) {
    val focus = LocalFocusManager.current
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                strings(if (investment) Strings.money_add_investment_title else Strings.money_add_transaction),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            IconButton(onClick = actions.onClose) {
                Icon(Icons.Filled.Close, contentDescription = strings(Strings.action_cancel))
            }
        }

        if (state.loading) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        if (state.loadFailed) {
            ErrorText(state.errorKey ?: Strings.manual_entry_load_failed)
            GradientButton(text = strings(Strings.manual_entry_retry), onClick = actions.onRetry)
            Spacer(Modifier.height(24.dp))
            return@Column
        }

        Segmented(
            chosen = state.draft.direction,
            investment = investment,
            isError = state.notice == ManualEntryBlock.NO_DIRECTION,
            onChosen = actions.onDirectionChosen,
        )

        AmountField(
            value = state.draft.amount,
            onValueChange = actions.onAmountChange,
            label = strings(Strings.manual_entry_amount_label),
            symbol = state.symbol,
            placeholder = state.amountPlaceholder,
            isError = state.notice in setOf(ManualEntryBlock.NO_AMOUNT, ManualEntryBlock.AMOUNT_NOT_MONEY, ManualEntryBlock.AMOUNT_ZERO),
            large = false,
        )

        PickerField(
            label = strings(Strings.manual_entry_date_label),
            value = state.dateLabel,
            placeholder = strings(Strings.manual_entry_date_placeholder),
            onClick = {
                actions.onRefreshToday()
                onPickDate()
            },
            isError = state.notice == ManualEntryBlock.NO_DATE || state.notice == ManualEntryBlock.FUTURE_DATE,
            leading = Icons.Filled.CalendarMonth,
            trailing = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        )

        if (!investment) {
            PickerField(
                label = strings(Strings.manual_entry_category_label),
                value = state.categoryName ?: strings(Strings.manual_entry_category_none),
                placeholder = strings(Strings.manual_entry_category_none),
                onClick = onPickCategory,
                leading = Icons.AutoMirrored.Filled.Label,
                trailing = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            )
        }

        WizardField(
            value = state.draft.description,
            onValueChange = actions.onDescriptionChange,
            label = strings(if (investment) Strings.money_investment_label else Strings.money_merchant_label),
            placeholder = strings(if (investment) Strings.money_investment_hint else Strings.money_merchant_hint),
            imeAction = ImeAction.Done,
            capitalization = KeyboardCapitalization.Sentences,
            isError = state.notice == ManualEntryBlock.NO_DESCRIPTION || state.notice == ManualEntryBlock.DESCRIPTION_TOO_LONG,
            onDone = { focus.clearFocus() },
        )

        PickerField(
            label = strings(Strings.money_payment_method),
            value = state.account?.name,
            placeholder = strings(Strings.manual_entry_account_placeholder),
            onClick = onPickAccount,
            isError = state.notice == ManualEntryBlock.NO_ACCOUNT,
            leading = Icons.Filled.AccountBalance,
            trailing = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        )

        // Money out only: income is not something that falls due.
        if (!investment && state.draft.direction == TransactionDirection.DEBIT) {
            ObligationToggle(markObligation, onMarkObligation)
        }

        ErrorText(state.errorKey)
        if (state.errorKey == null) ErrorText(state.notice?.messageKey)
        GradientButton(
            text = strings(Strings.money_save_transaction),
            onClick = {
                focus.clearFocus()
                actions.onSave()
            },
            enabled = state.canSave,
            busy = state.saving,
        )
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Expense / Income, or Investment / Withdrawal: which way the money went.
 * Investing is money leaving the account for savings, so it is a debit.
 */
@Composable
private fun Segmented(
    chosen: TransactionDirection?,
    investment: Boolean,
    isError: Boolean,
    onChosen: (TransactionDirection) -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val options = if (investment) {
        listOf(
            Triple(TransactionDirection.DEBIT, Strings.money_investment, FinAiPalette.Blue),
            Triple(TransactionDirection.CREDIT, Strings.money_withdrawal, FinAiPalette.Blue),
        )
    } else {
        listOf(
            Triple(TransactionDirection.DEBIT, Strings.money_expense, FinAiPalette.Red),
            Triple(TransactionDirection.CREDIT, Strings.money_income, FinAiPalette.Green),
        )
    }
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Mint.card())
            .border(1.dp, if (isError) MaterialTheme.colorScheme.error else Mint.edge(), shape)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (direction, label, colour) ->
            val selected = chosen == direction
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) colour.copy(alpha = if (dark) 0.28f else 0.16f) else Color.Transparent)
                    .selectable(selected = selected, role = Role.RadioButton) { onChosen(direction) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    strings(label),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) colour else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun ObligationToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val label = strings(Strings.money_mark_obligation)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Switch) { onChange(!on) }
            .semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.EventRepeat, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(strings(Strings.money_mark_obligation_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = null)
    }
}
