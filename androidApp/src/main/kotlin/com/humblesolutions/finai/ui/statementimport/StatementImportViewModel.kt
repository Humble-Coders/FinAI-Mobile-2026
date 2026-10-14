package com.humblesolutions.finai.ui.statementimport

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.finai.config.ApiConfig
import com.humblesolutions.finai.config.Supabase
import com.humblesolutions.finai.data.AndroidStatementReader
import com.humblesolutions.finai.data.KtorAccountsRepository
import com.humblesolutions.finai.data.KtorAiConsentRepository
import com.humblesolutions.finai.data.KtorCategoriesRepository
import com.humblesolutions.finai.data.KtorStatementImportRepository
import com.humblesolutions.finai.data.KtorTransactionsRepository
import com.humblesolutions.finai.data.SupabaseTokenSource
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.AccountKind
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.ExtractedDocument
import com.humblesolutions.finai.model.ParsedStatement
import com.humblesolutions.finai.model.SaveOutcome
import com.humblesolutions.finai.model.TransactionDirection
import com.humblesolutions.finai.repository.AccountsRepository
import com.humblesolutions.finai.repository.AiConsentRepository
import com.humblesolutions.finai.repository.CategoriesRepository
import com.humblesolutions.finai.repository.StatementImportRepository
import com.humblesolutions.finai.repository.StatementReadException
import com.humblesolutions.finai.repository.StatementReader
import com.humblesolutions.finai.repository.TransactionsRepository
import com.humblesolutions.finai.usecase.CheckRow
import com.humblesolutions.finai.usecase.CorrectionDraft
import com.humblesolutions.finai.usecase.ImportCheck
import com.humblesolutions.finai.usecase.ImportFailure
import com.humblesolutions.finai.usecase.ImportProblem
import com.humblesolutions.finai.usecase.ImportStatement
import com.humblesolutions.finai.usecase.ImportStep
import com.humblesolutions.finai.usecase.ManualEntry
import com.humblesolutions.finai.usecase.NewAccountDraft
import com.humblesolutions.finai.usecase.NewAccountForm
import com.humblesolutions.finai.usecase.ReviewQueue
import com.humblesolutions.finai.usecase.StatementImportFlow
import com.humblesolutions.finai.util.LedgerChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.coroutines.cancellation.CancellationException

/**
 * Drives the statement import (#31): account, file, consent, reading, sending,
 * checking (#55), saving, and every way it can end.
 *
 * **No extraction here.** The platform reader reads; [ImportStatement] redacts
 * and sends; [StatementImportFlow] decides what every outcome says and offers.
 * This model moves between steps and keeps the one thing that must not be
 * lost — which account and which file — in [saved], so a rotation continues the
 * read and the app being killed re-reads the same file rather than starting
 * over. The document and any password stay in memory only: neither is ever
 * written anywhere.
 */
class StatementImportViewModel(private val saved: SavedStateHandle) : ViewModel() {

    private val _uiState = MutableStateFlow(
        StatementImportUiState(
            accountId = saved[KEY_ACCOUNT],
            fileUri = saved[KEY_FILE],
        ),
    )
    val uiState: StateFlow<StatementImportUiState> = _uiState.asStateFlow()

    private var repositories: ImportRepositories? = null
    private var boundTo: String? = null
    private var generation = 0
    private var work: Job? = null

    /** The redacted-ready document, in memory only, for a retry or diagnostics. */
    private var document: ExtractedDocument? = null

    /** What came back, while it is checked and saved. Memory only. */
    private var parsed: ParsedStatement? = null

    /** Held only while a read needs it; never saved, never logged. */
    private var password: String? = null

    /**
     * What the summary was written from, so it can be written again when a
     * saved row is fixed — "1 needs your review" must not outlive the fix.
     */
    private var summarised: Pair<ParsedStatement, SaveOutcome>? = null

