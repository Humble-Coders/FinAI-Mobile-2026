package com.humblesolutions.finai.ui.split

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.R
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.SplitContact
import com.humblesolutions.finai.model.SplitMode
import com.humblesolutions.finai.model.SplitParticipant
import com.humblesolutions.finai.model.SplitSummary
import com.humblesolutions.finai.ui.components.AccountTints
import com.humblesolutions.finai.ui.components.AmountField
import com.humblesolutions.finai.ui.components.ChoiceCard
import com.humblesolutions.finai.ui.components.ErrorText
import com.humblesolutions.finai.ui.components.Field
import com.humblesolutions.finai.ui.components.FieldVectors
import com.humblesolutions.finai.ui.components.FinAiIcon
import com.humblesolutions.finai.ui.components.GradientButton
import com.humblesolutions.finai.ui.components.LightStatusBarIcons
import com.humblesolutions.finai.ui.components.Mint
import com.humblesolutions.finai.ui.components.MintBackdrop
import com.humblesolutions.finai.ui.components.MintHeader
import com.humblesolutions.finai.ui.components.MintPanel
import com.humblesolutions.finai.ui.components.MintTitle
import com.humblesolutions.finai.ui.components.PickerField
import com.humblesolutions.finai.ui.components.Waves
import com.humblesolutions.finai.ui.components.WizardField
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.ui.theme.FinAiPalette
import com.humblesolutions.finai.usecase.ManualEntry
import com.humblesolutions.finai.usecase.SplitExpense
import com.humblesolutions.finai.usecase.SplitSource
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.Money
import kotlinx.datetime.LocalDate

/** What the split flow can ask of its model, passed down rather than reached for. */
internal data class SplitActions(
    val onBack: () -> Unit,
    val onClose: () -> Unit,
    val onHistory: () -> Unit,
    val onChooseSource: (SplitSource) -> Unit,
    val onChooseMode: (SplitMode) -> Unit,
    val onAddPeople: () -> Unit,
    val onRemovePerson: (Int) -> Unit,
    val onContinueFromStart: () -> Unit,
    val onTitleChange: (String) -> Unit,
    val onDateChosen: (LocalDate) -> Unit,
    val onTotalChange: (String) -> Unit,
    val onTaxChange: (String) -> Unit,
    val onTipChange: (String) -> Unit,
    val onItemCountChange: (String) -> Unit,
    val onItemChange: (Int, String, String) -> Unit,
    val onAddItem: () -> Unit,
    val onRemoveItem: (Int) -> Unit,
    val onContinueFromBill: () -> Unit,
    val onTakePhoto: () -> Unit,
    val onPickPhoto: () -> Unit,
    val onPickFile: () -> Unit,
    val onRetryReceipt: () -> Unit,
    val onRetake: () -> Unit,
    val onUseManual: () -> Unit,
    val onSearchChange: (String) -> Unit,
    val onToggleContact: (SplitContact) -> Unit,
    val onRetryContacts: () -> Unit,
    val onPickContact: () -> Unit,
    val onNewPerson: () -> Unit,
    val onAskDeleteContact: (SplitContact) -> Unit,
    val onContinueFromPeople: () -> Unit,
    val onToggleAssignee: (Int, Int) -> Unit,
    val onEveryone: (Int) -> Unit,
    val onContinueFromAssign: () -> Unit,
    val onSave: () -> Unit,
    val onSendWhatsApp: (SplitParticipant) -> Unit,
    val onToggleSettled: (String) -> Unit,
    val onCopy: (String) -> Unit,
    val onRetryOpen: () -> Unit,
    val onRetryHistory: () -> Unit,
    val onHistoryShown: () -> Unit,
    val onOpenSplit: (String) -> Unit,
    val onAskDeleteSplit: (SplitSummary) -> Unit,
)

internal val SplitMaxWidth = 560.dp

/**
 * Split expense, one step at a time: the start on Home's green field, then
 * the bill, the people, who had what, and the review, each on the pale
 * frame the import and manual entry share. Every word is shared; every share
 * is the server's once saved and the shared preview's before.
 */
