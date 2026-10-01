package com.humblesolutions.finai.ui.review

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.humblesolutions.finai.BuildConfig
import com.humblesolutions.finai.ui.components.LoaderSignal

/**
 * The review queue with its own view model, which holds a correction in
 * progress across rotation and — through its saved state — the process being
 * killed (#32).
 */
@Composable
internal fun ReviewRoute(userId: String, onClose: () -> Unit) {
    val model: ReviewViewModel = viewModel()
    val state by model.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(userId) { model.bind(userId, logging = BuildConfig.DEBUG) }
    LoaderSignal(key = "review", active = state.loading)

    // Leaving still owes the server any delete the undo window was holding.
    DisposableEffect(Unit) { onDispose { model.flushPendingDelete() } }

    val close = {
        model.flushPendingDelete()
        onClose()
    }
    BackHandler { if (state.editing != null) model.cancelEdit() else close() }

    ReviewScreen(
        state = state,
        actions = ReviewActions(
            onClose = close,
            onRetry = model::load,
            onLoadMore = model::loadMore,
            onConfirmAll = model::confirmAll,
            onConfirm = model::confirm,
            onEdit = model::edit,
            onDelete = model::delete,
            onUndoDelete = model::undoDelete,
            onDismissAnnouncements = model::dismissAnnouncements,
            onCancelEdit = model::cancelEdit,
            onDateChange = model::onDateChange,
            onAmountChange = model::onAmountChange,
            onDirectionChange = model::onDirectionChange,
            onDescriptionChange = model::onDescriptionChange,
            onCategoryChosen = model::onCategoryChosen,
            onSaveCorrection = model::saveCorrection,
            onOpenNewCategory = model::openNewCategory,
            onNewCategoryName = model::onNewCategoryName,
            onCreateCategory = model::createCategory,
            onCancelNewCategory = model::cancelNewCategory,
        ),
    )
}
