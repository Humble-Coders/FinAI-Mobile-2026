import Foundation
import SharedLogic

/// Drives the financial setup wizard (PRD F1, ticket #17).
///
/// The repository is built fresh in `bind()` and closed in `unbind()` — never a
/// singleton (kmp-arch-v2). Which step to show and whether it may be left are
/// never decided here: `SetupWizard` answers both, and Android reads the same
/// rules.
///
/// **Every step saves.** The call replaces what the wizard owns, so each save
/// carries the whole draft; closing the app mid-way resumes where it left off,
/// and a failed save keeps what was typed.
@MainActor
final class SetupViewModel: ObservableObject {

    /// Which itemised list is open, if any.
    enum ItemList { case obligations, debts, investments }

    @Published private(set) var step: SetupStep = .income
    /// The furthest step reached. Swiping back over answered steps is free;
    /// swiping forward past one that is not answered is not, so the gate the
    /// Continue button enforces cannot be slid around.
    @Published private(set) var reached: SetupStep = .income
    @Published private(set) var draft = SetupDraft(income: "", monthlyExpense: "", obligations: [], debts: [], investments: [])
    @Published private(set) var currency = ""
    /// The language figures are formatted in, from capabilities — never the
    /// device's, so two people in one household read the same figures the same
    /// way. Blank until it has loaded, which formats as English.
    @Published private(set) var locale = ""
    @Published private(set) var loading = true
    @Published private(set) var busy = false
    @Published private(set) var errorKey: String?
    @Published private(set) var editing: ItemList?
    /// The rows being edited, kept apart until they are kept or dropped.
    @Published var rows: [ItemDraft] = []
    /// Whether this step's figure has been edited since the step was shown.
    ///
    /// The notice waits for it. A step that opens with "Enter your monthly
    /// income to continue." in red, before the user has typed anything, reads
    /// as a mistake they have already made; the disabled button says the same
    /// thing without the accusation.
    @Published private(set) var touched = false

    private var repository: FinancialSetupRepository?
    private var capabilities: CapabilitiesRepository?
    /// Bumped on every unbind. A request records the value it set out under
    /// and applies its reply only if that is still the value, so a slow answer
    /// for the last session cannot land in the next one (kmp-arch-v2).
    private var generation = 0

    #if DEBUG
    private let logging = true
    #else
    private let logging = false
    #endif

    // MARK: - Derived

    var fractionDigits: Int32 { Money.shared.fractionDigits(currency: currency) }
    var symbol: String { Money.shared.symbol(currency: currency) }

    /// Why this step cannot be left, or nil — the one rule the button and the
    /// notice share.
    var block: SetupBlock? {
        SetupWizard.shared.blockingReason(step: step, draft: draft, fractionDigits: fractionDigits)
    }

    var canContinue: Bool { !busy && block == nil }

    /// What the notice renders.
    ///
    /// A figure that is already wrong is said whenever it is on screen; a
    /// question merely unanswered waits until the user has typed, so a step
    /// never opens by telling them off for not having started.
    var notice: SetupBlock? {
        guard let block else { return nil }
        return (touched || !block.isUnanswered) ? block : nil
    }

    /// Only a step that is optional in full offers a Skip (ticket #17).
    ///
    /// The mandatory figures cannot be skipped, so no control claims they can —
    /// and because the step this leaves has nothing that can refuse, a Skip that
    /// is drawn can never turn out to do nothing when it is pressed.
    var canSkip: Bool { !busy && step.isOptional }
    var canKeepRows: Bool { !busy && rows.indices.allSatisfy { rowBlock(at: $0) == nil } }

    /// What a list adds up to, written for reading, or nil while it is empty.
    ///
    /// The row shows the figure rather than how many rows are behind it: the
    /// total is what the user came to check.
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
        // Any reply still on its way belongs to the session being left.
        generation += 1
        // Nothing of this user survives into the next one's session. The model
        // itself outlives a sign-out, so leaving the draft here would show one
        // person's figures to whoever signs in next if their load failed.
        draft = SetupDraft(income: "", monthlyExpense: "", obligations: [], debts: [], investments: [])
        currency = ""
        locale = ""
        step = .income
        reached = .income
        editing = nil
        rows = []
        errorKey = nil
        touched = false
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
                let resume = SetupWizard.shared.resumeAt(setup: saved)
                self.step = resume
                self.reached = resume
                self.touched = false
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

    /// Saves and moves on. The last step hands back to onboarding, which
    /// reloads `/me` — the gate clears once income and expense are stored.
    func continueStep(onFinished: @escaping () -> Void) {
        guard canContinue else { return }
        save { [weak self] in self?.advance(onFinished) }
    }

    /// Past the last step without filling it in: an empty list is the record (#29).
    ///
    /// Only a step that is optional in full offers this, so there is nothing
    /// left here that could refuse — dropping the lists drops the only thing on
    /// the step that can block. A Skip the user can see always goes through.
    func skip(onFinished: @escaping () -> Void) {
        guard canSkip, step == .portfolio else { return }
        draft = draft.doCopy(
            income: draft.income, monthlyExpense: draft.monthlyExpense,
            obligations: draft.obligations, debts: [], investments: []
        )
        touched = false
        save { [weak self] in self?.advance(onFinished) }
    }

    private func advance(_ onFinished: () -> Void) {
        switch step {
        case .income: step = .expenses
        case .expenses: step = .portfolio
        default: return onFinished()
        }
        touched = false
        if step.ordinal > reached.ordinal { reached = step }
    }

    /// The user swiped. Only as far as the wizard has already been: a step whose
    /// figure is still missing is not reachable by sliding past it.
    func goTo(_ destination: SetupStep) {
        guard destination.ordinal <= reached.ordinal else { return }
        step = destination
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

    /// Keeps the rows and saves them, so a list survives the app closing.
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
        save {}
    }

    /// Leaves the list as it was before it was opened.
    func discardRows() {
        editing = nil
        rows = []
        errorKey = nil
    }

    // MARK: - Plumbing

    private func save(onSaved: @escaping () -> Void) {
        guard let repository else { return }
        let payload = SetupWizard.shared.payload(
            draft: draft, currency: currency, fractionDigits: fractionDigits
        )
        let started = generation
        busy = true
        errorKey = nil
        Task { [weak self] in
            do {
                let saved = try await repository.save(setup: payload)
                // Someone else's wizard now: neither their figures nor a step
                // forward belong on it.
                guard let self, started == self.generation else { return }
                // What came back is what is stored, so the screens show the
                // server's version rather than what was typed at it.
                self.currency = saved.currency
                self.draft = SetupWizard.shared.draftFrom(setup: saved)
                self.busy = false
                onSaved()
            } catch {
                guard let self, started == self.generation else { return }
                // The draft is untouched: a failed save must never lose figures.
                self.busy = false
                self.errorKey = Self.messageKey(error)
            }
        }
    }

    private static func messageKey(_ error: Error) -> String {
        ((error as NSError).userInfo["KotlinException"] as? ApiException)?.messageKey
            ?? Strings.shared.error_unexpected
    }
}
