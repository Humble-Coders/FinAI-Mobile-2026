package com.humblesolutions.finai.ui.split

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.SplitContact
import com.humblesolutions.finai.ui.components.ChoiceCard
import com.humblesolutions.finai.ui.components.ErrorText
import com.humblesolutions.finai.ui.components.FinAiIcon
import com.humblesolutions.finai.ui.components.GradientButton
import com.humblesolutions.finai.ui.components.Mint
import com.humblesolutions.finai.ui.components.MintPanel
import com.humblesolutions.finai.ui.components.MintTitle
import com.humblesolutions.finai.ui.components.WizardField
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.ui.theme.FinAiPalette
import com.humblesolutions.finai.usecase.SplitExpense
import com.humblesolutions.finai.util.Money

// ── Select people ───────────────────────────────────────────────────────

/** Who the bill is split with: the saved people to tick, the phone's contacts, or someone new. */
@Composable
internal fun PeopleStep(state: SplitUiState, actions: SplitActions) {
    MintTitle(strings(Strings.split_select_people), strings(Strings.split_select_people_detail))
    MintPanel {
        WizardField(
            value = state.search,
            onValueChange = actions.onSearchChange,
            label = strings(Strings.split_search_people),
            leading = Icons.Filled.Search,
            capitalization = KeyboardCapitalization.None,
            imeAction = ImeAction.Done,
        )
        ChoiceCard(
            icon = Icons.Filled.Contacts,
            accent = FinAiPalette.Blue,
            title = strings(Strings.split_from_contacts),
            detail = null,
            onClick = actions.onPickContact,
        )
        ChoiceCard(
            icon = Icons.Filled.PersonAdd,
            accent = FinAiPalette.Green,
            title = strings(Strings.split_add_new_person),
            detail = null,
            onClick = actions.onNewPerson,
        )

        SectionTitle(strings(Strings.split_saved_people))
        state.contactsErrorKey?.let {
            ErrorText(it)
            TextButton(onClick = actions.onRetryContacts) { Text(strings(Strings.action_retry)) }
        }
        if (state.contactsLoaded && state.contacts.isEmpty()) {
            Notice(strings(Strings.split_no_saved_people))
        }
        val chosen = state.chosenContactIds
        state.shownContacts.forEach { contact ->
            val index = state.draft.people.indexOfFirst { it.contactId == contact.id }
            ContactRow(
                contact = contact,
                tint = if (index > 0) index else state.contacts.indexOf(contact) + 1,
                chosen = contact.id in chosen,
                enabled = contact.id in chosen || !state.atPeopleLimit,
                onToggle = { actions.onToggleContact(contact) },
                onDelete = { actions.onAskDeleteContact(contact) },
            )
        }
    }
}

