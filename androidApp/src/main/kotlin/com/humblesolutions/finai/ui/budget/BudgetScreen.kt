package com.humblesolutions.finai.ui.budget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.BudgetLine
import com.humblesolutions.finai.model.SpendingCheck
import com.humblesolutions.finai.ui.components.CollapsingTitleBar
import com.humblesolutions.finai.ui.components.ErrorText
import com.humblesolutions.finai.ui.components.Field
import com.humblesolutions.finai.ui.components.FieldVectors
import com.humblesolutions.finai.ui.components.GradientButton
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
import com.humblesolutions.finai.usecase.BudgetBoard
import com.humblesolutions.finai.usecase.DashboardMonths
import com.humblesolutions.finai.usecase.NextStep
import com.humblesolutions.finai.usecase.NextStepAction
import com.humblesolutions.finai.usecase.OverviewCommitment
import com.humblesolutions.finai.usecase.OverviewRow
import com.humblesolutions.finai.usecase.SpendingCheckWords
import com.humblesolutions.finai.usecase.SpendingShareRow
import com.humblesolutions.finai.usecase.SpendingStatus
import kotlinx.coroutines.launch

/**
 * The month's budget, as a board of cards on the green field (#47, PRD F4):
 * the month so far, what is still expected, the AI's word on it, every
 * category against its share of the budget, and the next steps.
 *
 * Laid out after the reference design the product owner shared; every figure
 * in it is the server's, worded once in the shared `BudgetBoard`.
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
    /** "Use" on a line's AI suggestion. */
    onUseAi: (String) -> Unit = {},
    /** "Add" on a category the AI suggests a line for. */
    onAddAi: (String) -> Unit = {},
    /** "Ask AI for a budget". */
    onAskAi: () -> Unit = {},
    /** Your plan (#76), from the Savings line; null when the plan is off, which hides the link. */
    onOpenPlan: (() -> Unit)? = null,
    /** "How you're spending": asking the AI, and its consent. */
    spending: SpendingActions = SpendingActions(),
    /** The Income screen, where expected income is added. */
    onOpenIncome: () -> Unit = {},
) {
    val dark = isSystemInDarkTheme()
    LightStatusBarIcons(dark)
    val scroll = rememberScrollState()
    // How far the big title has shrunk into the bar, as on Home.
    val collapsed = rememberTitleCollapse(scroll)
    var insightOpen by rememberSaveable { mutableStateOf(false) }
    // "Check unseen commitments" brings the Expected card's list into view.
    val commitmentsCard = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val onStep: (NextStep) -> Unit = { step ->
        when (step.action) {
            NextStepAction.INCOME -> onOpenIncome()
            NextStepAction.COMMITMENTS -> scope.launch { commitmentsCard.bringIntoView() }
            NextStepAction.PLAN -> onOpenPlan?.invoke()
        }
    }

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
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header(state, onMonth, onReviewUnfiled, collapsed)

                when {
                    // The coin loader covers a first load. Drawing the board
                    // under it would flash an empty budget with "Add a
                    // category" in it — which reads as having no budget.
                    state.loading && state.budget == null -> Unit

                    state.loadFailed -> LoadFailed(state, onRetry)

                    else -> {
                        state.learning?.let { LearningFieldCard(it, onImport = onImport) }
                        state.boardSoFar?.let { SoFarCard(it) }
                        val forecast = state.boardForecast
                        if (forecast != null) {
                            ForecastCard(forecast, state.commitments, Modifier.bringIntoViewRequester(commitmentsCard))
                        } else if (state.commitments.isNotEmpty()) {
                            BoardCard(Modifier.bringIntoViewRequester(commitmentsCard)) {
                                Text(strings(Strings.budget_commitments_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                                state.commitments.forEach { CommitmentRow(it) }
                            }
                        }
                        if (state.showsInsight) {
                            InsightCard(state.insightHeadline, state.insightDetail) { insightOpen = true }
                        }
                        if (state.showsPlan) {
                            // Hand-set lines still show while learning:
                            // manual budgeting is available before the first
                            // full month (PRD F9), and hiding someone's own
                            // figures would be a lie about what they had set.
                            Categories(state, onEdit, onAddCategory, onSetSavingsTarget, BudgetAiActions(onUseAi, onAddAi, onAskAi))
                            Extras(state, onReviewUnfiled, onAddUnbudgeted, onAddAi)
                        } else {
                            // A month that has ended is a record, not a plan:
                            // a one-off is a row in its month, not a line.
                            EndedCategories(state)
                        }
                        val steps = state.nextSteps(planAvailable = onOpenPlan != null)
                        if (steps.isNotEmpty()) {
                            NextStepsCard(BudgetBoard.nextStepsTitle(state.locale), BudgetBoard.nextStepsSubtitle(steps.size, state.locale), steps, onStep)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        // Pinned once the page has scrolled: the screen's name, centred.
        CollapsingTitleBar(strings(Strings.tab_budget), dark, collapsed)
    }

    if (insightOpen) InsightSheet(state, spending, onOpenPlan) { insightOpen = false }
}

// ── In the field ────────────────────────────────────────────────────────

/** "FINANCIAL PLANNING", the screen's name, the month, and the review bell. */
@Composable
private fun Header(state: BudgetUiState, onMonth: (String) -> Unit, onReview: () -> Unit, collapsed: () -> Float) {
    Row(
        Modifier.fillMaxWidth().fadesAsTitleCollapses(collapsed),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    BudgetBoard.eyebrow(state.locale).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.4.sp,
                    color = Color(0xFF86EFAC),
                )
                Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF86EFAC)))
            }
            Text(
                text = strings(Strings.budget_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = Field.ink(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
        }
        MonthButton(state, onMonth)
        ReviewBell(state.unfiledCount, onReview)
    }
}

/** The bell: the review queue, with a dot while anything waits there. */
@Composable
private fun ReviewBell(waiting: Int, onClick: () -> Unit) {
    val label = strings(Strings.board_review)
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Field.Glass)
            .border(1.dp, Field.GlassEdge, CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = if (waiting > 0) "$label, $waiting" else label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Notifications, contentDescription = null, tint = Field.ink(), modifier = Modifier.size(22.dp))
        if (waiting > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 11.dp, end = 12.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4ADE80))
                    .border(2.dp, Field.top(isSystemInDarkTheme()), CircleShape),
            )
        }
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
            .clip(CircleShape)
            .background(Field.Glass)
            .border(1.dp, Field.GlassEdge, CircleShape)
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
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = Field.ink(), modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = Field.ink(), maxLines = 1)
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Field.ink(0.85f), modifier = Modifier.size(18.dp))
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

