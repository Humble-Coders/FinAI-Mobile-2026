package com.humblesolutions.finai.ui.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Capabilities
import com.humblesolutions.finai.model.SessionState
import com.humblesolutions.finai.ui.strings

/**
 * Throwaway demo (ticket #6), replaced by real screens in M2. Every label comes
 * from sharedLogic/i18n — no literals a user can read.
 */
@Composable
fun ApiDemoScreen(
    logging: Boolean,
    modifier: Modifier = Modifier,
    viewModel: ApiDemoViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { viewModel.bind(logging) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeContentPadding()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(strings(Strings.demo_title), style = MaterialTheme.typography.headlineMedium)

        val problem = state.configurationProblem
        if (problem != null) {
            Text(strings(problem.messageKey), color = MaterialTheme.colorScheme.error)
            return@Column
        }

        Text(strings(sessionLabel(state.session)), style = MaterialTheme.typography.bodyMedium)
        when (state.session) {
            SessionState.SIGNED_OUT -> SignInForm(state, viewModel)
            SessionState.SIGNED_IN -> SignedInActions(state, viewModel)
            else -> Unit
        }
        state.errorKey?.let { Text(strings(it), color = MaterialTheme.colorScheme.error) }
        state.tokenLifeAfterExpire?.let { LabelledRow(strings(Strings.demo_token_after_expire), it.toString()) }
        state.tokenLifeAfterLoad?.let { LabelledRow(strings(Strings.demo_token_after_load), it.toString()) }
        state.capabilities?.let { CapabilitiesSummary(it) }
    }
}

@Composable
private fun SignInForm(state: ApiDemoUiState, viewModel: ApiDemoViewModel) {
    OutlinedTextField(
        value = state.phone,
        onValueChange = viewModel::onPhoneChange,
        label = { Text(strings(Strings.demo_phone_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = viewModel::sendCode, enabled = state.canSendCode) { Text(strings(Strings.demo_send_code)) }
    if (state.codeSent) {
        OutlinedTextField(
            value = state.code,
            onValueChange = viewModel::onCodeChange,
            label = { Text(strings(Strings.demo_code_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = viewModel::verifyCode, enabled = state.canVerify) { Text(strings(Strings.demo_verify_code)) }
    }
}

@Composable
private fun SignedInActions(state: ApiDemoUiState, viewModel: ApiDemoViewModel) {
    Button(onClick = viewModel::loadCapabilities, enabled = !state.busy) { Text(strings(Strings.demo_fetch)) }
    Button(onClick = viewModel::expireTokenThenLoad, enabled = !state.busy) { Text(strings(Strings.demo_expire_token)) }
    Button(onClick = viewModel::signOut, enabled = !state.busy) { Text(strings(Strings.demo_sign_out)) }
}

/** Renders the payload as the API returned it. Values are data; only labels are localized. */
@Composable
private fun CapabilitiesSummary(capabilities: Capabilities) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LabelledRow(strings(Strings.demo_region), capabilities.region ?: strings(Strings.demo_value_none))
        LabelledRow(strings(Strings.demo_currency), capabilities.currency)
        LabelledRow(strings(Strings.demo_locale), capabilities.locale)
        Text(strings(Strings.demo_onboarding), fontWeight = FontWeight.Medium)
        if (capabilities.onboardingRequired.isEmpty()) {
            Text(strings(Strings.demo_value_none))
        } else {
            capabilities.onboardingRequired.forEach { step -> Text(step.wire) }
        }
        Text(strings(Strings.demo_features), style = MaterialTheme.typography.titleMedium)
        capabilities.features.keys.sorted().forEach { key ->
            val reason = capabilities.blockingReason(key)
            LabelledRow(key, if (reason == null) strings(Strings.demo_on) else strings(reason.messageKey))
        }
    }
}

@Composable
private fun LabelledRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text(label, fontWeight = FontWeight.Medium)
        Text(value)
    }
}

private fun sessionLabel(session: SessionState): String = when (session) {
    SessionState.LOADING -> Strings.demo_session_loading
    SessionState.SIGNED_IN -> Strings.demo_signed_in
    SessionState.SIGNED_OUT -> Strings.demo_signed_out
    SessionState.REFRESH_FAILED -> Strings.demo_refresh_failed
}
