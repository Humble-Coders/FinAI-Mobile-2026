package com.humblesolutions.finai.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.R
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.PlanDiagnostics
import com.humblesolutions.finai.model.PlanStrategy
import com.humblesolutions.finai.ui.components.Field
import com.humblesolutions.finai.ui.components.FieldVectors
import com.humblesolutions.finai.ui.components.LightStatusBarIcons
import com.humblesolutions.finai.ui.components.Mint
import com.humblesolutions.finai.ui.components.Waves
import com.humblesolutions.finai.ui.components.tint
import com.humblesolutions.finai.ui.components.vector
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.usecase.CategoryIcon
import com.humblesolutions.finai.usecase.DebtRow
import com.humblesolutions.finai.usecase.SplitKind
import com.humblesolutions.finai.usecase.SplitRow
import com.humblesolutions.finai.usecase.SurplusCard
import com.humblesolutions.finai.usecase.WeightRow
import com.humblesolutions.finai.usecase.YourPlan

/** What the plan's screens can ask of their model, passed down rather than reached for. */
internal data class PlanActions(
    val onBack: () -> Unit,
    val onRetry: () -> Unit,
    val onChangeChoices: () -> Unit,
    val onOpenDebts: () -> Unit,
    val onStrategy: (PlanStrategy) -> Unit,
    val onEditDebt: (String) -> Unit,
    val onUpdateBalance: (String) -> Unit,
    /** Opens a diagnostic: `allocation` or `invest`. */
    val onRunDiagnostic: (String) -> Unit = {},
    /** Opens the editor on one row of the split. */
    val onEditRow: (SplitRow) -> Unit = {},
    /** The emergency fund suggestion's "Go to Goals"; null without a Goals tab. */
    val onOpenGoals: (() -> Unit)? = null,
)

/**
 * Where the money left over goes (redesign, 2026-10-31): the monthly surplus
 * with a bar of its parts, a row each for where it goes — each one editable —
 * the choices behind it in priority order, the diagnostics and the disclaimer.
 *
 * On the green field of the Goals and Budget tabs, white sheets beneath.
 * Every figure is the server's and every sentence shared `YourPlan`'s.
 * Guidance, not advice: "Save or invest" names no product, and the
 * disclaimer is on the page.
 */
@Composable
internal fun PlanScreen(state: PlanUiState, actions: PlanActions) {
    PlanFrame(state, actions, title = strings(Strings.plan_title)) {
        state.surplus?.let { SurplusPanel(it) }
        if (state.showsSplit) {
            Sheet {
                SectionTitle(strings(Strings.plan_split_title))
                state.splitNote?.let { note ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(note.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        note.why?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                        }
                    }
                }
                state.splitRows.forEach { SplitLine(it, actions.onEditRow) }
            }
        }
        Sheet {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(strings(Strings.plan_choices_title), Modifier.weight(1f))
                TextButton(onClick = actions.onChangeChoices, enabled = state.plan != null && state.weightRows.isNotEmpty()) {
                    Text(strings(Strings.plan_choices_change))
                }
            }
            state.weightRows.forEach { WeightLine(it) }
            state.emergencySuggestion?.let { (title, body) -> EmergencySuggestion(title, body, actions.onOpenGoals) }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            LinkRow(strings(Strings.plan_debts_link), CategoryIcon.CARD, actions.onOpenDebts)
        }
        if (state.plan != null) {
            Sheet {
                SectionTitle(strings(Strings.diagnostic_card_title))
                LinkRow(strings(Strings.diagnostic_allocation_title), CategoryIcon.PIGGY) { actions.onRunDiagnostic(PlanDiagnostics.ALLOCATION) }
                LinkRow(strings(Strings.diagnostic_invest_title), CategoryIcon.SALARY) { actions.onRunDiagnostic(PlanDiagnostics.INVEST) }
            }
        }
        Disclaimer(state)
    }
}

/**
 * Your debts (#76): the payoff order with each debt's month and interest,
 * the totals, and the strategy switch with what the other one would cost.
 * Each debt opens its details; "Update balance" is a button on its row.
 */
