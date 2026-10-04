package com.humblesolutions.finai.ui.transactions

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.humblesolutions.finai.BuildConfig
import com.humblesolutions.finai.ui.components.LoaderSignal
import com.humblesolutions.finai.ui.edit.TransactionEditorActions

/** Everything the household has, with its own view model (#F3). */
@Composable
internal fun TransactionsRoute(userId: String, onClose: () -> Unit, showsBack: Boolean = true) {
    val model: TransactionsViewModel = viewModel()
    val state by model.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(userId) { model.bind(userId, logging = BuildConfig.DEBUG) }
    // Only a first load earns the coin; changing slice refreshes underneath.
    LoaderSignal(key = "transactions", active = state.loading)
    BackHandler { onClose() }

    TransactionsScreen(
        state = state,
        onMode = model::showMode,
        onStatement = model::showStatement,
        onMonth = model::showMonth,
        onLoadMore = model::loadMore,
        onRetry = { model.load() },
        onClose = onClose,
        showsBack = showsBack,
        onEdit = model::edit,
        editor = TransactionEditorActions(
            onCancel = model::cancelEdit,
            onDateChange = model::onDateChange,
            onAmountChange = model::onAmountChange,
            onDirectionChange = model::onDirectionChange,
            onDescriptionChange = model::onDescriptionChange,
            onCategoryChosen = model::onCategoryChosen,
            onSave = model::saveEdit,
            onOpenNewCategory = model::openNewCategory,
            onNewCategoryName = model::onNewCategoryName,
            onCreateCategory = model::createCategory,
            onCancelNewCategory = model::cancelNewCategory,
            onDelete = model::deleteEditing,
        ),
    )
}
