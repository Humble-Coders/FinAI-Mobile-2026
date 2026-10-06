package com.humblesolutions.finai.ui.manualentry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.R
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.AccountKind
import com.humblesolutions.finai.model.TransactionDirection
import com.humblesolutions.finai.ui.components.AccountSheet
import com.humblesolutions.finai.ui.components.AccountTints
import com.humblesolutions.finai.ui.components.AmountField
import com.humblesolutions.finai.ui.components.ErrorText
import com.humblesolutions.finai.ui.components.FieldLabel
import com.humblesolutions.finai.ui.components.FinAiIcon
import com.humblesolutions.finai.ui.components.GradientButton
import com.humblesolutions.finai.ui.components.IconTile
import com.humblesolutions.finai.ui.components.Mint
import com.humblesolutions.finai.ui.components.MintBackdrop
import com.humblesolutions.finai.ui.components.MintHeader
import com.humblesolutions.finai.ui.components.MintPanel
import com.humblesolutions.finai.ui.components.NewAccountSheet
import com.humblesolutions.finai.ui.components.PickerField
import com.humblesolutions.finai.ui.components.SheetRow
import com.humblesolutions.finai.ui.components.SheetTitle
import com.humblesolutions.finai.ui.components.WizardField
import com.humblesolutions.finai.ui.components.fieldFrame
import com.humblesolutions.finai.ui.components.neutralChipColors
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.ui.theme.FinAiPalette
import com.humblesolutions.finai.usecase.ManualEntryBlock
import kotlinx.datetime.LocalDate

/** What the screen can ask its model to do. */
class ManualEntryActions(
    val onClose: () -> Unit,
    val onRetry: () -> Unit,
    val onAccountChosen: (String) -> Unit,
    val onDateChosen: (LocalDate) -> Unit,
    val onToday: () -> Unit,
    val onRefreshToday: () -> Unit,
    val onAmountChange: (String) -> Unit,
    val onDirectionChosen: (TransactionDirection) -> Unit,
    val onDescriptionChange: (String) -> Unit,
    val onCategoryChosen: (String?) -> Unit,
    val onSave: () -> Unit,
    val onKeepDuplicate: () -> Unit,
    val onDismissDuplicate: () -> Unit,
    val onOpenNewAccount: () -> Unit,
    val onNewAccountName: (String) -> Unit,
    val onNewAccountKind: (AccountKind) -> Unit,
    val onCreateAccount: () -> Unit,
    val onCancelNewAccount: () -> Unit,
)

