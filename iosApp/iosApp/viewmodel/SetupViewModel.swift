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
    @Published private(set) var draft = SetupDraft(income: "", monthlyExpense: "", obligations: [], debts: [], investments: [])
    @Published private(set) var currency = ""
    @Published private(set) var loading = true
    @Published private(set) var busy = false
    @Published private(set) var errorKey: String?
    @Published private(set) var editing: ItemList?
    /// The rows being edited, kept apart until they are kept or dropped.
    @Published var rows: [ItemDraft] = []

    private var repository: FinancialSetupRepository?

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
    /// The optional lists can be left empty, so they always offer a way past.
    var canSkip: Bool { !busy && step != .income }
    var canKeepRows: Bool { !busy && rows.indices.allSatisfy { rowBlock(at: $0) == nil } }

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
        repository = KtorFinancialSetupRepository(
            baseUrl: ApiConfig.shared.BASE_URL,
            tokens: SupabaseTokenSource(client: client),
            logging: logging
        )
        load()
    }

    func unbind() {
        repository?.close()
        repository = nil
    }

    /// What is already saved decides where the wizard opens.
    func load() {
        guard let repository else { return }
        loading = true
        errorKey = nil
        Task { [weak self] in
            do {
                let saved = try await repository.get()
                self?.currency = saved.currency
                self?.draft = SetupWizard.shared.draftFrom(setup: saved)
                self?.step = SetupWizard.shared.resumeAt(setup: saved)
            } catch {
                self?.errorKey = Self.messageKey(error)
            }
            self?.loading = false
        }
    }

    // MARK: - The figures

    func setIncome(_ value: String) {
        draft = draft.doCopy(
            income: value, monthlyExpense: draft.monthlyExpense,
            obligations: draft.obligations, debts: draft.debts, investments: draft.investments
        )
        errorKey = nil
    }

    func setExpense(_ value: String) {
        draft = draft.doCopy(
            income: draft.income, monthlyExpense: value,
            obligations: draft.obligations, debts: draft.debts, investments: draft.investments
        )
        errorKey = nil
    }

    func back() {
        errorKey = nil
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

    /// Past the optional part without filling it in: an empty list is the record (#29).
    func skip(onFinished: @escaping () -> Void) {
        guard canSkip else { return }
        switch step {
        case .expenses:
            draft = draft.doCopy(
                income: draft.income, monthlyExpense: draft.monthlyExpense,
                obligations: [], debts: draft.debts, investments: draft.investments
            )
        case .portfolio:
            draft = draft.doCopy(
                income: draft.income, monthlyExpense: draft.monthlyExpense,
                obligations: draft.obligations, debts: [], investments: []
            )
        default: break
        }
        // The mandatory pair is still required, so a skip only saves when the
        // step's own figure is answered; otherwise it just drops the extras.
        if block == nil { save { [weak self] in self?.advance(onFinished) } }
    }

    private func advance(_ onFinished: () -> Void) {
        switch step {
        case .income: step = .expenses
        case .expenses: step = .portfolio
        default: onFinished()
        }
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
        busy = true
        errorKey = nil
        Task { [weak self] in
            do {
                let saved = try await repository.save(setup: payload)
                // What came back is what is stored, so the screens show the
                // server's version rather than what was typed at it.
                self?.currency = saved.currency
                self?.draft = SetupWizard.shared.draftFrom(setup: saved)
                self?.busy = false
                onSaved()
            } catch {
                // The draft is untouched: a failed save must never lose figures.
                self?.busy = false
                self?.errorKey = Self.messageKey(error)
            }
        }
    }

    private static func messageKey(_ error: Error) -> String {
        ((error as NSError).userInfo["KotlinException"] as? ApiException)?.messageKey
            ?? Strings.shared.error_unexpected
    }
}
