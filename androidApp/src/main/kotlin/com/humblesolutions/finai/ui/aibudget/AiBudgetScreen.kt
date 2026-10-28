package com.humblesolutions.finai.ui.aibudget

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.humblesolutions.finai.BuildConfig
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.ui.components.AmountField
import com.humblesolutions.finai.ui.components.ErrorText
import com.humblesolutions.finai.ui.components.GradientButton
import com.humblesolutions.finai.ui.components.Mint
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.usecase.AiBudgetBlock
import com.humblesolutions.finai.usecase.AiBudgetEdit

/**
 * The suggested budget, with its own view model. [inSetup] draws it as setup's
 * last step ("Step 4 of 4", with Skip); otherwise it is opened from the Budget
 * tab. [onDone] runs once the budget is started, or on Skip / back.
 */
@Composable
internal fun AiBudgetRoute(userId: String, inSetup: Boolean, onDone: () -> Unit) {
    val model: AiBudgetViewModel = viewModel(key = "ai-budget-$inSetup")
    val state by model.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(userId) { model.bind(userId, logging = BuildConfig.DEBUG) }
    // Nothing to suggest — budgets off, or no income — is not a dead end in
    // setup: the step simply passes.
    LaunchedEffect(state.unavailable) { if (state.unavailable && inSetup) onDone() }
    BackHandler { onDone() }
    AiBudgetScreen(
        state = state,
        inSetup = inSetup,
        actions = AiBudgetActions(
            onAmount = model::onAmount,
            onSavings = model::onSavings,
            onStart = { model.start(onDone) },
            onSkip = onDone,
            onSuggestAgain = model::suggest,
            onRetry = model::load,
            onConsentTicked = model::onConsentTicked,
            onAgree = model::agree,
        ),
    )
}

internal class AiBudgetActions(
    val onAmount: (Int, String) -> Unit = { _, _ -> },
    val onSavings: (String) -> Unit = {},
    val onStart: () -> Unit = {},
    val onSkip: () -> Unit = {},
    val onSuggestAgain: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onConsentTicked: (Boolean) -> Unit = {},
    val onAgree: () -> Unit = {},
)

@Composable
internal fun AiBudgetScreen(state: AiBudgetUiState, inSetup: Boolean, actions: AiBudgetActions) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (inSetup) {
                    Text(strings(Strings.ai_budget_step), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    strings(Strings.ai_budget_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                when {
                    state.needsConsent -> Consent(state, actions)
                    state.proposal == null -> Waiting(state, actions)
                    else -> Proposal(state, actions)
                }
                if (inSetup || state.proposal == null) {
                    TextButton(onClick = actions.onSkip, modifier = Modifier.fillMaxWidth()) {
                        Text(strings(if (inSetup) Strings.ai_budget_skip else Strings.action_cancel))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun Waiting(state: AiBudgetUiState, actions: AiBudgetActions) {
    Text(strings(Strings.ai_budget_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.loading || state.suggesting) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 24.dp).semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.height(24.dp))
            Text(strings(Strings.ai_budget_loading), style = MaterialTheme.typography.bodyLarge)
        }
    } else {
        ErrorText(state.errorKey)
        if (!state.unavailable) {
            GradientButton(text = strings(Strings.ai_budget_retry), onClick = actions.onRetry)
        }
    }
}

@Composable
private fun Consent(state: AiBudgetUiState, actions: AiBudgetActions) {
    Text(strings(Strings.ai_budget_consent_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(strings(Strings.ai_budget_consent_body), style = MaterialTheme.typography.bodyMedium)
    ErrorText(state.errorKey)
    // The words are the server's, and it records which version was shown.
    state.policy?.let { policy ->
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp) {
            Text(policy.body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = state.consentTicked, role = Role.Checkbox, onValueChange = actions.onConsentTicked),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = state.consentTicked, onCheckedChange = null)
        Text(strings(Strings.import_consent_agree), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
    GradientButton(
        text = strings(Strings.ai_budget_consent_allow),
        onClick = actions.onAgree,
        enabled = state.consentTicked,
        busy = state.consentBusy,
    )
}

@Composable
private fun Proposal(state: AiBudgetUiState, actions: AiBudgetActions) {
    val proposal = state.proposal ?: return
    Text(strings(Strings.ai_budget_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    state.shareLabel?.let { Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
    proposal.note?.let { note ->
        Row(
            Modifier.fillMaxWidth().background(Mint.tile(Mint.greenText()), RoundedCornerShape(14.dp)).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = Mint.greenText())
            Text(note, style = MaterialTheme.typography.bodyMedium)
        }
    }

    state.draft.lines.forEachIndexed { index, line ->
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), tonalElevation = 2.dp) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(line.line.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(AiBudgetEdit.kindLabel(line.line, state.locale), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (line.line.isDebt) {
                    // The minimums: the budget's own rule sets this line.
                    Text(
                        com.humblesolutions.finai.util.Money.format(line.line.amount, state.currency, state.locale),
                        style = MaterialTheme.typography.titleLarge,
                    )
                } else {
                    AmountField(
                        value = line.amount,
                        onValueChange = { actions.onAmount(index, it) },
                        label = strings(Strings.setup_item_amount),
                        symbol = state.symbol,
                        placeholder = "0",
                        suffix = strings(Strings.setup_per_month),
                        isError = state.lineBlock(index) != null,
                        large = false,
                        enabled = !state.starting,
                    )
                }
                AiBudgetEdit.usualLabel(line.line, state.currency, state.locale)?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = Mint.greenText())
                }
                AiBudgetEdit.committedLabel(line.line, state.currency, state.locale)?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                line.line.reason?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.lineBlock(index) == AiBudgetBlock.BELOW_COMMITMENT) ErrorText(AiBudgetBlock.BELOW_COMMITMENT.messageKey)
            }
        }
    }

    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), tonalElevation = 2.dp) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AmountField(
                value = state.draft.savings,
                onValueChange = actions.onSavings,
                label = strings(Strings.ai_budget_savings),
                symbol = state.symbol,
                placeholder = "0",
                suffix = strings(Strings.setup_per_month),
                large = false,
                imeAction = ImeAction.Done,
                enabled = !state.starting,
            )
        }
    }

    state.leftLabel?.let {
        Text(
            it,
            style = MaterialTheme.typography.titleSmall,
            color = if (state.block == AiBudgetBlock.OVER_INCOME) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    ErrorText(state.errorKey ?: state.block?.takeIf { it != AiBudgetBlock.BELOW_COMMITMENT }?.messageKey)
    GradientButton(text = strings(Strings.ai_budget_start), onClick = actions.onStart, enabled = state.canStart, busy = state.starting)
    state.attemptsLabel?.let { label ->
        TextButton(onClick = actions.onSuggestAgain, enabled = state.canSuggestAgain, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.suggesting) strings(Strings.ai_budget_loading) else label)
        }
    }
}
