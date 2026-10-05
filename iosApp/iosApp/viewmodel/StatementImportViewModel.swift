import Foundation
import SharedLogic

/// Drives the statement import (#31): account, file, consent, reading,
/// sending, saving, and every way it can end.
///
/// **No extraction here.** `IOSStatementReader` reads; `ImportStatement`
/// redacts and sends; `StatementImportFlow` decides what every outcome says and
/// offers — the same rules Android reads. This model moves between steps and
/// keeps the account and the file in `snapshot`, which the view puts in scene
/// storage, so the app coming back after iOS reclaimed it reads the same file
/// again. The document and any password are held in memory only.
@MainActor
final class StatementImportViewModel: ObservableObject, NewAccountHost {

    @Published private(set) var step: ImportStep = .chooseAccount
    @Published private(set) var accounts: [Account] = []
    @Published private(set) var accountsLoading = true
    @Published private(set) var accountsErrorKey: String?
    /// Chosen, never defaulted: the wrong account breaks dedup for both.
    @Published private(set) var accountId: String?
    @Published private(set) var newAccount: NewAccountDraft?
    @Published private(set) var newAccountTouched = false
    @Published private(set) var creatingAccount = false
    @Published private(set) var newAccountErrorKey: String?
    /// The picked file, copied into the app's own folder.
    @Published private(set) var fileURL: URL?
    @Published private(set) var passwordWrong = false
    @Published private(set) var page = 0
    @Published private(set) var pages = 0
    @Published private(set) var policy: AiPolicy?
    /// Unticked until the person ticks it — express consent, nothing pre-ticked.
    @Published var consentTicked = false
    @Published private(set) var consentBusy = false
    @Published private(set) var consentErrorKey: String?
    @Published private(set) var problem: ImportProblem?
    @Published private(set) var canResend = false
    /// The "help us fix this" box — unticked every time, never remembered.
    @Published var diagnosticsTicked = false
    @Published private(set) var diagnosticsSent = false
    @Published private(set) var diagnosticsThanks: String?
    @Published private(set) var summary: [String] = []
    @Published private(set) var needsReview = 0
    /// The import just finished, so its rows can be asked for by id.
    @Published private(set) var importId: String?
    /// What the import actually produced — "imported 24" is a claim the person
    /// cannot check, and the one question they have is whether the categories
    /// are right. These are the 24.
    @Published private(set) var imported: [SharedLogic.Transaction] = []
    @Published private(set) var importedLoading = false
    /// A list that would not load must not make a successful import look failed.
    @Published private(set) var importedErrorKey: String?
    @Published private(set) var importedCategories: [SharedLogic.Category] = []
    /// A read was under way — so a restore reads the file again.
    @Published private(set) var inProgress = false

    private var accountsRepository: AccountsRepository?
    private var importsRepository: StatementImportRepository?
    private var consentRepository: AiConsentRepository?
    /// Read only after a finished import, for the rows it produced.
    private var transactionsRepository: TransactionsRepository?
    private var categoriesRepository: CategoriesRepository?
    private let reader = IOSStatementReader()
    private var owner: String?
    private var generation = 0
    private var work: Task<Void, Never>?
    private var document: ExtractedDocument?
    private var parsed: ParsedStatement?
    private var password: String?

    #if DEBUG
    private let logging = true
    #else
    private let logging = false
    #endif

    // MARK: - Derived (the same gates as Android's UI state)

    var account: Account? { accounts.first { $0.id == accountId } }
    var canContinueFromAccount: Bool { account != nil }
    var working: Bool { step == .reading || step == .sending || step == .saving }
    var problemMessage: String? { problem.map { StatementImportFlow.shared.message(problem: $0) } }
    var offersRetry: Bool { problem?.failure.canRetry ?? false }
    var offersManualEntry: Bool { problem?.failure.offersManualEntry ?? false }
    var offersDiagnostics: Bool { (problem?.failure.offersDiagnostics ?? false) && canResend && !diagnosticsSent }
    var canSendDiagnostics: Bool { offersDiagnostics && diagnosticsTicked }
    var canAgree: Bool { policy != nil && consentTicked && !consentBusy }

