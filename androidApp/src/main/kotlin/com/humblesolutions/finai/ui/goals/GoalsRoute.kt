package com.humblesolutions.finai.ui.goals

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.humblesolutions.finai.BuildConfig
import com.humblesolutions.finai.ui.components.LoaderSignal

/** The household's goals, with their own view model (#52, PRD F5). */
@Composable
internal fun GoalsRoute(userId: String, onClose: () -> Unit) {
    val model: GoalsViewModel = viewModel()
    val state by model.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(userId) { model.bind(userId, logging = BuildConfig.DEBUG) }
    // Only a first load earns the coin; a refresh happens underneath.
    LoaderSignal(key = "goals", active = state.loading)
    BackHandler { onClose() }

    GoalsScreen(
        state = state,
        actions = GoalsActions(
            onNew = model::startNew,
            onEdit = model::edit,
            onAddMoney = model::startAdd,
            onToggleReorder = model::toggleReordering,
            onMoveUp = model::moveUp,
            onMoveDown = model::moveDown,
            onRetry = { model.load() },
        ),
    )

    GoalEditorSheet(
        state = state,
        actions = GoalEditorActions(
            onDraftChange = model::onDraftChange,
            onSave = model::save,
            onCancel = model::cancelEdit,
            onDelete = { state.editingId?.let(model::askDelete) },
        ),
    )
    AddMoneySheet(
        state = state,
        actions = AddMoneyActions(onAmountChange = model::onAddAmountChange, onAdd = model::add, onCancel = model::cancelAdd),
    )
    DeleteGoalDialog(state = state, onConfirm = model::delete, onDismiss = model::cancelDelete)
}
