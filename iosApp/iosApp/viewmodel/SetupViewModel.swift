import Foundation
import SharedLogic

/// Drives the financial setup wizard (PRD F1, ticket #17).
///
/// The repositories are built fresh in `bind()` and closed in `unbind()` — never
/// singletons (kmp-arch-v2). Which step may be left, and what the notice says,
/// are never decided here: `SetupWizard` answers both, and Android reads the
/// same rules.
///
/// **Continue does not wait.** The step slides the moment it is pressed and the
/// save runs behind it, shown only by a small loader, and only while a request
/// is really in flight: a draft the server already holds is not sent again. The
/// last step is the one exception — the gate clears on that save, so Complete
/// waits. Every save still carries the whole draft, so closing the app resumes
/// where it left off and a failed save keeps what was typed.
@MainActor
final class SetupViewModel: ObservableObject {

    /// Which itemised list is open, if any.
    enum ItemList { case obligations, debts, investments }

    /// The step on screen. Any step can be swiped to; Continue is what is gated.
    @Published private(set) var step: SetupStep = .income
    @Published private(set) var draft = SetupDraft(income: "", monthlyExpense: "", obligations: [], debts: [], investments: [])
    @Published private(set) var currency = ""
    /// The language figures are formatted in, from capabilities — never the
    /// device's. Blank until it has loaded, which formats as English.
    @Published private(set) var locale = ""
    /// The first load, while the app's coin loader covers the screen.
    @Published private(set) var loading = true
    /// Finishing: the save the gate clears on is under way.
    @Published private(set) var busy = false
    /// A save running behind the steps. Only the small loader says so.
    @Published private(set) var syncing = false
    @Published private(set) var errorKey: String?
    @Published private(set) var editing: ItemList?
    /// The rows being edited, kept apart until they are kept or dropped.
    @Published var rows: [ItemDraft] = []
    /// Whether this step's figure has been edited since the step was shown.
    @Published private(set) var touched = false
    /// The user cancelled setup: the wizard says it is required instead of a step.
    @Published private(set) var cancelled = false

    private var repository: FinancialSetupRepository?
    private var capabilities: CapabilitiesRepository?
    /// Bumped on every unbind. A request records the value it set out under and
    /// applies its reply only if that is still the value, so a slow answer for
    /// the last session cannot land in the next one (kmp-arch-v2).
    private var generation = 0
    /// What the server holds as far as this model knows — last loaded or last
    /// saved. A draft equal to it is not sent again.
    private var lastSaved: FinancialSetup?
    /// The save loop, while one is running.
    private var saver: Task<Void, Never>?

    #if DEBUG
    private let logging = true
    #else
    private let logging = false
    #endif

    // MARK: - Derived

    var fractionDigits: Int32 { Money.shared.fractionDigits(currency: currency) }
    var symbol: String { Money.shared.symbol(currency: currency) }

    /// Why Continue on this step cannot go ahead, or nil — shared with the notice.
    var block: SetupBlock? {
        SetupWizard.shared.blockingReason(step: step, draft: draft, fractionDigits: fractionDigits)
    }

    /// Continue waits for every mandatory figure up to this step, and for a finish.
    var canContinue: Bool { !busy && block == nil }

    /// What the notice under Continue says — the same reason, with the shared timing.
    var notice: SetupBlock? {
        SetupWizard.shared.notice(step: step, draft: draft, fractionDigits: fractionDigits, touched: touched)
    }

    /// Skip is drawn only on the step that is optional in full…
    var showsSkip: Bool { step.isOptional }

    /// …and goes through only once both mandatory figures are in.
    var canSkip: Bool {
        !busy && SetupWizard.shared.canSkip(step: step, draft: draft, fractionDigits: fractionDigits)
    }

    /// The small loader: a save under way. Never the coin.
    var showsSaving: Bool { syncing || busy }

    var canKeepRows: Bool { !busy && rows.indices.allSatisfy { rowBlock(at: $0) == nil } }

    /// What a list adds up to, written for reading, or nil while it is empty.
    func total(of list: ItemList) -> String? {
        guard let sum = SetupWizard.shared.total(
            items: items(of: list),
            fractionDigits: fractionDigits,
            debt: list == .debts
        ) else { return nil }
        let written = Money.shared.format(amount: sum, currency: currency, locale: locale)
        return written.isEmpty ? nil : written
    }