// ── The AI ──────────────────────────────────────────────────────────────

/** What "How you're spending" can ask of the model. */
internal class SpendingActions(
    val onAsk: () -> Unit = {},
    val onConsentTicked: (Boolean) -> Unit = {},
    val onAgree: () -> Unit = {},
)

/**
 * The AI card opened: the month's needs, wants and savings as shares of
 * income beside what the rule aims for, the AI's advice on spending the right
 * way for that income, and the monthly review. Every figure is the server's;
 * the AI's part is words.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InsightSheet(state: BudgetUiState, actions: SpendingActions, onOpenPlan: (() -> Unit)?, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(Icons.Outlined.AutoAwesome, BoardPalette.Violet, size = 40.dp, filled = Brush.linearGradient(listOf(BoardPalette.VioletSoft, BoardPalette.Violet)))
                Text(strings(Strings.board_ai_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            }
            state.spendingCheck?.let { SpendingCheckBody(state, it, actions) }
            state.aiAdvice?.let { advice ->
                HorizontalDivider(color = Mint.edge())
                Text(strings(Strings.ai_budget_review_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Text(advice, style = MaterialTheme.typography.bodyMedium)
            }
            onOpenPlan?.let { open ->
                TextButton(
                    onClick = {
                        onDismiss()
                        open()
                    },
                    contentPadding = PaddingValues(0.dp),
                ) { Text(strings(Strings.ai_budget_see_plan), fontWeight = FontWeight.Medium) }
            }
        }
    }
}

@Composable
private fun SpendingCheckBody(state: BudgetUiState, check: SpendingCheck, actions: SpendingActions) {
    Text(SpendingCheckWords.title(state.locale), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
    Text(SpendingCheckWords.incomeLine(check, state.locale), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    state.spendingRows.forEach { ShareRow(it) }
    val policy = state.spendingPolicy
    if (policy != null) {
        Text(strings(Strings.ai_budget_consent_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        Text(strings(Strings.ai_budget_consent_body), style = MaterialTheme.typography.bodySmall)
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), tonalElevation = 2.dp) {
            Text(policy.body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = state.spendingConsentTicked, role = Role.Checkbox, onValueChange = actions.onConsentTicked),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = state.spendingConsentTicked, onCheckedChange = null)
            Text(strings(Strings.import_consent_agree), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
        }
        ErrorText(state.spendingErrorKey)
        GradientButton(
            text = strings(Strings.ai_budget_consent_allow),
            onClick = actions.onAgree,
            enabled = state.spendingConsentTicked,
            busy = state.spendingConsentBusy,
        )
        return
    }
    check.headline?.let { headline ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = BoardPalette.Violet, modifier = Modifier.size(20.dp))
            Text(headline, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
    check.tips.forEach { tip ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("•", style = MaterialTheme.typography.bodyMedium, color = BoardPalette.Violet)
            Text(tip, style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (check.hasAdvice && check.stale) {
        Text(strings(Strings.spending_stale), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    ErrorText(state.spendingErrorKey)
    if (state.checkingSpending) {
        Row(
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(strings(Strings.spending_asking), style = MaterialTheme.typography.bodyMedium)
        }
    } else if (SpendingCheckWords.canAsk(check)) {
        GradientButton(text = SpendingCheckWords.askLabel(check, state.locale), onClick = actions.onAsk)
        Text(SpendingCheckWords.runsLeft(check, state.locale), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (check.hasAdvice) {
        Text(strings(Strings.diagnostic_guidance), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One share of income: what it is, the bar with the aim marked, and how it sits. */
