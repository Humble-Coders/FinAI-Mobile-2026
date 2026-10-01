package com.humblesolutions.finai.ui.review

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.R
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.model.TransactionDirection
import com.humblesolutions.finai.ui.components.AmountField
import com.humblesolutions.finai.ui.components.ErrorText
import com.humblesolutions.finai.ui.components.FieldLabel
import com.humblesolutions.finai.ui.components.GradientButton
import com.humblesolutions.finai.ui.components.PickerField
import com.humblesolutions.finai.ui.components.SheetRow
import com.humblesolutions.finai.ui.components.SheetTitle
import com.humblesolutions.finai.ui.components.WizardField
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.usecase.CorrectionBlock
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money
import kotlinx.datetime.LocalDate

/** What the review screen can ask its model to do. */
class ReviewActions(
    val onClose: () -> Unit,
    val onRetry: () -> Unit,
    val onLoadMore: () -> Unit,
    val onConfirmAll: () -> Unit,
    val onConfirm: (String) -> Unit,
    val onEdit: (String) -> Unit,
    val onDelete: (String) -> Unit,
    val onUndoDelete: () -> Unit,
    val onDismissAnnouncements: () -> Unit,
    val onCancelEdit: () -> Unit,
    val onDateChange: (LocalDate) -> Unit,
    val onAmountChange: (String) -> Unit,
    val onDirectionChange: (TransactionDirection) -> Unit,
    val onDescriptionChange: (String) -> Unit,
    val onCategoryChosen: (String) -> Unit,
    val onSaveCorrection: () -> Unit,
    val onOpenNewCategory: () -> Unit,
    val onNewCategoryName: (String) -> Unit,
    val onCreateCategory: () -> Unit,
    val onCancelNewCategory: () -> Unit,
)

/**
 * The review queue (#32): the rows extraction could not resolve.
 *
 * Not an error list to apologise for — the app asking someone to confirm their
 * own money. Twenty right rows and two wrong ones is the normal case, so
 * confirming is one tap and fixing is two.
 */
@Composable
fun ReviewScreen(state: ReviewUiState, actions: ReviewActions) {
    val ground = MaterialTheme.colorScheme.background

    // The app's coin loader covers the first load; underneath it, just ground.
    if (state.loading) {
        Box(Modifier.fillMaxSize().background(ground))
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().background(ground).safeDrawingPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Header(onClose = actions.onClose)
        when {
            state.loadFailed -> LoadFailed(state.errorKey, actions.onRetry)
            state.isEmpty -> Empty(onClose = actions.onClose)
            else -> Queue(state, actions, Modifier.weight(1f))
        }
    }

    if (state.editing != null) CorrectionSheet(state, actions)
}

private val ContentMaxWidth = 560.dp

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
            text = strings(Strings.review_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp).semantics { heading() },
        )
    }
}

@Composable
private fun LoadFailed(errorKey: String?, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(strings(Strings.review_load_failed), textAlign = TextAlign.Center)
        if (errorKey != null && errorKey != Strings.error_network) ErrorText(errorKey)
        GradientButton(text = strings(Strings.review_retry), onClick = onRetry)
    }
}

