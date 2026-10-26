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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import com.humblesolutions.finai.usecase.ChoiceRow
import com.humblesolutions.finai.usecase.DebtRow
import com.humblesolutions.finai.usecase.SplitRow
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
)

/**
 * Where the money left over goes (#76): the figure, its split, the four
 * choices behind it, and the disclaimer.
 *
 * In the Goals tab's language — the green field with the headline, a white
 * sheet beneath — because no design was supplied (decision 5). Every figure
 * is the server's and every sentence shared `YourPlan`'s. Guidance, not
 * advice: "Save or invest" names no product, and the disclaimer is on the page.
 */
@Composable
internal fun PlanScreen(state: PlanUiState, actions: PlanActions) {
    PlanFrame(state, actions, title = strings(Strings.plan_title)) {
        state.headline?.let { Headline(it, if (state.showsSplit) strings(Strings.plan_leftover_from) else null) }
        Sheet {
            if (state.showsSplit) {
                SectionTitle(strings(Strings.plan_split_title))
                state.splitRows.forEach { SplitLine(it) }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(strings(Strings.plan_choices_title), Modifier.weight(1f))
                TextButton(onClick = actions.onChangeChoices, enabled = state.plan != null) { Text(strings(Strings.plan_choices_change)) }
            }
            state.choiceRows.forEach { ChoiceLine(it) }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            LinkRow(strings(Strings.plan_debts_link), CategoryIcon.CARD, actions.onOpenDebts)
        }
        if (state.plan != null) {
            Sheet {
                SectionTitle(strings(Strings.diagnostic_card_title))
                Text(strings(Strings.diagnostic_card_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp)) {
                IconButton(onClick = actions.onBack, modifier = Modifier.align(Alignment.CenterStart)) {
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

@Composable
private fun SplitLine(row: SplitRow) {
    Row(
        Modifier
            .fillMaxWidth()
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
}

@Composable
private fun ChoiceLine(row: ChoiceRow) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(row.value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Text(row.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        row.suggestion?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Mint.greenText()) }
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
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
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