@Composable
internal fun SplitScreen(state: SplitUiState, actions: SplitActions) {
    when (state.step) {
        SplitStep.START -> StartStep(state, actions)

        SplitStep.BILL -> Framed(
            onBack = actions.onBack,
            decoration = Icons.AutoMirrored.Filled.ReceiptLong,
            bottom = if (state.showsBillForm) {
                { ContinueBar(state.billNotice, state.canContinueFromBill, actions.onContinueFromBill) }
            } else {
                null
            },
        ) { BillStep(state, actions) }

        SplitStep.PEOPLE -> Framed(
            onBack = actions.onBack,
            decoration = null,
            bottom = {
                ContinueBar(
                    notice = state.peopleBlock?.let { SplitExpense.blockText(it, state.locale) },
                    enabled = state.canContinueFromPeople,
                    onContinue = actions.onContinueFromPeople,
                    above = SplitExpense.selectedLine(state.chosenCount, state.locale),
                )
            },
        ) { PeopleStep(state, actions) }

        SplitStep.ASSIGN -> Framed(
            onBack = actions.onBack,
            decoration = null,
            bottom = {
                ContinueBar(
                    notice = state.assignBlock?.let { SplitExpense.blockText(it, state.locale) },
                    enabled = state.assignBlock == null,
                    onContinue = actions.onContinueFromAssign,
                )
            },
        ) { AssignStep(state, actions) }

        SplitStep.REVIEW -> Framed(
            onBack = actions.onBack,
            decoration = null,
            bottom = if (state.reviewingSaved) {
                null
            } else {
                { SaveBar(state, actions) }
            },
        ) { ReviewStep(state, actions) }

        SplitStep.HISTORY -> Framed(onBack = actions.onBack, decoration = Icons.Outlined.History, bottom = null) {
            HistoryStep(state, actions)
        }
    }
}

// ── The frame ───────────────────────────────────────────────────────────

/** The pale field with back and the screen's name, the content scrolling, and its button kept in reach. */
@Composable
private fun Framed(
    onBack: () -> Unit,
    decoration: ImageVector?,
    bottom: (@Composable () -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        MintBackdrop(decoration = decoration)
        // safeDrawing includes the keyboard, so a focused field and the bar under it stay above it.
        Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            MintHeader(title = strings(Strings.split_title), onBack = onBack)
            Column(
                Modifier
                    .widthIn(max = SplitMaxWidth)
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                content()
                Spacer(Modifier.height(16.dp))
            }
            bottom?.invoke()
        }
    }
}

/** Continue, pinned under the step, with what still stops it said in words above. */
@Composable
internal fun ContinueBar(notice: String?, enabled: Boolean, onContinue: () -> Unit, above: String? = null) {
    BottomBar {
        above?.let {
            Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Mint.greenText())
        }
        notice?.let { Notice(it) }
        GradientButton(text = strings(Strings.split_continue), onClick = onContinue, enabled = enabled)
    }
}

@Composable
internal fun BottomBar(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Box(contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .widthIn(max = SplitMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

/** What still stops the step, in the muted voice: advice, not an alarm. */
@Composable
internal fun Notice(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() },
    )
}

/**
 * A person as an initials monogram — no photos — tinted so neighbours differ.
 * Drawing only: the name beside it is what a screen reader hears.
 */
@Composable
internal fun Monogram(initials: String, index: Int, size: Dp = 40.dp, ringed: Boolean = false) {
    val accent = if (index == 0) FinAiPalette.GreenDeep else AccountTints[index % AccountTints.size]
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Mint.tile(accent))
            .then(if (ringed) Modifier.border(1.5.dp, accent, CircleShape) else Modifier)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials,
            style = if (size < 32.dp) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (index == 0) Mint.greenText() else accent,
            maxLines = 1,
        )
    }
}

// ── Start ───────────────────────────────────────────────────────────────

