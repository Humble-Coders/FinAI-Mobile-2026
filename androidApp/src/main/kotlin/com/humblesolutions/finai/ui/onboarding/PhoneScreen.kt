package com.humblesolutions.finai.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.ui.components.ErrorText
import com.humblesolutions.finai.ui.components.countryName
import com.humblesolutions.finai.ui.components.FinAiTextField
import com.humblesolutions.finai.ui.components.PrimaryButton
import com.humblesolutions.finai.ui.components.ScreenScaffold
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.util.DialCode
import com.humblesolutions.finai.util.DialCodes

/**
 * The phone step: once per account, after whichever sign-in created it
 * (PRD §4.6). The number is the key that stops one person becoming two
 * households, and it sets the region. It is never a way to sign in.
 *
 * A number another account already has is refused under the field, and the
 * user types a different one. Nothing offers to sign in to or link with that
 * account (manager decision, 2026-09-15).
 */
@Composable
fun PhoneScreen(
    state: OnboardingUiState,
    onPhoneChange: (String) -> Unit,
    onDialCodeSelected: (DialCode) -> Unit,
    onContinue: () -> Unit,
    onSignOut: () -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }

    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(24.dp))
        Text(strings(Strings.phone_link_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = strings(Strings.phone_link_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DialCodeField(state.dialCode) { pickerOpen = true }
            FinAiTextField(
                value = state.phoneDigits,
                onValueChange = onPhoneChange,
                label = strings(Strings.welcome_phone_label),
                placeholder = strings(Strings.welcome_phone_hint),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Done,
                ),
                isError = state.errorKey != null,
                modifier = Modifier.weight(1f),
            )
        }

        ErrorText(state.errorKey)

        PrimaryButton(
            text = strings(Strings.action_continue),
            onClick = onContinue,
            enabled = state.canSendCode,
            busy = state.busy,
        )
        Text(
            text = strings(Strings.welcome_code_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // The way out. Without it someone who signed in with the wrong account
        // is held here with no route back to the welcome screen.
        TextButton(onClick = onSignOut, enabled = !state.busy) { Text(strings(Strings.action_sign_out)) }
    }

    if (pickerOpen) {
        DialCodeSheet(
            onDismiss = { pickerOpen = false },
            onPick = {
                onDialCodeSelected(it)
                pickerOpen = false
            },
        )
    }
}

/** The dialling prefix, and only that — never the user's region. */
@Composable
private fun DialCodeField(dialCode: DialCode, onClick: () -> Unit) {
    // Needs to read as a control, not a label: it was bare text with a
    // clickable modifier, so nothing suggested it could be tapped — and iOS
    // gave the same picker a filled background, so the two disagreed.
    Row(
        modifier = Modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(dialCode.display, style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialCodeSheet(onDismiss: () -> Unit, onPick: (DialCode) -> Unit) {
    var query by remember { mutableStateOf("") }
    val entries = remember(query) { search(query) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(strings(Strings.welcome_dial_code_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            items(entries, key = { it.region }) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(entry) }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(countryName(entry.region), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = entry.display,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun search(query: String): List<DialCode> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return DialCodes.all.sortedBy { countryName(it.region) }
    val needle = trimmed.removePrefix("+").lowercase()
    return DialCodes.all
        .filter { countryName(it.region).lowercase().contains(needle) || it.code.startsWith(needle) }
        .sortedBy { countryName(it.region) }
}
