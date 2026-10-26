package com.humblesolutions.finai.ui.plan

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
import com.humblesolutions.finai.usecase.DiagnosticRow

/** A diagnostic over Your plan, with its own view model; [onClose] on back. */
@Composable
internal fun DiagnosticRoute(userId: String, kind: String, onClose: () -> Unit) {
    val model: DiagnosticViewModel = viewModel()
    val state by model.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(userId, kind) { model.bind(userId, kind, logging = BuildConfig.DEBUG) }
    BackHandler(onBack = onClose)
    DiagnosticScreen(
        state = state,
        onClose = onClose,
        onSavingsTarget = model::onSavingsTarget,
        onSavingsNow = model::onSavingsNow,
        onRun = model::run,
        onApplyDebtFirst = model::applyDebtFirst,
        onConsentTicked = model::onConsentTicked,
        onAgree = model::agree,
    )
}

@Composable
internal fun DiagnosticScreen(
    state: DiagnosticUiState,
    onClose: () -> Unit,
    onSavingsTarget: (String) -> Unit,
    onSavingsNow: (String) -> Unit,
    onRun: () -> Unit,
    onConsentTicked: (Boolean) -> Unit,
    onAgree: () -> Unit,
    onApplyDebtFirst: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings(Strings.action_back))
                }
                Text(state.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })

                if (state.policy != null) {
                    Consent(state, onConsentTicked, onAgree)
                } else if (state.diagnostics != null) {
                    Form(state, onSavingsTarget, onSavingsNow, onRun)
                    state.result?.let { Result(state) }
                    state.recommendation?.let { Recommendation(state, it, onApplyDebtFirst) }
                } else if (!state.loading) {
                    ErrorText(state.errorKey)
                }
                Text(strings(Strings.diagnostic_guidance), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.result?.disclaimer?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun Form(state: DiagnosticUiState, onSavingsTarget: (String) -> Unit, onSavingsNow: (String) -> Unit, onRun: () -> Unit) {
    AmountField(
        value = state.savingsTarget,
        onValueChange = onSavingsTarget,
        label = strings(Strings.diagnostic_savings_target),
        symbol = state.symbol,
        placeholder = "0",
        isError = state.block != null,
        large = false,
        enabled = !state.running,
    )
    AmountField(
        value = state.savingsNow,
        onValueChange = onSavingsNow,
        label = strings(Strings.diagnostic_savings_now),
        symbol = state.symbol,
        placeholder = "0",
        isError = state.block != null,
        large = false,
        imeAction = ImeAction.Done,
        enabled = !state.running,
    )
    ErrorText(state.errorKey ?: state.block?.messageKey)
    GradientButton(
        text = strings(if (state.result == null) Strings.diagnostic_run else Strings.diagnostic_run_again),
        onClick = onRun,
        enabled = state.canRun,
        busy = state.running,
    )
    state.runsLeftLabel?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    if (state.running) {
        Row(
            Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(20.dp))
            Text(strings(Strings.diagnostic_running), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Result(state: DiagnosticUiState) {
    val result = state.result ?: return
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), tonalElevation = 2.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            result.headline?.let {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = Mint.greenText())
                    Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            if (result.stored) {
                Text(strings(Strings.diagnostic_stored), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.checks.isNotEmpty()) {
                Text(strings(Strings.diagnostic_check_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                state.checks.forEach { check ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 32.dp).clearAndSetSemantics { contentDescription = check.text },
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (check.done) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (check.done) Mint.greenText() else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(check.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                HorizontalDivider()
            } else {
                Text(strings(Strings.diagnostic_where_goes), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            }
            state.rows.forEach { Figure(it) }
            state.comparison?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            state.payoff?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            if (result.steps.isNotEmpty() || result.why != null) {
                HorizontalDivider()
                Text(strings(Strings.diagnostic_why), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            }
            result.steps.forEach { step ->
                Column {
                    Text(state.stepLabel(step.to), style = MaterialTheme.typography.labelLarge, color = Mint.greenText())
                    Text(step.why, style = MaterialTheme.typography.bodyMedium)
                }
            }
            result.why?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            result.where?.let {
                Text(strings(Strings.diagnostic_where), style = MaterialTheme.typography.labelLarge, color = Mint.greenText())
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            result.next?.let {
                Text(strings(Strings.diagnostic_next), style = MaterialTheme.typography.labelLarge, color = Mint.greenText())
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** The AI's pick between the plan's order and paying debt before goals, with both side by side. */
@Composable
private fun Recommendation(state: DiagnosticUiState, title: String, onApply: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), tonalElevation = 2.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = Mint.greenText())
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            }
            state.result?.recommendWhy?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            state.orders.forEach { Figure(it) }
            state.waiting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.applied) {
                Text(
                    strings(Strings.diagnostic_applied),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Mint.greenText(),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            } else if (state.canApplyDebtFirst) {
                GradientButton(text = strings(Strings.diagnostic_apply_debt_first), onClick = onApply, busy = state.applying)
                Text(strings(Strings.diagnostic_apply_hint), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Figure(row: DiagnosticRow) {
    val color = if (row.isZero) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(row.label, style = MaterialTheme.typography.bodyLarge, color = color)
            row.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(row.amount, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
private fun Consent(state: DiagnosticUiState, onConsentTicked: (Boolean) -> Unit, onAgree: () -> Unit) {
    Text(strings(Strings.ai_budget_consent_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(strings(Strings.ai_budget_consent_body), style = MaterialTheme.typography.bodyMedium)
    ErrorText(state.errorKey)
    state.policy?.let { policy ->
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp) {
            Text(policy.body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
        }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = state.consentTicked, role = Role.Checkbox, onValueChange = onConsentTicked),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = state.consentTicked, onCheckedChange = null)
        Text(strings(Strings.import_consent_agree), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
    GradientButton(text = strings(Strings.ai_budget_consent_allow), onClick = onAgree, enabled = state.consentTicked, busy = state.consentBusy)
}
