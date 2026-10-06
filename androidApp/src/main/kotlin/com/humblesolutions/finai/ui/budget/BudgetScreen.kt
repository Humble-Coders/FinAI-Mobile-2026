package com.humblesolutions.finai.ui.budget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.BudgetLine
import com.humblesolutions.finai.ui.components.Field
import com.humblesolutions.finai.ui.components.FieldVectors
import com.humblesolutions.finai.ui.components.LearningFieldCard
import com.humblesolutions.finai.ui.components.LightStatusBarIcons
import com.humblesolutions.finai.ui.components.Mint
import com.humblesolutions.finai.ui.components.Waves
import com.humblesolutions.finai.ui.components.tint
import com.humblesolutions.finai.ui.components.vector
import com.humblesolutions.finai.ui.strings

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
) {
    val dark = isSystemInDarkTheme()
    LightStatusBarIcons(dark)

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
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header(state, onMonth)

                val progress = state.learning
                when {
                    // The coin loader covers a first load. Drawing the sheet
                    // under it would flash an empty budget with "Add a
                    // category" in it — which reads as having no budget.
                    state.loading && state.budget == null -> Unit

                    state.loadFailed -> LoadFailed(state, onRetry)

                    progress != null -> {
                        LearningFieldCard(progress, onImport = onImport)
                        // Hand-set lines still show: manual budgeting is
                        // available before the first full month (PRD F9),
                        // and hiding someone's own figures would be a lie
                        // about what they had set.
                        Sheet(state, onEdit, onAddCategory, onReviewUnfiled)
                    }

                    else -> Sheet(state, onEdit, onAddCategory, onReviewUnfiled)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ── In the field ────────────────────────────────────────────────────────

@Composable
private fun Header(state: BudgetUiState, onMonth: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = strings(Strings.budget_title),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = Field.ink(),
            modifier = Modifier.semantics { heading() },
        )

        MonthStrip(state, onMonth)

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
            }
            state.shortfallLabel?.let { Shortfall(it) }
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

@Composable
private fun MonthStrip(state: BudgetUiState, onMonth: (String) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.months.forEach { key ->
            val selected = key == state.month
            Text(
                text = state.monthOption(key),
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) Field.ink() else Field.ink(0.72f),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selected) Field.Glass else Color.Transparent)
                    .border(1.dp, if (selected) Field.GlassEdge else Color.Transparent, RoundedCornerShape(20.dp))
                    .clickable(role = Role.Tab) { onMonth(key) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .semantics { this.selected = selected },
            )
        }
    }
}

// ── The sheet ───────────────────────────────────────────────────────────

@Composable
private fun Sheet(
    state: BudgetUiState,
    onEdit: (String) -> Unit,
    onAddCategory: () -> Unit,
    onReviewUnfiled: () -> Unit,
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

        state.savings?.let { line -> LineRow(state, line) { onEdit(line.categoryId) } }

        state.debt?.let { line ->
            LineRow(state, line, caption = strings(Strings.budget_debt_caption)) { onEdit(line.categoryId) }
        }

        AddCategoryRow(onAddCategory)

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
                onClick {
                    onClick()
                    true
                }
            }
            .clickable(role = Role.Button, onClick = onClick)
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
