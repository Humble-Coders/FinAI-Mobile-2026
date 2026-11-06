package com.humblesolutions.finai.ui.profile

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
import com.humblesolutions.finai.ui.onboarding.OnboardingUiState
import com.humblesolutions.finai.ui.onboarding.RegionScreen
import com.humblesolutions.finai.ui.setup.SetupScreen
import com.humblesolutions.finai.ui.setup.SetupViewModel
import com.humblesolutions.finai.util.BudgetChanged
import com.humblesolutions.finai.util.GoalsChanged
import com.humblesolutions.finai.util.LedgerChanged
import com.humblesolutions.finai.util.PlanChanged
import kotlinx.coroutines.delay

/**
 * Profile with its own view model (PM decision, 2026-11-06), opened over the
 * bar from Home's monogram and from every "Open Profile".
 *
 * [currency] is the household's, from capabilities. [onSignOut] is the app's
 * own sign-out. [onAccountChanged] runs after the name or region changed, so
 * the router re-reads what it shows from them — Home's monogram, the currency.
 */
@Composable
internal fun ProfileRoute(
    userId: String,
    currency: String,
    onClose: () -> Unit,
    onSignOut: () -> Unit,
    onAccountChanged: () -> Unit,
) {
    val model: ProfileViewModel = viewModel()
    val state by model.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(userId) { model.bind(userId, logging = BuildConfig.DEBUG) }
    // Said once, then gone.
    LaunchedEffect(state.noticeKey) {
        if (state.noticeKey != null) {
            delay(NOTICE_MS)
            model.clearNotice()
        }
    }

    // Both open over Profile and give it back; saveable, so a rotation keeps them.
    var pickingRegion by rememberSaveable { mutableStateOf(false) }
    var editingSetup by rememberSaveable { mutableStateOf(false) }

    if (editingSetup) {
        SetupEditRoute(userId) { changed ->
            editingSetup = false
            if (changed) {
                // Income, commitments, debts and investments feed Home, the
                // budget, the plan and goals: every one of them re-reads.
                LedgerChanged.announce()
                BudgetChanged.announce()
                GoalsChanged.announce()
                PlanChanged.announce()
                model.setupUpdated()
            }
        }
        return
    }

    if (pickingRegion) {
        val leave = {
            pickingRegion = false
            model.clearRegionError()
        }
        BackHandler(onBack = leave)
        // The onboarding picker, as it is: it reads only the error and the busy flag.
        RegionScreen(OnboardingUiState(errorKey = state.regionErrorKey, busy = state.regionBusy)) { region ->
            model.changeRegion(region) {
                pickingRegion = false
                onAccountChanged()
            }
        }
        return
    }

    LoaderSignal(key = "profile", active = state.loading || state.regionBusy)
    BackHandler(onBack = onClose)

    ProfileScreen(
        state = state,
        currency = currency,
        actions = ProfileActions(
            onBack = onClose,
            onRetry = model::load,
            onEditName = model::openName,
            onNameChange = model::onNameChange,
            onSaveName = { model.saveName(onAccountChanged) },
            onCloseName = model::closeName,
            onChangeRegion = { pickingRegion = true },
            onEditSetup = { editingSetup = true },
            onRead = model::read,
            onCloseReading = model::closeReading,
            onTurnAiOn = model::openAiOn,
            onAiTicked = model::onAiTicked,
            onAgreeToAi = model::agreeToAi,
            onCloseAiOn = model::closeAiOn,
            onTurnAiOff = model::askAiOff,
            onConfirmAiOff = model::turnAiOff,
            onKeepAiOn = model::keepAiOn,
            onSignOut = onSignOut,
            onDelete = model::askDelete,
            onDeleteTyped = model::onDeleteTyped,
            onConfirmDelete = model::deleteAccount,
            onCancelDelete = model::cancelDelete,
        ),
    )
}

private const val NOTICE_MS = 4_000L

/**
 * The setup wizard over answers already given — Profile's "Edit your setup
 * answers". Its own instance of the wizard's view model, apart from the one
 * onboarding used, and opened fresh from the first step each time.
 *
 * No suggested-budget step at the end: Save, Cancel and back all return to
 * Profile, with [onDone] told whether anything the server holds changed —
 * each step saves as it goes, so even Cancel can have.
 */
@Composable
private fun SetupEditRoute(userId: String, onDone: (changed: Boolean) -> Unit) {
    val model: SetupViewModel = viewModel(key = "setup-edit")
    val state by model.uiState.collectAsStateWithLifecycle()
    // Once per opening, not per rotation.
    var started by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(userId) {
        model.bind(userId, logging = BuildConfig.DEBUG)
        if (!started) {
            model.startEditing()
            started = true
        }
    }
    LoaderSignal(key = "setup-edit", active = state.loading)
    val leave = { onDone(model.changedWhileEditing) }

    SetupScreen(
        state = state,
        onBack = model::back,
        onCancel = leave,
        onResume = model::resume,
        onIncomeChange = model::onIncomeChange,
        onExpenseChange = model::onExpenseChange,
        onOpenList = model::openList,
        onContinue = { model.continueStep { onDone(true) } },
        onSkip = {},
        onGoTo = model::goTo,
        onRowChange = model::onRowChange,
        onAddRow = model::addRow,
        onRemoveRow = model::removeRow,
        onKeepRows = model::keepRows,
        onDiscardRows = model::discardRows,
        editingAnswers = true,
    )
}