@Composable
internal fun DebtsScreen(state: PlanUiState, actions: PlanActions) {
    PlanFrame(state, actions, title = strings(Strings.debts_title)) {
        state.debtFreeLine?.let { Headline(it, listOfNotNull(state.totalInterestLine, state.interestSavedLine).joinToString(" · ").ifBlank { null }) }
        Sheet {
            if (state.showsNoDebts) {
                Text(strings(Strings.debts_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                StrategySwitch(state, actions.onStrategy)
                state.debtRows.forEach { DebtLine(state, it, actions) }
            }
            state.noticeKey?.let {
                Text(
                    strings(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        Disclaimer(state)
    }
}

// ── The frame ───────────────────────────────────────────────────────────

@Composable
private fun PlanFrame(state: PlanUiState, actions: PlanActions, title: String, content: @Composable ColumnScope.() -> Unit) {
    val dark = isSystemInDarkTheme()
    LightStatusBarIcons(dark)
    Box(Modifier.fillMaxSize().background(Field.brush(dark))) {
        Waves(Modifier.fillMaxSize())
        FieldVectors(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.widthIn(max = 560.dp).fillMaxWidth().height(60.dp).padding(horizontal = 12.dp)) {
                // A glass pill on the field, as the Budget tab's month button.
                IconButton(
                    onClick = actions.onBack,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Field.Glass)
                        .border(1.dp, Field.GlassEdge, CircleShape),
                ) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = strings(Strings.action_back), tint = Field.ink())
                }
            }
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = Field.ink(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                when {
                    // The coin covers a first load.
                    state.loading && state.plan == null -> Unit

                    state.loadFailed -> LoadFailed(state, actions.onRetry)

                    else -> content()
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** The figure at the top, on glass in the green field, with a smaller line under it. */
@Composable
private fun Headline(text: String, detail: String?) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Field.Glass)
            .border(1.dp, Field.GlassEdge, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Field.ink())
        detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Field.ink(0.85f)) }
    }
}

@Composable
private fun Sheet(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Mint.panel())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = modifier.semantics { heading() })
}

@Composable
private fun Tile(icon: CategoryIcon) {
    val dark = isSystemInDarkTheme()
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Mint.tile(icon.tint(dark))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon.vector(), contentDescription = null, tint = icon.tint(dark), modifier = Modifier.size(20.dp))
    }
}

// ── Your plan ───────────────────────────────────────────────────────────

/** A deeper green than the field, so the surplus card reads as a card on it. */
private object SurplusColors {
    fun card(dark: Boolean): Color = if (dark) Color(0xFF0C2A18) else Color(0xFF0B4425)

    /** Bright on the deep green, one per part of the split; never the only way a part is told apart. */
    fun of(kind: SplitKind): Color = when (kind) {
        SplitKind.EMERGENCY -> Color(0xFFF472B6)
        SplitKind.GOAL -> Color(0xFFA78BFA)
        SplitKind.EXTRA_DEBT -> Color(0xFF60A5FA)
        SplitKind.SAVE_OR_INVEST -> Color(0xFF4ADE80)
    }
}

/**
 * The month, "Monthly surplus" and the figure, with a bar of where it goes
 * and a legend under it. With nothing to split, the headline says why.
 */
