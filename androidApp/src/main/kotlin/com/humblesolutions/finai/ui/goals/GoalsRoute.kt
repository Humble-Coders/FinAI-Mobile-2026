package com.humblesolutions.finai.ui.goals

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.humblesolutions.finai.BuildConfig
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.ui.components.TabSkeleton
import com.humblesolutions.finai.ui.strings

/** The household's goals, with their own view model (#52, PRD F5). [onOpenProfile] is where the disclaimer is read. */
@Composable
internal fun GoalsRoute(userId: String, onClose: () -> Unit, onOpenProfile: () -> Unit = {}) {
    val model: GoalsViewModel = viewModel()
    val state by model.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(userId) { model.bind(userId, logging = BuildConfig.DEBUG) }
    BackHandler { onClose() }
    // A first load draws the page's shape; a refresh happens underneath.
    if (state.loading && state.page == null) {
        TabSkeleton(strings(Strings.tab_goals))
        return
    }

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
            onAddEmergency = model::startEmergency,
            onOpenProfile = onOpenProfile,
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
    AddToGoalSheet(
        state = state,
        actions = AddToGoalActions(onAmountChange = model::onAddAmountChange, onAdd = model::add, onCancel = model::cancelAdd),
    )
    DeleteGoalDialog(state = state, onConfirm = model::delete, onDismiss = model::cancelDelete)
}
