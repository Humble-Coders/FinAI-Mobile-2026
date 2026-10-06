package com.humblesolutions.finai.ui.budget

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.humblesolutions.finai.BuildConfig
import com.humblesolutions.finai.ui.components.LoaderSignal

/** The month's budget, with its own view model (#47, PRD F4). */
@Composable
internal fun BudgetRoute(
    userId: String,
    onClose: () -> Unit,
    onReview: () -> Unit,
    onImport: () -> Unit,
) {
    val model: BudgetViewModel = viewModel()
    val state by model.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(userId) { model.bind(userId, logging = BuildConfig.DEBUG) }
    // Only a first load earns the coin; changing month refreshes underneath.
    LoaderSignal(key = "budget", active = state.loading)
    BackHandler { onClose() }

    BudgetScreen(
        state = state,
        onMonth = model::showMonth,
        onEdit = model::edit,
        onAddCategory = model::addCategory,
        onReviewUnfiled = onReview,
        onRetry = { model.load() },
        onImport = onImport,
    )

    if (state.editing != null) {
        BudgetEditorSheet(
            state = state,
            actions = BudgetEditorActions(
                onAmountChange = model::onAmountChange,
                onSave = model::save,
                onUseSuggestion = model::useSuggestion,
                onCancel = model::cancelEdit,
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
