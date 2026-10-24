package com.humblesolutions.finai.ui.budget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.BudgetLine
import com.humblesolutions.finai.ui.components.CollapsingTitleBar
import com.humblesolutions.finai.ui.components.Field
import com.humblesolutions.finai.ui.components.FieldVectors
import com.humblesolutions.finai.ui.components.LearningFieldCard
import com.humblesolutions.finai.ui.components.LightStatusBarIcons
import com.humblesolutions.finai.ui.components.Mint
import com.humblesolutions.finai.ui.components.MonthYearPickerDialog
import com.humblesolutions.finai.ui.components.Waves
import com.humblesolutions.finai.ui.components.fadesAsTitleCollapses
import com.humblesolutions.finai.ui.components.rememberTitleCollapse
import com.humblesolutions.finai.ui.components.tint
import com.humblesolutions.finai.ui.components.vector
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.usecase.DashboardMonths
import com.humblesolutions.finai.usecase.OverviewCommitment
import com.humblesolutions.finai.usecase.OverviewRow

/**
 * The month's budget: every category with what has been spent against it,
 * and any line changeable by hand (#47, PRD F4).
 *
 * Built in Home's language — the green field with the totals, a white sheet
 * with the lines — because no design was supplied and the ticket's fallback
 * is to match what is already there.
 *
 * **Over budget is never colour alone.** Every line that has passed its
 * allocation carries an "Over by" label and a warning mark, which is also
 * what a screen reader reads.
 */
@Composable
internal fun BudgetScreen(
    state: BudgetUiState,
    onMonth: (String) -> Unit,
    onEdit: (String) -> Unit,
    onAddCategory: () -> Unit,
    onReviewUnfiled: () -> Unit,
    onRetry: () -> Unit,
    onImport: () -> Unit,
    /** "Add to budget" on a category spent in with no line. */
    onAddUnbudgeted: (String) -> Unit = {},
    /** "Set a savings target", when there is none. */
    onSetSavingsTarget: () -> Unit = {},
    /** Your plan (#76), from the Savings line; null when the plan is off, which hides the link. */
    onOpenPlan: (() -> Unit)? = null,
) {
    val dark = isSystemInDarkTheme()
    LightStatusBarIcons(dark)
    val scroll = rememberScrollState()
    // How far the big title has shrunk into the bar, as on Home.
    val collapsed = rememberTitleCollapse(scroll)

    Box(Modifier.fillMaxSize().background(Field.brush(dark))) {
        Waves(Modifier.fillMaxSize())
        FieldVectors(Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().safeDrawingPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header(state, onMonth, collapsed)

                when {
                    // The coin loader covers a first load. Drawing the sheet
                    // under it would flash an empty budget with "Add a
                    // category" in it — which reads as having no budget.
                    state.loading && state.budget == null -> Unit

                    state.loadFailed -> LoadFailed(state, onRetry)

                    else -> {
                        state.learning?.let { LearningFieldCard(it, onImport = onImport) }
                        // The month as the statements tell it, first.
                        Overview(state)
                        if (state.showsPlan) {
                            // Hand-set lines still show while learning:
                            // manual budgeting is available before the first
                            // full month (PRD F9), and hiding someone's own
                            // figures would be a lie about what they had set.
                            PlanSummary(state, onOpenPlan)
                            Sheet(state, onEdit, onAddCategory, onReviewUnfiled, onAddUnbudgeted, onSetSavingsTarget)
                        } else {
                            // A month that has ended is a record, not a plan:
                            // a one-off is a row in its month, not a line.
                            Spent(state)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        // Pinned once the page has scrolled: the screen's name, centred.
        CollapsingTitleBar(strings(Strings.tab_budget), dark, collapsed)
    }
}

// ── In the field ────────────────────────────────────────────────────────

@Composable
private fun Header(state: BudgetUiState, onMonth: (String) -> Unit, collapsed: () -> Float) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = strings(Strings.budget_title),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = Field.ink(),
            modifier = Modifier
                .fadesAsTitleCollapses(collapsed)
                .semantics { heading() },
        )

        MonthButton(state, onMonth)
    }
}

/** "Your budget": spent of allocated, expected income, what is set aside. */
@Composable
private fun PlanSummary(state: BudgetUiState, onOpenPlan: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            strings(Strings.budget_your_budget),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Field.ink(),
            modifier = Modifier.semantics { heading() },
        )
        if (state.budget != null) {
            Text(
                text = state.totalsLabel,
                style = MaterialTheme.typography.titleMedium,
                color = Field.ink(0.92f),
            )
            LinearProgressIndicator(
                progress = { state.totalFraction },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = Field.ink(0.95f),
                trackColor = Field.Glass,
            )
            FieldFact(strings(Strings.budget_expected_income), state.expectedIncomeLabel)
            state.savingsLabel?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Field.ink(0.85f))
                // What the plan does with it (#76) — the same figure, split.
                onOpenPlan?.let { open ->
                    TextButton(onClick = open, contentPadding = PaddingValues(0.dp)) {
                        Text(strings(Strings.budget_savings_plan_link), color = Field.ink(), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            state.shortfallLabel?.let { Shortfall(it) }
        }
    }
}

// ── Above the lines ─────────────────────────────────────────────────────

/** The month so far, its commitments, what is still to come, and what to do with the rest. */
@Composable
private fun Overview(state: BudgetUiState) {
    state.soFar?.let { rows ->
        Card(state.soFarTitle, subtitle = strings(Strings.budget_from_statements)) { rows.forEach { FigureRow(it) } }
    }
    if (state.commitments.isNotEmpty()) {
        Card(strings(Strings.budget_commitments_title)) { state.commitments.forEach { CommitmentLine(it) } }
    }
    state.stillExpected?.let { rows ->
        Card(strings(Strings.budget_still_expected_title)) { rows.forEach { FigureRow(it) } }
    }
    state.suggestion?.let { Suggestion(it, state.suggestsUsing) }
}

@Composable
private fun Card(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Mint.panel())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        content()
    }
}

