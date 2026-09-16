package com.humblesolutions.finai.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.R
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.ui.components.ErrorText
import com.humblesolutions.finai.ui.components.GradientButton
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.ui.theme.FinAiPalette
import com.humblesolutions.finai.usecase.ItemDraft
import com.humblesolutions.finai.usecase.SetupStep

/**
 * The financial setup wizard: income, then expenses and obligations, then debts
 * and investments (PRD F1, ticket #17).
 *
 * The steps can be swiped through freely; Continue is what waits for the
 * mandatory figures. Continue slides straight to the next step and saves behind
 * the move, with a small loader only while a request is in flight — the coin
 * loader is for the first load alone, and lives at the app's root.
 */
@Composable
fun SetupScreen(
    state: SetupUiState,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    onResume: () -> Unit,
    onIncomeChange: (String) -> Unit,
    onExpenseChange: (String) -> Unit,
    onOpenList: (ItemList) -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    onGoTo: (SetupStep) -> Unit,
    onRowChange: (Int, ItemDraft) -> Unit,
    onAddRow: () -> Unit,
    onRemoveRow: (Int) -> Unit,
    onKeepRows: () -> Unit,
    onDiscardRows: () -> Unit,
) {
    val ground = MaterialTheme.colorScheme.background

    // The app's coin loader covers the first load; underneath it, just ground.
    if (state.loading) {
        Box(Modifier.fillMaxSize().background(ground))
        return
    }
    if (state.cancelled) {
        SetupRequiredScreen(onResume)
        return
    }
    if (state.editing != null) {
        BackHandler { onDiscardRows() }
        ItemListScreen(
            state = state,
            list = state.editing,
            onRowChange = onRowChange,
            onAddRow = onAddRow,
            onRemoveRow = onRemoveRow,
            onKeep = onKeepRows,
            onDiscard = onDiscardRows,
        )
        return
    }

    // Back steps back; on the first step it is Cancel, never a way out of the gate.
    BackHandler { if (state.step == SetupStep.INCOME) onCancel() else onBack() }

    val focus = LocalFocusManager.current
    val pager = rememberPagerState(initialPage = state.step.ordinal) { SetupStep.COUNT }
    // The two follow each other: a swipe moves the wizard, and Continue moves
    // the pager, so the page on screen and the step being gated never disagree.
    LaunchedEffect(state.step) {
        if (pager.currentPage != state.step.ordinal) {
            pager.animateScrollToPage(state.step.ordinal, animationSpec = tween(SLIDE_MS))
        }
    }
    LaunchedEffect(pager.settledPage) { onGoTo(SetupStep.entries[pager.settledPage]) }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(Modifier.fillMaxSize().background(ground)) {
        // Tracks the drag itself, so the path runs on as the steps slide.
        PathBand(offset = pager.currentPage + pager.currentPageOffsetFraction, top = statusTop)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Spacer(Modifier.height(statusTop + BandHeight))

            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f),
                userScrollEnabled = !state.busy,
                // The neighbours stay composed, so a swipe never waits on them.
                beyondViewportPageCount = 1,
            ) { page ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (SetupStep.entries[page]) {
                        SetupStep.INCOME -> IncomeStep(onIncomeChange, state)
                        SetupStep.EXPENSES -> ExpensesStep(state, onExpenseChange, onOpenList)
                        SetupStep.PORTFOLIO -> PortfolioStep(state, onOpenList)
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ErrorText(state.errorKey)
                // The same rule the button reads, said out loud.
                if (state.errorKey == null) ErrorText(state.notice?.messageKey)
                GradientButton(
                    text = strings(
                        if (state.step.isLast) Strings.setup_complete else Strings.action_continue,
                    ),
                    onClick = {
                        focus.clearFocus()
                        onContinue()
                    },
                    enabled = state.canContinue,
                )
                if (state.showsSkip) {
                    TextButton(
                        onClick = onSkip,
                        enabled = state.canSkip,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text(strings(Strings.setup_skip))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        // Below the notch, over the faded top of the art.
        StepHeader(
            step = state.step,
            onBack = onBack,
            onCancel = onCancel,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = statusTop),
        )

        SavingIndicator(
            visible = state.showsSaving,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = statusTop + HeaderHeight + 4.dp),
        )
    }
}

