package com.humblesolutions.finai.ui.money

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
import com.humblesolutions.finai.model.TransactionDirection
import com.humblesolutions.finai.ui.components.LoaderSignal
import com.humblesolutions.finai.ui.manualentry.ManualEntryActions
import com.humblesolutions.finai.ui.manualentry.ManualEntryViewModel
import com.humblesolutions.finai.usecase.MoneyDetail
import com.humblesolutions.finai.usecase.MoneyKind

/**
 * One money screen with its own model, and the quick entry it opens.
 *
 * The entry uses the manual entry screen's own model — under its own key, so a
 * draft typed here and one typed on that screen never meet.
 */
@Composable
internal fun MoneyDetailRoute(userId: String, kind: MoneyKind, onBack: () -> Unit) {
    val model: MoneyDetailViewModel = viewModel(key = "money-$kind")
    val state by model.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(userId, kind) { model.bind(userId, kind, logging = BuildConfig.DEBUG) }
    LoaderSignal(key = "money_$kind", active = state.loading)

    var adding by rememberSaveable { mutableStateOf(false) }
    var markObligation by rememberSaveable { mutableStateOf(false) }
    val entry: ManualEntryViewModel = viewModel(key = "money-entry")
    val entryState by entry.uiState.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)

    MoneyDetailScreen(
        state = state,
        actions = MoneyDetailActions(
            onBack = onBack,
            onRetry = { model.load() },
            onMonth = model::showMonth,
            onTab = model::showTab,
            onOpenSearch = model::openSearch,
            onCloseSearch = model::closeSearch,
            onQuery = model::onQuery,
            onLoadMore = model::loadMore,
            onAdd = {
                entry.discard()
                markObligation = false
                entry.bind(userId, logging = BuildConfig.DEBUG)
                adding = true
            },
            onOpenObligation = model::openObligation,
            onObligationChange = model::onObligationChange,
            onSaveObligation = model::saveObligation,
            onCancelObligation = model::cancelObligation,
            onDismissNotice = model::dismissNotice,
        ),
    )

    if (!adding) return

    // The screen's own kind of money, shown chosen and one tap to change: on
    // Expenses an entry is an expense unless said otherwise. Debts and
    // Investments also file it under their category.
    LaunchedEffect(entryState.loading) {
        if (entryState.loading) return@LaunchedEffect
        if (entryState.draft.direction == null) {
            entry.onDirectionChosen(if (kind == MoneyKind.INCOME) TransactionDirection.CREDIT else TransactionDirection.DEBIT)
        }
        val slug = when (kind) {
            MoneyKind.INVESTMENTS -> MoneyDetail.SAVINGS_SLUG
            MoneyKind.DEBTS -> MoneyDetail.DEBT_PAYMENT_SLUG
            else -> null
        }
        if (slug != null && entryState.draft.categoryId == null) {
            entryState.categories.firstOrNull { it.slug == slug }?.let { entry.onCategoryChosen(it.id) }
        }
    }

    // Saved: close, and add the obligation when asked — a second write, after
    // the entry is safely in.
    LaunchedEffect(entryState.saved) {
        if (entryState.saved == null) return@LaunchedEffect
        val draft = entryState.draft
        if (markObligation && draft.direction == TransactionDirection.DEBIT) {
            model.markAsObligation(draft.description.trim(), draft.amount, draft.occurredOn?.day)
        }
        entry.discard()
        adding = false
    }

    AddMoneySheet(
        state = entryState,
        investment = kind == MoneyKind.INVESTMENTS,
        markObligation = markObligation,
        onMarkObligation = { markObligation = it },
        actions = ManualEntryActions(
            onClose = {
                entry.discard()
                adding = false
            },
            onRetry = entry::load,
            onAccountChosen = entry::onAccountChosen,
            onDateChosen = entry::onDateChosen,
            onToday = entry::onToday,
            onRefreshToday = entry::refreshToday,
            onAmountChange = entry::onAmountChange,
            onDirectionChosen = entry::onDirectionChosen,
            onDescriptionChange = entry::onDescriptionChange,
            onCategoryChosen = entry::onCategoryChosen,
            onSave = entry::save,
            onKeepDuplicate = entry::keepDuplicate,
            onDismissDuplicate = entry::dismissDuplicate,
            onOpenNewAccount = entry::openNewAccount,
            onNewAccountName = entry::onNewAccountName,
            onNewAccountKind = entry::onNewAccountKind,
            onCreateAccount = entry::createAccount,
            onCancelNewAccount = entry::cancelNewAccount,
        ),
    )
}
