package com.humblesolutions.finai.ui.statementimport

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.R
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.AccountKind
import com.humblesolutions.finai.ui.components.AccountSheet
import com.humblesolutions.finai.ui.components.ErrorText
import com.humblesolutions.finai.ui.components.GradientButton
import com.humblesolutions.finai.ui.components.NewAccountSheet
import com.humblesolutions.finai.ui.components.PasswordField
import com.humblesolutions.finai.ui.components.PickerField
import com.humblesolutions.finai.ui.components.ProviderButton
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.usecase.ImportStep

/** What the screen can ask for; the pickers are the platform's, so the route supplies them. */
class StatementImportActions(
    val onClose: () -> Unit,
    val onRetryAccounts: () -> Unit,
    val onChooseAccount: (String) -> Unit,
    val onContinueFromAccount: () -> Unit,
    val onBackToAccount: () -> Unit,
    val onOpenNewAccount: () -> Unit,
    val onNewAccountName: (String) -> Unit,
    val onNewAccountKind: (AccountKind) -> Unit,
    val onCreateAccount: () -> Unit,
    val onCancelNewAccount: () -> Unit,
    val onPickFile: () -> Unit,
    val onPickPhoto: () -> Unit,
    val onTakePhoto: () -> Unit,
    val onSubmitPassword: (String) -> Unit,
    val onConsentTicked: (Boolean) -> Unit,
    val onAgree: () -> Unit,
    val onDeclineConsent: () -> Unit,
    val onRetry: () -> Unit,
    val onChooseAnother: () -> Unit,
    val onTypeInstead: () -> Unit,
    val onDiagnosticsTicked: (Boolean) -> Unit,
    val onSendDiagnostics: () -> Unit,
)

/**
 * Importing a statement (#31): which account, which file, consent the first
 * time, the read, and how it ended.
 *
 * The waits — reading, sending, saving — are the app's one coin loader, with a
 * caption saying which (the route asks for it); this screen draws what sits
 * underneath. Every outcome's words come from shared rules.
 */
@Composable
fun StatementImportScreen(state: StatementImportUiState, actions: StatementImportActions) {
    var choosingAccount by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Header(onClose = actions.onClose)
        Column(
            modifier = Modifier
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state.step) {
                ImportStep.CHOOSE_ACCOUNT -> AccountStep(state, actions) { choosingAccount = true }
                ImportStep.CHOOSE_FILE -> FileStep(state, actions)
                ImportStep.CONSENT -> ConsentStep(state, actions)
                ImportStep.PASSWORD -> PasswordStep(state, actions)
                // The coin loader covers these; its caption says which.
                ImportStep.READING, ImportStep.SENDING, ImportStep.SAVING -> Spacer(Modifier.height(1.dp))
                ImportStep.DONE -> DoneStep(state, actions)
                ImportStep.FAILED -> FailedStep(state, actions)
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (choosingAccount) {
        AccountSheet(
            accounts = state.accounts,
            chosenId = state.accountId,
            onChosen = {
                actions.onChooseAccount(it)
                choosingAccount = false
            },
            onAdd = {
                choosingAccount = false
                actions.onOpenNewAccount()
            },
            onDismiss = { choosingAccount = false },
        )
    }
    state.newAccount?.let { draft ->
        NewAccountSheet(
            draft = draft,
            notice = state.newAccountNotice,
            errorKey = state.newAccountErrorKey,
            canCreate = state.canCreateAccount,
            creating = state.creatingAccount,
            onName = actions.onNewAccountName,
            onKind = actions.onNewAccountKind,
            onCreate = actions.onCreateAccount,
            onCancel = actions.onCancelNewAccount,
        )
    }
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
            text = strings(Strings.import_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp).semantics { heading() },
        )
    }
}

@Composable
private fun Title(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun Body(text: String, muted: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
    )
}

@Composable
private fun AccountStep(state: StatementImportUiState, actions: StatementImportActions, onPick: () -> Unit) {
    Title(strings(Strings.import_account_title))
    Body(strings(Strings.import_account_hint), muted = true)
    if (state.accountsErrorKey != null) {
        ErrorText(state.accountsErrorKey)
        TextButton(onClick = actions.onRetryAccounts) { Text(strings(Strings.import_try_again)) }
        return
    }
    PickerField(
        label = strings(Strings.manual_entry_account_label),
        value = state.account?.name,
        placeholder = strings(Strings.manual_entry_account_placeholder),
        onClick = onPick,
        enabled = !state.accountsLoading,
    )
    // Not optional and not defaulted: Continue waits for a real choice.
    GradientButton(
        text = strings(Strings.action_continue),
        onClick = actions.onContinueFromAccount,
        enabled = state.canContinueFromAccount,
    )
}

