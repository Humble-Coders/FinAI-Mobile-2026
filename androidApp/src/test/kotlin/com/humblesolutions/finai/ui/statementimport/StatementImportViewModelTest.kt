package com.humblesolutions.finai.ui.statementimport

import androidx.lifecycle.SavedStateHandle
import com.humblesolutions.finai.model.Account
import com.humblesolutions.finai.model.AccountKind
import com.humblesolutions.finai.model.AiConsentStatus
import com.humblesolutions.finai.model.AiPolicy
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Category
import com.humblesolutions.finai.model.ConfirmOutcome
import com.humblesolutions.finai.model.DeleteOutcome
import com.humblesolutions.finai.model.ExtractedDocument
import com.humblesolutions.finai.model.ExtractedLine
import com.humblesolutions.finai.model.ExtractedPage
import com.humblesolutions.finai.model.FeatureReason
import com.humblesolutions.finai.model.NewAccount
import com.humblesolutions.finai.model.NewTransaction
import com.humblesolutions.finai.model.ParsedRow
import com.humblesolutions.finai.model.ParsedStatement
import com.humblesolutions.finai.model.PatchOutcome
import com.humblesolutions.finai.model.ReviewPage
import com.humblesolutions.finai.model.RowsToSave
import com.humblesolutions.finai.model.SaveOutcome
import com.humblesolutions.finai.model.StatementUpload
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.model.TransactionPatch
import com.humblesolutions.finai.repository.AccountsRepository
import com.humblesolutions.finai.repository.AiConsentRepository
import com.humblesolutions.finai.repository.CategoriesRepository
import com.humblesolutions.finai.repository.StatementImportRepository
import com.humblesolutions.finai.repository.StatementReadException
import com.humblesolutions.finai.repository.StatementReader
import com.humblesolutions.finai.repository.TransactionsRepository
import com.humblesolutions.finai.usecase.ImportFailure
import com.humblesolutions.finai.usecase.ImportStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The import screen's model (#31), driven through [StatementImportViewModel.bind]
 * with fake clients and a fake reader. Every rule the ticket lists is asserted
 * over the UI state, not by looking at a screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatementImportViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val chequing = Account(id = "acct-1", name = "RBC Chequing", kind = AccountKind.CHEQUING, currency = "CAD")
    private val file = "content://files/statement.pdf"

    /** A statement with an account number on it, which must never reach the wire. */
    private val statement = ExtractedDocument(
        pages = listOf(
            ExtractedPage(
                lines = listOf(
                    ExtractedLine("Account 1234567890123"),
                    ExtractedLine("2026-08-14  TIM HORTONS #4821  12.40"),
                    ExtractedLine("2026-08-15  LOBLAWS  134.02"),
                ),
            ),
        ),
    )

    private val twoRows = ParsedStatement(
        importId = "i-1",
        currency = "CAD",
        rows = listOf(
            ParsedRow("2026-08-14", "TIM HORTONS", "12.40", "debit", 96),
            ParsedRow("2026-08-15", "LOBLAWS", "134.02", "debit", 93),
        ),
    )

    private fun model(saved: SavedStateHandle = SavedStateHandle()) = StatementImportViewModel(saved)

    private fun StatementImportViewModel.upToFile() {
        chooseAccount(chequing.id)
        continueFromAccount()
    }

    // ── The account ─────────────────────────────────────────────────────

    @Test
    fun `the account step cannot be skipped`() {
        val model = model()
        model.bind("alice") { repositories() }

        model.continueFromAccount()
        model.onFilePicked(file)

        val state = model.uiState.value
        assertEquals(ImportStep.CHOOSE_ACCOUNT, state.step)
        assertNull(state.accountId)
        assertFalse(state.canContinueFromAccount)
    }

    @Test
    fun `a new account is chosen without leaving the flow`() {
        val accounts = FakeAccounts()
        val model = model()
        model.bind("alice") { repositories(accounts = accounts) }

        model.openNewAccount()
        model.onNewAccountName("Visa")
        model.onNewAccountKind(AccountKind.CREDIT_CARD)
        model.createAccount()

        val state = model.uiState.value
        assertEquals("acct-new", state.accountId)
        assertTrue(state.canContinueFromAccount)
        assertEquals(ImportStep.CHOOSE_ACCOUNT, state.step)
    }

    // ── A good import ───────────────────────────────────────────────────

    @Test
    fun `a statement is read, sent, saved and summed up`() {
        val imports = FakeImports(parse = { twoRows })
        val model = model()
        model.bind("alice") { repositories(imports = imports) }
        model.upToFile()

        model.onFilePicked(file)

        val state = model.uiState.value
        assertEquals(ImportStep.DONE, state.step)
        assertEquals(listOf("2 transactions found", "1 needs your review"), state.summary)
        assertEquals(1, imports.saved.size)
        assertEquals("acct-1", imports.saved.single().second.accountId)
    }

    @Test
    fun `the only thing sent is redacted text with no long digit runs`() {
        val imports = FakeImports(parse = { twoRows })
        val model = model()
        model.bind("alice") { repositories(imports = imports) }
        model.upToFile()

        model.onFilePicked(file)

        val sent = imports.uploads.single()
        assertFalse(Regex("\\d{5,}").containsMatchIn(sent.text), sent.text)
        assertEquals("acct-1", sent.accountId)
        // The file itself goes nowhere: only the reader saw it.
        assertFalse(sent.text.contains(file))
    }

    // ── Consent ─────────────────────────────────────────────────────────

    @Test
    fun `consent is asked before the first import and never ticked for the person`() {
        val consent = FakeConsent()
        val imports = FakeImports(parse = { if (consent.agreed == null) throw ApiException.ConsentRequired("ai-v1") else twoRows })
        val model = model()
        model.bind("alice") { repositories(imports = imports, consent = consent) }
        model.upToFile()

        model.onFilePicked(file)

        val asking = model.uiState.value
        assertEquals(ImportStep.CONSENT, asking.step)
        assertEquals("ai-v1", asking.policy?.version)
        assertFalse(asking.consentTicked)
        assertFalse(asking.canAgree)

        model.agree()
        assertNull(consent.agreed, "agreeing without the box ticked records nothing")

        model.onConsentTicked(true)
        model.agree()

        assertEquals("ai-v1", consent.agreed)
        assertEquals(ImportStep.DONE, model.uiState.value.step)
        // The statement already read is sent again, not read a second time.
        assertEquals(1, (model.readerFor()).reads)
    }

    @Test
    fun `once consent is recorded it is not asked again`() {
        val consent = FakeConsent()
        val imports = FakeImports(parse = { if (consent.agreed == null) throw ApiException.ConsentRequired("ai-v1") else twoRows })
        val model = model()
        model.bind("alice") { repositories(imports = imports, consent = consent) }
        model.upToFile()
        model.onFilePicked(file)
        model.onConsentTicked(true)
        model.agree()

        model.chooseAnotherFile()
        model.onFilePicked("content://files/second.pdf")

        assertEquals(ImportStep.DONE, model.uiState.value.step)
        assertEquals(1, consent.consents)
    }

    // ── Every refusal its own words ─────────────────────────────────────

    @Test
    fun `each server refusal renders its own message`() {
        val refusals = listOf(
            ApiException.FeatureUnavailable("document_upload", FeatureReason.UNKNOWN),
            ApiException.ImportQuotaExceeded(1, "2026-10-01T00:00:00+00:00"),
            ApiException.StatementTooLarge(),
            ApiException.TooManyTransactions(),
            ApiException.ParseFailed(),
            ApiException.ImportUnavailable(),
            ApiException.Network(RuntimeException("offline")),
        )

        val messages = refusals.map { refusal ->
            val model = model()
            model.bind("alice") { repositories(imports = FakeImports(parse = { throw refusal })) }
            model.upToFile()
            model.onFilePicked(file)
            val state = model.uiState.value
            assertEquals(ImportStep.FAILED, state.step, "$refusal")
            assertNotNull(state.problemMessage, "$refusal")
        }

        assertEquals(refusals.size, messages.toSet().size, messages.joinToString("\n"))
    }

    @Test
    fun `a model failure is retried by sending again not by reading again`() {
        var attempts = 0
        val imports = FakeImports(parse = { if (attempts++ == 0) throw ApiException.ParseFailed() else twoRows })
        val reader = FakeReader()
        val model = model()
        model.bind("alice") { repositories(imports = imports, reader = reader) }
        model.upToFile()
        model.onFilePicked(file)
        assertTrue(model.uiState.value.offersRetry)

        model.retry()

        assertEquals(ImportStep.DONE, model.uiState.value.step)
        assertEquals(1, reader.reads)
        assertEquals(2, imports.uploads.size)
    }

    // ── A password ──────────────────────────────────────────────────────

    @Test
    fun `a locked PDF prompts and the password is used on the device only`() {
        val reader = FakeReader(locked = "hunter2")
        val imports = FakeImports(parse = { twoRows })
        val saved = SavedStateHandle()
        val model = model(saved)
        model.bind("alice") { repositories(imports = imports, reader = reader) }
        model.upToFile()

        model.onFilePicked(file)
        assertEquals(ImportStep.PASSWORD, model.uiState.value.step)
        model.submitPassword("wrong")
        assertTrue(model.uiState.value.passwordWrong)
        model.submitPassword("hunter2")

        assertEquals(ImportStep.DONE, model.uiState.value.step)
        assertEquals(listOf(null, "wrong", "hunter2"), reader.passwords)
        // Never written down, never sent.
        assertTrue(saved.keys().none { saved.get<Any?>(it) == "hunter2" })
        assertFalse(imports.uploads.single().text.contains("hunter2"))
    }

    // ── Nothing could be read ───────────────────────────────────────────

    @Test
    fun `when nothing can be read manual entry is offered and diagnostics are not`() {
        val model = model()
        model.bind("alice") { repositories(reader = FakeReader(fail = StatementReadException.NothingReadable())) }
        model.upToFile()

        model.onFilePicked(file)

        val state = model.uiState.value
        assertEquals(ImportFailure.NOTHING_READABLE, state.problem?.failure)
        assertTrue(state.offersManualEntry)
        // Nothing reached the server, so there is no text to offer.
        assertFalse(state.offersDiagnostics)
    }

    // ── Diagnostics ─────────────────────────────────────────────────────

    @Test
    fun `after a failed read the offer appears unticked and declining sends nothing`() {
        val imports = FakeImports(parse = { ParsedStatement(importId = "i-1") })
        val model = model()
        model.bind("alice") { repositories(imports = imports) }
        model.upToFile()
        model.onFilePicked(file)

        val state = model.uiState.value
        assertEquals(ImportFailure.NO_TRANSACTIONS_FOUND, state.problem?.failure)
        assertTrue(state.offersDiagnostics)
        assertFalse(state.diagnosticsTicked)
        assertFalse(state.canSendDiagnostics)

        model.sendDiagnostics()

        assertEquals(1, imports.uploads.size, "an unticked offer sent something")
        assertFalse(imports.uploads.single().keepTextForDiagnostics)
    }

    @Test
    fun `ticking and sending re-sends with the flag once and says until when`() {
        val imports = FakeImports(parse = { ParsedStatement(importId = "i-1", textRetainedUntil = if (it.keepTextForDiagnostics) "2026-10-30" else null) })
        val model = model()
        model.bind("alice") { repositories(imports = imports) }
        model.upToFile()
        model.onFilePicked(file)

        model.onDiagnosticsTicked(true)
        model.sendDiagnostics()

        assertEquals(listOf(false, true), imports.uploads.map { it.keepTextForDiagnostics })
        val state = model.uiState.value
        assertTrue(assertNotNull(state.diagnosticsThanks).contains("Oct 30, 2026"))
        assertFalse(state.offersDiagnostics, "offered again after it was sent")
    }

    @Test
    fun `no diagnostics offer after a clean import or a quota refusal`() {
        val clean = model()
        clean.bind("alice") { repositories(imports = FakeImports(parse = { twoRows })) }
        clean.upToFile()
        clean.onFilePicked(file)
        assertFalse(clean.uiState.value.offersDiagnostics)

        val quota = model()
        quota.bind("alice") { repositories(imports = FakeImports(parse = { throw ApiException.ImportQuotaExceeded(1, null) })) }
        quota.upToFile()
        quota.onFilePicked(file)
        assertFalse(quota.uiState.value.offersDiagnostics)
    }

    // ── Rotation and the app being killed ───────────────────────────────

    @Test
    fun `the app killed mid-read reads the same file again for the same account`() {
        val saved = SavedStateHandle(
            mapOf(
                StatementImportViewModel.KEY_OWNER to "alice",
                StatementImportViewModel.KEY_ACCOUNT to "acct-1",
                StatementImportViewModel.KEY_FILE to file,
                StatementImportViewModel.KEY_IN_PROGRESS to true,
            ),
        )
        val reader = FakeReader()
        val model = model(saved)

        model.bind("alice") { repositories(imports = FakeImports(parse = { twoRows }), reader = reader) }

        assertEquals(listOf(file), reader.sources)
        assertEquals(ImportStep.DONE, model.uiState.value.step)
    }

    @Test
    fun `a photo that comes back before the screen is bound is still read`() {
        // Opening the camera is when Android kills the app. Coming back, the
        // photo arrives as soon as the picker is registered — before `bind`
        // has built the clients — so it must be kept for the restore to read.
        val saved = SavedStateHandle(
            mapOf(
                StatementImportViewModel.KEY_OWNER to "alice",
                StatementImportViewModel.KEY_ACCOUNT to "acct-1",
            ),
        )
        val reader = FakeReader()
        val model = model(saved)
        val photo = "content://com.humblesolutions.finai.captures/statement_captures/statement-1.jpg"

        model.onFilePicked(photo)
        model.bind("alice") { repositories(imports = FakeImports(parse = { twoRows }), reader = reader) }

        assertEquals(listOf(photo), reader.sources)
        assertEquals(ImportStep.DONE, model.uiState.value.step)
    }

    @Test
    fun `access to a picked file is given back when the import is done with it`() {
        val files = FakeFiles()
        val model = model()
        model.bind("alice") { repositories(imports = FakeImports(parse = { twoRows }), files = files) }
        model.upToFile()

        model.onFilePicked(file)

        // Kept only as long as a restore might need it, never for good.
        assertEquals(listOf(file), files.released)
    }

    @Test
    fun `access is given back when the file is abandoned or replaced`() {
        val files = FakeFiles()
        val model = model()
        model.bind("alice") {
            repositories(imports = FakeImports(parse = { throw ApiException.ParseFailed() }), files = files)
        }
        model.upToFile()
        model.onFilePicked(file)

        model.onFilePicked("content://files/second.pdf")
        model.chooseAnotherFile()
        model.onFilePicked("content://files/third.pdf")
        model.discard()

        assertEquals(listOf(file, "content://files/second.pdf", "content://files/third.pdf"), files.released)
    }

    @Test
    fun `someone else's half-finished import is never picked up`() {
        val saved = SavedStateHandle(
            mapOf(
                StatementImportViewModel.KEY_OWNER to "alice",
                StatementImportViewModel.KEY_ACCOUNT to "acct-1",
                StatementImportViewModel.KEY_FILE to file,
                StatementImportViewModel.KEY_IN_PROGRESS to true,
            ),
        )
        val reader = FakeReader()
        val model = model(saved)

        model.bind("bob") { repositories(reader = reader) }

        assertEquals(emptyList(), reader.sources)
        assertNull(model.uiState.value.fileUri)
        assertNull(model.uiState.value.accountId)
    }

    @Test
    fun `leaving on purpose forgets the file and the account`() {
        val saved = SavedStateHandle()
        val model = model(saved)
        model.bind("alice") { repositories(imports = FakeImports(parse = { twoRows })) }
        model.upToFile()
        model.onFilePicked(file)

        model.discard()

        assertEquals(ImportStep.CHOOSE_ACCOUNT, model.uiState.value.step)
        assertNull(saved.get<String>(StatementImportViewModel.KEY_FILE))
        assertNull(saved.get<String>(StatementImportViewModel.KEY_ACCOUNT))
    }

    // ── Fakes ───────────────────────────────────────────────────────────

    private var lastReader: FakeReader? = null

    private fun StatementImportViewModel.readerFor(): FakeReader = assertNotNull(lastReader)

    private fun repositories(
        accounts: FakeAccounts = FakeAccounts(),
        imports: FakeImports = FakeImports(parse = { twoRows }),
        consent: FakeConsent = FakeConsent(agreed = "ai-v1"),
        reader: FakeReader = FakeReader(),
        files: FakeFiles = FakeFiles(),
        transactions: FakeImportedTransactions = FakeImportedTransactions(),
        categories: FakeImportCategories = FakeImportCategories(),
    ): ImportRepositories {
        lastReader = reader
        lastTransactions = transactions
        return ImportRepositories(accounts, imports, consent, reader, files, transactions, categories)
    }

    private var lastTransactions: FakeImportedTransactions? = null

    /** Only `list` is reachable from the import; the rest belong to the queue. */
    class FakeImportedTransactions(
        var page: ReviewPage = ReviewPage(),
        var fail: ApiException? = null,
    ) : TransactionsRepository {
        val askedFor = mutableListOf<String?>()

        override suspend fun list(
            statementImportId: String?,
            needsReview: Boolean?,
            cursor: String?,
        ): ReviewPage {
            askedFor += statementImportId
            fail?.let { throw it }
            return page
        }

        override suspend fun create(entry: NewTransaction): Transaction = unreachable()

        override suspend fun review(cursor: String?): ReviewPage = unreachable()

        override suspend fun correct(id: String, patch: TransactionPatch): PatchOutcome = unreachable()

        override suspend fun confirm(id: String): PatchOutcome = unreachable()

        override suspend fun confirmAll(ids: List<String>): ConfirmOutcome = unreachable()

        override suspend fun delete(id: String): DeleteOutcome = unreachable()

        override fun close() = Unit

        private fun unreachable(): Nothing = error("the import screen does not call this")
    }

    class FakeImportCategories(
        private val categories: List<Category> = emptyList(),
    ) : CategoriesRepository {
        override suspend fun list(): List<Category> = categories

        override suspend fun create(name: String): Category = error("not called")

        override fun close() = Unit
    }

    private class FakeFiles : PickedFiles {
        val released = mutableListOf<String>()

        override fun release(uri: String) {
            released += uri
        }
    }

    private inner class FakeAccounts : AccountsRepository {
        override suspend fun list() = listOf(chequing)

        override suspend fun create(account: NewAccount) = Account(id = "acct-new", name = account.name, kind = account.kind, currency = "CAD")

        override fun close() = Unit
    }

    private inner class FakeReader(
        private val locked: String? = null,
        private val fail: Exception? = null,
    ) : StatementReader {
        var reads = 0
        val sources = mutableListOf<String>()
        val passwords = mutableListOf<String?>()

        override suspend fun read(
            source: String,
            password: String?,
            onPage: (completed: Int, total: Int) -> Unit,
        ): ExtractedDocument {
            sources += source
            passwords += password
            fail?.let { throw it }
            if (locked != null && password != locked) {
                throw StatementReadException.PasswordRequired(wrongPassword = password != null)
            }
            reads++
            onPage(1, 1)
            return statement
        }

        override fun close() = Unit
    }

    private class FakeImports(private val parse: (StatementUpload) -> ParsedStatement) : StatementImportRepository {
        val uploads = mutableListOf<StatementUpload>()
        val saved = mutableListOf<Pair<String, RowsToSave>>()

        override suspend fun parse(upload: StatementUpload): ParsedStatement {
            uploads += upload
            return parse.invoke(upload)
        }

        override suspend fun save(importId: String, rows: RowsToSave): SaveOutcome {
            saved += importId to rows
            return SaveOutcome(importId = importId, saved = rows.rows.size, needsReview = 1)
        }

        override fun close() = Unit
    }

    private class FakeConsent(var agreed: String? = null) : AiConsentRepository {
        var consents = 0

        override suspend fun policy() = AiPolicy(version = "ai-v1", body = "To read your statement…")

        override suspend fun consent(version: String) {
            consents++
            agreed = version
        }

        override suspend fun status() = AiConsentStatus(consented = agreed != null, version = "ai-v1")

        override fun close() = Unit
    }

    /** A whole import, carried through to DONE, reading rows from [transactions]. */
    private fun importedThrough(
        transactions: FakeImportedTransactions,
        categories: CategoriesRepository = FakeImportCategories(),
    ): StatementImportViewModel {
        val model = model()
        model.bind("alice") {
            repositories(transactions = transactions).let {
                ImportRepositories(it.accounts, it.imports, it.consent, it.reader, it.files, transactions, categories)
            }
        }
        model.upToFile()
        model.onFilePicked(file)
        return model
    }

    // ── What the import read ────────────────────────────────────────────

    @Test
    fun `a finished import asks for the rows it just created`() {
        val transactions = FakeImportedTransactions(
            page = ReviewPage(rows = listOf(Transaction(id = "r1", occurredOn = "2026-08-02"))),
        )
        val model = importedThrough(transactions)

        assertEquals(listOf<String?>("i-1"), transactions.askedFor.toList())
        assertEquals(listOf("r1"), model.uiState.value.imported.map { it.id })
    }

    @Test
    fun `a list that will not load leaves the import succeeded`() {
        // The import worked. Turning a finished import into an error screen
        // because a follow-up read failed would be a lie about what happened.
        val model = importedThrough(
            FakeImportedTransactions(fail = ApiException.Network(RuntimeException("offline"))),
        )

        val state = model.uiState.value
        assertEquals(ImportStep.DONE, state.step)
        assertTrue(state.summary.isNotEmpty())
        assertTrue(state.imported.isEmpty())
        assertNotNull(state.importedErrorKey)
    }

    @Test
    fun `the list can be asked for again without redoing the import`() {
        val transactions = FakeImportedTransactions(
            fail = ApiException.Network(RuntimeException("offline")),
        )
        val model = importedThrough(transactions)
        assertNotNull(model.uiState.value.importedErrorKey)

        transactions.fail = null
        transactions.page = ReviewPage(rows = listOf(Transaction(id = "r9")))
        model.reloadImported()

        assertEquals(listOf("r9"), model.uiState.value.imported.map { it.id })
        assertNull(model.uiState.value.importedErrorKey)
        assertEquals(2, transactions.askedFor.size, "the same import, asked for twice")
    }

    @Test
    fun `categories failing still shows the rows`() {
        // A name beside each row is a nicety; the rows are the point.
        val model = importedThrough(
            FakeImportedTransactions(page = ReviewPage(rows = listOf(Transaction(id = "r1")))),
            categories = object : CategoriesRepository {
                override suspend fun list(): List<Category> = throw ApiException.Network(RuntimeException("offline"))

                override suspend fun create(name: String): Category = error("not called")

                override fun close() = Unit
            },
        )

        assertEquals(listOf("r1"), model.uiState.value.imported.map { it.id })
        assertNull(model.uiState.value.importedErrorKey)
    }
}
