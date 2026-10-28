package com.humblesolutions.finai.ui.budget

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.ui.components.AmountField
import com.humblesolutions.finai.ui.components.Mint
import com.humblesolutions.finai.ui.components.tint
import com.humblesolutions.finai.ui.components.vector
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.util.Money

/** What the editor sheet can do, passed down rather than reached for. */
internal data class BudgetEditorActions(
    val onAmountChange: (String) -> Unit,
    /** Save for every month: the months after start from this amount. */
    val onSave: () -> Unit,
    val onUseSuggestion: () -> Unit,
    val onCancel: () -> Unit,
    /** Save for this month only: the months after keep what they would have had. */
    val onSaveThisMonth: () -> Unit = {},
)

/**
 * Setting one line by hand (#47).
 *
 * In the transaction editor's style, with the same rule behind Save: the
 * disabled button, the notice under the field and the view model's refusal
 * all read one `BudgetEdit.blockingReason`, so they cannot disagree about
 * what is wrong.
 *
 * A failed save keeps the sheet open with what was typed. Nothing is applied
 * by halves — the server answers with the whole month or not at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BudgetEditorSheet(state: BudgetUiState, actions: BudgetEditorActions) {
    val line = state.editing ?: return
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val busy = state.saving || state.resetting

    ModalBottomSheet(
        onDismissRequest = { if (!busy) actions.onCancel() },
        sheetState = sheet,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .imePadding()
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = state.nameOf(line),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )

            AmountField(
                value = state.draft.amount,
                onValueChange = actions.onAmountChange,
                label = strings(Strings.budget_edit_amount),
                symbol = state.currencySymbol,
                placeholder = Money.normalize("0", Money.fractionDigits(state.currency)).orEmpty(),
                isError = state.editNotice != null,
                large = false,
                // iOS has no return key on a decimal pad and Android's is
                // easy to miss; Done closes the keyboard, Save submits.
                imeAction = ImeAction.Done,
                // Locked while the amount is in flight (#47 UI standards):
                // typing then would leave the field showing one figure while
                // the server answers about another.
                enabled = !busy,
            )

            // The reason Save is off, in the words the shared rule chose.
            state.editNotice?.let {
                Text(
                    strings(it.messageKey, state.currency),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // A refusal from the server, which has the last word.
            state.editErrorKey?.let {
                Text(strings(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            state.useSuggestionLabel?.let { label ->
                TextButton(onClick = actions.onUseSuggestion, enabled = !busy) { Text(label) }
            }

            if (state.asksHowLong) {
                // Asked, never assumed: a laptop month raised by hand must not
                // become every month's budget without the person saying so.
                var savingThisMonth by remember { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            savingThisMonth = false
                            actions.onSave()
                        },
                        enabled = state.canSave,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { SaveLabel(state.saving && !savingThisMonth, Strings.budget_save_every_month) }
                    OutlinedButton(
                        onClick = {
                            savingThisMonth = true
                            actions.onSaveThisMonth()
                        },
                        enabled = state.canSave,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { SaveLabel(state.saving && savingThisMonth, Strings.budget_save_this_month) }
                    TextButton(onClick = actions.onCancel, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(strings(Strings.action_cancel))
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = actions.onCancel, enabled = !busy) { Text(strings(Strings.action_cancel)) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = actions.onSave, enabled = state.canSave) { SaveLabel(state.saving, Strings.action_save) }
                }
            }
        }
    }
}

@Composable
private fun SaveLabel(saving: Boolean, labelKey: String) {
    if (saving) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(8.dp))
        Text(strings(Strings.budget_edit_saving))
    } else {
        Text(strings(labelKey))
    }
}

/**
 * Choosing a category to add a line for.
 *
 * `income` and `transfers` are not offered, and nor is a category that
 * already has a line — both filtered in [BudgetUiState.pickable], so the
 * server's `not_budgetable` should never be reachable from here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BudgetCategorySheet(
    state: BudgetUiState,
    onChosen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dark = isSystemInDarkTheme()
    val options = state.pickable

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = strings(Strings.budget_picker_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp).semantics { heading() },
            )

            if (options.isEmpty()) {
                Text(
                    strings(Strings.budget_picker_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }

            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(options, key = { it.id }) { category ->
                    val icon = state.iconOf(category)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { onChosen(category.id) }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Mint.tile(icon.tint(dark))),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                icon.vector(),
                                contentDescription = null,
                                tint = icon.tint(dark),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Text(
                            category.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
