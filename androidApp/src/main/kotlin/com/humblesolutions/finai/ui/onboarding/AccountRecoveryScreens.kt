package com.humblesolutions.finai.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.ui.components.ErrorText
import com.humblesolutions.finai.ui.components.FinAiTextField
import com.humblesolutions.finai.ui.components.PasswordField
import com.humblesolutions.finai.ui.components.PrimaryButton
import com.humblesolutions.finai.ui.components.ScreenScaffold
import com.humblesolutions.finai.ui.strings

/** Forgot password, first stage: which address gets the code. */
@Composable
fun ResetRequestScreen(
    state: OnboardingUiState,
    onEmailChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(24.dp))
        Text(strings(Strings.reset_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = strings(Strings.reset_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FinAiTextField(
            value = state.email,
            onValueChange = onEmailChange,
            label = strings(Strings.welcome_email_label),
            placeholder = strings(Strings.welcome_email_hint),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
            isError = state.errorKey != null,
        )
        ErrorText(state.errorKey)
        PrimaryButton(
            text = strings(Strings.reset_send),
            onClick = onSend,
            enabled = state.canRequestReset,
            busy = state.busy,
        )
        TextButton(onClick = onCancel) { Text(strings(Strings.action_cancel)) }
    }
}

/**
 * Forgot password, last stage. The user is already signed in by the code, so
 * cancelling here signs them out rather than letting them in without a password.
 */
@Composable
fun NewPasswordScreen(
    state: OnboardingUiState,
    onPasswordChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(24.dp))
        Text(strings(Strings.reset_new_password_title), style = MaterialTheme.typography.headlineSmall)
        PasswordField(
            value = state.password,
            onValueChange = onPasswordChange,
            label = strings(Strings.reset_new_password_label),
            onDone = onSave,
            isError = state.errorKey != null,
        )
        Text(
            text = strings(Strings.welcome_password_rule),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ErrorText(state.errorKey)
        PrimaryButton(
            text = strings(Strings.reset_save),
            onClick = onSave,
            enabled = state.canSaveNewPassword,
            busy = state.busy,
        )
        TextButton(onClick = onCancel) { Text(strings(Strings.action_cancel)) }
    }
}