/** A label and its amount; the card's bottom line is ruled off and set in bold. */
@Composable
private fun FigureRow(row: OverviewRow) {
    if (row.isTotal) HorizontalDivider(color = Mint.edge())
    Row(
        Modifier.fillMaxWidth().heightIn(min = 32.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val weight = if (row.isTotal) FontWeight.SemiBold else FontWeight.Normal
        Text(row.label, style = MaterialTheme.typography.bodyMedium, fontWeight = weight, modifier = Modifier.weight(1f))
        Text(row.amount, style = MaterialTheme.typography.bodyMedium, fontWeight = weight)
    }
}

/** A commitment: a tick when its payment was seen, a dashed circle when not — and the words, always. */
@Composable
private fun CommitmentLine(item: OverviewCommitment) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clearAndSetSemantics { contentDescription = item.description },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (item.seen) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (item.seen) Mint.greenText() else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(item.amount, style = MaterialTheme.typography.bodyMedium)
            Text(item.status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What to do with what is left — or, when the month went short, that it did. */
@Composable
private fun Suggestion(text: String, using: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Field.Glass)
            .border(1.dp, Field.GlassEdge, RoundedCornerShape(16.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (using) Icons.Outlined.Savings else Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = Field.ink(0.9f),
            modifier = Modifier.size(22.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Field.ink())
    }
}

/** A month that has ended: every category at what was actually spent. */
@Composable
private fun Spent(state: BudgetUiState) {
    val dark = isSystemInDarkTheme()
    Text(
        strings(Strings.budget_where_it_went),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = Field.ink(),
        modifier = Modifier.semantics { heading() },
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Mint.panel())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.spentRows.isEmpty()) {
            Text(strings(Strings.budget_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.spentRows.forEach { spend ->
            val icon = state.iconOf(spend)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Mint.tile(icon.tint(dark))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon.vector(), contentDescription = null, tint = icon.tint(dark), modifier = Modifier.size(18.dp))
                }
                Text(spend.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(spend.amount, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun FieldFact(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Field.ink(0.7f))
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = Field.ink(),
        )
    }
}

/** Said in words, not in red: a shortfall is a fact, not a warning colour. */
@Composable
private fun Shortfall(label: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Field.Glass)
            .border(1.dp, Field.GlassEdge, RoundedCornerShape(14.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = Field.ink(0.9f), modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Field.ink())
    }
}

/**
 * The month shown, on a glass pill that opens the month-and-year calendar.
 * One control rather than a strip of a dozen pills: any month in range is a
 * tap or two away, and the field stays uncluttered.
 */
@Composable
private fun MonthButton(state: BudgetUiState, onMonth: (String) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val label = state.monthLabel
    val description = strings(Strings.budget_month_change, label)
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Field.Glass)
            .border(1.dp, Field.GlassEdge, RoundedCornerShape(20.dp))
            .clickable(role = Role.Button) { picking = true }
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick {
                    picking = true
                    true
                }
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = Field.ink(), modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Field.ink())
        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = Field.ink(0.85f), modifier = Modifier.size(20.dp))
    }
    if (picking) {
        val current = DashboardMonths.current()
        MonthYearPickerDialog(
            selected = DashboardMonths.parse(state.month) ?: current,
            current = current,
            locale = state.locale,
            onPick = {
                picking = false
                onMonth(it)
            },
            onDismiss = { picking = false },
        )
    }
}