/**
 * One transaction typed in by hand (#30).
 *
 * Nothing that decides where the money goes or which period it lands in is
 * filled in for the user: the account, date, amount and direction all start
 * empty, with a one-tap Today beside the date. Save stays disabled until the
 * shared rule is satisfied, and the notice under it says why.
 *
 * @param fromUnreadable opened because a statement could not be read (#31):
 *   the screen says so, so the person knows why they are typing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualEntryScreen(state: ManualEntryUiState, fromUnreadable: Boolean, actions: ManualEntryActions) {
    val focus = LocalFocusManager.current

    // The app's coin loader covers the first load; underneath it, just ground.
    if (state.loading) {
        MintBackdrop(decoration = Icons.Filled.CreditCard)
        return
    }

    var choosingAccount by rememberSaveable { mutableStateOf(false) }
    var choosingDate by rememberSaveable { mutableStateOf(false) }
    var choosingCategory by rememberSaveable { mutableStateOf(false) }

    MintBackdrop(decoration = Icons.Filled.CreditCard)
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Status bar, navigation bar, keyboard *and* the camera cutout:
            // held sideways, a phone's cutout sits beside the content, which
            // the bars alone do not account for. The ground still bleeds under.
            .safeDrawingPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MintHeader(title = strings(Strings.manual_entry_title), onBack = actions.onClose)

        if (state.loadFailed) {
            LoadFailed(state.errorKey, actions.onRetry.takeIf { state.canRetry })
            return@Column
        }

        Column(
            modifier = Modifier
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (fromUnreadable) UnreadableNote()

            MintPanel {
                DirectionChoice(
                    chosen = state.draft.direction,
                    onChosen = actions.onDirectionChosen,
                    isError = state.notice == ManualEntryBlock.NO_DIRECTION,
                )

                AccountField(
                    state = state,
                    onClick = { choosingAccount = true },
                    isError = state.notice == ManualEntryBlock.NO_ACCOUNT,
                )

                DateRow(
                    state,
                    onPick = {
                        // The calendar's last pickable day is today as of now,
                        // not as of the last edit: the screen may have sat open
                        // past midnight.
                        actions.onRefreshToday()
                        choosingDate = true
                    },
                    onToday = actions.onToday,
                )

                AmountField(
                    value = state.draft.amount,
                    onValueChange = actions.onAmountChange,
                    label = strings(Strings.manual_entry_amount_label),
                    symbol = state.symbol,
                    placeholder = state.amountPlaceholder,
                    isError = state.notice in AmountBlocks,
                )

                WizardField(
                    value = state.draft.description,
                    onValueChange = actions.onDescriptionChange,
                    label = strings(Strings.manual_entry_description_label),
                    placeholder = strings(Strings.manual_entry_description_hint),
                    imeAction = ImeAction.Done,
                    capitalization = KeyboardCapitalization.Sentences,
                    isError = state.notice in DescriptionBlocks,
                    onDone = { focus.clearFocus() },
                    leading = Icons.AutoMirrored.Filled.ReceiptLong,
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PickerField(
                        label = strings(Strings.manual_entry_category_label),
                        value = state.categoryName ?: strings(Strings.manual_entry_category_none),
                        placeholder = strings(Strings.manual_entry_category_none),
                        onClick = { choosingCategory = true },
                        leading = Icons.AutoMirrored.Filled.Label,
                        trailing = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    )
                    // Said out loud, so an empty category reads as a choice
                    // made, not a field forgotten.
                    if (state.draft.categoryId == null) {
                        Text(
                            text = strings(Strings.manual_entry_category_auto),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Column(
            modifier = Modifier
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ErrorText(state.errorKey)
            // The same rule the button reads, said out loud.
            if (state.errorKey == null) ErrorText(state.notice?.messageKey)
            state.saved?.let { saved ->
                Text(
                    text = strings(saved.messageKey),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            GradientButton(
                text = strings(Strings.manual_entry_save),
                onClick = {
                    focus.clearFocus()
                    actions.onSave()
                },
                enabled = state.canSave,
                busy = state.saving,
            )
            Spacer(Modifier.height(16.dp))
        }
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

    val message = state.duplicateMessage
    if (message != null) {
        AlertDialog(
            onDismissRequest = actions.onDismissDuplicate,
            title = { Text(strings(Strings.manual_entry_duplicate_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = actions.onKeepDuplicate) { Text(strings(Strings.manual_entry_duplicate_keep)) }
            },
            dismissButton = {
                TextButton(onClick = actions.onDismissDuplicate) {
                    Text(strings(Strings.manual_entry_duplicate_cancel))
                }
            },
        )
    }
}

private val ContentMaxWidth = 560.dp

private val AmountBlocks = setOf(
    ManualEntryBlock.NO_AMOUNT,
    ManualEntryBlock.AMOUNT_NOT_MONEY,
    ManualEntryBlock.AMOUNT_ZERO,
)
private val DescriptionBlocks = setOf(ManualEntryBlock.NO_DESCRIPTION, ManualEntryBlock.DESCRIPTION_TOO_LONG)
private val DateBlocks = setOf(ManualEntryBlock.NO_DATE, ManualEntryBlock.FUTURE_DATE)

private const val MILLIS_PER_DAY = 86_400_000L

@Composable
private fun LoadFailed(errorKey: String?, onRetry: (() -> Unit)?) {
    Column(
        modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = strings(Strings.manual_entry_load_failed),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        // The specific cause, when there is one beyond "no connection".
        if (errorKey != null && errorKey != Strings.error_network) ErrorText(errorKey)
        // No button when trying again cannot help: one that does nothing reads as broken.
        if (onRetry != null) GradientButton(text = strings(Strings.manual_entry_retry), onClick = onRetry)
    }
}

@Composable
private fun UnreadableNote() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Text(
            text = strings(Strings.manual_entry_from_unreadable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun DateRow(state: ManualEntryUiState, onPick: () -> Unit, onToday: () -> Unit) {
    val isToday = state.draft.occurredOn == state.today
    PickerField(
        label = strings(Strings.manual_entry_date_label),
        value = state.dateLabel?.let { if (isToday) strings(Strings.manual_entry_date_today_value, it) else it },
        placeholder = strings(Strings.manual_entry_date_placeholder),
        onClick = onPick,
        isError = state.notice in DateBlocks,
        leading = Icons.Filled.CalendarMonth,
        trailing = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        // The one-tap Today, kept: offered until today is the date chosen.
        end = if (isToday) {
            null
        } else {
            {
                FilterChip(
                    selected = false,
                    onClick = onToday,
                    label = { Text(strings(Strings.manual_entry_date_today)) },
                    colors = neutralChipColors(),
                )
            }
        },
    )
}

@Composable
private fun DirectionChoice(
    chosen: TransactionDirection?,
    onChosen: (TransactionDirection) -> Unit,
    isError: Boolean,
) {
    val label = strings(Strings.manual_entry_direction_label)
    // Neither is preselected: "money out" is the likelier answer, which is
    // exactly why guessing it would go unnoticed when it is wrong.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup()
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Directions.forEach { (direction, labelKey) ->
            val selected = chosen == direction
            val shape = RoundedCornerShape(18.dp)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 64.dp)
                    .clip(shape)
                    .background(if (selected) FinAiPalette.Green.copy(alpha = 0.12f) else Mint.card())
                    .border(
                        width = if (selected || isError) 1.5.dp else 1.dp,
                        color = when {
                            selected -> FinAiPalette.Green
                            isError -> MaterialTheme.colorScheme.error
                            else -> Mint.edge()
                        },
                        shape = shape,
                    )
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onChosen(direction) })
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                val tint = if (selected) Mint.greenText() else MaterialTheme.colorScheme.onSurfaceVariant
                FinAiIcon(
                    if (direction == TransactionDirection.CREDIT) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                    tint = tint,
                    size = 22.dp,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    strings(labelKey),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) Mint.greenText() else MaterialTheme.colorScheme.onSurface,
                    // Two lines at a large font size, rather than "Mone…".
                    maxLines = 2,
                )
            }
        }
    }
}

/**
 * The account, as the design has it: its bank tile, its name and kind, and a
 * chevron. Tapping opens the same account sheet, with "Add an account".
 */