    /// What the one coin loader says under the coin.
    var loaderCaption: String? {
        switch step {
        case .reading: return StatementImportFlow.shared.readingProgress(page: Int32(page), pages: Int32(pages))
        case .sending: return L.t(Strings.shared.import_sending)
        case .saving: return L.t(Strings.shared.import_saving)
        default: return nil
        }
    }

    var newAccountNotice: NewAccountBlock? {
        newAccount.flatMap { NewAccountForm.shared.notice(draft: $0, touched: newAccountTouched) }
    }

    var canCreateAccount: Bool {
        guard let newAccount, !creatingAccount else { return false }
        return NewAccountForm.shared.blockingReason(draft: newAccount) == nil
    }

    // MARK: - Lifecycle

    func bind(userId: String, restoring snapshot: String) {
        guard !userId.isEmpty else { return }
        if owner != userId {
            reset()
            owner = userId
            restore(snapshot, for: userId)
        }
        guard accountsRepository == nil, let client = Supabase.shared.clientOrNull() else { return }
        let tokens = SupabaseTokenSource(client: client)
        let base = ApiConfig.shared.BASE_URL
        accountsRepository = KtorAccountsRepository(baseUrl: base, tokens: tokens, logging: logging)
        importsRepository = KtorStatementImportRepository(baseUrl: base, tokens: tokens, logging: logging)
        consentRepository = KtorAiConsentRepository(baseUrl: base, tokens: tokens, logging: logging)
        transactionsRepository = KtorTransactionsRepository(baseUrl: base, tokens: tokens, logging: logging)
        categoriesRepository = KtorCategoriesRepository(baseUrl: base, tokens: tokens, logging: logging)
        loadAccounts()
    }

    func unbind() {
        transactionsRepository?.close()
        transactionsRepository = nil
        categoriesRepository?.close()
        categoriesRepository = nil
        accountsRepository?.close()
        accountsRepository = nil
        importsRepository?.close()
        importsRepository = nil
        consentRepository?.close()
        consentRepository = nil
        work?.cancel()
        generation += 1
    }

    private func reset() {
        work?.cancel()
        generation += 1
        step = .chooseAccount
        accounts = []
        accountsLoading = true
        accountsErrorKey = nil
        accountId = nil
        newAccount = nil
        fileURL = nil
        clearImportState()
        owner = nil
    }

    private func clearImportState() {
        document = nil
        parsed = nil
        password = nil
        passwordWrong = false
        problem = nil
        canResend = false
        diagnosticsTicked = false
        diagnosticsSent = false
        diagnosticsThanks = nil
        summary = []
        needsReview = 0
        importId = nil
        imported = []
        importedLoading = false
        importedErrorKey = nil
        importedCategories = []
        inProgress = false
        policy = nil
        consentTicked = false
        consentErrorKey = nil
    }

    // MARK: - What the import read (the same rules as Android's UI state)

    /// The import screen does not fetch capabilities, so figures here are
    /// grouped the English way. Android's import state defaults the same.
    /// Only the thousands separator differs, and only in French — worth a
    /// follow-up rather than a second client on this screen.
    private let locale = "en"

    /// The currency these rows are in — the account's, since one import is one account.
    private var importedCurrency: String {
        imported.first?.currency ?? accounts.first { $0.id == accountId }?.currency ?? ""
    }

    private var importedDigits: Int32 { Money.shared.fractionDigits(currency: importedCurrency) }

    private func importedMoney(_ amount: String) -> String {
        Money.shared.format(amount: amount, currency: importedCurrency, locale: locale)
    }

    /// What left the account across the import, or nil when nothing did.
    var totalOut: String? {
        ImportedRows.shared.totalOut(rows: imported, fractionDigits: importedDigits).map(importedMoney)
    }

    /// What arrived, or nil when nothing did.
    var totalIn: String? {
        ImportedRows.shared.totalIn(rows: imported, fractionDigits: importedDigits).map(importedMoney)
    }

