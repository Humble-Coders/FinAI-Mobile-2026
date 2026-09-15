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
import com.humblesolutions.finai.model.SocialProvider
import com.humblesolutions.finai.ui.components.ErrorText
import com.humblesolutions.finai.ui.components.FinAiTextField
import com.humblesolutions.finai.ui.components.PasswordField
import com.humblesolutions.finai.ui.components.PrimaryButton
import com.humblesolutions.finai.ui.components.ProviderButton
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

/**
 * Signed in to the account that has the number: remove the empty account, then
 * add the sign-in method that made it.
 *
 * Two deliberate steps rather than one. Linking needs a fresh ID token from the
 * provider's own sheet, and asking for it before the empty account is gone
 * would fail, because Supabase will not attach an identity another account holds.
 */
@Composable
fun LinkAccountScreen(
    state: OnboardingUiState,
    onRemoveOrphan: () -> Unit,
    onAddGoogle: () -> Unit,
    onCancel: () -> Unit,
) {
    val provider = state.pendingLink?.provider
    val providerName = when (provider) {
        SocialProvider.GOOGLE -> strings(Strings.provider_google)
        SocialProvider.APPLE -> strings(Strings.provider_apple)
        null -> null
    }

    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(24.dp))
        Text(strings(Strings.link_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = if (providerName != null) {
                strings(Strings.link_body_provider, providerName)
            } else {
                strings(Strings.link_body_email)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ErrorText(state.errorKey)

        if (!state.orphanRemoved) {
            PrimaryButton(
                text = strings(Strings.action_continue),
                onClick = onRemoveOrphan,
                busy = state.busy,
            )
        } else if (provider == SocialProvider.GOOGLE) {
            ProviderButton(strings(Strings.link_add_provider, providerName.orEmpty()), onAddGoogle, enabled = !state.busy)
            ErrorText(state.providerErrorKey)
        }
        // An Apple orphan cannot reach this screen on Android: the link state is
        // in memory on the device that started it, and Android offers no Apple.

        TextButton(onClick = onCancel, enabled = !state.busy) { Text(strings(Strings.link_cancel)) }
    }
}
