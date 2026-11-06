package com.humblesolutions.finai.ui.budget

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.humblesolutions.finai.BuildConfig
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.ui.aibudget.AiBudgetRoute
import com.humblesolutions.finai.ui.components.LoaderSignal
import com.humblesolutions.finai.ui.components.RetryWhenBack
import com.humblesolutions.finai.ui.components.TabSkeleton
import com.humblesolutions.finai.ui.strings

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
    /** Profile, where AI is turned on, from a notice that says it is off. */
    onOpenProfile: () -> Unit = {},
) {
    val model: BudgetViewModel = viewModel()
    val state by model.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(userId) { model.bind(userId, logging = BuildConfig.DEBUG) }
    // Kept here, above the AI budget's early return, so coming back from it
    // lands where the person left off.
    val scroll = rememberScrollState()
    // "Ask AI for a budget": the suggested budget, over this screen.
    var askingAi by rememberSaveable { mutableStateOf(false) }
    if (askingAi) {
        AiBudgetRoute(
            userId = userId,
            inSetup = false,
            onDone = {
                askingAi = false
                model.load(refresh = true)
            },
            onOpenProfile = onOpenProfile,
        )
        return
    }
    RetryWhenBack(state.spendingAiOff, model::retrySpendingAfterAi)
    // After the bind above, which would otherwise reset a first visit to the
    // current month.
    LaunchedEffect(requestedMonth) {
        requestedMonth?.let {
            model.showMonth(it)
            onRequestedMonthShown()
        }
    }
    // A first load draws the page's shape; a change of month earns the coin;
    // a refresh after an import happens underneath, with the figures left up.
    LoaderSignal(key = "budget", active = state.switchingMonth)
    BackHandler { onClose() }
    if (state.loading && state.budget == null && !state.switchingMonth) {
        TabSkeleton(strings(Strings.tab_budget))
        return
    }

    BudgetScreen(
        scroll = scroll,
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
            onOpenProfile = onOpenProfile,
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