@Composable
private fun FileStep(state: StatementImportUiState, actions: StatementImportActions) {
    Title(strings(Strings.import_file_title))
    PrivacyNote()
    GradientButton(text = strings(Strings.import_pick_file), onClick = actions.onPickFile)
    ProviderButton(text = strings(Strings.import_pick_photo), onClick = actions.onPickPhoto)
    ProviderButton(text = strings(Strings.import_take_photo), onClick = actions.onTakePhoto)
    TextButton(onClick = actions.onBackToAccount) {
        Text(
            text = state.account?.name ?: strings(Strings.manual_entry_account_placeholder),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The true thing, said plainly: the file is read here and never uploaded. */
@Composable
private fun PrivacyNote() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Text(
            text = strings(Strings.import_on_device),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun ConsentStep(state: StatementImportUiState, actions: StatementImportActions) {
    Title(strings(Strings.import_consent_title))
    ErrorText(state.consentErrorKey)
    // The words are the server's, so they can change without an app release,
    // and it records which version was shown.
    state.policy?.let { policy ->
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
        ) {
            Text(text = policy.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        }
        TickRow(
            text = strings(Strings.import_consent_agree),
            ticked = state.consentTicked,
            onTicked = actions.onConsentTicked,
        )
    }
    GradientButton(
        text = strings(Strings.import_consent_continue),
        onClick = actions.onAgree,
        enabled = state.canAgree,
        busy = state.consentBusy,
    )
    TextButton(onClick = actions.onDeclineConsent, modifier = Modifier.fillMaxWidth()) {
        Text(strings(Strings.import_consent_not_now))
    }
}

/** A box nothing pre-ticks, with its label as the tap target. */
@Composable
private fun TickRow(text: String, ticked: Boolean, onTicked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = ticked, role = Role.Checkbox, onValueChange = onTicked),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = ticked, onCheckedChange = null)
        Text(text = text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun PasswordStep(state: StatementImportUiState, actions: StatementImportActions) {
    // `remember`, not `rememberSaveable`: a password is never written into
    // saved state, even the process's own.
    var password by remember { mutableStateOf("") }
    Body(strings(if (state.passwordWrong) Strings.statement_password_wrong else Strings.statement_password_prompt))
    PasswordField(
        value = password,
        onValueChange = { password = it },
        label = strings(Strings.import_password_label),
        onDone = { actions.onSubmitPassword(password) },
        isError = state.passwordWrong,
    )
    GradientButton(
        text = strings(Strings.import_password_submit),
        onClick = { actions.onSubmitPassword(password) },
        enabled = password.isNotEmpty(),
    )
    TextButton(onClick = actions.onChooseAnother, modifier = Modifier.fillMaxWidth()) {
        Text(strings(Strings.import_choose_another))
    }
}

@Composable
private fun DoneStep(state: StatementImportUiState, actions: StatementImportActions) {
    Title(strings(Strings.import_done_title))
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        state.summary.forEach { Body(it) }
    }
    // The review screen (#32) is not built yet; say where the rows are.
    if (state.needsReview > 0) Body(strings(Strings.import_review_later), muted = true)
    GradientButton(text = strings(Strings.import_done), onClick = actions.onClose)
}

@Composable
private fun FailedStep(state: StatementImportUiState, actions: StatementImportActions) {
    Title(strings(Strings.import_failed_title))
    state.problemMessage?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    if (state.offersRetry) GradientButton(text = strings(Strings.import_try_again), onClick = actions.onRetry)
    if (state.offersManualEntry) {
        if (state.offersRetry) {
            ProviderButton(text = strings(Strings.import_type_instead), onClick = actions.onTypeInstead)
        } else {
            GradientButton(text = strings(Strings.import_type_instead), onClick = actions.onTypeInstead)
        }
    }
    TextButton(onClick = actions.onChooseAnother, modifier = Modifier.fillMaxWidth()) {
        Text(strings(Strings.import_choose_another))
    }
    if (state.offersDiagnostics) DiagnosticsOffer(state, actions)
    state.diagnosticsThanks?.let { Body(it, muted = true) }
}

/** Asked per import, unticked, and only here — never after a clean import. */
@Composable
private fun DiagnosticsOffer(state: StatementImportUiState, actions: StatementImportActions) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = strings(Strings.import_diagnostics_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = strings(Strings.import_diagnostics_body), style = MaterialTheme.typography.bodyMedium)
            TickRow(
                text = strings(Strings.import_diagnostics_agree),
                ticked = state.diagnosticsTicked,
                onTicked = actions.onDiagnosticsTicked,
            )
            ProviderButton(
                text = strings(Strings.import_diagnostics_send),
                onClick = actions.onSendDiagnostics,
                enabled = state.canSendDiagnostics,
            )
        }
    }
}