    /// "3 need you", or nil when the model filed every row.
    var waitingLabel: String? {
        let count = Int(ImportedRows.shared.waitingCount(rows: imported))
        guard count > 0 else { return nil }
        if count == 1 { return L.t(Strings.shared.import_extracted_waiting_one) }
        return L.t(Strings.shared.import_extracted_waiting, String(count))
    }

    /// The days of the import, newest first.
    var importedDays: [ImportedRows.Day] { ImportedRows.shared.byDate(rows: imported) }

    /// A day's heading, as a person reads a date.
    func dateLabel(_ iso: String) -> String {
        Dates.shared.parse(iso: iso).map { Dates.shared.display(date: $0) } ?? iso
    }

    func titleOf(_ row: SharedLogic.Transaction) -> String { ImportedRows.shared.titleOf(row: row) }

    func amountLabel(_ row: SharedLogic.Transaction) -> String {
        Money.shared.format(amount: row.amount, currency: row.currency, locale: locale)
    }

    /// What it was filed as, or that nothing filed it — the useful case.
    /// Nil when it is filed but the names are not to hand: drawn as nothing, never as unfiled.
    func categoryLabel(_ row: SharedLogic.Transaction) -> String? {
        ImportedRows.shared.categoryLabel(
            row: row,
            categories: importedCategories,
            unfiled: L.t(Strings.shared.import_extracted_uncategorised)
        )
    }

    func isFiled(_ row: SharedLogic.Transaction) -> Bool { ImportedRows.shared.isFiled(row: row) }

    /// The whole row as one sentence, so VoiceOver announces it once instead of
    /// stopping at the name, the category and the amount in turn.
    func rowDescription(_ row: SharedLogic.Transaction) -> String {
        L.t(
            Strings.shared.import_extracted_row,
            titleOf(row), categoryLabel(row) ?? "", amountLabel(row)
        )
    }

    /// Try the list again after it failed; the import itself already succeeded.
    func reloadImported() {
        guard let importId else { return }
        loadImported(importId)
    }

    /**
     The rows this import produced, for the result screen.

     Asked for by the import's own id, so the list is exactly what just happened
     rather than the household's whole ledger. Both kinds: the rows flagged for
     review AND the ones the model filed with confidence, which are the ones
     nobody has ever been shown and the only way to answer the question somebody
     actually has — did it get my categories right.

     A failure here leaves the import succeeded and the summary standing. The
     rows are worth showing and not worth turning a finished import into an
     error screen over.
     */
    private func loadImported(_ importId: String) {
        guard let transactionsRepository, !importId.isEmpty else { return }
        let categoriesRepository = self.categoriesRepository
        let started = generation
        importedLoading = true
        importedErrorKey = nil
        Task { [weak self] in
            guard let self else { return }
            do {
                let page = try await transactionsRepository.list(
                    statementImportId: importId, month: nil, needsReview: nil, cursor: nil
                )
                // A name beside each row is a nicety; the rows are the point.
                // Once more if the first try fails: without the names every row
                // would read as unfiled. Kept from before if both fail.
                var categories = try? await categoriesRepository?.list()
                if categories == nil { categories = try? await categoriesRepository?.list() }
                guard started == self.generation else { return }
                self.imported = page.rows
                if let categories { self.importedCategories = categories }
                self.importedLoading = false
                self.importedErrorKey = nil
            } catch {
                guard started == self.generation else { return }
                self.importedLoading = false
                self.importedErrorKey = Self.messageKey(error)
            }
        }
    }

    /// Leaving on purpose: the file, the document and any password go; the
    /// next visit starts at the account step. Accounts stay loaded.
    func discard() {
        work?.cancel()
        generation += 1
        step = .chooseAccount
        accountId = nil
        fileURL = nil
        clearImportState()
        ImportFiles.clear()
        loadAccounts()
    }

    // MARK: - The account