@Composable
private fun ShareRow(row: SpendingShareRow) {
    val tone = when (row.status) {
        SpendingStatus.OVER, SpendingStatus.SHORT -> MaterialTheme.colorScheme.error
        SpendingStatus.TO_GO -> MaterialTheme.colorScheme.onSurfaceVariant
        SpendingStatus.ON_TRACK -> Mint.greenText()
    }
    Column(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "${row.label}, ${row.amount}, ${row.share}. ${row.aim}. ${row.statusLabel}" },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("${row.amount} · ${row.share}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(6.dp)) {
            LinearProgressIndicator(
                progress = { row.fraction },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = tone,
            )
            // Where the aim sits on the bar.
            BoxWithConstraints(Modifier.fillMaxWidth().height(6.dp)) {
                Box(
                    Modifier
                        .offset(x = maxWidth * row.aimFraction - 1.dp)
                        .width(2.dp)
                        .height(6.dp)
                        .background(MaterialTheme.colorScheme.onSurface),
                )
            }
        }
        Row {
            Text(row.aim, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(row.statusLabel, style = MaterialTheme.typography.labelMedium, color = tone, fontWeight = FontWeight.Medium)
        }
    }
}

/** What the AI rows can ask of the model. */
internal class BudgetAiActions(
    val onUse: (String) -> Unit = {},
    val onAdd: (String) -> Unit = {},
    val onAsk: () -> Unit = {},
)

/** "AI suggests $430.00", with Use, under a line whose suggestion differs. */
@Composable
private fun AiHint(state: BudgetUiState, line: BudgetLine, onUse: (String) -> Unit) {
    val label = state.aiLabel(line) ?: return
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = BoardPalette.Violet, modifier = Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = BoardPalette.Violet, modifier = Modifier.weight(1f))
        TextButton(onClick = { onUse(line.categoryId) }, enabled = !state.saving, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(strings(Strings.ai_budget_use)) }
    }
}

// ── Categories ──────────────────────────────────────────────────────────