// ── The sheet ───────────────────────────────────────────────────────────

@Composable
private fun Sheet(
    state: BudgetUiState,
    onEdit: (String) -> Unit,
    onAddCategory: () -> Unit,
    onReviewUnfiled: () -> Unit,
    onAddUnbudgeted: (String) -> Unit,
    onSetSavingsTarget: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Mint.panel())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.showsEmpty) {
            Text(
                strings(Strings.budget_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.lines.forEach { line -> LineRow(state, line) { onEdit(line.categoryId) } }

        // Savings is a target the person sets, never a figure filled in for them.
        state.savings?.let { line ->
            LineRow(state, line, caption = strings(Strings.budget_savings_caption)) { onEdit(line.categoryId) }
        }
        if (state.canSetSavingsTarget) SetSavingsTargetRow(state.savingsRoomLabel, onSetSavingsTarget)

        state.debt?.let { line ->
            LineRow(state, line, caption = strings(Strings.budget_debt_caption)) { onEdit(line.categoryId) }
        }

        state.unbudgetedLabel?.let { Unbudgeted(state, it, onAddUnbudgeted) }

        AddCategoryRow(onAddCategory)
        Text(
            strings(Strings.budget_carries_forward),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.unfiledLabel?.let { label ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Mint.card())
                    .border(1.dp, Mint.edge(), RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onReviewUnfiled, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(strings(Strings.budget_unfiled_action))
                }
            }
        }
    }
}

/** One category: its tile, its name, spent of allocated, and a bar. */
@Composable
private fun LineRow(
    state: BudgetUiState,
    line: BudgetLine,
    caption: String? = null,
    onClick: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val icon = state.iconOf(line)
    val over = state.isOver(line)
    val overLabel = state.overLabel(line)
    val editLabel = strings(Strings.budget_edit_line, state.nameOf(line))

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            // One sentence, so the row is heard once and "over by" is heard
            // in words rather than inferred from a colour. Cleared rather than
            // added to: the name, the amounts and the labels below would
            // otherwise each be read again after it.
            .clearAndSetSemantics {
                contentDescription = state.descriptionOf(line)
                role = Role.Button
                onClick(label = editLabel) {
                    onClick()
                    true
                }
            }
            .clickable(role = Role.Button, onClickLabel = editLabel, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Mint.tile(icon.tint(dark))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon.vector(), contentDescription = null, tint = icon.tint(dark), modifier = Modifier.size(20.dp))
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.nameOf(line),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (over) {
                    Icon(
                        Icons.Filled.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                }
                // The edit button beside the name. The whole row opens the
                // same editor — this says so, where the eye looks first.
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(Mint.tile(Mint.greenText())),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null, tint = Mint.greenText(), modifier = Modifier.size(15.dp))
                }
            }
            Text(
                state.amountsOf(line),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { state.fractionOf(line) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = if (over) MaterialTheme.colorScheme.error else Mint.greenText(),
            )
            overLabel?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
            state.suggestionLabel(line)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            caption?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * "Not in your budget": the categories spent in this month that have no line,
 * each with what was spent and a way to give it one.
 */
@Composable
private fun Unbudgeted(state: BudgetUiState, label: String, onAdd: (String) -> Unit) {
    val dark = isSystemInDarkTheme()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Mint.card())
            .border(1.dp, Mint.edge(), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            strings(Strings.budget_unbudgeted_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.unbudgeted.forEach { spend ->
            val icon = state.iconOf(spend)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Mint.tile(icon.tint(dark))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon.vector(), contentDescription = null, tint = icon.tint(dark), modifier = Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(spend.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        state.spentOf(spend),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onAdd(spend.categoryId) }) {
                    Text(strings(Strings.budget_unbudgeted_add), maxLines = 1)
                }
            }
        }
    }
}

/** "Set a savings target", with how much room there is for one. */
@Composable
private fun SetSavingsTargetRow(room: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Mint.tile(Mint.greenText())),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Savings, contentDescription = null, tint = Mint.greenText(), modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(strings(Strings.budget_set_target), style = MaterialTheme.typography.bodyLarge, color = Mint.greenText())
            room?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun AddCategoryRow(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Mint.tile(Mint.greenText())),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = Mint.greenText(), modifier = Modifier.size(20.dp))
        }
        Text(strings(Strings.budget_add_category), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun LoadFailed(state: BudgetUiState, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Mint.card())
            .border(1.dp, Mint.edge(), RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            // The server's own words for this refusal when it gave any —
            // "not included in your plan" beats "something went wrong".
            text = state.errorKey?.let { strings(it) } ?: strings(Strings.error_title),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onRetry) { Text(strings(Strings.action_retry)) }
    }
}