    fun bind(userId: String, context: Context, logging: Boolean) = bind(userId) {
        Supabase.clientOrNull()?.let { client ->
            val tokens = SupabaseTokenSource(client)
            ImportRepositories(
                accounts = KtorAccountsRepository(ApiConfig.BASE_URL, tokens, logging),
                imports = KtorStatementImportRepository(ApiConfig.BASE_URL, tokens, logging),
                consent = KtorAiConsentRepository(ApiConfig.BASE_URL, tokens, logging),
                reader = AndroidStatementReader(context.applicationContext),
                files = AndroidPickedFiles(context.applicationContext),
                transactions = KtorTransactionsRepository(ApiConfig.BASE_URL, tokens, logging),
                categories = KtorCategoriesRepository(ApiConfig.BASE_URL, tokens, logging),
            )
        }
    }

    internal fun bind(userId: String, build: () -> ImportRepositories?) {
        if (userId.isBlank() || userId == boundTo) return
        repositories?.close()
        generation++
        work?.cancel()
        document = null
        parsed = null
        password = null
        summarised = null
        // Saved state belongs to whoever picked the file; anyone else starts clean.
        if (saved.get<String>(KEY_OWNER) != userId) {
            saved.keys().forEach { saved.remove<Any>(it) }
            saved[KEY_OWNER] = userId
            _uiState.value = StatementImportUiState()
        }
        boundTo = userId
        repositories = build() ?: return
        loadAccounts()
    }

    override fun onCleared() {
        repositories?.close()
        repositories = null
    }

    // ── The account ─────────────────────────────────────────────────────

    fun loadAccounts() {
        val accounts = repositories?.accounts ?: return
        val started = generation
        // A first load only; see ManualEntryViewModel.load.
        _uiState.update { it.copy(accountsLoading = it.accounts.isEmpty(), accountsErrorKey = null) }
        viewModelScope.launch {
            try {
                val list = accounts.list()
                if (started != generation) return@launch
                _uiState.update { state ->
                    // A remembered choice that no longer exists goes back to
                    // unanswered rather than to some other account.
                    val kept = state.accountId?.takeIf { id -> list.any { it.id == id } }
                    state.copy(accounts = list, accountsLoading = false, accountId = kept)
                }
                resumeAfterRestore()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (started != generation) return@launch
                _uiState.update { it.copy(accountsLoading = false, accountsErrorKey = e.messageKey) }
            }
        }
    }

    fun chooseAccount(id: String) {
        _uiState.update { it.copy(accountId = id) }
        saved[KEY_ACCOUNT] = id
    }

    fun continueFromAccount() {
        if (!_uiState.value.canContinueFromAccount) return
        _uiState.update { it.copy(step = ImportStep.CHOOSE_FILE) }
    }

    fun backToAccount() {
        if (_uiState.value.working) return
        _uiState.update { it.copy(step = ImportStep.CHOOSE_ACCOUNT) }
    }

    fun openNewAccount() = _uiState.update {
        it.copy(newAccount = NewAccountDraft(), newAccountTouched = false, newAccountErrorKey = null)
    }

    fun onNewAccountName(name: String) = _uiState.update {
        it.copy(newAccount = (it.newAccount ?: NewAccountDraft()).copy(name = name), newAccountTouched = true, newAccountErrorKey = null)
    }

    fun onNewAccountKind(kind: AccountKind) = _uiState.update {
        it.copy(newAccount = (it.newAccount ?: NewAccountDraft()).copy(kind = kind), newAccountTouched = true, newAccountErrorKey = null)
    }

    fun cancelNewAccount() {
        if (_uiState.value.creatingAccount) return
        _uiState.update { it.copy(newAccount = null, newAccountTouched = false, newAccountErrorKey = null) }
    }

