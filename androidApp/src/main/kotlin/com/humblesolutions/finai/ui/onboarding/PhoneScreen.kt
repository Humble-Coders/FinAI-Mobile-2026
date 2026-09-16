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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.ui.components.CardScreen
import com.humblesolutions.finai.ui.components.ErrorText
import com.humblesolutions.finai.ui.components.FinAiTextField
import com.humblesolutions.finai.ui.components.PrimaryButton
import com.humblesolutions.finai.ui.components.countryName
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.util.DialCode
import com.humblesolutions.finai.util.DialCodes
import com.humblesolutions.finai.util.flagEmoji

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
    CardScreen {
        Spacer(Modifier.height(20.dp))
        Text(
            text = strings(Strings.phone_link_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
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
            DialCodeField(state.dialCode, enabled = !state.busy, onPick = onDialCodeSelected)
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
            // No spinner here: the coin on the card's edge is the indicator.
            enabled = state.canSendCode && !state.busy,
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

}

/**
 * The dialling prefix and its flag — never the user's region (PRD §4.6).
 *
 * A dropdown anchored to the field rather than a sheet over the whole screen:
 * it is one small choice, and the number being typed stays in view.
 */
@Composable
private fun DialCodeField(dialCode: DialCode, enabled: Boolean, onPick: (DialCode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val entries = remember(query) { search(query) }

    fun close() {
        open = false
        query = ""
    }

    Box {
        Row(
            modifier = Modifier
                .heightIn(min = 56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = enabled) { open = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(flagEmoji(dialCode.region), style = MaterialTheme.typography.titleMedium)
            Text(dialCode.display, style = MaterialTheme.typography.titleMedium)
        }

        DropdownMenu(
            expanded = open,
            onDismissRequest = { close() },
            modifier = Modifier.heightIn(max = 360.dp),
        ) {
            FinAiTextField(
                value = query,
                onValueChange = { query = it },
                label = strings(Strings.welcome_dial_code_label),
                modifier = Modifier.width(264.dp).padding(horizontal = 12.dp),
            )
            // Capped rather than endless: the search field is the way to the
            // rest, and a menu holding every region scrolls forever.
            entries.take(MAX_VISIBLE_REGIONS).forEach { entry ->
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.width(240.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = flagEmoji(entry.region) + "  " + countryName(entry.region),
                                maxLines = 1,
                            )
                            Text(entry.display, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = {
                        onPick(entry)
                        close()
                    },
                )
            }
        }
    }
}

private const val MAX_VISIBLE_REGIONS = 40

private fun search(query: String): List<DialCode> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return DialCodes.all.sortedBy { countryName(it.region) }
    val needle = trimmed.removePrefix("+").lowercase()
    return DialCodes.all
        .filter { countryName(it.region).lowercase().contains(needle) || it.code.startsWith(needle) }
        .sortedBy { countryName(it.region) }
}
