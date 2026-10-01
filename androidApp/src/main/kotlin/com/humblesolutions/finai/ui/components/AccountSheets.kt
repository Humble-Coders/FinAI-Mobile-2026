package com.humblesolutions.finai.ui.components

/*
 * The account chooser and the "add an account" sheet — shared by manual entry
 * (#30) and the statement import's account step (#31), so both ask the same
 * question the same way.
 */

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Account
import com.humblesolutions.finai.model.AccountKind
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.usecase.NewAccountBlock
import com.humblesolutions.finai.usecase.NewAccountDraft


/**
 * A chosen chip is drawn in the inverse surface — not the accent, which belongs
 * to Save alone — and both come from theme tokens defined for light and dark.
 */
@Composable
internal fun neutralChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.inverseSurface,
    selectedLabelColor = MaterialTheme.colorScheme.inverseOnSurface,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountSheet(
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun NewAccountSheet(
    draft: NewAccountDraft,
    notice: NewAccountBlock?,
    errorKey: String?,
    canCreate: Boolean,
    creating: Boolean,
    onName: (String) -> Unit,
    onKind: (AccountKind) -> Unit,
    onCreate: () -> Unit,
    onCancel: () -> Unit,
) {
    val focus = LocalFocusManager.current
    // While the account is being created the sheet must stay up: the model
    // refuses to cancel then, and a sheet swiped away regardless would hide
    // but stay in place, with any error it shows unseen.
    val stillCreating by rememberUpdatedState(creating)
    val sheet = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !stillCreating },
    )
    // Back closes the sheet, not the screen underneath it.
    BackHandler { onCancel() }
    ModalBottomSheet(
        onDismissRequest = onCancel,
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
                onValueChange = onName,
                label = strings(Strings.account_new_name_label),
                placeholder = strings(Strings.account_new_name_hint),
                imeAction = ImeAction.Done,
                isError = notice == NewAccountBlock.NO_NAME || notice == NewAccountBlock.NAME_TOO_LONG,
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
                            onClick = { onKind(kind) },
                            label = { Text(kind.labelKey?.let { strings(it) }.orEmpty()) },
                            colors = neutralChipColors(),
                        )
                    }
                }
            }
            ErrorText(errorKey)
            if (errorKey == null) ErrorText(notice?.messageKey)
            GradientButton(
                text = strings(Strings.account_new_create),
                onClick = {
                    focus.clearFocus()
                    onCreate()
                },
                enabled = canCreate,
                busy = creating,
            )
            TextButton(
                onClick = onCancel,
                enabled = !creating,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text(strings(Strings.account_new_cancel)) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
internal fun SheetTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
    )
}

/** A row in a chooser: Material's list item, with a radio button when it is one of a set. */
@Composable
internal fun SheetRow(
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
