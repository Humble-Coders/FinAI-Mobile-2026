package com.humblesolutions.finai.ui.manualentry

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
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
import com.humblesolutions.finai.model.Account
import com.humblesolutions.finai.model.AccountKind
import com.humblesolutions.finai.model.TransactionDirection
import com.humblesolutions.finai.ui.components.AmountField
import com.humblesolutions.finai.ui.components.ErrorText
import com.humblesolutions.finai.ui.components.FieldLabel
import com.humblesolutions.finai.ui.components.GradientButton
import com.humblesolutions.finai.ui.components.PickerField
import com.humblesolutions.finai.ui.components.WizardField
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.usecase.ManualEntryBlock
import com.humblesolutions.finai.usecase.NewAccountBlock
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
    val ground = MaterialTheme.colorScheme.background
    val focus = LocalFocusManager.current

    // The app's coin loader covers the first load; underneath it, just ground.
    if (state.loading) {
        Box(Modifier.fillMaxSize().background(ground))
        return
    }

    var choosingAccount by rememberSaveable { mutableStateOf(false) }
    var choosingDate by rememberSaveable { mutableStateOf(false) }
    var choosingCategory by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ground)
            // Status bar, navigation bar, keyboard *and* the camera cutout:
            // held sideways, a phone's cutout sits beside the content, which
            // the bars alone do not account for. The ground still bleeds under.
            .safeDrawingPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Header(onClose = actions.onClose)

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
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (fromUnreadable) UnreadableNote()

            PickerField(
                label = strings(Strings.manual_entry_account_label),
                value = state.account?.name,
                placeholder = strings(Strings.manual_entry_account_placeholder),
                onClick = { choosingAccount = true },
                isError = state.notice == ManualEntryBlock.NO_ACCOUNT,
            )

            DateRow(
                state,
                onPick = {
                    // The calendar's last pickable day is today as of now, not
                    // as of the last edit: the screen may have sat open past midnight.
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

            DirectionChoice(
                chosen = state.draft.direction,
                onChosen = actions.onDirectionChosen,
                isError = state.notice == ManualEntryBlock.NO_DIRECTION,
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
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PickerField(
                    label = strings(Strings.manual_entry_category_label),
                    value = state.categoryName ?: strings(Strings.manual_entry_category_none),
                    placeholder = strings(Strings.manual_entry_category_none),
                    onClick = { choosingCategory = true },
                )
                // Said out loud, so an empty category reads as a choice made,
                // not a field forgotten.
                if (state.draft.categoryId == null) {
                    Text(
                        text = strings(Strings.manual_entry_category_auto),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Column(
            modifier = Modifier
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ErrorText(state.errorKey)
            // The same rule the button reads, said out loud.
            if (state.errorKey == null) ErrorText(state.notice?.messageKey)
            if (state.saved) {
                Text(
                    text = strings(Strings.manual_entry_saved),
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

    if (state.newAccount != null) NewAccountSheet(state, actions)

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
private fun Header(onClose: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp)) {
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(
                painter = painterResource(R.drawable.ic_back),
                contentDescription = strings(Strings.action_back),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(
            text = strings(Strings.manual_entry_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 56.dp)
                .semantics { heading() },
        )
    }
}

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
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f)) {
            PickerField(
                label = strings(Strings.manual_entry_date_label),
                value = state.dateLabel,
                placeholder = strings(Strings.manual_entry_date_placeholder),
                onClick = onPick,
                isError = state.notice in DateBlocks,
            )
        }
        FilterChip(
            selected = state.draft.occurredOn == state.today,
            onClick = onToday,
            label = { Text(strings(Strings.manual_entry_date_today)) },
            colors = neutralChipColors(),
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }
}

@Composable
private fun DirectionChoice(
    chosen: TransactionDirection?,
    onChosen: (TransactionDirection) -> Unit,
    isError: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(strings(Strings.manual_entry_direction_label))
        // Neither is preselected: "money out" is the likelier answer, which is
        // exactly why guessing it would go unnoticed when it is wrong.
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Directions.forEachIndexed { index, (direction, labelKey) ->
                SegmentedButton(
                    selected = chosen == direction,
                    onClick = { onChosen(direction) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = Directions.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.inverseSurface,
                        activeContentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        inactiveContainerColor = MaterialTheme.colorScheme.surface,
                        inactiveBorderColor = if (isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(strings(labelKey), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private val Directions = listOf(
    TransactionDirection.DEBIT to Strings.manual_entry_direction_out,
    TransactionDirection.CREDIT to Strings.manual_entry_direction_in,
)

/**
 * A chosen chip is drawn in the inverse surface — not the accent, which belongs
 * to Save alone — and both come from theme tokens defined for light and dark.
 */
@Composable
private fun neutralChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.inverseSurface,
    selectedLabelColor = MaterialTheme.colorScheme.inverseOnSurface,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountSheet(
    accounts: List<Account>,
    chosenId: String?,
    onChosen: (String) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        SheetTitle(strings(Strings.manual_entry_account_label))
        LazyColumn(Modifier.fillMaxWidth()) {
            if (accounts.isEmpty()) {
                item {
                    Text(
                        text = strings(Strings.manual_entry_accounts_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
            }
            items(accounts, key = { it.id }) { account ->
                val kind = account.kind.labelKey?.let { strings(it) }
                SheetRow(
                    title = account.name,
                    detail = listOfNotNull(kind, account.currency.ifBlank { null }).joinToString(" · "),
                    selected = account.id == chosenId,
                    onClick = { onChosen(account.id) },
                )
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SheetRow(
                    title = strings(Strings.manual_entry_account_add),
                    detail = null,
                    selected = false,
                    onClick = onAdd,
                    role = Role.Button,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategorySheet(state: ManualEntryUiState, onChosen: (String?) -> Unit, onDismiss: () -> Unit) {
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NewAccountSheet(state: ManualEntryUiState, actions: ManualEntryActions) {
    val draft = state.newAccount ?: return
    val focus = LocalFocusManager.current
    // While the account is being created the sheet must stay up: the model
    // refuses to cancel then, and a sheet swiped away regardless would hide
    // but stay in place, with any error it shows unseen.
    val creating by rememberUpdatedState(state.creatingAccount)
    val sheet = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !creating },
    )
    // Back closes the sheet, not the screen underneath it.
    BackHandler { actions.onCancelNewAccount() }
    ModalBottomSheet(
        onDismissRequest = actions.onCancelNewAccount,
        sheetState = sheet,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = strings(Strings.account_new_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            WizardField(
                value = draft.name,
                onValueChange = actions.onNewAccountName,
                label = strings(Strings.account_new_name_label),
                placeholder = strings(Strings.account_new_name_hint),
                imeAction = ImeAction.Done,
                isError = state.newAccountNotice == NewAccountBlock.NO_NAME ||
                    state.newAccountNotice == NewAccountBlock.NAME_TOO_LONG,
                onDone = { focus.clearFocus() },
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel(strings(Strings.account_new_kind_label))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AccountKind.choosable.forEach { kind ->
                        FilterChip(
                            selected = draft.kind == kind,
                            onClick = { actions.onNewAccountKind(kind) },
                            label = { Text(kind.labelKey?.let { strings(it) }.orEmpty()) },
                            colors = neutralChipColors(),
                        )
                    }
                }
            }
            ErrorText(state.newAccountErrorKey)
            if (state.newAccountErrorKey == null) ErrorText(state.newAccountNotice?.messageKey)
            GradientButton(
                text = strings(Strings.account_new_create),
                onClick = {
                    focus.clearFocus()
                    actions.onCreateAccount()
                },
                enabled = state.canCreateAccount,
                busy = state.creatingAccount,
            )
            TextButton(
                onClick = actions.onCancelNewAccount,
                enabled = !state.creatingAccount,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text(strings(Strings.account_new_cancel)) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
    )
}

/** A row in a chooser: Material's list item, with a radio button when it is one of a set. */
@Composable
private fun SheetRow(
    title: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
    role: Role = Role.RadioButton,
) {
    val choice = role == Role.RadioButton
    ListItem(
        headlineContent = {
            Text(
                text = title,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = detail?.takeIf { it.isNotEmpty() }?.let {
            { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        },
        // The row carries the click, so the button only shows the state.
        trailingContent = if (choice) {
            { RadioButton(selected = selected, onClick = null) }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .then(
                if (choice) {
                    Modifier.selectable(selected = selected, role = role, onClick = onClick)
                } else {
                    Modifier.clickable(role = role, onClick = onClick)
                },
            ),
    )
}

/**
 * The calendar. Days after [today] cannot be picked, the same limit the shared
 * rule holds Save to. The picker speaks in UTC midnights, which is exactly a
 * calendar date, so no time zone is involved in the conversion.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(
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