    /** Adds the account and chooses it — selectable without leaving the flow (#31). */
    fun createAccount() {
        val accounts = repositories?.accounts ?: return
        val draft = _uiState.value.newAccount ?: return
        if (_uiState.value.creatingAccount) return
        val request = NewAccountForm.request(draft) ?: run {
            _uiState.update { it.copy(newAccountTouched = true) }
            return
        }
        val started = generation
        _uiState.update { it.copy(creatingAccount = true, newAccountErrorKey = null) }
        viewModelScope.launch {
            try {
                val created = accounts.create(request)
                if (started != generation) return@launch
                _uiState.update {
                    it.copy(
                        creatingAccount = false,
                        newAccount = null,
                        accounts = it.accounts + created,
                        accountId = created.id,
                    )
                }
                saved[KEY_ACCOUNT] = created.id
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (started != generation) return@launch
                _uiState.update { it.copy(creatingAccount = false, newAccountErrorKey = e.messageKey) }
            }
        }
    }

    // ── The file, and reading it ────────────────────────────────────────

    /** A file was picked (or photographed): read it straight away. */
    fun onFilePicked(uri: String) {
        if (_uiState.value.working || _uiState.value.accountId == null) return
        // A different file replaces this one: give back access to the old.
        _uiState.value.fileUri?.takeIf { it != uri }?.let { release(it) }
        saved[KEY_FILE] = uri
        // Marked before reading, not inside it. Opening the camera or the
        // picker is exactly when Android kills the app, and the result then
        // arrives before `bind` has built the clients — `read` returns at once.
        // Marked here, the restore after `bind` reads the file instead of
        // quietly dropping the photo that was just taken.
        saved[KEY_IN_PROGRESS] = true
        password = null
        document = null
        parsed = null
        summarised = null
        _uiState.update {
            it.copy(
                fileUri = uri,
                problem = null,
                passwordWrong = false,
                diagnosticsTicked = false,
                diagnosticsSent = false,
                diagnosticsThanks = null,
                canResend = false,
                checkRows = emptyList(),
                readAgain = false,
            )
        }
        read()
    }

    fun submitPassword(value: String) {
        if (_uiState.value.step != ImportStep.PASSWORD || value.isEmpty()) return
        password = value
        read()
    }

    fun chooseAnotherFile() {
        if (_uiState.value.working) return
        _uiState.value.fileUri?.let { release(it) }
        work?.cancel()
        saved.remove<String>(KEY_FILE)
        saved.remove<Boolean>(KEY_IN_PROGRESS)
        saved.remove<Boolean>(KEY_CHECKING)
        document = null
        parsed = null
        password = null
        _uiState.update {
            it.copy(
                step = ImportStep.CHOOSE_FILE,
                fileUri = null,
                problem = null,
                checkRows = emptyList(),
                readAgain = false,
                canResend = false,
                diagnosticsTicked = false,
                diagnosticsSent = false,
                diagnosticsThanks = null,
            )
        }
    }

    /** Try the same file again: resend if it was already read, else read it again. */
    fun retry() {
        val state = _uiState.value
        if (state.working || state.problem?.failure?.canRetry != true) return
        val readDocument = document
        when {
            // Read and still being checked: back to the rows, edits and all.
            parsed != null && state.checkRows.isNotEmpty() -> _uiState.update { it.copy(step = ImportStep.CHECK, problem = null) }

            readDocument != null -> send(readDocument, keepText = false)

            else -> read()
        }
    }

