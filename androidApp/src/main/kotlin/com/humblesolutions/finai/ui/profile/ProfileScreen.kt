package com.humblesolutions.finai.ui.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.ui.components.AiConsentPanel
import com.humblesolutions.finai.ui.components.ErrorText
import com.humblesolutions.finai.ui.components.GradientButton
import com.humblesolutions.finai.ui.components.Mint
import com.humblesolutions.finai.ui.components.ProviderButton
import com.humblesolutions.finai.ui.components.SheetTitle
import com.humblesolutions.finai.ui.components.closingKeyboardActions
import com.humblesolutions.finai.ui.components.countryName
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.ui.theme.FinAiPalette
import com.humblesolutions.finai.usecase.Profile
import com.humblesolutions.finai.util.Money

internal class ProfileActions(
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onEditName: () -> Unit = {},
    val onNameChange: (String) -> Unit = {},
    val onSaveName: () -> Unit = {},
    val onCloseName: () -> Unit = {},
    val onChangeRegion: () -> Unit = {},
    val onEditSetup: () -> Unit = {},
    val onRead: (LegalText) -> Unit = {},
    val onCloseReading: () -> Unit = {},
    val onTurnAiOn: () -> Unit = {},
    val onAiTicked: (Boolean) -> Unit = {},
    val onAgreeToAi: () -> Unit = {},
    val onCloseAiOn: () -> Unit = {},
    val onTurnAiOff: () -> Unit = {},
    val onConfirmAiOff: () -> Unit = {},
    val onKeepAiOn: () -> Unit = {},
    val onSignOut: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onDeleteTyped: (String) -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onCancelDelete: () -> Unit = {},
)

/**
 * Profile (PM decision, 2026-11-06): the monogram and name, then Account,
 * Your setup, Privacy and consents, and at the foot signing out and deleting
 * the account. Every word is the shared table's; every rule shared `Profile`'s.
 */
@Composable
internal fun ProfileScreen(state: ProfileUiState, currency: String, actions: ProfileActions) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = actions.onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings(Strings.action_back))
                    }
                    Text(
                        strings(Strings.profile_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp).semantics { heading() },
                    )
                }
                when {
                    state.me != null -> Content(state, currency, actions)

                    state.loadErrorKey != null -> {
                        ErrorText(state.loadErrorKey)
                        ProviderButton(text = strings(Strings.dashboard_retry), onClick = actions.onRetry)
                    }

                    // The coin covers a first load.
                    else -> Unit
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (state.editingName) NameSheet(state, actions)
    state.reading?.let { ReadingSheet(state, it, actions.onCloseReading) }
    if (state.turningAiOn) AiOnSheet(state, actions)
    if (state.confirmingAiOff) AiOffDialog(state, actions)
    if (state.confirmingDelete) DeleteDialog(state, actions)
}

@Composable
private fun ColumnScope.Content(state: ProfileUiState, currency: String, actions: ProfileActions) {
    Header(state)

    Section(strings(Strings.profile_section_account)) {
        Line(
            label = strings(Strings.profile_name),
            value = state.me?.user?.displayName?.takeIf { it.isNotBlank() },
            action = strings(Strings.profile_edit),
            onAction = actions.onEditName,
        )
        state.email?.let {
            RowDivider()
            Line(label = strings(Strings.profile_email), value = it)
        }
        state.phone?.let {
            RowDivider()
            Line(label = strings(Strings.profile_phone), value = it)
        }
        RowDivider()
        Line(
            label = strings(Strings.profile_region),
            value = state.countryCode?.let(::countryName),
            action = strings(Strings.profile_change),
            onAction = actions.onChangeRegion,
        )
        if (currency.isNotBlank()) {
            RowDivider()
            val symbol = Money.symbol(currency)
            Line(
                label = strings(Strings.profile_currency),
                value = if (symbol == currency.uppercase()) symbol else "${currency.uppercase()} · $symbol",
                action = strings(Strings.profile_change),
                onAction = actions.onChangeRegion,
            )
        }
    }

    Section(strings(Strings.profile_section_setup)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClick = actions.onEditSetup)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(strings(Strings.profile_setup_edit), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Hint(strings(Strings.profile_setup_hint))
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }

    Section(strings(Strings.profile_section_privacy)) {
        Line(
            label = strings(Strings.profile_terms),
            value = state.acceptedTerms?.let { strings(Strings.profile_terms_accepted, it) },
            action = strings(Strings.profile_read),
            onAction = { actions.onRead(LegalText.TERMS) },
        )
        if (state.disclaimer != null) {
            RowDivider()
            Line(
                label = strings(Strings.profile_disclaimer),
                value = strings(Strings.profile_disclaimer_hint),
                action = strings(Strings.profile_read),
                onAction = { actions.onRead(LegalText.DISCLAIMER) },
            )
        }
        RowDivider()
        AiLine(state, actions)
    }

    ProviderButton(text = strings(Strings.profile_sign_out), onClick = actions.onSignOut)
    OutlinedButton(
        onClick = actions.onDelete,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
    ) {
        Text(strings(Strings.profile_delete), style = MaterialTheme.typography.titleSmall)
    }
}

