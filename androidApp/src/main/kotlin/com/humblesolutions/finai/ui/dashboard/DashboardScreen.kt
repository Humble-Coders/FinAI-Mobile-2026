package com.humblesolutions.finai.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.ui.components.ErrorText
import com.humblesolutions.finai.ui.components.GradientButton
import com.humblesolutions.finai.ui.components.ProviderButton
import com.humblesolutions.finai.ui.components.ScreenScaffold
import com.humblesolutions.finai.ui.strings

/**
 * The dashboard (PRD F3): one month of what happened, against what was
 * expected of it.
 *
 * Every figure here arrives already decided — by the server, and then by
 * [DashboardUiState]. Nothing on this screen computes money, which is what
 * keeps Android and iOS from disagreeing about a number somebody is acting on.
 */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onRetry: () -> Unit,
    onImportStatement: () -> Unit,
    onAddTransaction: () -> Unit,
    onReview: () -> Unit,
    onSignOut: () -> Unit,
) {
    ScreenScaffold {
        Text(
            text = strings(Strings.dashboard_greeting),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = strings(Strings.dashboard_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))
        MonthStrip(state, onPreviousMonth, onNextMonth)

        Spacer(Modifier.height(12.dp))
        when {
            state.loadFailed -> LoadFailed(state, onRetry)
            state.showsEmptyState -> FirstSteps(onImportStatement, onAddTransaction)
            else -> Figures(state)
        }

        Spacer(Modifier.height(24.dp))
        GradientButton(text = strings(Strings.import_entry), onClick = onImportStatement)
        Spacer(Modifier.height(8.dp))
        ProviderButton(text = strings(Strings.manual_entry_title), onClick = onAddTransaction)
        Spacer(Modifier.height(8.dp))
        ProviderButton(text = strings(Strings.review_entry), onClick = onReview)
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSignOut, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(strings(Strings.action_sign_out), color = MaterialTheme.colorScheme.onBackground)
        }
    }
}

@Composable
private fun MonthStrip(state: DashboardUiState, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Text glyphs rather than Material icons: the project carries no icon
        // dependency, and two chevrons do not justify one. The label each
        // carries is what a screen reader announces, so the glyph itself is
        // hidden from it.
        MonthStep(
            glyph = "\u2039",
            label = strings(Strings.dashboard_previous_month),
            enabled = state.canGoBack,
            onClick = onPrevious,
        )
        Text(text = state.monthLabel, style = MaterialTheme.typography.titleMedium)
        // Hidden rather than disabled on the month that is running: a month
        // which has not happened holds nothing, so there is nothing to offer.
        if (state.canGoForward) {
            MonthStep(
                glyph = "\u203A",
                label = strings(Strings.dashboard_next_month),
                enabled = true,
                onClick = onNext,
            )
        } else {
            Spacer(Modifier.width(StepWidth))
        }
    }
}

@Composable
private fun MonthStep(glyph: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .width(StepWidth)
            .heightIn(min = 48.dp)
            .semantics { contentDescription = label },
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
private fun Figures(state: DashboardUiState) {
    NetCard(state)

    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FigureCard(
            label = strings(Strings.dashboard_income),
            amount = state.incomeAmount,
            detail = state.incomeExpectation,
            modifier = Modifier.weight(1f),
        )
        FigureCard(
            label = strings(Strings.dashboard_expenses),
            amount = state.expensesAmount,
            detail = state.expensesExpectation,
            // Only expenses: earning more than expected is good news.
            detailIsWarning = state.expensesAreOver,
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FigureCard(
            label = strings(Strings.dashboard_investments),
            amount = state.investmentsAmount,
            detail = state.investmentsMovement,
            modifier = Modifier.weight(1f),
        )
        FigureCard(
            label = strings(Strings.dashboard_debts),
            amount = state.debtsAmount,
            detail = state.debtsMovement,
            modifier = Modifier.weight(1f),
        )
    }

    state.pendingReviewLabel?.let {
        Spacer(Modifier.height(12.dp))
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }

    if (state.showsTrend) {
        Spacer(Modifier.height(24.dp))
        Trend(state)
    }

    if (state.commitments.isNotEmpty()) {
        Spacer(Modifier.height(24.dp))
        Commitments(state)
    }
}

@Composable
private fun NetCard(state: DashboardUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(20.dp),
    ) {
        Text(
            text = strings(Strings.dashboard_net_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = state.net,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
        // Absent, not "+0%", when there is no month to compare against.
        state.changeLabel?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun FigureCard(
    label: String,
    amount: String,
    detail: String?,
    modifier: Modifier = Modifier,
    detailIsWarning: Boolean = false,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(text = amount, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        detail?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (detailIsWarning) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun Trend(state: DashboardUiState) {
    Text(
        text = strings(Strings.dashboard_trend_title),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth().height(BarHeight),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.bars.forEach { bar ->
            Column(
                modifier = Modifier.weight(1f).semantics { contentDescription = bar.description },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // A month with no rows is drawn as an outline. A
                        // zero-height bar would state a fact nobody observed.
                        .height(bar.fraction?.let { BarHeight * it } ?: EmptyBarHeight)
                        .clip(RoundedCornerShape(4.dp))
                        .then(
                            if (bar.fraction == null) {
                                Modifier.border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(4.dp),
                                )
                            } else {
                                Modifier.background(
                                    if (bar.isNegative) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                )
                            },
                        ),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = bar.shortLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // The label repeats what the bar's description already
                    // says, so a screen reader hears it once.
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
    }
}

@Composable
private fun Commitments(state: DashboardUiState) {
    Text(
        text = strings(Strings.dashboard_commitments_title),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    state.commitmentsSummary?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(8.dp))
    state.commitments.forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = row.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = row.detail,
                    style = MaterialTheme.typography.bodySmall,
                    // Not red when unseen: we do not know it is unpaid, only
                    // that we did not find it.
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = row.expected,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            // The tick is decorative; `detail` already says it in words, so a
            // screen reader is not told twice.
            Text(
                text = if (row.wasSeen) "✓" else "–",
                color = if (row.wasSeen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

@Composable
private fun FirstSteps(onImportStatement: () -> Unit, onAddTransaction: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = strings(Strings.dashboard_empty_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = strings(Strings.dashboard_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoadFailed(state: DashboardUiState, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        ErrorText(state.errorKey)
        Spacer(Modifier.height(8.dp))
        ProviderButton(text = strings(Strings.dashboard_retry), onClick = onRetry)
    }
}

/** Wide enough for a comfortable touch target on both sides of the label. */
private val StepWidth = 56.dp

private val BarHeight = 96.dp

/** A month with nothing recorded still leaves a mark, so the gap is visible. */
private val EmptyBarHeight = 6.dp
