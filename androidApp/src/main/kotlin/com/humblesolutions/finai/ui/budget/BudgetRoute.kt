package com.humblesolutions.finai.ui.budget

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.humblesolutions.finai.BuildConfig
import com.humblesolutions.finai.ui.aibudget.AiBudgetRoute
import com.humblesolutions.finai.ui.components.LoaderSignal

/** The month's budget, with its own view model (#47, PRD F4). */
@Composable
internal fun BudgetRoute(
    userId: String,
    onClose: () -> Unit,
    onReview: () -> Unit,
    onImport: () -> Unit,
    /** A month (`YYYY-MM`) to open on — Home's "See budget" — or null for wherever it was. */
    requestedMonth: String? = null,
    /** Called once [requestedMonth] is shown, so a later visit opens where the person left it. */
    onRequestedMonthShown: () -> Unit = {},
    /** Your plan (#76); null when it is off. */
    onOpenPlan: (() -> Unit)? = null,
    /** The Income screen, from "Add any expected income". */
    onOpenIncome: () -> Unit = {},
) {
    val model: BudgetViewModel = viewModel()
    val state by model.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(userId) { model.bind(userId, logging = BuildConfig.DEBUG) }
    // "Ask AI for a budget": the suggested budget, over this screen.
    var askingAi by rememberSaveable { mutableStateOf(false) }
    if (askingAi) {
        AiBudgetRoute(userId = userId, inSetup = false) {
            askingAi = false
            model.load(refresh = true)
        }
        return
    }
    // After the bind above, which would otherwise reset a first visit to the
    // current month.
    LaunchedEffect(requestedMonth) {
        requestedMonth?.let {
            model.showMonth(it)
            onRequestedMonthShown()
        }
    }
    // A first load and a change of month earn the coin; a refresh after an
    // import happens underneath, with the figures left up.
    LoaderSignal(key = "budget", active = state.loading || state.switchingMonth)
    BackHandler { onClose() }

    BudgetScreen(
        state = state,
        onMonth = model::showMonth,
        onEdit = model::edit,
        onAddCategory = model::addCategory,
        onAddUnbudgeted = model::addFromUnbudgeted,
        onSetSavingsTarget = model::setSavingsTarget,
        onUseAi = model::useAiSuggestion,
        onAddAi = model::addFromAi,
        onAskAi = { askingAi = true },
        onReviewUnfiled = onReview,
        onRetry = { model.load() },
        onImport = onImport,
        onOpenPlan = onOpenPlan,
        onOpenIncome = onOpenIncome,
        tick = TickActions(
            onOpen = model::openTick,
            onClose = model::closeTick,
            onMatch = model::tickWith,
            onAmount = model::onPayAmount,
            onDate = model::onPayDate,
            onAccount = model::onPayAccount,
            onAdd = { model.addPayment() },
            onUseDuplicate = model::useDuplicate,
            onUntick = model::untick,
        ),
        spending = SpendingActions(
            onAsk = model::askSpending,
            onConsentTicked = model::onSpendingConsentTicked,
            onAgree = model::agreeSpending,
        ),
    )

    if (state.editing != null) {
        BudgetEditorSheet(
            state = state,
            actions = BudgetEditorActions(
                onAmountChange = model::onAmountChange,
                onSave = { model.save() },
                onUseSuggestion = model::useSuggestion,
                onCancel = model::cancelEdit,
                onSaveThisMonth = { model.save(thisMonthOnly = true) },
            ),
        )
    }

    if (state.picking) {
        BudgetCategorySheet(
            state = state,
            onChosen = model::pickCategory,
            onDismiss = model::cancelPicking,
        )
    }
}