/** Nothing waiting is a good outcome, and is worded as one. */
@Composable
private fun Empty(onClose: () -> Unit) {
    Column(
        modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = strings(Strings.review_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = strings(Strings.review_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        GradientButton(text = strings(Strings.import_done), onClick = onClose)
    }
}

@Composable
private fun Queue(state: ReviewUiState, actions: ReviewActions, modifier: Modifier) {
    val listState = rememberLazyListState()
    // One page ahead of the bottom, so scrolling never stops to wait.
    LaunchedEffect(listState, state.canLoadMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last ->
                if (state.canLoadMore && last >= listState.layoutInfo.totalItemsCount - 3) actions.onLoadMore()
            }
    }

    Column(modifier.widthIn(max = ContentMaxWidth).fillMaxWidth()) {
        // Re-reading after an action: a thin line, not the coin, so the list
        // keeps its place.
        if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp),
        ) {
            item {
                Text(
                    text = strings(Strings.review_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            state.byDate.forEach { (day, rows) ->
                item(key = "day-$day") { DayHeading(day) }
                items(rows, key = { it.id }) { row ->
                    ReviewRow(row = row, state = state, actions = actions)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            if (state.loadingMore) {
                item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Said once, and read out: what was confirmed, what a correction moved.
            state.announcements.forEach { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            ErrorText(state.errorKey)
            state.pendingDelete?.let { UndoBar(actions.onUndoDelete) }
            GradientButton(
                text = state.confirmAllLabel,
                onClick = actions.onConfirmAll,
                enabled = state.canConfirmAll,
                busy = state.confirmingAll,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DayHeading(day: String) {
    Text(
        text = Dates.parse(day)?.let(Dates::display) ?: day,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

/**
 * One row: what it is, what it cost, why it is here.
 *
 * Money out and money in are told apart by a sign and a word, never by colour
 * alone — the amount reads the same to someone who cannot see the difference.
 */
@Composable
private fun ReviewRow(row: Transaction, state: ReviewUiState, actions: ReviewActions) {
    val busy = state.isBusy(row.id)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy) { actions.onEdit(row.id) }
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = row.description.orEmpty().ifBlank { strings(Strings.review_reason_other) },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.categoryNameFor(row) ?: strings(Strings.review_category_choose),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = (if (row.direction == TransactionDirection.CREDIT) "+" else "−") + state.amountFor(row),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = strings(
                        if (row.direction == TransactionDirection.CREDIT) {
                            Strings.manual_entry_direction_in
                        } else {
                            Strings.manual_entry_direction_out
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        AssistChip(
            onClick = { actions.onEdit(row.id) },
            label = { Text(strings(state.reasonKeyFor(row)), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.onSurfaceVariant),
            enabled = !busy,
        )
        // A suspected duplicate shows what it matched, before anything can be
        // done to it: this is the one case where deleting loses real data.
        state.duplicateTextFor(row)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.errorFor(row.id)?.let { ErrorText(it) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) {
                Text(
                    strings(Strings.review_row_saving),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                TextButton(onClick = { actions.onConfirm(row.id) }) {
                    Text(strings(if (row.duplicateOf != null) Strings.review_not_the_same else Strings.action_done))
                }
                TextButton(onClick = { actions.onEdit(row.id) }) { Text(strings(Strings.review_edit_title)) }
                TextButton(onClick = { actions.onDelete(row.id) }) { Text(strings(Strings.review_delete)) }
            }
        }
    }
}

/** Deleted — but nothing has been sent yet, so Undo simply puts it back. */
@Composable
private fun UndoBar(onUndo: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = strings(Strings.review_deleted),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(onClick = onUndo) {
                Text(strings(Strings.review_undo), color = MaterialTheme.colorScheme.inversePrimary)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CorrectionSheet(state: ReviewUiState, actions: ReviewActions) {
    if (state.editing == null) return
    var choosingCategory by rememberSaveable { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = actions.onCancelEdit, sheetState = sheet) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = strings(Strings.review_edit_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            CorrectionFields(state, actions) { choosingCategory = true }
            ErrorText(state.editErrorKey)
            if (state.editErrorKey == null) ErrorText(state.editNotice?.messageKey)
            GradientButton(
                text = strings(Strings.review_edit_save),
                onClick = actions.onSaveCorrection,
                enabled = state.canSaveCorrection,
                busy = state.saving,
            )
            TextButton(
                onClick = actions.onCancelEdit,
                enabled = !state.saving,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text(strings(Strings.review_edit_cancel)) }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (choosingCategory) {
        CategorySheet(
            state = state,
            onChosen = {
                actions.onCategoryChosen(it)
                choosingCategory = false
            },
            onAdd = {
                choosingCategory = false
                actions.onOpenNewCategory()
            },
            onDismiss = { choosingCategory = false },
        )
    }
    if (state.newCategoryName != null) NewCategorySheet(state, actions)
}

/** The fields of a correction: date, amount, direction, description, category. */
@Composable
private fun CorrectionFields(state: ReviewUiState, actions: ReviewActions, onPickCategory: () -> Unit) {
    val row = state.editing ?: return
    val focus = LocalFocusManager.current
    var choosingDate by rememberSaveable { mutableStateOf(false) }

    PickerField(
        label = strings(Strings.manual_entry_date_label),
        value = state.draft.occurredOn?.let(Dates::display),
        placeholder = strings(Strings.manual_entry_date_placeholder),
        onClick = { choosingDate = true },
        isError = state.editNotice == CorrectionBlock.NO_DATE || state.editNotice == CorrectionBlock.FUTURE_DATE,
    )
    AmountField(
        value = state.draft.amount,
        onValueChange = actions.onAmountChange,
        label = strings(Strings.manual_entry_amount_label),
        symbol = Money.symbol(row.currency),
        placeholder = Money.normalize("0", Money.fractionDigits(row.currency)).orEmpty(),
        isError = state.editNotice in AmountBlocks,
        large = false,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(strings(Strings.manual_entry_direction_label))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Directions.forEachIndexed { index, (direction, labelKey) ->
                SegmentedButton(
                    selected = state.draft.direction == direction,
                    onClick = { actions.onDirectionChange(direction) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = Directions.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.inverseSurface,
                        activeContentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        inactiveContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(strings(labelKey), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    WizardField(
        value = state.draft.description,
        onValueChange = actions.onDescriptionChange,
        label = strings(Strings.manual_entry_description_label),
        placeholder = strings(Strings.manual_entry_description_hint),
        imeAction = ImeAction.Done,
        capitalization = KeyboardCapitalization.Sentences,
        isError = state.editNotice in DescriptionBlocks,
        onDone = { focus.clearFocus() },
    )
    PickerField(
        label = strings(Strings.review_category_label),
        value = state.editCategoryName,
        placeholder = strings(Strings.review_category_choose),
        onClick = onPickCategory,
    )

    if (choosingDate) {
        CorrectionDateDialog(
            chosen = state.draft.occurredOn,
            today = state.today,
            onChosen = {
                actions.onDateChange(it)
                choosingDate = false
            },
            onDismiss = { choosingDate = false },
        )
    }
}

private val Directions = listOf(
    TransactionDirection.DEBIT to Strings.manual_entry_direction_out,
    TransactionDirection.CREDIT to Strings.manual_entry_direction_in,
)
private val AmountBlocks = setOf(
    CorrectionBlock.NO_AMOUNT,
    CorrectionBlock.AMOUNT_NOT_MONEY,
    CorrectionBlock.AMOUNT_ZERO,
)
private val DescriptionBlocks = setOf(CorrectionBlock.NO_DESCRIPTION, CorrectionBlock.DESCRIPTION_TOO_LONG)

private const val MILLIS_PER_DAY = 86_400_000L

/** The calendar, with the same limit the shared rule holds a correction to. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CorrectionDateDialog(
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

/** The taxonomy and the household's own, with a way to add one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategorySheet(
    state: ReviewUiState,
    onChosen: (String) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        SheetTitle(strings(Strings.review_category_label))
        LazyColumn(Modifier.fillMaxWidth()) {
            items(state.pickableCategories, key = { it.first.id }) { (category, name) ->
                SheetRow(
                    title = name,
                    detail = null,
                    selected = category.id == state.draft.categoryId,
                    onClick = { onChosen(category.id) },
                )
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SheetRow(
                    title = strings(Strings.review_category_new),
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
private fun NewCategorySheet(state: ReviewUiState, actions: ReviewActions) {
    val name = state.newCategoryName ?: return
    val focus = LocalFocusManager.current
    val creating by rememberUpdatedState(state.creatingCategory)
    val sheet = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !creating },
    )
    ModalBottomSheet(onDismissRequest = actions.onCancelNewCategory, sheetState = sheet) {
        Column(
            modifier = Modifier.fillMaxWidth().imePadding().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = strings(Strings.review_category_new),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            WizardField(
                value = name,
                onValueChange = actions.onNewCategoryName,
                label = strings(Strings.review_category_new_name),
                placeholder = strings(Strings.review_category_new_hint),
                imeAction = ImeAction.Done,
                isError = state.newCategoryErrorKey != null,
                onDone = { focus.clearFocus() },
            )
            ErrorText(state.newCategoryErrorKey)
            GradientButton(
                text = strings(Strings.review_category_create),
                onClick = {
                    focus.clearFocus()
                    actions.onCreateCategory()
                },
                enabled = state.canCreateCategory,
                busy = state.creatingCategory,
            )
            TextButton(
                onClick = actions.onCancelNewCategory,
                enabled = !state.creatingCategory,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text(strings(Strings.account_new_cancel)) }
            Spacer(Modifier.height(16.dp))
        }
    }
}