@Composable
private fun AccountField(state: ManualEntryUiState, onClick: () -> Unit, isError: Boolean) {
    val label = strings(Strings.manual_entry_account_label)
    val account = state.account
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(label)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .fieldFrame(focused = false, isError = isError)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val index = state.accounts.indexOfFirst { it.id == account?.id }.coerceAtLeast(0)
            IconTile(Icons.Filled.AccountBalance, AccountTints[index % AccountTints.size], size = 44.dp, iconSize = 22.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = account?.name ?: strings(Strings.manual_entry_account_placeholder),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (account == null) muted.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                account?.let { chosen ->
                    val detail = listOfNotNull(chosen.kind.labelKey?.let { strings(it) }, chosen.currency.ifBlank { null })
                        .joinToString(" · ")
                    if (detail.isNotBlank()) {
                        Text(detail, style = MaterialTheme.typography.bodyMedium, color = muted, maxLines = 1)
                    }
                }
            }
            FinAiIcon(Icons.Filled.KeyboardArrowDown, tint = muted, size = 24.dp)
        }
    }
}

// In first, as the design has it. Order only: neither is chosen until tapped.
private val Directions = listOf(
    TransactionDirection.CREDIT to Strings.manual_entry_direction_in,
    TransactionDirection.DEBIT to Strings.manual_entry_direction_out,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategorySheet(state: ManualEntryUiState, onChosen: (String?) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetTitle(strings(Strings.manual_entry_category_label))
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                SheetRow(
                    title = strings(Strings.manual_entry_category_none),
                    detail = strings(Strings.manual_entry_category_auto),
                    selected = state.draft.categoryId == null,
                    onClick = { onChosen(null) },
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
            items(state.categories, key = { it.id }) { category ->
                SheetRow(
                    title = category.name,
                    detail = null,
                    selected = category.id == state.draft.categoryId,
                    onClick = { onChosen(category.id) },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * The calendar. Days after [today] cannot be picked, the same limit the shared
 * rule holds Save to. The picker speaks in UTC midnights, which is exactly a
 * calendar date, so no time zone is involved in the conversion.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateDialog(
    chosen: LocalDate?,
    today: LocalDate,
    onChosen: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val latest = today.toEpochDays() * MILLIS_PER_DAY
    val limit = remember(today) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= latest
            override fun isSelectableYear(year: Int) = year <= today.year
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
                onClick = {
                    picker.selectedDateMillis?.let { onChosen(LocalDate.fromEpochDays(Math.floorDiv(it, MILLIS_PER_DAY))) }
                },
                enabled = picker.selectedDateMillis != null,
            ) { Text(strings(Strings.action_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings(Strings.action_cancel)) } },
    ) {
        DatePicker(state = picker)
    }
}
