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
import com.humblesolutions.finai.data.KtorStatementImportRepository
import com.humblesolutions.finai.data.SupabaseTokenSource
import com.humblesolutions.finai.model.AccountKind
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.ExtractedDocument
import com.humblesolutions.finai.model.ParsedStatement
import com.humblesolutions.finai.repository.AccountsRepository
import com.humblesolutions.finai.repository.AiConsentRepository
import com.humblesolutions.finai.repository.StatementImportRepository
import com.humblesolutions.finai.repository.StatementReadException
import com.humblesolutions.finai.repository.StatementReader
import com.humblesolutions.finai.usecase.ImportFailure
import com.humblesolutions.finai.usecase.ImportProblem
import com.humblesolutions.finai.usecase.ImportStatement
import com.humblesolutions.finai.usecase.ImportStep
import com.humblesolutions.finai.usecase.NewAccountDraft
import com.humblesolutions.finai.usecase.NewAccountForm
import com.humblesolutions.finai.usecase.StatementImportFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Drives the statement import (#31): account, file, consent, reading, sending,
 * saving, and every way it can end.
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

    /** What came back, for a retried save after the parse already succeeded. */
    private var parsed: ParsedStatement? = null

    /** Held only while a read needs it; never saved, never logged. */
    private var password: String? = null

    fun bind(userId: String, context: Context, logging: Boolean) = bind(userId) {
        Supabase.clientOrNull()?.let { client ->
            val tokens = SupabaseTokenSource(client)
            ImportRepositories(
                accounts = KtorAccountsRepository(ApiConfig.BASE_URL, tokens, logging),
                imports = KtorStatementImportRepository(ApiConfig.BASE_URL, tokens, logging),
                consent = KtorAiConsentRepository(ApiConfig.BASE_URL, tokens, logging),
                reader = AndroidStatementReader(context.applicationContext),
                files = AndroidPickedFiles(context.applicationContext),
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
        _uiState.update { it.copy(accountsLoading = true, accountsErrorKey = null) }
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
        _uiState.update {
            it.copy(fileUri = uri, problem = null, passwordWrong = false, diagnosticsTicked = false,
                diagnosticsSent = false, diagnosticsThanks = null, canResend = false)
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
        document = null
        parsed = null
        password = null
        _uiState.update {
            it.copy(step = ImportStep.CHOOSE_FILE, fileUri = null, problem = null, canResend = false,
                diagnosticsTicked = false, diagnosticsSent = false, diagnosticsThanks = null)
        }
    }

    /** Try the same file again: resend if it was already read, else read it again. */
    fun retry() {
        val state = _uiState.value
        if (state.working || state.problem?.failure?.canRetry != true) return
        val readDocument = document
        val readParse = parsed
        when {
            readParse != null -> save(readParse)
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
        val repos = repositories ?: return
        val accountId = _uiState.value.accountId ?: return
        val started = generation
        _uiState.update { it.copy(step = ImportStep.SENDING, problem = null, canResend = true) }
        work = viewModelScope.launch {
            try {
                val result = ImportStatement(repos.imports).execute(read, accountId, keepText)
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
                    save(result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (started != generation) return@launch
                if (StatementImportFlow.needsConsent(e)) askForConsent(e as? ApiException) else fail(e)
            }
        }
    }

    private fun save(result: ParsedStatement) {
        val repos = repositories ?: return
        val accountId = _uiState.value.accountId ?: return
        val started = generation
        _uiState.update { it.copy(step = ImportStep.SAVING, problem = null) }
        work = viewModelScope.launch {
            try {
                val outcome = repos.imports.save(result.importId, StatementImportFlow.rowsToSave(accountId, result))
                if (started != generation) return@launch
                saved.remove<Boolean>(KEY_IN_PROGRESS)
                document = null
                _uiState.value.fileUri?.let { release(it) }
                _uiState.update {
                    it.copy(
                        step = ImportStep.DONE,
                        summary = StatementImportFlow.summary(result, outcome),
                        needsReview = outcome.needsReview,
                        canResend = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (started != generation) return@launch
                fail(e)
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

    private fun fail(error: Throwable) {
        showProblem(StatementImportFlow.problemFor(error) ?: ImportProblem(ImportFailure.OTHER))
    }

    private fun showProblem(problem: ImportProblem) {
        saved.remove<Boolean>(KEY_IN_PROGRESS)
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
        saved.remove<String>(KEY_FILE)
        saved.remove<Boolean>(KEY_IN_PROGRESS)
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
        read()
    }

    internal companion object {
        const val KEY_OWNER = "import.owner"
        const val KEY_ACCOUNT = "import.account_id"
        const val KEY_FILE = "import.file_uri"
        const val KEY_IN_PROGRESS = "import.in_progress"
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
) {
    fun close() {
        accounts.close()
        imports.close()
        consent.close()
        reader.close()
    }
}