/** The categories card: the donut, every line as a row in its slice's colour, and ways to add. */
@Composable
private fun Categories(
    state: BudgetUiState,
    onEdit: (String) -> Unit,
    onAddCategory: () -> Unit,
    onSetSavingsTarget: () -> Unit,
    ai: BudgetAiActions,
) {
    BoardCard(glow = BoardPalette.Orange) {
        val donut = state.boardDonut
        if (donut != null) {
            DonutHeader(donut)
        } else {
            BoardHeader(Icons.Filled.PieChart, BoardPalette.Orange, strings(Strings.board_categories_title), null)
        }
        state.shortfallLabel?.let { Shortfall(it) }
        if (state.showsEmpty) {
            Text(strings(Strings.budget_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.lines.forEach { line -> LineRow(state, line, ai.onUse) { onEdit(line.categoryId) } }
        // Savings is a target the person sets, never a figure filled in for them.
        state.savings?.let { line -> LineRow(state, line, ai.onUse) { onEdit(line.categoryId) } }
        state.debt?.let { line -> LineRow(state, line, ai.onUse, caption = strings(Strings.budget_debt_caption)) { onEdit(line.categoryId) } }
        if (state.canSetSavingsTarget) ActionRow(Icons.Outlined.Savings, strings(Strings.budget_set_target), state.savingsRoomLabel, onSetSavingsTarget)
        ActionRow(Icons.Filled.Add, strings(Strings.budget_add_category), null, onAddCategory)
        if (state.canAskAi) ActionRow(Icons.Outlined.AutoAwesome, strings(Strings.ai_budget_open), null, ai.onAsk, accent = BoardPalette.Violet)
    }
}

/** Spending with no line, the AI's extra categories, and rows with no category yet. */
@Composable
private fun Extras(state: BudgetUiState, onReviewUnfiled: () -> Unit, onAddUnbudgeted: (String) -> Unit, onAddAi: (String) -> Unit) {
    state.unbudgetedLabel?.let { Unbudgeted(state, it, onAddUnbudgeted) }
    if (state.aiExtras.isNotEmpty()) AiExtras(state, onAddAi)
    state.unfiledLabel?.let { label ->
        BoardCard(glow = BoardPalette.color(0, isSystemInDarkTheme())) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onReviewUnfiled, contentPadding = PaddingValues(0.dp)) {
                Text(strings(Strings.budget_unfiled_action), fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** A month that has ended: the donut of what was spent, and every category at its amount. */
@Composable
private fun EndedCategories(state: BudgetUiState) {
    val dark = isSystemInDarkTheme()
    BoardCard(glow = BoardPalette.Orange) {
        val donut = state.boardDonut
        if (donut != null) {
            DonutHeader(donut)
        } else {
            BoardHeader(Icons.Filled.PieChart, BoardPalette.Orange, strings(Strings.budget_where_it_went), null)
            Text(strings(Strings.budget_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.spentRows.forEachIndexed { index, spend ->
            val icon = state.iconOf(spend)
            val color = BoardPalette.color(BudgetBoard.colorIndexOfSpend(index), dark)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Mint.card())
                    .border(1.dp, Mint.edge(), RoundedCornerShape(18.dp))
                    .padding(12.dp)
                    .semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTile(icon.vector(), icon.tint(dark))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(spend.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.size(width = 64.dp, height = 4.dp).clip(CircleShape).background(color))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(spend.amount, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(state.shareOfSpending(index), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Said in words, not in red: a shortfall is a fact, not a warning colour. */
@Composable
private fun Shortfall(label: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

// ── The sheet ───────────────────────────────────────────────────────────

/**
 * One category: its tile, its name over a bar in its slice's colour, the
 * amount and its share of the budget — and under it anything worth saying.
 */
@Composable
private fun LineRow(
    state: BudgetUiState,
    line: BudgetLine,
    onUse: (String) -> Unit,
    caption: String? = null,
    onClick: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val icon = state.iconOf(line)
    val over = state.isOver(line)
    val overLabel = state.overLabel(line)
    val editLabel = strings(Strings.budget_edit_line, state.nameOf(line))
    val shape = RoundedCornerShape(18.dp)
    val color = if (over) MaterialTheme.colorScheme.error else BoardPalette.color(state.colorIndexOf(line), dark)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Mint.card())
            .border(1.dp, if (over) MaterialTheme.colorScheme.error.copy(alpha = 0.4f) else Mint.edge(), shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                // One sentence, so the row is heard once and "over by" is heard
                // in words rather than inferred from a colour.
                .clearAndSetSemantics {
                    contentDescription = state.descriptionOf(line)
                    role = Role.Button
                    onClick(label = editLabel) {
                        onClick()
                        true
                    }
                }
                .clickable(role = Role.Button, onClickLabel = editLabel, onClick = onClick)
                .heightIn(min = 56.dp)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(icon.vector(), icon.tint(dark))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.nameOf(line),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (over) Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                }
                CategoryBar(state.fractionOf(line), color)
                Text(state.amountsOf(line), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.allocatedOf(line), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(state.allottedOf(line), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Filled.Edit, contentDescription = null, tint = Mint.greenText(), modifier = Modifier.size(14.dp))
            }
        }
        val notes = listOfNotNull(
            overLabel?.let { it to MaterialTheme.colorScheme.error },
            state.suggestionLabel(line)?.let { it to MaterialTheme.colorScheme.onSurfaceVariant },
            state.oneOffOf(line)?.let { it to MaterialTheme.colorScheme.onSurfaceVariant },
            state.thisMonthOnlyOf(line)?.let { it to Mint.greenText() },
            state.tipFor(line.categoryId)?.let { it to BoardPalette.Violet },
            caption?.let { it to MaterialTheme.colorScheme.onSurfaceVariant },
        )
        val hasAi = state.aiLabel(line) != null
        if (notes.isNotEmpty() || hasAi) {
            HorizontalDivider(color = Mint.edge())
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                notes.forEach { (text, tone) -> Text(text, style = MaterialTheme.typography.labelMedium, color = tone) }
                AiHint(state, line, onUse)
            }
        }
    }
}

/** "Add a category", "Set a savings target", "Ask AI for a budget": a dashed-looking row with a tile. */
@Composable
private fun ActionRow(icon: ImageVector, title: String, detail: String?, onClick: () -> Unit, accent: Color = Mint.greenText()) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, accent.copy(alpha = 0.35f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, accent, size = 36.dp, iconSize = 18.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = accent)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Categories the AI suggests planning for that have no line and no spending yet. */
@Composable
private fun AiExtras(state: BudgetUiState, onAdd: (String) -> Unit) {
    BoardCard(glow = BoardPalette.VioletSoft) {
        BoardHeader(Icons.Outlined.AutoAwesome, BoardPalette.Violet, strings(Strings.ai_budget_extra_title), null)
        state.aiExtras.forEach { extra ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${extra.name} · ${state.amountOf(extra)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { onAdd(extra.categoryId) }) { Text(strings(Strings.budget_unbudgeted_add), maxLines = 1) }
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
    BoardCard(glow = BoardPalette.Orange) {
        BoardHeader(Icons.Filled.WarningAmber, BoardPalette.Orange, strings(Strings.budget_unbudgeted_title), label)
        state.unbudgeted.forEach { spend ->
            val icon = state.iconOf(spend)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTile(icon.vector(), icon.tint(dark), size = 36.dp, iconSize = 18.dp)
                Column(Modifier.weight(1f)) {
                    Text(spend.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state.spentOf(spend), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.aiLabel(spend)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = BoardPalette.Violet) }
                    state.oneOffOf(spend)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    state.tipFor(spend.categoryId)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = BoardPalette.Violet) }
                }
                TextButton(onClick = { onAdd(spend.categoryId) }) {
                    Text(strings(Strings.budget_unbudgeted_add), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun LoadFailed(state: BudgetUiState, onRetry: () -> Unit) {
    BoardCard {
        Text(
            // The server's own words for this refusal when it gave any —
            // "not included in your plan" beats "something went wrong".
            text = state.errorKey?.let { strings(it) } ?: strings(Strings.error_title),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onRetry) { Text(strings(Strings.action_retry)) }
    }
}