/** How far the art reaches below the notch: to the top of the fields, as in the design. */
private val BandHeight = 300.dp
private val HeaderHeight = 56.dp

/** The top fade runs from the notch through the header, so both sit on plain ground. */
private val TopFade = 72.dp
private val BottomFade = 72.dp
private const val SLIDE_MS = 380

@Composable
private fun StepHeader(
    step: SetupStep,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().height(HeaderHeight).padding(horizontal = 8.dp)) {
        if (step != SetupStep.INCOME) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = strings(Strings.action_back),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(SetupStep.COUNT) { index ->
                val here = index == step.ordinal
                Box(
                    Modifier
                        .size(width = if (here) 20.dp else 8.dp, height = 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (index <= step.ordinal) FinAiPalette.Green
                            else MaterialTheme.colorScheme.outline,
                        ),
                )
            }
            Text(
                text = strings(
                    Strings.setup_step_counter,
                    step.number.toString(),
                    SetupStep.COUNT.toString(),
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterEnd)) {
            Text(
                text = strings(Strings.action_cancel),
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * The path, running on from step to step.
 *
 * One wide illustration, each step showing its own third, starting below the
 * notch. The notch area is plain ground fading into the art, and the art fades
 * back into ground above the fields. [offset] is the pager's position rather
 * than the settled page, so the path slides with the finger.
 */
@Composable
private fun PathBand(offset: Float, top: Dp) {
    val ground = MaterialTheme.colorScheme.background
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(top + BandHeight)
            .clipToBounds(),
    ) {
        val screen = maxWidth
        Image(
            painter = painterResource(R.drawable.setup_path),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .padding(top = top)
                // Three screens wide, anchored at the left. `width` alone would
                // be clamped to the screen by the incoming constraints.
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .requiredWidth(screen * SetupStep.COUNT)
                .height(BandHeight)
                .offset(x = -screen * offset),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(top + TopFade)
                .background(
                    Brush.verticalGradient(
                        0f to ground,
                        (top / (top + TopFade)) to ground.copy(alpha = 0.85f),
                        1f to ground.copy(alpha = 0f),
                    ),
                ),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(BottomFade)
                .background(Brush.verticalGradient(listOf(ground.copy(alpha = 0f), ground))),
        )
    }
}

/** The small loader for a save behind the steps: a frosted pill, never the coin. */
@Composable
private fun SavingIndicator(visible: Boolean, modifier: Modifier = Modifier) {
    val label = strings(Strings.setup_saving)
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(180)),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            shadowElevation = 3.dp,
            modifier = Modifier.semantics { contentDescription = label },
        ) {
            CircularProgressIndicator(
                modifier = Modifier.padding(7.dp).size(14.dp),
                strokeWidth = 2.dp,
                color = FinAiPalette.Green,
            )
        }
    }
}

/** What Cancel leads to: setup is required, and the way back to step 1. */
@Composable
private fun SetupRequiredScreen(onResume: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = strings(Strings.setup_required_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = strings(Strings.setup_required_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onResume) {
                Text(
                    text = strings(Strings.setup_required_action),
                    color = FinAiPalette.Green,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = TextDecoration.Underline,
                )
            }
        }
    }
}

