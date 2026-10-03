package com.humblesolutions.finai.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.ui.components.ErrorText
import com.humblesolutions.finai.ui.components.FinAiIcon
import com.humblesolutions.finai.ui.components.ProviderButton
import com.humblesolutions.finai.ui.components.ScreenScaffold
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.ui.theme.FinAiPalette

/**
 * The dashboard (PRD F3): one month of what happened, against what was
 * expected of it.
 *
 * Every figure arrives already decided — by the server, then by
 * [DashboardUiState]. Nothing here computes money, which is what keeps the two
 * apps from disagreeing about a number somebody is acting on.
 *
 * The icons are Material's, with SF Symbols' equivalents on iOS. The two
 * differ slightly in shape, which nobody sees side by side, and in exchange
 * each app gets icons drawn for its own platform that scale with the reader's
 * text size.
 */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onToggleAmounts: () -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onRetry: () -> Unit,
    onImportStatement: () -> Unit,
    onAddTransaction: () -> Unit,
    onReview: () -> Unit,
    onViewAll: () -> Unit,
    onSignOut: () -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        Hills()
        ScreenScaffold {
            Header(state, onReview)
            Spacer(Modifier.height(18.dp))
            HeroCard(state, onToggleAmounts, onPreviousMonth, onNextMonth)

            Spacer(Modifier.height(14.dp))
            when {
                state.loadFailed -> LoadFailed(state, onRetry)
                state.showsEmptyState -> Unit
                else -> Figures(state)
            }

            Spacer(Modifier.height(16.dp))
            ActionPanel(state, onImportStatement, onAddTransaction, onReview, onViewAll)

            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onSignOut, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(strings(Strings.action_sign_out), color = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
}

// ── The header ──────────────────────────────────────────────────────────

@Composable
private fun Header(state: DashboardUiState, onReview: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // A silhouette, not an initial. We deliberately do not hold a name:
        // the requirements forbid collecting one, and a letter would need it.
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            FinAiIcon(Icons.Filled.Person, tint = MaterialTheme.colorScheme.primary, size = 24.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = strings(Strings.dashboard_greeting),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = strings(Strings.dashboard_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The bell is the review queue, and its dot means something: rows are
        // waiting. A decorative badge that never changes teaches people to
        // ignore it.
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onReview)
                .semantics { contentDescription = state.notificationsLabel },
            contentAlignment = Alignment.Center,
        ) {
            FinAiIcon(Icons.Filled.Notifications, tint = MaterialTheme.colorScheme.onBackground, size = 22.dp)
            if (state.hasPending) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 10.dp, end = 10.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                )
            }
        }
    }
}

/**
 * The soft hills behind the header, as in the design.
 *
 * Drawn rather than shipped as an asset so it tints with the theme and costs
 * no image. Purely decorative, so it is hidden from screen readers.
 */
@Composable
private fun Hills() {
    val primary = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .clearAndSetSemantics {},
    ) {
        val width = size.width
        val height = size.height
        fun ridge(startY: Float, peakY: Float, alpha: Float) {
            val path = Path().apply {
                moveTo(0f, height)
                lineTo(0f, startY)
                cubicTo(width * 0.25f, peakY, width * 0.55f, startY * 1.08f, width, peakY * 0.92f)
                lineTo(width, height)
                close()
            }
            drawPath(path, color = primary.copy(alpha = alpha))
        }
        ridge(startY = height * 0.62f, peakY = height * 0.40f, alpha = 0.06f)
        ridge(startY = height * 0.74f, peakY = height * 0.56f, alpha = 0.05f)
    }
}

// ── The hero ────────────────────────────────────────────────────────────

@Composable
private fun HeroCard(
    state: DashboardUiState,
    onToggleAmounts: () -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(FinAiPalette.Green, FinAiPalette.GreenDeep),
                    start = Offset.Zero,
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                ),
            )
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = strings(Strings.dashboard_net_label),
                style = MaterialTheme.typography.labelLarge,
                color = FinAiPalette.OnGreen,
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleAmounts)
                    .semantics { contentDescription = state.hideToggleLabel },
                contentAlignment = Alignment.Center,
            ) {
                FinAiIcon(
                    icon = if (state.amountsHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    tint = FinAiPalette.OnGreen,
                    size = 18.dp,
                )
            }
            Spacer(Modifier.weight(1f))
            MonthPill(state, onPreviousMonth, onNextMonth)
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = state.net,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = FinAiPalette.OnGreen,
        )

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            // Absent, not "+0%", when there is no month to compare against.
            state.changeLabel?.let { ChangePill(it, state.netIsPositive) }
            Spacer(Modifier.weight(1f))
            if (state.showsTrend) HeroBars(state)
        }
    }
}

@Composable
private fun MonthPill(state: DashboardUiState, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(FinAiPalette.OnGreen.copy(alpha = 0.12f))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Step(Icons.AutoMirrored.Filled.KeyboardArrowLeft, strings(Strings.dashboard_previous_month), state.canGoBack, onPrevious)
        Text(
            text = state.monthLabel,
            style = MaterialTheme.typography.labelLarge,
            color = FinAiPalette.OnGreen,
        )
        // Hidden rather than disabled on the month that is running: a month
        // that has not happened holds nothing to look at.
        if (state.canGoForward) {
            Step(Icons.AutoMirrored.Filled.KeyboardArrowRight, strings(Strings.dashboard_next_month), true, onNext)
        } else {
            Spacer(Modifier.width(32.dp))
        }
    }
}

@Composable
private fun Step(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        FinAiIcon(icon, tint = FinAiPalette.OnGreen, size = 14.dp)
    }
}

