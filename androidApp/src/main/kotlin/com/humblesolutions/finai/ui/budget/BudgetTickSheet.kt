package com.humblesolutions.finai.ui.budget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.ui.components.AmountField
import com.humblesolutions.finai.ui.components.ErrorText
import com.humblesolutions.finai.ui.components.GradientButton
import com.humblesolutions.finai.ui.components.Mint
import com.humblesolutions.finai.ui.components.PickerField
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.ui.theme.FinAiPalette
import com.humblesolutions.finai.usecase.TickCandidate
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money
import kotlinx.datetime.LocalDate

/** What the tick-off sheet can ask of the model. */
internal class TickActions(
    val onOpen: (String) -> Unit = {},
    val onClose: () -> Unit = {},
    val onMatch: (String) -> Unit = {},
    val onAmount: (String) -> Unit = {},
    val onDate: (LocalDate) -> Unit = {},
    val onAccount: (String) -> Unit = {},
    val onAdd: () -> Unit = {},
    val onUseDuplicate: () -> Unit = {},
    val onUntick: () -> Unit = {},
)

/**
 * Ticking a commitment off by hand: match a payment already recorded this
 * month — nothing new is counted — or type the payment in, which is recorded
 * once, in the commitment's category, and named as what paid it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommitmentTickSheet(state: BudgetUiState, actions: TickActions) {
    val commitment = state.ticking ?: return
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var choosingDate by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = actions.onClose, sheetState = sheet) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.tickTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                Text(state.tickSubtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (commitment.paidByHand) {
                TextButton(onClick = actions.onUntick, enabled = !state.tickBusy) {
                    Text(strings(Strings.tick_untick), color = MaterialTheme.colorScheme.error)
                }
            }

            Text(strings(Strings.tick_match_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            val rows = state.tickRows
            when {
                rows == null -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }

                state.tickCandidates.isEmpty() -> Text(strings(Strings.tick_match_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.tickCandidates.forEach { CandidateRow(it, commitment.transactionId == it.transactionId, !state.tickBusy, actions.onMatch) }
                }
            }

            HorizontalDivider(color = Mint.edge())
            Text(strings(Strings.tick_add_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            Text(strings(Strings.tick_add_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val currency = state.currency
            AmountField(
                value = state.payAmount,
                onValueChange = actions.onAmount,
                label = strings(Strings.tick_amount),
                symbol = Money.symbol(currency),
                placeholder = Money.normalize("0", Money.fractionDigits(currency)).orEmpty(),
                isError = state.payBlock == com.humblesolutions.finai.usecase.TickBlock.AMOUNT && state.payAmount.isNotEmpty(),
                large = false,
                enabled = !state.tickBusy,
            )
            PickerField(
                label = strings(Strings.tick_date),
                value = state.payDate?.let(Dates::display),
                placeholder = strings(Strings.manual_entry_date_placeholder),
                onClick = { choosingDate = true },
                enabled = !state.tickBusy,
            )
            if (state.accounts.isNotEmpty()) {
                Text(strings(Strings.tick_account), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.accounts.take(3).forEach { account ->
                        FilterChip(
                            selected = state.payAccountId == account.id,
                            onClick = { actions.onAccount(account.id) },
                            label = { Text(account.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            enabled = !state.tickBusy,
                        )
                    }
                }
            }
            state.payBlock?.takeIf { it != com.humblesolutions.finai.usecase.TickBlock.AMOUNT || state.payAmount.isNotEmpty() }?.let {
                Text(strings(it.messageKey), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val duplicate = state.tickDuplicate
            if (duplicate != null) {
                Text(
                    strings(Strings.tick_duplicate, duplicate.description.orEmpty(), Money.format(duplicate.amount, currency, state.locale)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = actions.onUseDuplicate, enabled = !state.tickBusy) { Text(strings(Strings.tick_use_existing)) }
            } else {
                ErrorText(state.tickErrorKey)
            }
            GradientButton(text = strings(Strings.tick_add), onClick = actions.onAdd, enabled = state.canPay, busy = state.tickBusy)
        }
    }
    if (choosingDate) {
        MonthDateDialog(state.payDate, state::payDateAllowed, onChosen = {
            choosingDate = false
            actions.onDate(it)
        }, onDismiss = { choosingDate = false })
    }
}

/** A payment out this month, with its amount — "Likely" when it is close to what is expected. */
@Composable
private fun CandidateRow(candidate: TickCandidate, chosen: Boolean, enabled: Boolean, onMatch: (String) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (chosen) FinAiPalette.Green.copy(alpha = 0.09f) else Mint.card())
            .border(1.dp, if (chosen || candidate.likely) FinAiPalette.Green.copy(alpha = 0.45f) else Mint.edge(), shape)
            .clickable(enabled = enabled, role = Role.Button) { onMatch(candidate.transactionId) }
            .heightIn(min = 52.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null, tint = Mint.greenText(), modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(candidate.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(candidate.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(candidate.amount, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (candidate.likely) {
                Text(
                    strings(Strings.tick_likely),
                    style = MaterialTheme.typography.labelSmall,
                    color = Mint.greenText(),
                    modifier = Modifier.clip(CircleShape).background(Mint.tile(FinAiPalette.Green)).padding(horizontal = 6.dp),
                )
            }
        }
    }
}

/** A date picker that offers only the days of the budget's month. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MonthDateDialog(chosen: LocalDate?, allowed: (LocalDate) -> Boolean, onChosen: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val limit = remember {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = allowed(LocalDate.fromEpochDays(Math.floorDiv(utcTimeMillis, MILLIS_PER_DAY)))
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

private const val MILLIS_PER_DAY = 86_400_000L
