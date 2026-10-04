package com.humblesolutions.finai.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.R
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Category
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
import com.humblesolutions.finai.usecase.CorrectionDraft
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money
import kotlinx.datetime.LocalDate

/**
 * One transaction being edited, as the editor sheet reads it.
 *
 * Shared by the review queue and the full transaction list: the same fields,
 * the same shared rules ([com.humblesolutions.finai.usecase.ReviewQueue]),
 * the same sheet. Each screen keeps its own model and builds this from it.
 */
data class TransactionEditorState(
    val row: Transaction,
    val draft: CorrectionDraft,
    /** Why Save is off, said under it; never "nothing changed" before a touch. */
    val notice: CorrectionBlock?,
    val errorKey: String?,
    val canSave: Boolean,
    val saving: Boolean,
    val categoryName: String?,
    /** The household's own categories first, then the shared ones. */
    val pickableCategories: List<Pair<Category, String>>,
    val today: LocalDate,
    val newCategoryName: String?,
    val newCategoryErrorKey: String?,
    val canCreateCategory: Boolean,
    val creatingCategory: Boolean,
    val titleKey: String = Strings.review_edit_title,
)

/** What the editor sheet can ask of the screen that opened it. */
class TransactionEditorActions(
    val onCancel: () -> Unit,
    val onDateChange: (LocalDate) -> Unit,
    val onAmountChange: (String) -> Unit,
    val onDirectionChange: (TransactionDirection) -> Unit,
    val onDescriptionChange: (String) -> Unit,
    val onCategoryChosen: (String) -> Unit,
    val onSave: () -> Unit,
    val onOpenNewCategory: () -> Unit,
    val onNewCategoryName: (String) -> Unit,
    val onCreateCategory: () -> Unit,
    val onCancelNewCategory: () -> Unit,
)

/** The editor: date, amount, direction, description and category. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionEditorSheet(state: TransactionEditorState, actions: TransactionEditorActions) {
    var choosingCategory by rememberSaveable { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = actions.onCancel, sheetState = sheet) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = strings(state.titleKey),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            CorrectionFields(state, actions) { choosingCategory = true }
            ErrorText(state.errorKey)
            if (state.errorKey == null) ErrorText(state.notice?.messageKey)
            GradientButton(
                text = strings(Strings.review_edit_save),
                onClick = actions.onSave,
                enabled = state.canSave,
                busy = state.saving,
            )
            TextButton(
                onClick = actions.onCancel,
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
private fun CorrectionFields(state: TransactionEditorState, actions: TransactionEditorActions, onPickCategory: () -> Unit) {
    val row = state.row
    val focus = LocalFocusManager.current
    var choosingDate by rememberSaveable { mutableStateOf(false) }

    PickerField(
        label = strings(Strings.manual_entry_date_label),
        value = state.draft.occurredOn?.let(Dates::display),
        placeholder = strings(Strings.manual_entry_date_placeholder),
        onClick = { choosingDate = true },
        isError = state.notice == CorrectionBlock.NO_DATE || state.notice == CorrectionBlock.FUTURE_DATE,
    )
    AmountField(
        value = state.draft.amount,
        onValueChange = actions.onAmountChange,
        label = strings(Strings.manual_entry_amount_label),
        symbol = Money.symbol(row.currency),
        placeholder = Money.normalize("0", Money.fractionDigits(row.currency)).orEmpty(),
        isError = state.notice in AmountBlocks,
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
        isError = state.notice in DescriptionBlocks,
        onDone = { focus.clearFocus() },
    )
    PickerField(
        label = strings(Strings.review_category_label),
        value = state.categoryName,
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
    state: TransactionEditorState,
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
private fun NewCategorySheet(state: TransactionEditorState, actions: TransactionEditorActions) {
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