/**
 * A saved person: tapped to put them on the bill or take them off. The bin
 * beside them — or holding the row, or "Delete" from a screen reader's
 * actions — deletes them, after asking.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactRow(contact: SplitContact, tint: Int, chosen: Boolean, enabled: Boolean, onToggle: () -> Unit, onDelete: () -> Unit) {
    val deleteLabel = strings(Strings.split_person_delete, contact.name)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                enabled = enabled,
                role = Role.Checkbox,
                onLongClickLabel = deleteLabel,
                onLongClick = onDelete,
                onClick = onToggle,
            )
            .semantics(mergeDescendants = true) {
                toggleableState = ToggleableState(chosen)
                customActions = listOf(
                    CustomAccessibilityAction(deleteLabel) {
                        onDelete()
                        true
                    },
                )
            }
            .heightIn(min = 56.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = chosen, onCheckedChange = null, enabled = enabled)
        Monogram(SplitExpense.initials(contact.name), tint)
        Column(Modifier.weight(1f)) {
            Text(contact.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(contact.phone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(48.dp).clearAndSetSemantics {}) {
            Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Someone new: a name and the number WhatsApp reaches them on, saved for every bill after. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewPersonSheet(
    draft: NewPersonDraft,
    onName: (String) -> Unit,
    onPhone: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = sheet) {
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
                strings(Strings.split_add_new_person),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            WizardField(
                value = draft.name,
                onValueChange = onName,
                label = strings(Strings.split_person_name),
                enabled = !draft.busy,
            )
            WizardField(
                value = draft.phone,
                onValueChange = onPhone,
                label = strings(Strings.split_person_phone),
                keyboardType = KeyboardType.Phone,
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Done,
                onDone = onSave,
                isError = draft.errorKey != null,
                enabled = !draft.busy,
            )
            Notice(strings(Strings.split_person_phone_hint))
            ErrorText(draft.errorKey)
            GradientButton(text = strings(Strings.split_person_save), onClick = onSave, enabled = draft.canSave, busy = draft.busy)
            TextButton(onClick = onCancel, enabled = !draft.busy, modifier = Modifier.fillMaxWidth()) { Text(strings(Strings.action_cancel)) }
        }
    }
}

@Composable
internal fun DeleteContactDialog(contact: SplitContact, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(contact.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = { Text(contact.phone + "\n\n" + strings(Strings.split_person_delete_confirm)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(strings(Strings.split_person_delete, contact.name), color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings(Strings.action_cancel)) } },
    )
}

// ── Assigning items ─────────────────────────────────────────────────────

/** Who had what: each item with its people to tick, what is still unassigned, and the shares so far. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AssignStep(state: SplitUiState, actions: SplitActions) {
    val draft = state.draft
    val symbol = Money.symbol(state.currency)
    val zero = Money.normalize("0", Money.fractionDigits(state.currency)).orEmpty()
    MintTitle(strings(Strings.split_assign_title), strings(Strings.split_assign_detail))

    MintPanel {
        SectionTitle(strings(Strings.split_items_title, draft.items.size.toString()))
        draft.items.forEachIndexed { itemIndex, item ->
            val shape = RoundedCornerShape(18.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(Mint.card())
                    .border(1.dp, if (item.assignees.isEmpty()) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f) else Mint.edge(), shape)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ItemFields(
                    index = itemIndex,
                    name = item.name,
                    amount = item.amount,
                    symbol = symbol,
                    zero = zero,
                    onChange = { name, amount -> actions.onItemChange(itemIndex, name, amount) },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = item.assignees.size == draft.people.size,
                        onClick = { actions.onEveryone(itemIndex) },
                        label = { Text(strings(Strings.split_assign_everyone)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                    draft.people.forEachIndexed { personIndex, person ->
                        FilterChip(
                            selected = personIndex in item.assignees,
                            onClick = { actions.onToggleAssignee(itemIndex, personIndex) },
                            leadingIcon = { Monogram(SplitExpense.initials(person, state.locale), personIndex, size = 24.dp) },
                            label = {
                                Text(
                                    SplitExpense.label(person, state.locale),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 120.dp),
                                )
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }
        }
        if (state.canAddItem) {
            TextButton(onClick = actions.onAddItem) {
                FinAiIcon(Icons.Filled.Add, tint = Mint.greenText())
                Spacer(Modifier.width(6.dp))
                Text(strings(Strings.split_add_item), color = Mint.greenText())
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Total(strings(Strings.split_assigned), state.assigned, Modifier.weight(1f))
            Total(strings(Strings.split_unassigned), state.unassigned ?: state.money("0"), Modifier.weight(1f))
        }
        state.unassignedWarning?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
    }

    // The shares as they stand, worked out the way the server will.
    val rows = state.previewRows
    if (rows.any { it.share != null }) {
        MintPanel {
            SectionTitle(strings(Strings.split_summary))
            rows.forEachIndexed { index, row -> PreviewLine(row, index) }
        }
    }
}

@Composable
private fun Total(label: String, amount: String, modifier: Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(amount, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** One person and their share so far, heard as one sentence. */
@Composable
internal fun PreviewLine(row: PreviewRow, index: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .describedAs(row.accessibility),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Monogram(row.initials, index, ringed = row.isSelf)
        Column(Modifier.weight(1f)) {
            Text(row.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            row.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        row.share?.let { Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
    }
}