    private fun read() {
        val repos = repositories ?: return
        val uri = _uiState.value.fileUri ?: return
        val started = generation
        saved[KEY_IN_PROGRESS] = true
        work?.cancel()
        _uiState.update { it.copy(step = ImportStep.READING, page = 0, pages = 0, problem = null) }
        work = viewModelScope.launch {
            try {
                val read = repos.reader.read(uri, password) { page, pages ->
                    if (started == generation) _uiState.update { it.copy(page = page, pages = pages) }
                }
                if (started != generation) return@launch
                password = null
                document = read
                send(read, keepText = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: StatementReadException.PasswordRequired) {
                if (started != generation) return@launch
                saved.remove<Boolean>(KEY_IN_PROGRESS)
                _uiState.update { it.copy(step = ImportStep.PASSWORD, passwordWrong = e.wrongPassword) }
            } catch (e: Exception) {
                if (started != generation) return@launch
                fail(e)
            }
        }
    }

    private fun send(read: ExtractedDocument, keepText: Boolean) {
        // Never a silent return: that would leave the screen on "reading" with
        // the loader up and Back ignored. Unreachable today — the clients only
        // go missing before `bind`, and `read` stops earlier — but a retryable
        // failure is the safe answer if that ever changes (as it did on iOS).
        val repos = repositories ?: return unbound()
        val accountId = _uiState.value.accountId ?: return unbound()
        val started = generation
        _uiState.update { it.copy(step = ImportStep.SENDING, problem = null, canResend = true) }
        work = viewModelScope.launch {
            try {
                // Every account is in the household's currency, so any of them
                // answers when none is chosen yet.
                val accounts = _uiState.value.accounts
                val currency = (accounts.firstOrNull { it.id == accountId } ?: accounts.firstOrNull())?.currency
                val result = ImportStatement(repos.imports).execute(read, accountId, keepText, accountCurrency = currency)
                if (started != generation) return@launch
                if (keepText) {
                    _uiState.update {
                        it.copy(diagnosticsSent = true, diagnosticsThanks = StatementImportFlow.diagnosticsThanks(result.textRetainedUntil))
                    }
                }
                val empty = StatementImportFlow.problemAfterParse(result)
                if (empty != null) {
                    showProblem(empty)
                } else {
                    parsed = result
                    check(result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (started != generation) return@launch
                if (StatementImportFlow.needsConsent(e)) askForConsent(e as? ApiException) else fail(e)
            }
        }
    }

    /**
     * Read and not saved: every row is shown for checking (#55). Nothing
     * reaches the person's records until they press Save.
     *
     * Only a flag goes into saved state, never a row: if Android ends the app
     * here, [resumeAfterRestore] reads the file again (free since backend #73)
     * and the screen says the earlier changes were not kept.
     */
    private fun check(result: ParsedStatement) {
        val accountId = _uiState.value.accountId ?: return unbound()
        saved[KEY_CHECKING] = true
        _uiState.update {
            it.copy(
                step = ImportStep.CHECK,
                checkRows = ImportCheck.rowsOf(result, accountId),
                saveErrorKey = null,
                editing = null,
                confirmingDiscard = false,
            )
        }
    }

    /** Save the rows as the person left them. */
    fun saveChecked() {
        val state = _uiState.value
        if (!state.canSaveChecked) return
        val result = parsed ?: return unbound()
        save(result, state.checkRows)
    }

    private fun save(result: ParsedStatement, rows: List<CheckRow>) {
        val repos = repositories ?: return unbound()
        val accountId = _uiState.value.accountId ?: return unbound()
        val started = generation
        _uiState.update { it.copy(step = ImportStep.SAVING, problem = null, saveErrorKey = null) }
        work = viewModelScope.launch {
            try {
                val outcome = repos.imports.save(result.importId, ImportCheck.rowsToSave(accountId, rows))
                if (started != generation) return@launch
                saved.remove<Boolean>(KEY_IN_PROGRESS)
                saved.remove<Boolean>(KEY_CHECKING)
                document = null
                parsed = null
                summarised = result to outcome
                _uiState.value.fileUri?.let { release(it) }
                _uiState.update {
                    it.copy(
                        step = ImportStep.DONE,
                        summary = StatementImportFlow.summary(result, outcome),
                        needsReview = outcome.needsReview,
                        importId = outcome.importId,
                        canResend = false,
                        checkRows = emptyList(),
                        readAgain = false,
                    )
                }
                // Home shows figures derived from these rows, and the server
                // has confirmed them. Announced here rather than optimistically
                // on send, so a refresh never shows the figures it already had.
                LedgerChanged.announce()
                loadImported(outcome.importId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException.ImportSuperseded) {
                if (started != generation) return@launch
                // Read again elsewhere since; this reading cannot be saved. Read
                // it once more and check the fresh rows — saying so, because the
                // changes made to these are gone.
                parsed = null
                _uiState.update { it.copy(readAgain = true, checkRows = emptyList()) }
                document?.let { send(it, keepText = false) } ?: read()
            } catch (e: Exception) {
                if (started != generation) return@launch
                // Back to the rows, every edit kept, with why it did not save:
                // pressing Save again is the whole of the recovery.
                _uiState.update {
                    it.copy(step = ImportStep.CHECK, saveErrorKey = (e as? ApiException)?.messageKey ?: Strings.error_unexpected)
                }
            }
        }
    }

    // ── Discarding from the check (#55) ─────────────────────────────────

    /** Discard asks first: the rows were read, and leaving loses them. */
    fun askDiscard() {
        if (_uiState.value.step != ImportStep.CHECK) return
        _uiState.update { it.copy(confirmingDiscard = true) }
    }

    fun keepChecking() = _uiState.update { it.copy(confirmingDiscard = false) }

    fun dismissReadAgain() = _uiState.update { it.copy(readAgain = false) }

    // ── Editing a row: before saving, or after ──────────────────────────

    /**
     * Open the editor on [id]: a row being checked (fixed here, in memory, and
     * sent when the import is saved) or one just saved (fixed on the server,
     * as from Transactions).
     */
    fun edit(id: String) {
        val state = _uiState.value
        val row = when (state.step) {
            ImportStep.CHECK -> state.checkRows.firstOrNull { it.row.id == id }?.row
            ImportStep.DONE -> state.imported.firstOrNull { it.id == id }
            else -> null
        } ?: return
        _uiState.update {
            it.copy(
                editing = row,
                draft = ReviewQueue.draftOf(row),
                editErrorKey = null,
                editSaving = false,
                deleting = false,
                today = ManualEntry.today(),
            )
        }
    }

    fun cancelEdit() {
        if (_uiState.value.editSaving || _uiState.value.deleting) return
        _uiState.update { it.copy(editing = null, draft = CorrectionDraft(), editErrorKey = null) }
    }

    fun onDateChange(date: LocalDate) = editDraft { it.copy(occurredOn = date) }

    fun onAmountChange(value: String) = editDraft { it.copy(amount = value) }

    fun onDirectionChange(direction: TransactionDirection) = editDraft { it.copy(direction = direction) }

    fun onDescriptionChange(value: String) = editDraft { it.copy(description = value) }

    fun onCategoryChosen(id: String) = editDraft { it.copy(categoryId = id) }

    private fun editDraft(change: (CorrectionDraft) -> CorrectionDraft) {
        if (_uiState.value.editing == null) return
        _uiState.update { it.copy(draft = change(it.draft), editErrorKey = null) }
    }

    fun saveEdit() {
        val state = _uiState.value
        val row = state.editing ?: return
        if (state.editSaving) return
        if (state.step == ImportStep.CHECK) {
            val rows = ImportCheck.edit(state.checkRows, row.id, state.draft, state.today) ?: return
            _uiState.update { it.copy(checkRows = rows, editing = null, draft = CorrectionDraft(), saveErrorKey = null) }
            return
        }
        val repos = repositories ?: return
        val patch = ReviewQueue.correction(row, state.draft, state.today) ?: return
        val started = generation
        _uiState.update { it.copy(editSaving = true, editErrorKey = null) }
        viewModelScope.launch {
            try {
                val fixed = repos.transactions.correct(row.id, patch).transaction
                if (started != generation) return@launch
                _uiState.update { it.copy(editSaving = false, editing = null, draft = CorrectionDraft()) }
                if (row.needsReview && !fixed.needsReview) answered()
                LedgerChanged.announce()
                _uiState.value.importId?.let { loadImported(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (started != generation) return@launch
                // A duplicate is said in the sheet, with the row still open:
                // the person's draft is the thing they need to fix.
                _uiState.update { it.copy(editSaving = false, editErrorKey = e.messageKey) }
            }
        }
    }

    /**
     * The sheet asked first. Before saving, the row is simply left out of the
     * import; after, it is deleted from the person's records.
     */
    fun deleteEditing() {
        val state = _uiState.value
        val row = state.editing ?: return
        if (state.editSaving || state.deleting) return
        if (state.step == ImportStep.CHECK) {
            _uiState.update {
                it.copy(checkRows = ImportCheck.remove(it.checkRows, row.id), editing = null, draft = CorrectionDraft())
            }
            return
        }
        val repos = repositories ?: return
        val started = generation
        _uiState.update { it.copy(deleting = true, editErrorKey = null) }
        viewModelScope.launch {
            try {
                repos.transactions.delete(row.id)
                if (started != generation) return@launch
                _uiState.update {
                    it.copy(
                        deleting = false,
                        editing = null,
                        draft = CorrectionDraft(),
                        imported = it.imported.filterNot { r -> r.id == row.id },
                    )
                }
                if (row.needsReview) answered()
                LedgerChanged.announce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (started != generation) return@launch
                _uiState.update { it.copy(deleting = false, editErrorKey = e.messageKey) }
            }
        }
    }

    fun openNewCategory() = _uiState.update { it.copy(newCategoryName = "", newCategoryErrorKey = null) }

    fun onNewCategoryName(name: String) = _uiState.update { it.copy(newCategoryName = name, newCategoryErrorKey = null) }

    fun cancelNewCategory() {
        if (_uiState.value.creatingCategory) return
        _uiState.update { it.copy(newCategoryName = null, newCategoryErrorKey = null) }
    }

    /** Adds the category and files the row being edited into it. */
    fun createCategory() {
        val repos = repositories ?: return
        val name = _uiState.value.newCategoryName?.trim().orEmpty()
        if (name.isEmpty() || _uiState.value.creatingCategory) return
        _uiState.update { it.copy(creatingCategory = true, newCategoryErrorKey = null) }
        viewModelScope.launch {
            try {
                val made = repos.categories.create(name)
                _uiState.update {
                    it.copy(
                        creatingCategory = false,
                        newCategoryName = null,
                        categories = it.categories + made,
                        draft = it.draft.copy(categoryId = made.id),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException.CategoryExists) {
                // Taken by one they can already use, so use it.
                _uiState.update {
                    it.copy(
                        creatingCategory = false,
                        newCategoryName = null,
                        draft = it.draft.copy(categoryId = e.categoryId ?: it.draft.categoryId),
                    )
                }
            } catch (e: ApiException) {
                _uiState.update { it.copy(creatingCategory = false, newCategoryErrorKey = e.messageKey) }
            }
        }
    }

    /**
     * One row that was waiting for a person no longer is — fixed here, or
     * deleted. The prompt to open Review and the summary line both follow.
     *
     * Counted down from the save's own figure rather than recounted from the
     * list: the list is the import's first page, and a long statement has more
     * rows waiting than one page shows.
     */
    private fun answered() {
        val (result, outcome) = summarised ?: return
        val waiting = (_uiState.value.needsReview - 1).coerceAtLeast(0)
        val counted = outcome.copy(needsReview = waiting)
        summarised = result to counted
        _uiState.update { it.copy(needsReview = waiting, summary = StatementImportFlow.summary(result, counted)) }
    }

    /** Try the list again after it failed; the import itself already succeeded. */
    fun reloadImported() {
        _uiState.value.importId?.let { loadImported(it) }
    }

    /**
     * The rows this import produced, for the result screen.
     *
     * Asked for by the import's own id, so the list is exactly what just
     * happened rather than the household's whole ledger. Both kinds: the rows
     * flagged for review AND the ones the model filed with confidence, which
     * are the ones nobody has ever been shown and the only way to answer the
     * question somebody actually has — did it get my categories right.
     *
     * A failure here leaves the import succeeded and the summary standing. The
     * rows are worth showing and not worth turning a finished import into an
     * error screen over.
     */
    private suspend fun categoriesOrNull(repos: ImportRepositories): List<com.humblesolutions.finai.model.Category>? = try {
        repos.categories.list()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        null
    }

    private fun loadImported(importId: String) {
        val repos = repositories ?: return
        if (importId.isBlank()) return
        val started = generation
        _uiState.update { it.copy(importedLoading = true, importedErrorKey = null) }
        viewModelScope.launch {
            try {
                val page = repos.transactions.list(statementImportId = importId)
                // A name beside each row is a nicety; the rows are the point. Tried
                // twice, and kept from before if both fail: without the names a
                // row cannot say what it was filed as.
                val categories = categoriesOrNull(repos) ?: categoriesOrNull(repos)
                if (started != generation) return@launch
                _uiState.update {
                    it.copy(
                        imported = page.rows,
                        categories = categories ?: it.categories,
                        importedLoading = false,
                        importedErrorKey = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (started != generation) return@launch
                _uiState.update { it.copy(importedLoading = false, importedErrorKey = e.messageKey) }
            }
        }
    }

    /**
     * Gives back the lasting access taken to a picked file so a restore could
     * read it again. Kept, it would pile up — the app holding read access to
     * every statement ever imported, long after the import.
     */
    private fun release(uri: String) {
        repositories?.files?.release(uri)
    }

    /** A retryable failure instead of a stuck loader; see [send]. */
    private fun unbound() = showProblem(ImportProblem(ImportFailure.OTHER))

    private fun fail(error: Throwable) {
        showProblem(StatementImportFlow.problemFor(error) ?: ImportProblem(ImportFailure.OTHER))
    }

    private fun showProblem(problem: ImportProblem) {
        saved.remove<Boolean>(KEY_IN_PROGRESS)
        saved.remove<Boolean>(KEY_CHECKING)
        _uiState.update {
            it.copy(step = ImportStep.FAILED, problem = problem, canResend = document != null)
        }
    }

    // ── Consent ─────────────────────────────────────────────────────────

    private fun askForConsent(error: ApiException?) {
        val consent = repositories?.consent ?: return
        val started = generation
        saved.remove<Boolean>(KEY_IN_PROGRESS)
        _uiState.update {
            it.copy(
                step = ImportStep.CONSENT,
                consentTicked = false,
                consentBusy = true,
                consentErrorKey = (error as? ApiException.AiPolicyChanged)?.messageKey,
            )
        }
        viewModelScope.launch {
            try {
                val policy = consent.policy()
                if (started != generation) return@launch
                _uiState.update { it.copy(policy = policy, consentBusy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (started != generation) return@launch
                _uiState.update { it.copy(consentBusy = false, consentErrorKey = e.messageKey) }
            }
        }
    }

    fun onConsentTicked(ticked: Boolean) = _uiState.update { it.copy(consentTicked = ticked) }

    /** Records consent to the version shown, then sends the statement it was asked for. */
    fun agree() {
        val consent = repositories?.consent ?: return
        val state = _uiState.value
        val policy = state.policy ?: return
        if (!state.canAgree) return
        val started = generation
        _uiState.update { it.copy(consentBusy = true, consentErrorKey = null) }
        viewModelScope.launch {
            try {
                consent.consent(policy.version)
                if (started != generation) return@launch
                _uiState.update { it.copy(consentBusy = false, consentTicked = false) }
                document?.let { send(it, keepText = false) } ?: read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException.AiPolicyChanged) {
                if (started != generation) return@launch
                // The text changed under them: show the new one, unticked.
                askForConsent(e)
            } catch (e: ApiException) {
                if (started != generation) return@launch
                _uiState.update { it.copy(consentBusy = false, consentErrorKey = e.messageKey) }
            }
        }
    }

    /** "Not now": nothing is sent, and the file can be imported later. */
    fun declineConsent() = _uiState.update {
        it.copy(step = ImportStep.CHOOSE_FILE, consentTicked = false, consentErrorKey = null)
    }

    // ── Diagnostics ─────────────────────────────────────────────────────

    fun onDiagnosticsTicked(ticked: Boolean) = _uiState.update { it.copy(diagnosticsTicked = ticked) }

    /**
     * The person's explicit yes: send this import's redacted text again, with
     * the flag on. Nothing is sent unless the box was ticked.
     */
    fun sendDiagnostics() {
        val state = _uiState.value
        val read = document ?: return
        if (!state.canSendDiagnostics) return
        send(read, keepText = true)
    }

    // ── Leaving ─────────────────────────────────────────────────────────

    /**
     * Leaving the screen on purpose: the file, the document, any password and
     * the half-finished import all go, and the next visit starts at the account
     * step. The household's accounts stay loaded — they are not the import.
     * Work still on its way is cancelled; a parse the server already finished
     * stays on the server (a failed one costs nothing, a good one is saved
     * only when this screen saves it).
     */
    fun discard() {
        _uiState.value.fileUri?.let { release(it) }
        work?.cancel()
        generation++
        document = null
        parsed = null
        password = null
        summarised = null
        saved.remove<String>(KEY_FILE)
        saved.remove<Boolean>(KEY_IN_PROGRESS)
        saved.remove<Boolean>(KEY_CHECKING)
        saved.remove<String>(KEY_ACCOUNT)
        _uiState.update { StatementImportUiState(accounts = it.accounts, accountsLoading = false) }
        // Bumping the generation drops replies meant for the old import, but a
        // fresh account list is still wanted: reload so a later visit is live.
        loadAccounts()
    }

    // ── Process death ───────────────────────────────────────────────────

    /**
     * The app came back after Android killed it mid-import. The account and the
     * file are remembered; the document and any password were never written
     * down, so the file is read again. Continuing on the reading screen is the
     * honest thing — the work was lost, not the choice.
     */
    private fun resumeAfterRestore() {
        val state = _uiState.value
        if (saved.get<Boolean>(KEY_IN_PROGRESS) != true) {
            if (state.accountId != null && state.step == ImportStep.CHOOSE_ACCOUNT && state.fileUri != null) {
                _uiState.update { it.copy(step = ImportStep.CHOOSE_FILE) }
            }
            return
        }
        if (state.working || state.accountId == null || state.fileUri == null) return
        // Ended mid-check: the rows and any changes to them were memory only.
        if (saved.get<Boolean>(KEY_CHECKING) == true) _uiState.update { it.copy(readAgain = true) }
        read()
    }

    internal companion object {
        const val KEY_OWNER = "import.owner"
        const val KEY_ACCOUNT = "import.account_id"
        const val KEY_FILE = "import.file_uri"
        const val KEY_IN_PROGRESS = "import.in_progress"

        /** A flag, never the rows: the import was being checked (#55). */
        const val KEY_CHECKING = "import.checking"
    }
}

/**
 * Lasting read access to a picked file. Taken when the file is picked (the
 * route does that — it must happen at once); given back here when the import
 * is done with it.
 */
internal interface PickedFiles {
    fun release(uri: String)
}

internal class AndroidPickedFiles(private val context: Context) : PickedFiles {
    override fun release(uri: String) {
        // Not every file has a lasting grant — a camera photo is the app's own,
        // and some providers refuse one — so there may be nothing to give back.
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                android.net.Uri.parse(uri),
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }
}

/** The clients the import needs, built together for one signed-in user. */
internal class ImportRepositories(
    val accounts: AccountsRepository,
    val imports: StatementImportRepository,
    val consent: AiConsentRepository,
    val reader: StatementReader,
    val files: PickedFiles,
    val transactions: TransactionsRepository,
    val categories: CategoriesRepository,
) {
    fun close() {
        accounts.close()
        imports.close()
        consent.close()
        reader.close()
        transactions.close()
        categories.close()
    }
}