/** The way in, on Home's green field: where the figures come from, how to split, and who with. */
@Composable
private fun StartStep(state: SplitUiState, actions: SplitActions) {
    val dark = isSystemInDarkTheme()
    LightStatusBarIcons(dark)
    Box(Modifier.fillMaxSize().background(Field.brush(dark))) {
        Waves(Modifier.fillMaxSize())
        FieldVectors(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier
                    .widthIn(max = SplitMaxWidth)
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = actions.onClose) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = strings(Strings.action_back), tint = Field.ink())
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = actions.onHistory) {
                        Icon(Icons.Outlined.History, contentDescription = strings(Strings.split_history), tint = Field.ink())
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        strings(Strings.split_title),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = Field.ink(),
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(strings(Strings.split_subtitle), style = MaterialTheme.typography.bodyLarge, color = Field.ink(0.85f))
                }
                StartSheet(state, actions)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun StartSheet(state: SplitUiState, actions: SplitActions) {
    val draft = state.draft
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Mint.panel())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceCard(
                icon = Icons.Filled.UploadFile,
                accent = FinAiPalette.Green,
                title = strings(Strings.split_upload_receipt),
                detail = strings(Strings.split_upload_receipt_detail),
                selected = draft.source == SplitSource.RECEIPT,
                onClick = { actions.onChooseSource(SplitSource.RECEIPT) },
            )
            ChoiceCard(
                icon = Icons.Filled.Edit,
                accent = FinAiPalette.Blue,
                title = strings(Strings.split_add_manually),
                detail = strings(Strings.split_add_manually_detail),
                selected = draft.source == SplitSource.MANUAL,
                onClick = { actions.onChooseSource(SplitSource.MANUAL) },
            )
        }

        SectionTitle(strings(Strings.split_how))
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceCard(
                icon = Icons.Filled.Balance,
                accent = FinAiPalette.Green,
                title = strings(Strings.split_equally),
                detail = strings(Strings.split_equally_detail),
                selected = draft.mode == SplitMode.EQUAL,
                onClick = { actions.onChooseMode(SplitMode.EQUAL) },
            )
            ChoiceCard(
                icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                accent = Color(0xFFF59E0B),
                title = strings(Strings.split_by_items),
                detail = strings(Strings.split_by_items_detail),
                selected = draft.mode == SplitMode.ITEMS,
                onClick = { actions.onChooseMode(SplitMode.ITEMS) },
            )
        }

        SectionTitle(strings(Strings.split_between))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            draft.people.forEachIndexed { index, person ->
                val label = SplitExpense.label(person, state.locale)
                val remove = strings(Strings.split_remove_person, label)
                Column(
                    Modifier.width(64.dp).semantics(mergeDescendants = true) {
                        // The person themselves is on every bill; anyone else can come off it.
                        if (!person.isSelf) {
                            customActions = listOf(
                                CustomAccessibilityAction(remove) {
                                    actions.onRemovePerson(index)
                                    true
                                },
                            )
                        }
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                        Monogram(SplitExpense.initials(person, state.locale), index, size = 48.dp, ringed = person.isSelf)
                        if (!person.isSelf) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .border(1.dp, Mint.edge(), CircleShape)
                                    .clickable(role = Role.Button, onClickLabel = remove) { actions.onRemovePerson(index) }
                                    .clearAndSetSemantics {},
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(
                Modifier
                    .width(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = actions.onAddPeople)
                    .heightIn(min = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).border(1.5.dp, Mint.greenText(), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { FinAiIcon(Icons.Filled.Add, tint = Mint.greenText(), size = 22.dp) }
                Text(
                    strings(Strings.split_add_people),
                    style = MaterialTheme.typography.labelMedium,
                    color = Mint.greenText(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        GradientButton(text = strings(Strings.split_continue), onClick = actions.onContinueFromStart, enabled = state.canContinueFromStart)
        Text(
            strings(Strings.split_edit_later),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ── The bill ────────────────────────────────────────────────────────────

@Composable
private fun BillStep(state: SplitUiState, actions: SplitActions) {
    val (title, subtitle) = when {
        state.fromReceipt && state.receiptStage == ReceiptStage.READ -> Strings.split_receipt_review to Strings.split_receipt_review_detail
        state.fromReceipt -> Strings.split_upload_receipt to Strings.split_upload_receipt_detail
        else -> Strings.split_add_manually to Strings.split_add_manually_detail
    }
    MintTitle(strings(title), strings(subtitle))
    if (state.showsBillForm) BillForm(state, actions) else ReceiptPicker(state, actions)
}

/** The three ways a receipt gets in, and — after a read that failed — why, with a way on. */
@Composable
private fun ReceiptPicker(state: SplitUiState, actions: SplitActions) {
    MintPanel {
        state.receiptErrorKey?.let { key ->
            ErrorText(key)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.receiptCanRetry) TextButton(onClick = actions.onRetryReceipt) { Text(strings(Strings.action_retry)) }
                TextButton(onClick = actions.onUseManual) { Text(strings(Strings.split_add_manually)) }
            }
        }
        ChoiceCard(
            icon = Icons.Filled.PhotoCamera,
            accent = FinAiPalette.Green,
            title = strings(Strings.import_take_photo),
            detail = strings(Strings.import_take_photo_detail),
            onClick = actions.onTakePhoto,
            tinted = true,
        )
        ChoiceCard(
            icon = Icons.Filled.Image,
            accent = FinAiPalette.Blue,
            title = strings(Strings.import_pick_photo),
            detail = strings(Strings.import_pick_photo_detail),
            onClick = actions.onPickPhoto,
        )
        ChoiceCard(
            icon = Icons.Filled.UploadFile,
            accent = Color(0xFFF59E0B),
            title = strings(Strings.import_pick_file_title),
            detail = strings(Strings.import_pick_file_detail),
            onClick = actions.onPickFile,
        )
    }
}

/** The bill as typed, or as the receipt read it: every figure editable. */
@Composable
private fun BillForm(state: SplitUiState, actions: SplitActions) {
    val draft = state.draft
    val symbol = Money.symbol(state.currency)
    val zero = Money.normalize("0", Money.fractionDigits(state.currency)).orEmpty()
    var choosingDate by rememberSaveable { mutableStateOf(false) }

    MintPanel {
        if (state.fromReceipt && state.unreadable > 0) {
            Notice(strings(Strings.split_receipt_unreadable, state.unreadable.toString()))
        }
        WizardField(
            value = draft.title,
            onValueChange = actions.onTitleChange,
            label = strings(Strings.split_bill_name),
            placeholder = strings(Strings.split_bill_name_hint),
            capitalization = KeyboardCapitalization.Sentences,
        )
        PickerField(
            label = strings(Strings.split_bill_date),
            value = Dates.parse(draft.occurredOn)?.let { state.dateText(draft.occurredOn) },
            placeholder = strings(Strings.manual_entry_date_placeholder),
            onClick = { choosingDate = true },
            leading = Icons.Filled.CalendarMonth,
        )

        if (!state.byItems) {
            AmountField(
                value = draft.total,
                onValueChange = actions.onTotalChange,
                label = strings(Strings.split_total),
                symbol = symbol,
                placeholder = zero,
                imeAction = ImeAction.Done,
            )
        } else {
            if (!state.fromReceipt) {
                WizardField(
                    value = state.itemCountInput,
                    onValueChange = actions.onItemCountChange,
                    label = strings(Strings.split_item_count),
                    keyboardType = KeyboardType.Number,
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Done,
                )
            }
            if (draft.items.isNotEmpty()) SectionTitle(strings(Strings.split_items_title, draft.items.size.toString()))
            draft.items.forEachIndexed { index, item ->
                ItemFields(
                    index = index,
                    name = item.name,
                    amount = item.amount,
                    symbol = symbol,
                    zero = zero,
                    onChange = { name, amount -> actions.onItemChange(index, name, amount) },
                    // A receipt's lines can be wrong; a typed bill's count says how many.
                    onRemove = if (state.fromReceipt) ({ actions.onRemoveItem(index) }) else null,
                )
            }
            if (state.fromReceipt && state.canAddItem) {
                TextButton(onClick = actions.onAddItem) {
                    FinAiIcon(Icons.Filled.Add, tint = Mint.greenText())
                    Spacer(Modifier.width(6.dp))
                    Text(strings(Strings.split_add_item), color = Mint.greenText())
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    AmountField(draft.tax, actions.onTaxChange, strings(Strings.split_tax), symbol, zero, large = false)
                }
                Box(Modifier.weight(1f)) {
                    AmountField(draft.tip, actions.onTipChange, strings(Strings.split_tip), symbol, zero, large = false, imeAction = ImeAction.Done)
                }
            }
        }

        if (state.fromReceipt) {
            TextButton(onClick = actions.onRetake) {
                FinAiIcon(Icons.Filled.PhotoCamera, tint = Mint.greenText())
                Spacer(Modifier.width(6.dp))
                Text(strings(Strings.split_retake), color = Mint.greenText())
            }
        }
    }

    if (choosingDate) {
        BillDateDialog(
            chosen = Dates.parse(draft.occurredOn),
            onChosen = {
                actions.onDateChosen(it)
                choosingDate = false
            },
            onDismiss = { choosingDate = false },
        )
    }
}

/** One item: its name ("Item 2") and its price, side by side. */
@Composable
internal fun ItemFields(index: Int, name: String, amount: String, symbol: String, zero: String, onChange: (String, String) -> Unit, onRemove: (() -> Unit)? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(1.4f)) {
            WizardField(
                value = name,
                onValueChange = { onChange(it, amount) },
                label = strings(Strings.split_item_name, (index + 1).toString()),
                capitalization = KeyboardCapitalization.Sentences,
            )
        }
        Box(Modifier.weight(1f)) {
            AmountField(
                value = amount,
                onValueChange = { onChange(name, it) },
                label = strings(Strings.split_item_price),
                symbol = symbol,
                placeholder = zero,
                large = false,
            )
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = strings(Strings.split_item_remove, name.ifBlank { strings(Strings.split_item_name, (index + 1).toString()) }),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val MILLIS_PER_DAY = 86_400_000L

/** The calendar, up to today: a bill already paid. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BillDateDialog(chosen: LocalDate?, onChosen: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    // Today where the person is, as the picker counts days: UTC midnight of the local date.
    val latest = remember { ManualEntry.today().toEpochDays() * MILLIS_PER_DAY }
    val limit = remember(latest) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= latest
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

/** Used by the review: the bill's name, when, and what it came to. */
@Composable
internal fun BillCard(title: String, date: String, items: Int?, total: String?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Mint.card())
            .border(1.dp, Mint.edge(), shape)
            .semantics(mergeDescendants = true) {}
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Mint.tile(FinAiPalette.Green)),
            contentAlignment = Alignment.Center,
        ) { FinAiIcon(Icons.AutoMirrored.Filled.ReceiptLong, tint = Mint.greenText(), size = 22.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(date, items?.let { strings(Strings.split_items_title, it.toString()) }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        total?.let { Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
    }
}

/** Hidden from screen readers: a label heard with its row. */
internal fun Modifier.describedAs(text: String): Modifier = clearAndSetSemantics { contentDescription = text }
