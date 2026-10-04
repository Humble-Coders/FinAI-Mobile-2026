package com.humblesolutions.finai.ui.manualentry

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.finai.config.ApiConfig
import com.humblesolutions.finai.config.Supabase
import com.humblesolutions.finai.data.KtorAccountsRepository
import com.humblesolutions.finai.data.KtorCapabilitiesRepository
import com.humblesolutions.finai.data.KtorCategoriesRepository
import com.humblesolutions.finai.data.KtorTransactionsRepository
import com.humblesolutions.finai.data.SupabaseTokenSource
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.AccountKind
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.model.TransactionDirection
import com.humblesolutions.finai.repository.AccountsRepository
import com.humblesolutions.finai.repository.CapabilitiesRepository
import com.humblesolutions.finai.repository.CategoriesRepository
import com.humblesolutions.finai.repository.TransactionsRepository
import com.humblesolutions.finai.usecase.ManualEntry
import com.humblesolutions.finai.usecase.ManualEntryDraft
import com.humblesolutions.finai.usecase.NewAccountDraft
import com.humblesolutions.finai.usecase.NewAccountForm
import com.humblesolutions.finai.util.Dates
import com.humblesolutions.finai.util.LedgerChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.coroutines.cancellation.CancellationException

/**
 * Drives the manual entry screen (#30): one transaction typed in by hand.
 *
 * What may be saved is never decided here — [ManualEntry] answers that, and iOS
 * reads the same rule. This model only moves answers in and out, and sends.
 *
 * **What was typed survives the app being killed.** The draft is written to
 * [saved] on every change, so Android restoring the screen after reclaiming the
 * process brings back the half-typed entry rather than an empty form. It holds
 * plain values only — ids, an ISO date, text as typed — and the id of the user
 * it belongs to, so a restore can never hand one person's draft to another.
 *
 * @param today where "today" comes from; a parameter so a test can fix it.
 */
