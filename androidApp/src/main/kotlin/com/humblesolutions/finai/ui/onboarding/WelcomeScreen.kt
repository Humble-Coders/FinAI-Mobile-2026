package com.humblesolutions.finai.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.WelcomeMode
import com.humblesolutions.finai.ui.components.ErrorText
import com.humblesolutions.finai.ui.components.FinAiTextField
import com.humblesolutions.finai.ui.components.OrDivider
import com.humblesolutions.finai.ui.components.PasswordField
import com.humblesolutions.finai.ui.components.PrimaryButton
import com.humblesolutions.finai.ui.components.ProviderButton
import com.humblesolutions.finai.ui.components.ScreenScaffold
import com.humblesolutions.finai.ui.components.Wordmark
import com.humblesolutions.finai.ui.strings

/**
 * Signed out: create an account or sign in, with email and password or Google.
 *
 * One screen with a mode rather than two screens. Google needs no such
 * distinction, and a person who picks the wrong mode is one tap from the other.
 *
 * No Apple button here, by decision (2026-09-11): Apple is iOS only. Someone
 * who signed up with Apple on an iPhone reaches the same account on Android
 * through the email and password or Google they linked to it.
 */
@Composable
fun WelcomeScreen(
    state: OnboardingUiState,
    onModeChange: (WelcomeMode) -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onForgotPassword: () -> Unit,
    onGoogle: () -> Unit,
    onCancelLink: () -> Unit,
) {
    val creating = state.creatingAccount

    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(16.dp))
        Wordmark()
        Text(
            text = strings(Strings.app_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.pendingLink != null) {
            Text(
                text = strings(Strings.link_banner),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            TextButton(onClick = onCancelLink) { Text(strings(Strings.action_cancel)) }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = strings(if (creating) Strings.welcome_create_title else Strings.welcome_sign_in_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        FinAiTextField(
            value = state.email,
            onValueChange = onEmailChange,
            label = strings(Strings.welcome_email_label),
            placeholder = strings(Strings.welcome_email_hint),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            isError = state.errorKey != null,
        )
        PasswordField(
            value = state.password,
            onValueChange = onPasswordChange,
            label = strings(Strings.welcome_password_label),
            onDone = onSubmit,
            isError = state.errorKey != null,
        )

        if (creating) {
            Text(
                text = strings(Strings.welcome_password_rule),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // Prominent on purpose: with email as a way in, a forgotten
            // password is the commonest reason someone cannot get back.
            TextButton(onClick = onForgotPassword, modifier = Modifier.align(Alignment.End)) {
                Text(strings(Strings.welcome_forgot_password))
            }
        }

        ErrorText(state.errorKey)

        PrimaryButton(
            text = strings(if (creating) Strings.welcome_create_action else Strings.welcome_sign_in_action),
            onClick = onSubmit,
            enabled = state.canSubmitCredentials,
            busy = state.busy,
        )

        TextButton(
            onClick = { onModeChange(if (creating) WelcomeMode.SIGN_IN else WelcomeMode.CREATE_ACCOUNT) },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(strings(if (creating) Strings.welcome_have_account else Strings.welcome_need_account))
        }

        OrDivider()
        ProviderButton(strings(Strings.welcome_google), onGoogle, enabled = !state.busy)
        // Under the button it belongs to, not under the form: a provider
        // failing says nothing about what the user typed.
        ErrorText(state.providerErrorKey)
    }
}
