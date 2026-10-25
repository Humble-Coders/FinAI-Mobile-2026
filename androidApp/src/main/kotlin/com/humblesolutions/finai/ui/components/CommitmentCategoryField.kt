package com.humblesolutions.finai.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.usecase.CommitmentCategories

/**
 * The budget category a commitment belongs to: its line never allocates
 * less than it. Shared by setup's commitments and Home's editor.
 */
@Composable
internal fun CommitmentCategoryField(
    selected: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    enabled: Boolean = true,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box(modifier) {
        PickerField(
            label = strings(Strings.setup_commitment_category),
            value = selected.takeIf(CommitmentCategories::isCategory)?.let { CommitmentCategories.nameOf(it) },
            placeholder = strings(Strings.setup_commitment_category_pick),
            onClick = { open = true },
            isError = isError,
            enabled = enabled,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CommitmentCategories.SLUGS.forEach { slug ->
                DropdownMenuItem(
                    text = { Text(CommitmentCategories.nameOf(slug)) },
                    onClick = {
                        onPick(slug)
                        open = false
                    },
                )
            }
        }
    }
}
