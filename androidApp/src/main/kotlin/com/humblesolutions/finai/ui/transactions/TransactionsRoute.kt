package com.humblesolutions.finai.ui.transactions

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.humblesolutions.finai.BuildConfig
import com.humblesolutions.finai.ui.components.LoaderSignal

/** Everything the household has, with its own view model (#F3). */
@Composable
internal fun TransactionsRoute(userId: String, onClose: () -> Unit) {
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
    )
}
