package com.humblesolutions.finai.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.DonutLarge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.ui.components.Field
import com.humblesolutions.finai.ui.components.FinAiIcon
import com.humblesolutions.finai.ui.components.tint
import com.humblesolutions.finai.ui.components.vector
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.usecase.BreakdownRow
import com.humblesolutions.finai.usecase.BudgetCard
import com.humblesolutions.finai.usecase.BudgetRow
import com.humblesolutions.finai.usecase.FreshnessLine
import com.humblesolutions.finai.usecase.ScoreBreakdown
import com.humblesolutions.finai.usecase.ScoreCard
import com.humblesolutions.finai.usecase.SpendRow
import com.humblesolutions.finai.usecase.SpendingCard

/*
 * Home's M4 sections (#46): the freshness line and the score card on the
 * green field; the budget and "Where it went" on the sheet; and the score's
 * breakdown. Built from Home's own pieces — the field's glass and ink, the
 * sheet's white cards, the category tiles — with no new colours.
 *
 * Everything shown arrives worded from [com.humblesolutions.finai.usecase.HomeInsights];
 * these only lay it out.
 */

// ── On the field ────────────────────────────────────────────────────────

/**
 * "As of Oct 2, 2026 · last import 3 days ago", under the hero figure.
 *
 * Stale data reads as a nudge rather than a report, on a glass pill that
 * opens the import — the one thing that would make it current.
 */
@Composable
internal fun FreshnessText(line: FreshnessLine, onImport: () -> Unit) {
    if (!line.isStale) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FinAiIcon(Icons.Filled.Schedule, tint = Field.ink(0.75f), size = 14.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = line.text,
                style = MaterialTheme.typography.labelMedium,
                color = Field.ink(0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Field.Glass)
            .border(1.dp, Field.GlassEdge, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onImport)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FinAiIcon(Icons.Filled.UploadFile, tint = Field.ink(), size = 16.dp)
        Spacer(Modifier.width(8.dp))
        Text(text = line.text, style = MaterialTheme.typography.labelMedium, color = Field.ink())
    }
}

/**
 * The score on the field: a ring out of 100, the change since last month,
 * and the way into its breakdown.
 *
 * The ring is Material's own determinate indicator — the platform's ring,
 * not a drawing of one — and does not animate, so reduce-motion has nothing
 * to stop.
 */
@Composable
internal fun ScoreFieldCard(card: ScoreCard, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    // A past month's card shows the score kept then; the breakdown is today's,
    // so only the running month's card opens it.
    val opens = if (card.canOpen) {
        Modifier.clickable(role = Role.Button, onClickLabel = strings(Strings.home_score_open), onClick = onOpen)
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(Field.Glass)
            .border(1.dp, Field.GlassEdge, shape)
            .then(opens)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .clearAndSetSemantics { contentDescription = card.accessibility },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { card.fraction.toFloat() },
                modifier = Modifier.size(64.dp),
                color = Field.ink(),
                trackColor = Field.ink(0.2f),
                strokeWidth = 6.dp,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
            )
            Text(
                text = card.score,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Field.ink(),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = strings(Strings.home_score_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Field.ink(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = strings(Strings.home_score_out_of),
                style = MaterialTheme.typography.labelMedium,
                color = Field.ink(0.75f),
            )
            card.change?.let {
                Spacer(Modifier.height(4.dp))
                Text(text = it, style = MaterialTheme.typography.labelLarge, color = Field.ink(0.92f))
            }
            card.held?.let {
                Spacer(Modifier.height(4.dp))
                Text(text = it, style = MaterialTheme.typography.labelMedium, color = Field.ink(0.75f))
            }
        }
        if (card.canOpen) {
            FinAiIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, tint = Field.ink(0.85f), size = 20.dp)
        }
    }
}

// ── On the sheet ────────────────────────────────────────────────────────

/** A sheet section's heading, as "Recent transactions" is drawn. */
@Composable
private fun SectionHeader(icon: ImageVector, title: String, action: (@Composable () -> Unit)? = null) {
    val dark = isSystemInDarkTheme()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(IncomeAccent.icon.copy(alpha = if (dark) 0.2f else 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            FinAiIcon(icon, tint = IncomeAccent.labelFor(dark), size = 20.dp)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        action?.invoke()
    }
}

/** The pill "View all" is, for "See budget". */
@Composable
private fun SectionLink(label: String, onClick: () -> Unit) {
    val dark = isSystemInDarkTheme()
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(IncomeAccent.icon.copy(alpha = if (dark) 0.18f else 0.1f))
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = IncomeAccent.labelFor(dark),
            )
            FinAiIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, tint = IncomeAccent.labelFor(dark), size = 18.dp)
        }
    }
}