@Composable
private fun StepTitle(step: SetupStep, titleKey: String, bodyKey: String) {
    Text(
        text = strings(Strings.setup_step_label, step.number.toString()).uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = FinAiPalette.Green,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(4.dp))
    Text(strings(titleKey), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text(
        text = strings(bodyKey),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun IncomeStep(onIncomeChange: (String) -> Unit, state: SetupUiState) {
    StepTitle(SetupStep.INCOME, Strings.setup_income_title, Strings.setup_income_body)
    Card {
        AmountField(
            value = state.draft.income,
            onValueChange = onIncomeChange,
            label = strings(Strings.setup_income_label),
            symbol = state.symbol,
            imeAction = ImeAction.Done,
        )
    }
    Text(
        text = strings(Strings.setup_income_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ExpensesStep(
    state: SetupUiState,
    onExpenseChange: (String) -> Unit,
    onOpenList: (ItemList) -> Unit,
) {
    StepTitle(SetupStep.EXPENSES, Strings.setup_expenses_title, Strings.setup_expenses_body)
    Card {
        AmountField(
            value = state.draft.monthlyExpense,
            onValueChange = onExpenseChange,
            label = strings(Strings.setup_expense_label),
            symbol = state.symbol,
            imeAction = ImeAction.Done,
        )
    }
    ListRow(
        label = strings(Strings.setup_obligations_label),
        subtitle = state.totalOf(ItemList.OBLIGATIONS) ?: strings(Strings.setup_amount_hint),
        onClick = { onOpenList(ItemList.OBLIGATIONS) },
    )
}

@Composable
private fun PortfolioStep(state: SetupUiState, onOpenList: (ItemList) -> Unit) {
    StepTitle(SetupStep.PORTFOLIO, Strings.setup_portfolio_title, Strings.setup_portfolio_body)
    ListRow(
        label = strings(Strings.setup_debts_label),
        subtitle = state.totalOf(ItemList.DEBTS) ?: strings(Strings.setup_total_balance_hint),
        onClick = { onOpenList(ItemList.DEBTS) },
    )
    ListRow(
        label = strings(Strings.setup_investments_label),
        subtitle = state.totalOf(ItemList.INVESTMENTS) ?: strings(Strings.setup_total_amount_hint),
        onClick = { onOpenList(ItemList.INVESTMENTS) },
    )
}

/** A row that opens an itemised list, showing what is in it. */
@Composable
private fun ListRow(label: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The itemised list behind a row: add rows, drop rows, keep them. */
@Composable
private fun ItemListScreen(
    state: SetupUiState,
    list: ItemList,
    onRowChange: (Int, ItemDraft) -> Unit,
    onAddRow: () -> Unit,
    onRemoveRow: (Int) -> Unit,
    onKeep: () -> Unit,
    onDiscard: () -> Unit,
) {
    val debts = list == ItemList.DEBTS
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDiscard) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = strings(Strings.action_back),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = strings(
                    when (list) {
                        ItemList.OBLIGATIONS -> Strings.setup_obligations_label
                        ItemList.DEBTS -> Strings.setup_debts_label
                        ItemList.INVESTMENTS -> Strings.setup_investments_label
                    },
                ),
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        state.rows.forEachIndexed { index, row ->
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WizardField(
                        value = row.name,
                        onValueChange = { onRowChange(index, row.copy(name = it)) },
                        label = strings(Strings.setup_item_name),
                    )
                    AmountField(
                        value = row.amount,
                        onValueChange = { onRowChange(index, row.copy(amount = it)) },
                        label = strings(
                            if (debts) Strings.setup_debt_balance else Strings.setup_item_amount,
                        ),
                        symbol = state.symbol,
                    )
                    if (debts) {
                        AmountField(
                            value = row.minimumPayment,
                            onValueChange = { onRowChange(index, row.copy(minimumPayment = it)) },
                            label = strings(Strings.setup_debt_minimum),
                            symbol = state.symbol,
                        )
                        WizardField(
                            value = row.interestRatePercent,
                            onValueChange = { onRowChange(index, row.copy(interestRatePercent = it)) },
                            label = strings(Strings.setup_debt_rate),
                            keyboardType = KeyboardType.Decimal,
                        )
                    }
                    state.rowBlock(index)?.let { ErrorText(it.messageKey) }
                    TextButton(onClick = { onRemoveRow(index) }) {
                        Text(strings(Strings.setup_remove_item))
                    }
                }
            }
        }

        TextButton(onClick = onAddRow) { Text(strings(Strings.setup_add_item)) }
        GradientButton(
            text = strings(Strings.action_save),
            onClick = onKeep,
            enabled = state.canKeepRows,
        )
        TextButton(
            onClick = onDiscard,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(strings(Strings.action_cancel))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Box(Modifier.padding(16.dp)) { content() }
    }
}

/** An amount, with the currency the server named beside it — never a hardcoded symbol. */
@Composable
private fun AmountField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    symbol: String,
    imeAction: ImeAction = ImeAction.Next,
) {
    WizardField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        keyboardType = KeyboardType.Decimal,
        imeAction = imeAction,
        leading = {
            Text(
                text = symbol,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun WizardField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    leading: (@Composable () -> Unit)? = null,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        leadingIcon = leading,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}