/** The monogram, large, and the name under it. */
@Composable
private fun Header(state: ProfileUiState) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(FinAiPalette.Green, FinAiPalette.GreenDeep)))
                // The name beside it says the same; the letters alone would be read out one by one.
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Text(state.initials, color = FinAiPalette.OnGreen, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        }
        if (state.displayName.isNotEmpty()) {
            Text(state.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
        state.noticeKey?.let {
            Text(
                strings(it),
                style = MaterialTheme.typography.bodyMedium,
                color = Mint.greenText(),
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** On, with the version agreed to, or off — and the one control that changes it. */
@Composable
private fun AiLine(state: ProfileUiState, actions: ProfileActions) {
    val status = state.aiStatus
    Line(
        label = strings(Strings.profile_ai),
        value = when {
            status == null -> null
            state.aiOn -> strings(Strings.profile_ai_on, status.version.orEmpty())
            else -> strings(Strings.profile_ai_off)
        },
        action = if (status == null) null else strings(if (state.aiOn) Strings.profile_ai_turn_off else Strings.profile_ai_turn_on),
        onAction = if (state.aiOn) actions.onTurnAiOff else actions.onTurnAiOn,
        enabled = !state.aiBusy,
    )
    Hint(strings(Strings.profile_ai_hint))
    if (status != null && !state.aiOn) Hint(strings(Strings.profile_ai_off_note))
    // A failure outside the sheet — turning off — is said here.
    if (!state.turningAiOn) ErrorText(state.aiErrorKey)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), tonalElevation = 2.dp) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), content = content)
        }
    }
}

/** A label, what it is now, and the action that changes it. */
@Composable
private fun Line(label: String, value: String?, action: String? = null, onAction: () -> Unit = {}, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            value?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        }
        action?.let {
            TextButton(onClick = onAction, enabled = enabled) { Text(it, color = Mint.greenText(), fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RowDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

// ── Sheets and dialogs ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NameSheet(state: ProfileUiState, actions: ProfileActions) {
    ModalBottomSheet(onDismissRequest = actions.onCloseName) {
        Column(
            Modifier.fillMaxWidth().padding(bottom = 16.dp).imePadding().navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetTitle(strings(Strings.profile_name_edit_title))
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.nameDraft,
                    onValueChange = actions.onNameChange,
                    label = { Text(strings(Strings.profile_name_hint)) },
                    singleLine = true,
                    isError = state.nameErrorKey != null,
                    enabled = !state.savingName,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = closingKeyboardActions(onDone = actions.onSaveName),
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorText(state.nameErrorKey)
                GradientButton(text = strings(Strings.profile_save), onClick = actions.onSaveName, busy = state.savingName)
            }
        }
    }
}

/** The terms or the disclaimer, in full: the server's words. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadingSheet(state: ProfileUiState, text: LegalText, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetTitle(strings(if (text == LegalText.TERMS) Strings.profile_terms else Strings.profile_disclaimer))
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .navigationBarsPadding(),
        ) {
            val body = state.readingBody
            when {
                body != null -> Text(body, style = MaterialTheme.typography.bodyMedium)

                state.readingErrorKey != null -> ErrorText(state.readingErrorKey)

                else -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** "Turn on": the policy, a box nothing pre-ticks, and Allow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiOnSheet(state: ProfileUiState, actions: ProfileActions) {
    ModalBottomSheet(onDismissRequest = actions.onCloseAiOn) {
        AiConsentPanel(
            title = strings(Strings.profile_ai_turn_on_title),
            policy = state.aiPolicy,
            ticked = state.aiTicked,
            busy = state.aiBusy,
            errorKey = state.aiErrorKey,
            onTicked = actions.onAiTicked,
            onAgree = actions.onAgreeToAi,
            onNotNow = actions.onCloseAiOn,
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        )
    }
}

@Composable
private fun AiOffDialog(state: ProfileUiState, actions: ProfileActions) {
    AlertDialog(
        onDismissRequest = actions.onKeepAiOn,
        title = { Text(strings(Strings.profile_ai_off_title)) },
        text = { Text(strings(Strings.profile_ai_off_body)) },
        confirmButton = {
            TextButton(onClick = actions.onConfirmAiOff, enabled = !state.aiBusy) {
                Text(strings(Strings.profile_ai_turn_off), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onKeepAiOn, enabled = !state.aiBusy) { Text(strings(Strings.action_cancel)) }
        },
    )
}

/** What deleting removes, and the word to type before it can go ahead. */
@Composable
private fun DeleteDialog(state: ProfileUiState, actions: ProfileActions) {
    AlertDialog(
        onDismissRequest = actions.onCancelDelete,
        title = { Text(strings(Strings.profile_delete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings(Strings.profile_delete_body))
                OutlinedTextField(
                    value = state.deleteTyped,
                    onValueChange = actions.onDeleteTyped,
                    label = { Text(strings(Strings.profile_delete_type)) },
                    placeholder = { Text(Profile.DELETE_WORD) },
                    singleLine = true,
                    enabled = !state.deleting,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                    keyboardActions = closingKeyboardActions(),
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorText(state.deleteErrorKey)
            }
        },
        confirmButton = {
            TextButton(
                onClick = actions.onConfirmDelete,
                enabled = state.canDelete,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                if (state.deleting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.error)
                } else {
                    Text(strings(Strings.profile_delete_confirm), fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onCancelDelete, enabled = !state.deleting) { Text(strings(Strings.action_cancel)) }
        },
    )
}