class ManualEntryViewModel internal constructor(
    private val saved: SavedStateHandle,
    private val today: () -> LocalDate,
) : ViewModel() {

    /** The constructor `viewModel()` calls; the saved state is Android's. */
    constructor(saved: SavedStateHandle) : this(saved, ManualEntry::today)

    private val _uiState = MutableStateFlow(restored())
    val uiState: StateFlow<ManualEntryUiState> = _uiState.asStateFlow()

    private var repositories: ManualEntryRepositories? = null

    /** Whose screen this is holding. Null until the first bind. */
    private var boundTo: String? = null

    /** Bumped on every rebind, so a reply meant for the last user is dropped (see SetupViewModel). */
    private var generation = 0

    /**
     * Bumped when the entry is discarded, so a save or an account create still
     * on its way cannot write into the clean form of the next visit.
     */
    private var entry = 0

    fun bind(userId: String, logging: Boolean) = bind(userId) {
        Supabase.clientOrNull()?.let { client ->
            val tokens = SupabaseTokenSource(client)
            ManualEntryRepositories(
                accounts = KtorAccountsRepository(ApiConfig.BASE_URL, tokens, logging),
                categories = KtorCategoriesRepository(ApiConfig.BASE_URL, tokens, logging),
                transactions = KtorTransactionsRepository(ApiConfig.BASE_URL, tokens, logging),
                capabilities = KtorCapabilitiesRepository(ApiConfig.BASE_URL, tokens, logging),
            )
        }
    }

    /** The whole of [bind], with the repositories' source left open for tests. */
    internal fun bind(userId: String, build: () -> ManualEntryRepositories?) {
        if (userId.isBlank()) return refuseUnknownUser()
        if (userId == boundTo) return
        closeClients()
        generation++
        boundTo = userId
        // A restored draft is kept only for the user who typed it.
        val owner = saved.get<String>(KEY_OWNER)
        if (owner != userId) {
            if (owner != null) clearSaved()
            saved[KEY_OWNER] = userId
            _uiState.value = ManualEntryUiState(today = today())
        }
        repositories = build() ?: return
        load()
    }

    private fun refuseUnknownUser() {
        closeClients()
        generation++
        boundTo = null
        clearSaved()
        _uiState.value = ManualEntryUiState(
            loading = false,
            loadFailed = true,
            canRetry = false,
            errorKey = Strings.error_unexpected,
        )
    }

    override fun onCleared() = closeClients()

    private fun closeClients() {
        repositories?.close()
        repositories = null
    }

    /** The household's accounts and categories — what an entry can be filed into. */
    fun load() {
        val repositories = repositories ?: return
        val started = generation
        _uiState.update { it.copy(loading = true, loadFailed = false, errorKey = null) }
        viewModelScope.launch {
            try {
                val accounts = repositories.accounts.list()
                // Optional extras: without them the entry can still be saved,
                // and the backend chooses the category.
                val categories = orNull { repositories.categories.list() }
                val locale = orNull { repositories.capabilities.fetch() }?.locale.orEmpty()
                if (started != generation) return@launch
                _uiState.update { state ->
                    // A restored choice that is no longer there is dropped, not
                    // swapped for another: the field shows unanswered again.
                    val draft = state.draft.copy(
                        accountId = state.draft.accountId?.takeIf { id -> accounts.any { it.id == id } },
                        categoryId = state.draft.categoryId?.takeIf { id -> categories.orEmpty().any { it.id == id } },
                    )
                    state.copy(
                        loading = false,
                        accounts = accounts,
                        categories = categories.orEmpty().sortedWith(categoryOrder),
                        locale = locale,
                        draft = draft,
                        today = today(),
                    )
                }
                store(_uiState.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (started != generation) return@launch
                _uiState.update { it.copy(loading = false, loadFailed = true, errorKey = e.messageKey) }
            }
        }
    }

    /**
     * Leaving the screen on purpose: what was typed goes with it, so coming
     * back later starts a clean entry instead of a stale half-finished one. The
     * household's accounts stay loaded — they are not the entry.
     */
    fun discard() {
        // Also mid-request: the person has left, so the reply is theirs to
        // ignore. A save still lands on the server; it just no longer changes
        // this form.
        entry++
        _uiState.update {
            ManualEntryUiState(
                today = today(),
                accounts = it.accounts,
                categories = it.categories,
                locale = it.locale,
                loading = it.loading,
                loadFailed = it.loadFailed,
                canRetry = it.canRetry,
                errorKey = it.errorKey.takeIf { _ -> it.loadFailed },
            )
        }
        store(_uiState.value)
    }

    // ── The answers ─────────────────────────────────────────────────────

    fun onAccountChosen(id: String) = edit { it.copy(accountId = id) }

    fun onDateChosen(date: LocalDate) = edit { it.copy(occurredOn = date) }

    /** The one-tap Today: the answer is still the user's, just quicker to give. */
    fun onToday() = edit { it.copy(occurredOn = today()) }

    /**
     * Re-reads the clock without touching the entry. "Today" is otherwise
     * refreshed only by an edit, a load or a save, so a screen left open past
     * midnight would still think it was yesterday: the calendar would refuse
     * the new day, and the notice would lag. Called when the app comes back to
     * the front and when the calendar opens.
     */
    fun refreshToday() {
        val now = today()
        if (_uiState.value.today != now) _uiState.update { it.copy(today = now) }
    }

    fun onAmountChange(value: String) = edit { it.copy(amount = value) }

    fun onDirectionChosen(direction: TransactionDirection) = edit { it.copy(direction = direction) }

    fun onDescriptionChange(value: String) = edit { it.copy(description = value) }

    /** Null is "choose for me": the backend categorizes. */
    fun onCategoryChosen(id: String?) = edit { it.copy(categoryId = id) }

    private fun edit(change: (ManualEntryDraft) -> ManualEntryDraft) {
        _uiState.update {
            it.copy(
                draft = change(it.draft),
                touched = true,
                saved = null,
                errorKey = null,
                today = today(),
            )
        }
        store(_uiState.value)
    }

    // ── Saving ──────────────────────────────────────────────────────────

    /**
     * Sends the entry. The request comes from [ManualEntry.request], which is
     * null whenever Save would be disabled — so this cannot send what the
     * button refused. Pressed while blocked, it shows why instead.
     */
    fun save() = send(allowDuplicate = false)

    /** "Yes, keep both" on the duplicate warning: the same entry, marked as meant. */
    fun keepDuplicate() = send(allowDuplicate = true)

    /** "No, cancel": nothing is saved, and the form keeps what was typed. */
    fun dismissDuplicate() = _uiState.update { it.copy(duplicate = null) }

    private fun send(allowDuplicate: Boolean) {
        val transactions = repositories?.transactions ?: return
        val state = _uiState.value.copy(today = today())
        // Not before the accounts have loaded: a restored account id is only
        // trusted once the household's list has confirmed it still exists.
        if (state.saving || state.loading || state.loadFailed) return
        val request = ManualEntry.request(state.draft, state.currency, state.today, allowDuplicate)
        if (request == null) {
            _uiState.update { it.copy(touched = true, today = state.today, duplicate = null) }
            return
        }
        val started = generation
        val typed = entry
        _uiState.update { it.copy(saving = true, errorKey = null, duplicate = null, today = state.today) }
        viewModelScope.launch {
            try {
                val stored = transactions.create(request)
                if (started != generation || typed != entry) return@launch
                _uiState.update {
                    // The account stays chosen for the next line of the same
                    // statement; everything else starts unanswered again.
                    it.copy(
                        saving = false,
                        saved = ManualEntry.savedAs(stored),
                        touched = false,
                        draft = ManualEntryDraft(accountId = it.draft.accountId),
                    )
                }
                store(_uiState.value)
                // One transaction moves the month's figures too. After the
                // server returned the stored row, never on send.
                LedgerChanged.announce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException.DuplicateTransaction) {
                if (started != generation || typed != entry) return@launch
                _uiState.update { it.copy(saving = false, duplicate = DuplicateWarning(e.match)) }
            } catch (e: ApiException) {
                if (started != generation || typed != entry) return@launch
                // What was typed stays: a failed save must never lose an entry.
                _uiState.update { it.copy(saving = false, errorKey = e.messageKey) }
            }
        }
    }

    // ── Adding an account ───────────────────────────────────────────────

    fun openNewAccount() = updateNewAccount(touched = false) { NewAccountDraft() }

    fun onNewAccountName(name: String) = updateNewAccount { it.copy(name = name) }

    fun onNewAccountKind(kind: AccountKind) = updateNewAccount { it.copy(kind = kind) }

    fun cancelNewAccount() {
        if (_uiState.value.creatingAccount) return
        _uiState.update { it.copy(newAccount = null, newAccountTouched = false, newAccountErrorKey = null) }
        store(_uiState.value)
    }

    private fun updateNewAccount(touched: Boolean = true, change: (NewAccountDraft) -> NewAccountDraft) {
        _uiState.update {
            it.copy(
                newAccount = change(it.newAccount ?: NewAccountDraft()),
                newAccountTouched = touched,
                newAccountErrorKey = null,
            )
        }
        store(_uiState.value)
    }

    /** Adds the account and chooses it for this entry — the reason it was added. */
    fun createAccount() {
        val accounts = repositories?.accounts ?: return
        val state = _uiState.value
        val draft = state.newAccount ?: return
        if (state.creatingAccount) return
        val request = NewAccountForm.request(draft)
        if (request == null) {
            _uiState.update { it.copy(newAccountTouched = true) }
            return
        }
        val started = generation
        val typed = entry
        _uiState.update { it.copy(creatingAccount = true, newAccountErrorKey = null) }
        viewModelScope.launch {
            try {
                val created = accounts.create(request)
                if (started != generation) return@launch
                if (typed != entry) {
                    // Left before it answered: the account exists now, so it
                    // is listed, but nobody chose it for the next entry.
                    _uiState.update { it.copy(accounts = it.accounts + created) }
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        creatingAccount = false,
                        newAccount = null,
                        newAccountTouched = false,
                        accounts = it.accounts + created,
                        draft = it.draft.copy(accountId = created.id),
                        touched = true,
                        saved = null,
                    )
                }
                store(_uiState.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (started != generation || typed != entry) return@launch
                _uiState.update { it.copy(creatingAccount = false, newAccountErrorKey = e.messageKey) }
            }
        }
    }

    // ── Saved state ─────────────────────────────────────────────────────

    /** The state Android handed back after killing the process, or an empty form. */
    private fun restored(): ManualEntryUiState {
        val draft = ManualEntryDraft(
            accountId = saved[KEY_ACCOUNT],
            occurredOn = Dates.parse(saved[KEY_DATE]),
            amount = saved[KEY_AMOUNT] ?: "",
            direction = saved.get<String>(KEY_DIRECTION)
                ?.let(TransactionDirection::fromWire)
                ?.takeIf { it != TransactionDirection.UNKNOWN },
            description = saved[KEY_DESCRIPTION] ?: "",
            categoryId = saved[KEY_CATEGORY],
        )
        val newAccount = saved.get<String>(KEY_NEW_ACCOUNT_NAME)?.let { name ->
            NewAccountDraft(
                name = name,
                kind = saved.get<String>(KEY_NEW_ACCOUNT_KIND)
                    ?.let(AccountKind::fromWire)
                    ?.takeIf { it != AccountKind.UNKNOWN },
            )
        }
        return ManualEntryUiState(
            draft = draft,
            today = today(),
            touched = saved[KEY_TOUCHED] ?: false,
            newAccount = newAccount,
        )
    }

    private fun store(state: ManualEntryUiState) {
        val draft = state.draft
        saved[KEY_ACCOUNT] = draft.accountId
        saved[KEY_DATE] = draft.occurredOn?.toString()
        saved[KEY_AMOUNT] = draft.amount
        saved[KEY_DIRECTION] = draft.direction?.wire
        saved[KEY_DESCRIPTION] = draft.description
        saved[KEY_CATEGORY] = draft.categoryId
        saved[KEY_TOUCHED] = state.touched
        saved[KEY_NEW_ACCOUNT_NAME] = state.newAccount?.name
        saved[KEY_NEW_ACCOUNT_KIND] = state.newAccount?.kind?.wire
    }

    private fun clearSaved() = saved.keys().forEach { saved.remove<Any>(it) }

    private suspend fun <T> orNull(fetch: suspend () -> T): T? = try {
        fetch()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        null
    }

    internal companion object {
        const val KEY_OWNER = "manual_entry.owner"
        const val KEY_ACCOUNT = "manual_entry.account_id"
        const val KEY_DATE = "manual_entry.occurred_on"
        const val KEY_AMOUNT = "manual_entry.amount"
        const val KEY_DIRECTION = "manual_entry.direction"
        const val KEY_DESCRIPTION = "manual_entry.description"
        const val KEY_CATEGORY = "manual_entry.category_id"
        const val KEY_TOUCHED = "manual_entry.touched"
        const val KEY_NEW_ACCOUNT_NAME = "manual_entry.new_account.name"
        const val KEY_NEW_ACCOUNT_KIND = "manual_entry.new_account.kind"

        /** The household's own categories first — they are the ones it made on purpose — then by name. */
        private val categoryOrder = compareBy<Category>({ it.isSystem }, { it.name.lowercase() })
    }
}

/** The clients the screen needs, built together for one signed-in user. */
internal class ManualEntryRepositories(
    val accounts: AccountsRepository,
    val categories: CategoriesRepository,
    val transactions: TransactionsRepository,
    val capabilities: CapabilitiesRepository,
) {
    fun close() {
        accounts.close()
        categories.close()
        transactions.close()
        capabilities.close()
    }
}