    func loadAccounts() {
        guard let accountsRepository else { return }
        let started = generation
        accountsLoading = true
        accountsErrorKey = nil
        Task { [weak self] in
            guard let self else { return }
            do {
                let list = try await accountsRepository.list()
                guard started == self.generation else { return }
                self.accounts = list
                self.accountsLoading = false
                // A remembered choice that no longer exists goes back to unanswered.
                if let id = self.accountId, !list.contains(where: { $0.id == id }) { self.accountId = nil }
                self.resumeAfterRestore()
            } catch {
                guard started == self.generation else { return }
                self.accountsLoading = false
                self.accountsErrorKey = Self.messageKey(error)
            }
        }
    }

    func chooseAccount(_ id: String) { accountId = id }

    func continueFromAccount() {
        guard canContinueFromAccount else { return }
        step = .chooseFile
    }

    func backToAccount() {
        guard !working else { return }
        step = .chooseAccount
    }

    func openNewAccount() {
        newAccount = NewAccountDraft(name: "", kind: nil)
        newAccountTouched = false
        newAccountErrorKey = nil
    }

    func setNewAccountName(_ name: String) {
        newAccount = NewAccountDraft(name: name, kind: newAccount?.kind)
        newAccountTouched = true
        newAccountErrorKey = nil
    }

    func chooseNewAccountKind(_ kind: AccountKind) {
        newAccount = NewAccountDraft(name: newAccount?.name ?? "", kind: kind)
        newAccountTouched = true
        newAccountErrorKey = nil
    }

    func cancelNewAccount() {
        guard !creatingAccount else { return }
        newAccount = nil
        newAccountTouched = false
        newAccountErrorKey = nil
    }

    /// Adds the account and chooses it — selectable without leaving the flow.
    func createAccount() {
        guard let accountsRepository, let newAccount, !creatingAccount else { return }
        guard let request = NewAccountForm.shared.request(draft: newAccount) else {
            newAccountTouched = true
            return
        }
        let started = generation
        creatingAccount = true
        newAccountErrorKey = nil
        Task { [weak self] in
            guard let self else { return }
            do {
                let created = try await accountsRepository.create(account: request)
                guard started == self.generation else { return }
                self.creatingAccount = false
                self.newAccount = nil
                self.accounts.append(created)
                self.accountId = created.id
            } catch {
                guard started == self.generation else { return }
                self.creatingAccount = false
                self.newAccountErrorKey = Self.messageKey(error)
            }
        }
    }

    // MARK: - The file, and reading it

    /// A file was picked (and copied into the app's folder): read it straight away.
    func filePicked(_ url: URL) {
        guard !working, accountId != nil else { return }
        work?.cancel()
        fileURL = url
        clearImportState()
        read()
    }

    func submitPassword(_ value: String) {
        guard step == .password, !value.isEmpty else { return }
        password = value
        read()
    }

    func chooseAnotherFile() {
        guard !working else { return }
        work?.cancel()
        fileURL = nil
        clearImportState()
        ImportFiles.clear()
        step = .chooseFile
    }

    /// Try the same file again: resend if it was already read, else read it again.
    func retry() {
        guard !working, offersRetry else { return }
        if let parsed { save(parsed) } else if let document { send(document, keepText: false) } else { read() }
    }

    private func read() {
        guard let url = fileURL else { return }
        let started = generation
        inProgress = true
        step = .reading
        page = 0
        pages = 0
        problem = nil
        let password = self.password
        work = Task { [weak self] in
            guard let self else { return }
            do {
                let read = try await self.reader.read(url: url, password: password) { page, pages in
                    Task { @MainActor [weak self] in
                        guard let self, started == self.generation else { return }
                        self.page = page
                        self.pages = pages
                    }
                }
                guard started == self.generation else { return }
                self.password = nil
                self.document = read
                self.send(read, keepText: false)
            } catch let error as IOSStatementReader.ReadError {
                guard started == self.generation else { return }
                self.handle(error)
            } catch {
                guard started == self.generation, !Task.isCancelled else { return }
                self.fail(error)
            }
        }
    }

