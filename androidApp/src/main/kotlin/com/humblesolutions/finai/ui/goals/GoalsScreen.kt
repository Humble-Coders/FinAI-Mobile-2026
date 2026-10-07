package com.humblesolutions.finai.ui.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Goal
import com.humblesolutions.finai.ui.components.CollapsingTitleBar
import com.humblesolutions.finai.ui.components.Field
import com.humblesolutions.finai.ui.components.FieldVectors
import com.humblesolutions.finai.ui.components.LightStatusBarIcons
import com.humblesolutions.finai.ui.components.Mint
import com.humblesolutions.finai.ui.components.Waves
import com.humblesolutions.finai.ui.components.fadesAsTitleCollapses
import com.humblesolutions.finai.ui.components.rememberTitleCollapse
import com.humblesolutions.finai.ui.components.tint
import com.humblesolutions.finai.ui.components.vector
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.usecase.GoalBlock
import com.humblesolutions.finai.usecase.GoalEdit

/** What the Goals tab can ask of its model, passed down rather than reached for. */
internal data class GoalsActions(
    val onNew: () -> Unit,
    val onEdit: (String) -> Unit,
    val onAddMoney: (String) -> Unit,
    val onToggleReorder: () -> Unit,
    val onMoveUp: (Int) -> Unit,
    val onMoveDown: (Int) -> Unit,
    val onRetry: () -> Unit,
)

/**
 * The household's savings goals: what each needs a month and whether it is
 * on pace (#52, PRD F5).
 *
 * In Home's language — the green field with the comparison, a white sheet with
 * the goals — because no design was supplied. Every figure is the server's and
 * every sentence comes from shared `GoalEdit`.
 *
 * **Where a goal stands is always said in words**, never left to the bar's
 * colour, and a long-term goal says plainly that its projection counts no
 * investment growth, beside the region's disclaimer.
 */
@Composable
internal fun GoalsScreen(state: GoalsUiState, actions: GoalsActions) {
    val dark = isSystemInDarkTheme()
    LightStatusBarIcons(dark)
    val scroll = rememberScrollState()
    // How far the big title has shrunk into the bar, as on Home.
    val collapsed = rememberTitleCollapse(scroll)

    Box(Modifier.fillMaxSize().background(Field.brush(dark))) {
        Waves(Modifier.fillMaxSize())
        FieldVectors(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header(state, actions, collapsed)
                when {
                    // The coin covers a first load; an empty sheet under it would
                    // read as having no goals.
                    state.loading && state.page == null -> Unit

                    state.loadFailed -> LoadFailed(state, actions.onRetry)

                    else -> Sheet(state, actions)
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        // Pinned once the page has scrolled: the screen's name, centred.
        CollapsingTitleBar(strings(Strings.tab_goals), dark, collapsed)
    }
}

// ── In the field ────────────────────────────────────────────────────────

@Composable
private fun Header(state: GoalsUiState, actions: GoalsActions, collapsed: () -> Float) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = strings(Strings.goals_title),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = Field.ink(),
                modifier = Modifier
                    .weight(1f)
                    .fadesAsTitleCollapses(collapsed)
                    .semantics { heading() },
            )
            if (state.goals.size > 1) {
                TextButton(onClick = actions.onToggleReorder) {
                    Text(
                        strings(if (state.reordering) Strings.action_done else Strings.goals_reorder_hint),
                        color = Field.ink(),
                    )
                }
            }
        }
        state.comparisonLine?.let { line ->
            val short = state.page?.budget?.shortfall != null
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
                // A shortfall is said in words; the mark only draws the eye to it.
                if (short) Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = Field.ink(0.9f), modifier = Modifier.size(20.dp))
                Text(line, style = MaterialTheme.typography.bodyMedium, color = Field.ink())
            }
        }
    }
}

// ── The sheet ───────────────────────────────────────────────────────────

@Composable
private fun Sheet(state: GoalsUiState, actions: GoalsActions) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Mint.panel())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.showsEmpty) {
            Text(strings(Strings.goals_empty_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(strings(Strings.goals_empty_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        state.goals.forEachIndexed { index, goal ->
            GoalRow(state, goal, index, actions)
        }

        NewGoalRow(state, actions.onNew)

        // A refusal that is not about one sheet — a reorder that failed, say.
        state.noticeKey?.let {
            Text(strings(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun GoalRow(state: GoalsUiState, goal: Goal, index: Int, actions: GoalsActions) {
    val dark = isSystemInDarkTheme()
    val icon = GoalEdit.iconOf(goal.kind)
    val description = state.descriptionOf(goal)
    val moveUp = strings(Strings.goals_move_up)
    val moveDown = strings(Strings.goals_move_down)
    val addMoney = strings(Strings.goals_add_money)

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            // One sentence, so the goal is heard once and its standing in words.
            // The moves and "Add money" are actions on it rather than more
            // stops for a screen reader to step through.
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick {
                    actions.onEdit(goal.id)
                    true
                }
                customActions = buildList {
                    if (!goal.isAchieved) {
                        add(
                            CustomAccessibilityAction(addMoney) {
                                actions.onAddMoney(goal.id)
                                true
                            },
                        )
                    }
                    if (state.canMoveUp(index)) {
                        add(
                            CustomAccessibilityAction(moveUp) {
                                actions.onMoveUp(index)
                                true
                            },
                        )
                    }
                    if (state.canMoveDown(index)) {
                        add(
                            CustomAccessibilityAction(moveDown) {
                                actions.onMoveDown(index)
                                true
                            },
                        )
                    }
                }
            }
            .clickable(role = Role.Button) { actions.onEdit(goal.id) }
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Mint.tile(icon.tint(dark))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon.vector(), contentDescription = null, tint = icon.tint(dark), modifier = Modifier.size(20.dp))
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(goal.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state.amountsOf(goal), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(
                progress = { goal.fraction },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = Mint.greenText(),
            )
            state.statusOf(goal).takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.labelMedium)
            }
            state.growthLineOf(goal)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.disclaimerOf(goal)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!goal.isAchieved && !state.reordering) {
                TextButton(onClick = { actions.onAddMoney(goal.id) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(addMoney)
                }
            }
        }

        // Native buttons rather than a hand-made drag: Compose has no list
        // reorder of its own, and these are what a screen reader needs anyway.
        if (state.reordering) {
            Column {
                IconButton(onClick = { actions.onMoveUp(index) }, enabled = state.canMoveUp(index)) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null)
                }
                IconButton(onClick = { actions.onMoveDown(index) }, enabled = state.canMoveDown(index)) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun NewGoalRow(state: GoalsUiState, onNew: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onNew)
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
        Column {
            Text(strings(Strings.goals_new), style = MaterialTheme.typography.bodyLarge)
            // At the limit, said before the editor opens rather than after five fields.
            if (state.atLimit) {
                Text(
                    GoalEdit.blockText(GoalBlock.TOO_MANY, state.currency, state.locale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LoadFailed(state: GoalsUiState, onRetry: () -> Unit) {
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