    func rowBlock(at index: Int) -> SetupBlock? {
        guard index < rows.count else { return nil }
        return SetupWizard.shared.itemBlock(
            item: rows[index],
            fractionDigits: fractionDigits,
            debt: editing == .debts
        )
    }

    func items(of list: ItemList) -> [ItemDraft] {
        switch list {
        case .obligations: return draft.obligations
        case .debts: return draft.debts
        case .investments: return draft.investments
        }
    }

    // MARK: - Lifecycle

    func bind() {
        guard repository == nil, let client = Supabase.shared.clientOrNull() else { return }
        let tokens = SupabaseTokenSource(client: client)
        repository = KtorFinancialSetupRepository(
            baseUrl: ApiConfig.shared.BASE_URL,
            tokens: tokens,
            logging: logging
        )
        capabilities = KtorCapabilitiesRepository(
            baseUrl: ApiConfig.shared.BASE_URL,
            tokens: tokens,
            logging: logging
        )
        load()
    }

    func unbind() {
        repository?.close()
        repository = nil
        capabilities?.close()
        capabilities = nil
        saver?.cancel()
        saver = nil
        lastSaved = nil
        // Any reply still on its way belongs to the session being left.
        generation += 1
        // Nothing of this user survives into the next one's session. The model
        // itself outlives a sign-out, so leaving the draft here would show one
        // person's figures to whoever signs in next if their load failed.
        draft = SetupDraft(income: "", monthlyExpense: "", obligations: [], debts: [], investments: [])
        currency = ""
        locale = ""
        step = .income
        editing = nil
        rows = []
        errorKey = nil
        touched = false
        cancelled = false
        busy = false
        syncing = false
        loading = true
    }

    /// What is already saved decides where the wizard opens.
    func load() {
        guard let repository else { return }
        let capabilities = self.capabilities
        let started = generation
        loading = true
        errorKey = nil
        Task { [weak self] in
            guard let self else { return }
            do {
                let saved = try await repository.get()
                // The currency and the language figures are written in come
                // from capabilities (ticket #17). The setup response names a
                // currency too, which stands in when capabilities cannot be
                // had: what this screen must not do is guess from the device.
                let payload = await Self.capabilitiesOrNil(capabilities)
                guard started == self.generation else { return }
                self.currency = payload.map { $0.currency.isEmpty ? saved.currency : $0.currency }
                    ?? saved.currency
                self.locale = payload?.locale ?? ""
                self.draft = SetupWizard.shared.draftFrom(setup: saved)
                self.step = SetupWizard.shared.resumeAt(setup: saved)
                self.touched = false
                // What was just loaded is what the server holds, so walking
                // through the steps without changing anything sends nothing.
                self.lastSaved = self.payload
            } catch {
                guard started == self.generation else { return }
                self.errorKey = Self.messageKey(error)
            }
            self.loading = false
        }
    }

    /// How the wizard asks for capabilities: it decorates the screen, so a
    /// failure to fetch it is not a failure of the screen.
    private static func capabilitiesOrNil(_ capabilities: CapabilitiesRepository?) async -> Capabilities? {
        guard let capabilities else { return nil }
        return try? await capabilities.fetch()
    }

    // MARK: - The figures

    func setIncome(_ value: String) {
        draft = draft.doCopy(
            income: value, monthlyExpense: draft.monthlyExpense,
            obligations: draft.obligations, debts: draft.debts, investments: draft.investments
        )
        errorKey = nil
        touched = true
    }

    func setExpense(_ value: String) {
        draft = draft.doCopy(
            income: draft.income, monthlyExpense: value,
            obligations: draft.obligations, debts: draft.debts, investments: draft.investments
        )
        errorKey = nil
        touched = true
    }

    func back() {
        errorKey = nil
        touched = false
        switch step {
        case .expenses: step = .income
        case .portfolio: step = .expenses
        default: break
        }
    }

    /// Moves on at once and saves behind the move. The last step instead
    /// finishes, which waits for its save: the gate clears on it.
    func continueStep(onFinished: @escaping () -> Void) {
        guard canContinue else { return }
        if step.isLast { return finish(onFinished) }
        step = step == .income ? .expenses : .portfolio
        touched = false
        errorKey = nil
        persist()
    }