    private func handle(_ error: IOSStatementReader.ReadError) {
        inProgress = false
        switch error {
        case .passwordRequired(let wrong):
            passwordWrong = wrong
            step = .password
        case .unsupported:
            show(ImportProblem(failure: .unsupportedFile, resetsAt: nil))
        case .nothingReadable:
            show(ImportProblem(failure: .nothingReadable, resetsAt: nil))
        case .tooManyPages:
            show(ImportProblem(failure: .tooManyPages, resetsAt: nil))
        }
    }

    private func send(_ read: ExtractedDocument, keepText: Bool) {
        // Never a silent return: that left the screen on "reading" with the
        // loader up and Back disabled. Say so, and let a retry send it once
        // the clients are back.
        guard let importsRepository, let accountId else { return unbound() }
        let started = generation
        step = .sending
        problem = nil
        canResend = true
        work = Task { [weak self] in
            guard let self else { return }
            do {
                let result = try await ImportStatement(imports: importsRepository).execute(
                    document: read,
                    accountId: accountId,
                    keepTextForDiagnostics: keepText,
                    // Every account is in the household's currency, so any of
                    // them answers when none is chosen yet.
                    accountCurrency: (accounts.first { $0.id == accountId } ?? accounts.first)?.currency,
                    onRedacted: { _ in }
                )
                guard started == self.generation else { return }
                if keepText {
                    self.diagnosticsSent = true
                    self.diagnosticsThanks = StatementImportFlow.shared.diagnosticsThanks(retainedUntil: result.textRetainedUntil)
                }
                if let empty = StatementImportFlow.shared.problemAfterParse(parsed: result) {
                    self.show(empty)
                } else {
                    self.parsed = result
                    self.save(result)
                }
            } catch {
                guard started == self.generation, !Task.isCancelled else { return }
                if let kotlin = Self.kotlin(error), StatementImportFlow.shared.needsConsent(error: kotlin) {
                    self.askForConsent(kotlin as? ApiException.AiPolicyChanged)
                } else {
                    self.fail(error)
                }
            }
        }
    }

    private func save(_ result: ParsedStatement) {
        guard let importsRepository, let accountId else { return unbound() }
        let started = generation
        step = .saving
        problem = nil
        work = Task { [weak self] in
            guard let self else { return }
            do {
                let outcome = try await importsRepository.save(
                    importId: result.importId,
                    rows: StatementImportFlow.shared.rowsToSave(accountId: accountId, parsed: result)
                )
                guard started == self.generation else { return }
                self.inProgress = false
                self.document = nil
                self.canResend = false
                self.summary = StatementImportFlow.shared.summary(parsed: result, saved: outcome)
                self.needsReview = Int(outcome.needsReview)
                self.importId = outcome.importId
                self.step = .done
                ImportFiles.clear()
                // Home shows figures derived from these rows, and the server
                // has confirmed them. Announced here rather than optimistically
                // on send, so a refresh never shows the figures it already had.
                LedgerChanged.announce()
                self.loadImported(outcome.importId)
            } catch {
                guard started == self.generation, !Task.isCancelled else { return }
                self.fail(error)
            }
        }
    }

    /// The clients are gone mid-import (the screen was away). A retryable
    /// failure, not a stuck loader: the document is still in memory, so Try
    /// again sends it once `bind` has rebuilt them.
    private func unbound() {
        show(ImportProblem(failure: .other, resetsAt: nil))
    }

    private func fail(_ error: Error) {
        let problem = Self.kotlin(error).flatMap { StatementImportFlow.shared.problemFor(error: $0) }
            ?? ImportProblem(failure: .other, resetsAt: nil)
        show(problem)
    }

    private func show(_ problem: ImportProblem) {
        inProgress = false
        self.problem = problem
        canResend = document != nil
        step = .failed
    }

    // MARK: - Consent

    private func askForConsent(_ changed: ApiException.AiPolicyChanged?) {
        guard let consentRepository else { return }
        let started = generation
        inProgress = false
        step = .consent
        consentTicked = false
        consentBusy = true
        consentErrorKey = changed?.messageKey
        Task { [weak self] in
            guard let self else { return }
            do {
                let policy = try await consentRepository.policy()
                guard started == self.generation else { return }
                self.policy = policy
                self.consentBusy = false
            } catch {
                guard started == self.generation else { return }
                self.consentBusy = false
                self.consentErrorKey = Self.messageKey(error)
            }
        }
    }

