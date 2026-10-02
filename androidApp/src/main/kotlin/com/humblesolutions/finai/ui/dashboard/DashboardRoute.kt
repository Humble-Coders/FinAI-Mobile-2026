package com.humblesolutions.finai.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.humblesolutions.finai.BuildConfig
import com.humblesolutions.finai.ui.components.LoaderSignal

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
    onSignOut: () -> Unit,
) {
    val model: DashboardViewModel = viewModel()
    val state by model.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(userId) { model.bind(userId, logging = BuildConfig.DEBUG) }
    // Only a first load earns the coin. Stepping between months refreshes, and
    // the figures already on screen stay up while the next ones arrive.
    LoaderSignal(key = "dashboard", active = state.loading)

    DashboardScreen(
        state = state,
        onPreviousMonth = model::showPreviousMonth,
        onNextMonth = model::showNextMonth,
        onRetry = { model.load() },
        onImportStatement = onImportStatement,
        onAddTransaction = onAddTransaction,
        onReview = onReview,
        onSignOut = onSignOut,
    )
}