/** A white row card, as the recent list's rows are. */
@Composable
private fun SheetCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (dark) MaterialTheme.colorScheme.surfaceContainer else Color.White)
            .border(1.dp, if (dark) Color.White.copy(alpha = 0.06f) else Color(0xFFE8F1EC), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) { content() }
}

@Composable
private fun CategoryTile(row: com.humblesolutions.finai.usecase.CategoryIcon) {
    val dark = isSystemInDarkTheme()
    val tint = row.tint(dark)
    Box(
        Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = if (dark) 0.2f else 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        FinAiIcon(row.vector(), tint = tint, size = 22.dp)
    }
}

/**
 * "This month's budget": the totals and the lines nearest or over their
 * allocation. "See budget" is drawn only when there is a Budget tab to open.
 */
@Composable
internal fun BudgetSection(card: BudgetCard, onOpenBudget: (() -> Unit)?) {
    SectionHeader(
        icon = Icons.Filled.PieChart,
        title = strings(Strings.home_budget_title),
        action = onOpenBudget?.let { open -> { SectionLink(strings(Strings.home_budget_see), open) } },
    )
    Spacer(Modifier.height(6.dp))
    Text(card.totals, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    card.shortfall?.let {
        Spacer(Modifier.height(2.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium, color = ExpensesAccent.labelFor(isSystemInDarkTheme()))
    }
    Spacer(Modifier.height(10.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        card.rows.forEach { BudgetLineCard(it) }
    }
}

@Composable
private fun BudgetLineCard(row: BudgetRow) {
    val dark = isSystemInDarkTheme()
    val warning = ExpensesAccent.labelFor(dark)
    SheetCard(Modifier.clearAndSetSemantics { contentDescription = row.accessibility }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryTile(row.icon)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = row.amounts,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { row.fraction.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp)),
                    color = if (row.isOver) warning else row.icon.tint(dark),
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                // Never colour alone: an over line says so, with an icon.
                row.overLabel?.let {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FinAiIcon(Icons.Filled.Warning, tint = warning, size = 14.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(it, style = MaterialTheme.typography.labelMedium, color = warning, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** "Where it went": the month's spending by category, the top five, then all. */
@Composable
internal fun SpendingSection(card: SpendingCard, expanded: Boolean, onToggle: () -> Unit) {
    SectionHeader(icon = Icons.Outlined.DonutLarge, title = strings(Strings.home_spend_title))
    Spacer(Modifier.height(10.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        (if (expanded) card.all else card.top).forEach { SpendLine(it) }
    }
    card.showAllLabel?.let { more ->
        TextButton(onClick = onToggle, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (expanded) strings(Strings.home_spend_show_less) else more)
        }
    }
}

@Composable
private fun SpendLine(row: SpendRow) {
    SheetCard(Modifier.clearAndSetSemantics { contentDescription = row.accessibility }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryTile(row.icon)
            Spacer(Modifier.width(12.dp))
            Text(
                text = row.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(row.amount, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

// ── The breakdown ───────────────────────────────────────────────────────

/**
 * What the score is made of, read from `/health-score` when opened.
 *
 * A part the server could not score says "Not counted yet" and draws no bar —
 * a bar at zero would claim a score of zero it does not have.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScoreBreakdownSheet(
    breakdown: ScoreBreakdown?,
    loading: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = strings(Strings.home_breakdown_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(16.dp))
            when {
                breakdown != null -> BreakdownBody(breakdown)

                failed -> {
                    Text(strings(Strings.home_breakdown_failed), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(strings(Strings.action_retry))
                    }
                }

                loading -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun BreakdownBody(breakdown: ScoreBreakdown) {
    Text(
        text = strings(Strings.home_breakdown_score, breakdown.score),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(16.dp))
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        breakdown.rows.forEach { BreakdownLine(it) }
    }
    Spacer(Modifier.height(20.dp))
    breakdown.formula?.let {
        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
    }
    Text(
        text = breakdown.disclaimer,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Start,
    )
}

@Composable
private fun BreakdownLine(row: BreakdownRow) {
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = row.accessibility }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = row.score,
                style = MaterialTheme.typography.labelLarge,
                color = if (row.available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (row.available) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { row.fraction.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp)),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
        if (row.sentence.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(row.sentence, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What the score card, the breakdown and the two sheet sections can do. */
class InsightActions(
    val onOpenBreakdown: () -> Unit = {},
    val onCloseBreakdown: () -> Unit = {},
    val onRetryBreakdown: () -> Unit = {},
    val onToggleSpending: () -> Unit = {},
    /**
     * Open the Budget tab on a month (`YYYY-MM`) — the one Home is showing, so
     * "See budget" under August's budget opens August. Null when there is no
     * Budget tab, which hides the link.
     */
    val onOpenBudget: ((String) -> Unit)? = null,
)