    /// Records consent to the version shown, then sends the statement it was asked for.
    func agree() {
        guard let consentRepository, let policy, canAgree else { return }
        let started = generation
        consentBusy = true
        consentErrorKey = nil
        Task { [weak self] in
            guard let self else { return }
            do {
                try await consentRepository.consent(version: policy.version)
                guard started == self.generation else { return }
                self.consentBusy = false
                self.consentTicked = false
                if let document = self.document { self.send(document, keepText: false) } else { self.read() }
            } catch {
                guard started == self.generation else { return }
                if let changed = Self.kotlin(error) as? ApiException.AiPolicyChanged {
                    self.askForConsent(changed)
                } else {
                    self.consentBusy = false
                    self.consentErrorKey = Self.messageKey(error)
                }
            }
        }
    }

    /// "Not now": nothing is sent, and the file can be imported later.
    func declineConsent() {
        consentTicked = false
        consentErrorKey = nil
        step = .chooseFile
    }

    // MARK: - Diagnostics

    /// The person's explicit yes: send this import's redacted text again with
    /// the flag on. Nothing is sent unless the box was ticked.
    func sendDiagnostics() {
        guard let document, canSendDiagnostics else { return }
        send(document, keepText: true)
    }

    // MARK: - Scene storage

    var snapshot: String {
        let value = Snapshot(owner: owner ?? "", accountId: accountId, filePath: fileURL?.path, inProgress: inProgress)
        guard let data = try? JSONEncoder().encode(value) else { return "" }
        return String(decoding: data, as: UTF8.self)
    }

    private func restore(_ snapshot: String, for userId: String) {
        guard let value = try? JSONDecoder().decode(Snapshot.self, from: Data(snapshot.utf8)),
              value.owner == userId else { return }
        accountId = value.accountId
        if let path = value.filePath, FileManager.default.fileExists(atPath: path) {
            fileURL = URL(fileURLWithPath: path)
        }
        inProgress = value.inProgress && fileURL != nil
    }

    /// Back after iOS reclaimed the app mid-import: the choice survived, the
    /// work did not, so the same file is read again.
    private func resumeAfterRestore() {
        if inProgress, !working, accountId != nil, fileURL != nil {
            read()
        } else if step == .chooseAccount, accountId != nil, fileURL != nil {
            step = .chooseFile
        }
    }

    private struct Snapshot: Codable {
        let owner: String
        let accountId: String?
        let filePath: String?
        let inProgress: Bool
    }

    private static func kotlin(_ error: Error) -> KotlinThrowable? {
        (error as NSError).userInfo["KotlinException"] as? KotlinThrowable
    }

    private static func messageKey(_ error: Error) -> String {
        (kotlin(error) as? ApiException)?.messageKey ?? Strings.shared.error_unexpected
    }
}

/// The app's own folder for the statement being imported: a copy of the picked
/// file, or the photo just taken. It is the statement, so it never outlives the
/// import — cleared on finishing, choosing another file, or leaving.
enum ImportFiles {
    private static var folder: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("statement-import", isDirectory: true)
    }

    /// Copies a picked file in, so it can still be read after the app is
    /// restored — access to the original ends with the picker.
    static func adopt(_ url: URL) throws -> URL {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        clear()
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let copy = folder.appendingPathComponent("statement." + (url.pathExtension.isEmpty ? "pdf" : url.pathExtension.lowercased()))
        try FileManager.default.copyItem(at: url, to: copy)
        // The same protection a photo gets: this is the statement, readable
        // only while the phone is unlocked.
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: copy.path)
        return copy
    }

    /// Writes a photo (from the library or the camera) in as a JPEG.
    static func write(_ data: Data) throws -> URL {
        clear()
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let file = folder.appendingPathComponent("statement.jpg")
        try data.write(to: file, options: .completeFileProtection)
        return file
    }

    static func clear() {
        try? FileManager.default.removeItem(at: folder)
    }
}
