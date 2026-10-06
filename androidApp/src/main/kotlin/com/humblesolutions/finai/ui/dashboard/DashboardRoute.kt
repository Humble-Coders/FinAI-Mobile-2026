package com.humblesolutions.finai.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.humblesolutions.finai.BuildConfig
import com.humblesolutions.finai.ui.components.LoaderSignal
import com.humblesolutions.finai.usecase.MoneyKind

/**
 * The dashboard with its own view model (PRD F3).
 *
 * This is home: the first screen a signed-in, set-up person meets, and the way
 * to everything else.
 */
@Composable
internal fun DashboardRoute(
    userId: String,
    onImportStatement: () -> Unit,
    onAddTransaction: () -> Unit,
    onReview: () -> Unit,
    onViewAll: () -> Unit,
    onSignOut: () -> Unit,
    onOpenMoney: (MoneyKind) -> Unit = {},
    /** Null when there is no Budget tab, which hides "See budget". */
    onOpenBudget: (() -> Unit)? = null,
) {
    val model: DashboardViewModel = viewModel()
    val state by model.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(userId) { model.bind(userId, logging = BuildConfig.DEBUG) }
    // Only a first load earns the coin. Stepping between months refreshes, and
    // the figures already on screen stay up while the next ones arrive.
    LoaderSignal(key = "dashboard", active = state.loading)

    DashboardScreen(
        state = state,
        onToggleAmounts = model::toggleAmounts,
        onPreviousMonth = model::showPreviousMonth,
        onNextMonth = model::showNextMonth,
        onRetry = { model.load() },
        onImportStatement = onImportStatement,
        onAddTransaction = onAddTransaction,
        onReview = onReview,
        onViewAll = onViewAll,
        onSignOut = onSignOut,
        onOpenMoney = onOpenMoney,
        commitments = CommitmentActions(
            onEdit = model::editCommitment,
            onAdd = model::addCommitment,
            onName = model::onCommitmentName,
            onAmount = model::onCommitmentAmount,
            onSave = model::saveCommitment,
            onCancel = model::cancelCommitment,
            onAskDelete = model::askDeleteCommitment,
            onDelete = model::deleteCommitment,
            onKeep = model::keepCommitment,
        ),
        insights = InsightActions(
            onOpenBreakdown = model::openBreakdown,
            onCloseBreakdown = model::closeBreakdown,
            onRetryBreakdown = model::retryBreakdown,
            onToggleSpending = model::toggleSpending,
            onOpenBudget = onOpenBudget,
        ),
    )
}
