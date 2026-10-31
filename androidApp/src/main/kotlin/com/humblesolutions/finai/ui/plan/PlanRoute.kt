package com.humblesolutions.finai.ui.plan

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
import com.humblesolutions.finai.ui.components.LoaderSignal

/**
 * Your plan, and Your debts behind it, with one view model (#76).
 *
 * [startOnDebts] opens Your debts first — from Home's Debts screen — and Back
 * from there closes the route rather than showing a plan the person did not
 * come for. From Your plan, Back on Your debts returns to the plan.
 * [onOpenGoals] is the emergency fund suggestion's way to the Goals tab; null
 * where there is no Goals tab, and the suggestion then has no button.
 */
@Composable
internal fun PlanRoute(userId: String, startOnDebts: Boolean, onClose: () -> Unit, onOpenGoals: (() -> Unit)? = null) {
    val model: PlanViewModel = viewModel()
    val state by model.uiState.collectAsStateWithLifecycle()
    // The first page once; a restored process keeps the page it was on.
    var opened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!opened) {
            model.showPage(if (startOnDebts) PlanPage.DEBTS else PlanPage.PLAN)
            opened = true
        }
    }
    LaunchedEffect(userId) { model.bind(userId, logging = BuildConfig.DEBUG) }
    LoaderSignal(key = "plan", active = state.loading)

    // A diagnostic, over the plan; back returns to it.
    var diagnostic by rememberSaveable { mutableStateOf<String?>(null) }
    diagnostic?.let { kind ->
        DiagnosticRoute(userId = userId, kind = kind) {
            diagnostic = null
            // A diagnostic can change the plan's choices ("Pay debt before goals").
            model.load(refresh = true)
        }
        return
    }

    val back = {
        if (state.page == PlanPage.DEBTS && !startOnDebts) model.showPage(PlanPage.PLAN) else onClose()
    }
    BackHandler(onBack = back)

    val actions = PlanActions(
        onBack = back,
        onRetry = { model.load() },
        onChangeChoices = model::startChoices,
        onOpenDebts = { model.showPage(PlanPage.DEBTS) },
        onStrategy = model::chooseStrategy,
        onEditDebt = model::editDebt,
        onUpdateBalance = model::startBalance,
        onRunDiagnostic = { diagnostic = it },
        onEditRow = model::startRowEdit,
        onOpenGoals = onOpenGoals,
    )
    when (state.page) {
        PlanPage.PLAN -> PlanScreen(state, actions)
        PlanPage.DEBTS -> DebtsScreen(state, actions)
    }

    RowEditSheet(
        state = state,
        actions = RowEditActions(
            onAmountChange = model::onRowAmount,
            onSave = model::requestRowSave,
            onCancel = model::cancelRowEdit,
            onApply = model::saveRow,
            onDismissApply = model::dismissApply,
        ),
    )
    ChoicesSheet(
        state = state,
        actions = ChoicesActions(
            onMove = model::moveChoice,
            onStep = model::stepChoice,
            onPercent = model::onChoicePercent,
            onSave = model::saveChoices,
            onCancel = model::cancelChoices,
        ),
    )
    DebtEditorSheet(
        state = state,
        actions = DebtEditorActions(onDraftChange = model::onDebtDraft, onSave = model::saveDebt, onCancel = model::cancelDebt),
    )
    BalanceSheet(
        state = state,
        actions = BalanceActions(onAmountChange = model::onBalanceAmount, onSave = model::saveBalance, onCancel = model::cancelBalance),
    )
}