    /// Past the last step without filling it in: an empty list is the record
    /// (#29). Offered only once both mandatory figures are in.
    func skip(onFinished: @escaping () -> Void) {
        guard canSkip else { return }
        draft = draft.doCopy(
            income: draft.income, monthlyExpense: draft.monthlyExpense,
            obligations: draft.obligations, debts: [], investments: []
        )
        touched = false
        finish(onFinished)
    }

    /// The user swiped. Any step may be looked at; Continue is what stays gated.
    func goTo(_ destination: SetupStep) {
        guard destination != step else { return }
        step = destination
        errorKey = nil
        touched = false
    }

    /// Cancel: the wizard says setup is required, keeping what was typed.
    func cancel() {
        cancelled = true
        editing = nil
        rows = []
        errorKey = nil
    }

    /// Back from the required-setup screen, to step 1.
    func resume() {
        cancelled = false
        step = .income
        errorKey = nil
        touched = false
    }

    // MARK: - The itemised lists

    func openList(_ list: ItemList) {
        editing = list
        let existing = items(of: list)
        rows = existing.isEmpty ? [ItemDraft(name: "", amount: "", minimumPayment: "", interestRatePercent: "")] : existing
        errorKey = nil
    }

    func addRow() {
        rows.append(ItemDraft(name: "", amount: "", minimumPayment: "", interestRatePercent: ""))
    }

    func removeRow(at index: Int) {
        guard rows.indices.contains(index) else { return }
        rows.remove(at: index)
    }

    /// Keeps the rows and saves them behind the step, so a list survives the app closing.
    func keepRows() {
        guard let list = editing, canKeepRows else { return }
        let kept = rows.filter { !($0.name.isEmpty && $0.amount.isEmpty) }
        switch list {
        case .obligations:
            draft = draft.doCopy(
                income: draft.income, monthlyExpense: draft.monthlyExpense,
                obligations: kept, debts: draft.debts, investments: draft.investments
            )
        case .debts:
            draft = draft.doCopy(
                income: draft.income, monthlyExpense: draft.monthlyExpense,
                obligations: draft.obligations, debts: kept, investments: draft.investments
            )
        case .investments:
            draft = draft.doCopy(
                income: draft.income, monthlyExpense: draft.monthlyExpense,
                obligations: draft.obligations, debts: draft.debts, investments: kept
            )
        }
        editing = nil
        rows = []
        persist()
    }

    /// Leaves the list as it was before it was opened.
    func discardRows() {
        editing = nil
        rows = []
        errorKey = nil
    }

    // MARK: - Saving

    /// Finishes: waits for the draft on screen to be stored, then hands back to
    /// onboarding, which reloads `/me`. A failed save stays here with its message
    /// and the figures intact.
    private func finish(_ onFinished: @escaping () -> Void) {
        let started = generation
        busy = true
        errorKey = nil
        let saving = persist()
        Task { [weak self] in
            await saving?.value
            guard let self, started == self.generation else { return }
            self.busy = false
            if let last = self.lastSaved, last == self.payload { onFinished() }
        }
    }

    /// Sends the draft as it is now, unless the server already holds exactly
    /// that. One loop at a time: a call while it runs returns the running loop,
    /// which reads the draft afresh before stopping, so a change made mid-save is
    /// sent right after rather than racing it.
    @discardableResult
    private func persist() -> Task<Void, Never>? {
        guard let repository else { return nil }
        if let saver { return saver }
        let started = generation
        let task = Task { [weak self] in
            guard let self else { return }
            defer { if started == self.generation { self.saver = nil } }
            do {
                while true {
                    let payload = self.payload
                    if let last = self.lastSaved, last == payload { break }
                    self.syncing = true
                    self.errorKey = nil
                    _ = try await repository.save(setup: payload)
                    guard started == self.generation else { return }
                    self.lastSaved = payload
                }
                self.syncing = false
            } catch {
                guard started == self.generation else { return }
                // The draft is untouched: a failed save must never lose figures.
                self.syncing = false
                self.errorKey = Self.messageKey(error)
            }
        }
        saver = task
        return task
    }

    private var payload: FinancialSetup {
        SetupWizard.shared.payload(draft: draft, currency: currency, fractionDigits: fractionDigits)
    }

    private static func messageKey(_ error: Error) -> String {
        ((error as NSError).userInfo["KotlinException"] as? ApiException)?.messageKey
            ?? Strings.shared.error_unexpected
    }
}