@Composable
private fun SurplusPanel(card: SurplusCard) {
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(24.dp)
    val spoken = listOfNotNull(card.month, card.title, card.amount ?: card.headline)
        .plus(card.segments.map { "${it.label}, ${it.amount}" })
        .joinToString(", ")
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SurplusColors.card(dark))
            .border(1.dp, Field.GlassEdge, shape)
            .padding(18.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(card.month, style = MaterialTheme.typography.labelLarge, color = Field.ink(0.8f), maxLines = 1)
        Text(card.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Field.ink())
        if (card.amount != null) {
            Text(
                card.amount.orEmpty(),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = Field.ink(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(card.headline, style = MaterialTheme.typography.bodyLarge, color = Field.ink(0.9f))
        }
        if (card.segments.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                card.segments.forEach { segment ->
                    Box(Modifier.weight(segment.fraction.coerceAtLeast(MIN_SEGMENT)).fillMaxSize().background(SurplusColors.of(segment.kind)))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                card.segments.forEach { segment ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(SurplusColors.of(segment.kind)))
                        Text(
                            segment.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Field.ink(0.9f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(segment.amount, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Field.ink(), maxLines = 1)
                    }
                }
            }
        }
    }
}

/** A sliver of a part still shows: a weight of zero would also crash the row. */
private const val MIN_SEGMENT = 0.02f

/** One part of the split: heard as one sentence, with its Edit button beside it. */
@Composable
private fun SplitLine(row: SplitRow, onEdit: (SplitRow) -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Mint.card())
            .border(1.dp, Mint.edge(), shape)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                // Nothing here this month: quieter, never hidden.
                .alpha(if (row.isZero) 0.6f else 1f)
                .clearAndSetSemantics { contentDescription = row.accessibility },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tile(row.icon)
            Column(Modifier.weight(1f)) {
                Text(row.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                row.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text(row.amount, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        IconButton(onClick = { onEdit(row) }) {
            Icon(Icons.Outlined.Edit, contentDescription = strings(Strings.plan_edit_named, row.label), tint = Mint.greenText())
        }
    }
}

/** One choice: its place in the order, its share and what it is for. */
@Composable
private fun WeightLine(row: WeightRow) {
    val priority = strings(Strings.plan_choices_priority, row.priority.toString())
    val spoken = listOf(priority, row.label, row.percent, row.explanation).joinToString(", ")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PriorityBadge(row.priority)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(row.percent, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** The number in a round green tile: first, second, third. */
@Composable
internal fun PriorityBadge(priority: Int) {
    val green = Mint.greenText()
    Box(Modifier.size(32.dp).clip(CircleShape).background(Mint.tile(green)), contentAlignment = Alignment.Center) {
        Text(priority.toString(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = green)
    }
}

/** "Add an emergency fund": it becomes a choice once it is a goal, on the Goals tab. */
@Composable
private fun EmergencySuggestion(title: String, body: String, onOpenGoals: (() -> Unit)?) {
    val shape = RoundedCornerShape(18.dp)
    val green = Mint.greenText()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Mint.tile(green))
            .border(1.dp, green.copy(alpha = 0.35f), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Shield, contentDescription = null, tint = green, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onOpenGoals != null) {
            TextButton(onClick = onOpenGoals, modifier = Modifier.align(Alignment.End)) {
                Text(strings(Strings.plan_emergency_suggest_action), color = green, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun LinkRow(label: String, icon: CategoryIcon, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tile(icon)
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

/** The plan's guidance line, always, and the region's disclaimer under it when it has one. */
@Composable
private fun Disclaimer(state: PlanUiState) {
    if (state.plan == null) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(strings(Strings.plan_guidance), style = MaterialTheme.typography.bodySmall, color = Field.ink(0.85f))
        state.disclaimerText?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Field.ink(0.75f)) }
    }
}

// ── Your debts ──────────────────────────────────────────────────────────

@Composable
private fun StrategySwitch(state: PlanUiState, onStrategy: (PlanStrategy) -> Unit) {
    val chosen = state.plan?.preferences?.strategy ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(strings(Strings.debts_strategy_title))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            PlanStrategy.entries.forEachIndexed { index, strategy ->
                SegmentedButton(
                    selected = chosen == strategy,
                    onClick = { onStrategy(strategy) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = PlanStrategy.entries.size),
                    enabled = !state.switchingStrategy,
                ) { Text(YourPlan.strategyLabel(strategy, state.locale), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        state.comparisonLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun DebtLine(state: PlanUiState, row: DebtRow, actions: PlanActions) {
    val update = strings(Strings.debts_update_balance)
    val source = state.sourceLineOf(row.debtId)
    val stale = state.staleLineOf(row.debtId)
    val promotion = state.promotionLineOf(row.debtId)
    val linked = state.linkedLineOf(row.debtId)?.let { strings(Strings.debts_linked, it) }
    val spoken = listOfNotNull(row.accessibility, promotion, source, stale, linked).joinToString(", ")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            // One sentence, heard once; "Update balance" is an action on it.
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                onClick {
                    actions.onEditDebt(row.debtId)
                    true
                }
                customActions = listOf(
                    CustomAccessibilityAction(update) {
                        actions.onUpdateBalance(row.debtId)
                        true
                    },
                )
            }
            .clickable(role = Role.Button) { actions.onEditDebt(row.debtId) }
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Tile(row.icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            row.step?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Mint.greenText(), fontWeight = FontWeight.SemiBold) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(row.balance, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            promotion?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(
                row.payoff,
                style = MaterialTheme.typography.labelMedium,
                color = if (row.payoffNeedsAttention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            row.note?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Mint.greenText()) }
            source?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            linked?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            stale?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { actions.onUpdateBalance(row.debtId) }, contentPadding = PaddingValues(0.dp)) { Text(update) }
        }
    }
}

@Composable
private fun LoadFailed(state: PlanUiState, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Mint.card())
            .border(1.dp, Mint.edge(), RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(state.errorKey?.let { strings(it) } ?: strings(Strings.error_title), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onRetry) { Text(strings(Strings.action_retry)) }
    }
}