@Composable
private fun ChangePill(label: String, rose: Boolean) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(FinAiPalette.OnGreen.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The same arrow either way, turned over for a fall — one shape, and
        // the direction is unmistakable.
        Box(Modifier.size(12.dp).then(if (rose) Modifier else Modifier.clip(CircleShape))) {
            FinAiIcon(
                icon = if (rose) Icons.Filled.ArrowUpward else Icons.Filled.KeyboardArrowDown,
                tint = FinAiPalette.OnGreen,
                size = 12.dp,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = FinAiPalette.OnGreen)
    }
}

/** The bars inside the hero, as in the design: small, pale, and to the right. */
@Composable
private fun HeroBars(state: DashboardUiState) {
    Row(
        modifier = Modifier.width(132.dp).height(44.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        state.bars.forEach { bar ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(bar.fraction?.let { 44.dp * it } ?: 4.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .then(
                        if (bar.fraction == null) {
                            // A month with nothing recorded is an outline, not
                            // a short bar: a bar would be a figure nobody has.
                            Modifier.border(1.dp, FinAiPalette.OnGreen.copy(alpha = 0.35f), RoundedCornerShape(3.dp))
                        } else {
                            Modifier.background(FinAiPalette.OnGreen.copy(alpha = if (bar.isNegative) 0.45f else 0.85f))
                        },
                    )
                    .semantics { contentDescription = bar.description },
            )
        }
    }
}

// ── The four cards ──────────────────────────────────────────────────────

@Composable
private fun Figures(state: DashboardUiState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FigureCard(
            icon = Icons.Filled.AccountBalanceWallet,
            accent = FinAiPalette.Green,
            label = strings(Strings.dashboard_income),
            amount = state.incomeAmount,
            detail = state.incomeExpectation,
            modifier = Modifier.weight(1f),
        )
        FigureCard(
            icon = Icons.Filled.CreditCard,
            accent = FinAiPalette.Red,
            label = strings(Strings.dashboard_expenses),
            amount = state.expensesAmount,
            detail = state.expensesExpectation,
            detailIsWarning = state.expensesAreOver,
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FigureCard(
            icon = Icons.Filled.Savings,
            accent = FinAiPalette.Purple,
            label = strings(Strings.dashboard_investments),
            amount = state.investmentsAmount,
            detail = state.investmentsMovement,
            modifier = Modifier.weight(1f),
        )
        FigureCard(
            icon = Icons.AutoMirrored.Filled.ReceiptLong,
            accent = FinAiPalette.Amber,
            label = strings(Strings.dashboard_debts),
            amount = state.debtsAmount,
            detail = state.debtsMovement,
            modifier = Modifier.weight(1f),
        )
    }

    if (state.commitments.isNotEmpty()) {
        Spacer(Modifier.height(20.dp))
        Commitments(state)
    }
}

@Composable
private fun FigureCard(
    icon: ImageVector,
    accent: Color,
    label: String,
    amount: String,
    detail: String?,
    modifier: Modifier = Modifier,
    detailIsWarning: Boolean = false,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(accent.copy(alpha = 0.08f))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                FinAiIcon(icon, tint = accent, size = 20.dp)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            FinAiIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, tint = accent.copy(alpha = 0.5f), size = 14.dp)
        }
        Spacer(Modifier.height(10.dp))
        Text(text = amount, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        detail?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = if (detailIsWarning) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

// ── Commitments ─────────────────────────────────────────────────────────

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
            Spacer(Modifier.width(10.dp))
            // Decorative: `detail` already says this in words.
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(
                        if (row.wasSeen) {
                            FinAiPalette.Green.copy(alpha = 0.18f)
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                        },
                    )
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                if (row.wasSeen) {
                    FinAiIcon(Icons.Filled.Check, tint = FinAiPalette.Green, size = 12.dp)
                }
            }
        }
    }
}

// ── The action panel ────────────────────────────────────────────────────

@Composable
private fun ActionPanel(
    state: DashboardUiState,
    onImportStatement: () -> Unit,
    onAddTransaction: () -> Unit,
    onReview: () -> Unit,
    onViewAll: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(FinAiPalette.Green.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                FinAiIcon(Icons.Filled.Bolt, tint = FinAiPalette.Green, size = 20.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = strings(
                        if (state.showsEmptyState) Strings.dashboard_empty_title else Strings.dashboard_quick_actions_title,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (state.showsEmptyState) {
                    Text(
                        text = strings(Strings.dashboard_empty_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        ActionRow(
            icon = Icons.Filled.UploadFile,
            label = strings(Strings.import_entry),
            primary = true,
            onClick = onImportStatement,
        )
        Spacer(Modifier.height(8.dp))
        ActionRow(icon = Icons.Filled.Add, label = strings(Strings.manual_entry_title), onClick = onAddTransaction)
        Spacer(Modifier.height(8.dp))
        ActionRow(icon = Icons.AutoMirrored.Filled.List, label = strings(Strings.review_entry), onClick = onReview)
        Spacer(Modifier.height(8.dp))
        // Everything the household has, as opposed to the queue above,
        // which is only what still needs a person.
        ActionRow(
            icon = Icons.AutoMirrored.Filled.ReceiptLong,
            label = strings(Strings.dashboard_view_all),
            onClick = onViewAll,
        )
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
) {
    val content = if (primary) FinAiPalette.OnGreen else MaterialTheme.colorScheme.onBackground
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (primary) {
                    Modifier.background(
                        Brush.linearGradient(listOf(FinAiPalette.Green, FinAiPalette.GreenDeep)),
                    )
                } else {
                    Modifier.background(MaterialTheme.colorScheme.surface)
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FinAiIcon(icon, tint = content, size = 20.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = content,
            modifier = Modifier.weight(1f),
        )
        FinAiIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, tint = content, size = 16.dp)
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
