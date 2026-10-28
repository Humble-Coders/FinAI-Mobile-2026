import Foundation
import SharedLogic

/**
 Your plan and Your debts, and every change to them (#76). Android's
 `PlanViewModel`.

 One model for both screens: the strategy switch on Your debts may need the
 choices editor of Your plan (see `chooseStrategy`), and both read the same
 plan. After every write the plan is read again — one change moves every
 figure, and those are the server's. Every sentence comes from shared
 `YourPlan` and `DebtEdit`, so this screen and Android's cannot disagree.

 What is being typed survives the scene going away, with the owner in the
 snapshot so the bind that follows a restore keeps it.
 */
@MainActor
final class PlanViewModel: ObservableObject {

    enum Page: String { case plan, debts }

    @Published var page: Page = .plan
    @Published private(set) var plan: Plan?
    @Published private(set) var debts: DebtsPage?
    @Published private(set) var accounts: [Account] = []
    @Published private(set) var disclaimer: Terms?
    @Published private(set) var currency = ""
    @Published private(set) var locale = "en"
    @Published private(set) var today = ManualEntry.shared.today()

    @Published private(set) var loading = true
    @Published private(set) var refreshing = false
    @Published private(set) var loadFailed = false
    @Published private(set) var errorKey: String?
    /// Why the strategy switch was refused.
    @Published private(set) var noticeKey: String?

    // MARK: The choices — Swift cannot `copy()` a Kotlin draft, so its fields live here
    @Published private(set) var choosing = false
    /// No buffer step since 2026-10-28: always false, kept for the shared draft's shape.
    @Published var hasBuffer = false { didSet { choicesErrorKey = nil } }
    @Published var buffer = "" { didSet { choicesErrorKey = nil } }
    @Published var goalShare = "" { didSet { choicesErrorKey = nil } }
    @Published var rateLine = "" { didSet { choicesErrorKey = nil } }
    @Published var strategy: PlanStrategy = .avalanche { didSet { choicesErrorKey = nil } }
    @Published private(set) var savingChoices = false
    @Published private(set) var choicesErrorKey: String?
    @Published private(set) var switchingStrategy = false

    // MARK: A debt's details
    @Published private(set) var editingDebtId: String?
    @Published var debtName = "" { didSet { debtErrorKey = nil } }
    @Published var debtKind: DebtKind? { didSet { debtErrorKey = nil } }
    @Published var minimum = "" { didSet { debtErrorKey = nil } }
    @Published var rate = "" { didSet { debtErrorKey = nil } }
    @Published var promoRate = "" { didSet { debtErrorKey = nil } }
    @Published var promoEndsOn: String? { didSet { debtErrorKey = nil } }
    @Published var accountId: String? { didSet { debtErrorKey = nil } }
    @Published private(set) var savingDebt = false
    @Published private(set) var debtErrorKey: String?

    // MARK: Update balance
    @Published private(set) var balanceDebtId: String?
    @Published var balanceAmount = "" { didSet { balanceErrorKey = nil } }
    @Published private(set) var savingBalance = false
    @Published private(set) var balanceErrorKey: String?

    private var planRepository: PlanRepository?
    private var debtsRepository: DebtsRepository?
    private var accountsRepository: AccountsRepository?
    private var capabilitiesRepository: CapabilitiesRepository?
    private var owner: String?
    private var generation = 0

    #if DEBUG
    private let logging = true
    #else
    private let logging = false
    #endif

    // MARK: Binding

    func bind(userId: String) {
        guard !userId.isEmpty else { return }
        if owner != userId {
            reset()
            owner = userId
        }
        guard planRepository == nil, let client = Supabase.shared.clientOrNull() else { return }
        let tokens = SupabaseTokenSource(client: client)
        let base = ApiConfig.shared.BASE_URL
        planRepository = KtorPlanRepository(baseUrl: base, tokens: tokens, logging: logging)
        debtsRepository = KtorDebtsRepository(baseUrl: base, tokens: tokens, logging: logging)
        accountsRepository = KtorAccountsRepository(baseUrl: base, tokens: tokens, logging: logging)
        capabilitiesRepository = KtorCapabilitiesRepository(baseUrl: base, tokens: tokens, logging: logging)
        Task { await load() }
    }

    func unbind() {
        planRepository?.close()
        debtsRepository?.close()
        accountsRepository?.close()
        capabilitiesRepository?.close()
        planRepository = nil
        debtsRepository = nil
        accountsRepository = nil
        capabilitiesRepository = nil
    }

    private func reset() {
        unbind()
        generation += 1
        plan = nil
        debts = nil
        accounts = []
        disclaimer = nil
        loading = true
        loadFailed = false
        errorKey = nil
        noticeKey = nil
        cancelChoices()
        cancelDebt()
        cancelBalance()
    }

    func retry() { Task { await load() } }

    /// The plan, the debts' details and the accounts. The accounts only name a
    /// link; without them a debt shows no link name, nothing worse.
    func load(refresh: Bool = false) async {
        guard let planRepository, let debtsRepository else { return }
        generation += 1
        let started = generation
        if refresh { refreshing = true } else { loading = true; loadFailed = false; errorKey = nil }
        let capabilities = try? await capabilitiesRepository?.fetch()
        do {
            let answer = try await planRepository.plan(month: nil)
            let page = try await debtsRepository.list()
            let found = try? await accountsRepository?.list()
            guard started == generation else { return }
            plan = answer
            debts = page
            if let found { accounts = found }
            if let value = capabilities?.currency, !value.isEmpty { currency = value } else if !page.currency.isEmpty { currency = page.currency }
            if let value = capabilities?.locale, !value.isEmpty { locale = value }
            today = ManualEntry.shared.today()
            loading = false
            refreshing = false
            loadFailed = false
            errorKey = nil
            if disclaimer == nil, let terms = try? await planRepository.disclaimer() { disclaimer = terms }
        } catch {
            guard started == generation else { return }
            loading = false
            refreshing = false
            // A refresh that fails keeps what it had (CLAUDE.md → Data & caching).
            loadFailed = plan == nil
            errorKey = (error as? ApiException)?.messageKey ?? Strings.shared.error_unexpected
        }
    }

    /// After a write: tell Home (and Goals, when a buffer may have moved), then read again.
    private func settled(goalsMoved: Bool) async {
        PlanChanged.announce()
        if goalsMoved { GoalsChanged.announce() }
        await load(refresh: true)
    }

    // MARK: What the screens show

    var moneyCurrency: String {
        if let plan, !plan.currency.isEmpty { return plan.currency }
        return currency
    }

    var words: YourPlan.Words { YourPlan.Words(currency: moneyCurrency, locale: locale, amountsHidden: false) }

    var currencySymbol: String { Money.shared.symbol(currency: moneyCurrency) }

    var amountPlaceholder: String {
        Money.shared.normalize(raw: "0", fractionDigits: Money.shared.fractionDigits(currency: moneyCurrency)) ?? "0"
    }

    var headline: String? { plan.map { YourPlan.shared.headline(plan: $0, words: words) } }

    var showsSplit: Bool { plan.map { YourPlan.shared.hasSplit(plan: $0, currency: moneyCurrency) } ?? false }

    var splitRows: [SplitRow] { plan.map { YourPlan.shared.splitRows(plan: $0, words: words) } ?? [] }

    var choiceRows: [SharedLogic.ChoiceRow] { plan.map { YourPlan.shared.choiceRows(preferences: $0.preferences, words: words) } ?? [] }

    var disclaimerText: String? {
        guard let body = disclaimer?.body, !body.isEmpty else { return nil }
        return body
    }

    var debtRows: [DebtRow] { plan.map { YourPlan.shared.debtRows(plan: $0, words: words) } ?? [] }

    var showsNoDebts: Bool { plan?.debts.isEmpty ?? false }

    var debtFreeLine: String? { plan.flatMap { YourPlan.shared.debtFreeLine(plan: $0, words: words) } }

    var totalsLine: String? {
        guard let plan else { return nil }
        let parts = [YourPlan.shared.totalInterestLine(plan: plan, words: words), YourPlan.shared.interestSavedLine(plan: plan, words: words)].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    var comparisonLine: String? { plan.flatMap { YourPlan.shared.comparisonLine(plan: $0, words: words) } }

    func record(_ debtId: String?) -> DebtRecord? {
        guard let debtId else { return nil }
        return debts?.debts.first { $0.id == debtId }
    }

    func sourceLine(_ debtId: String) -> String? { record(debtId).map { DebtEdit.shared.sourceLine(debt: $0, locale: locale) } }

    func staleLine(_ debtId: String) -> String? { record(debtId).flatMap { DebtEdit.shared.staleLine(debt: $0, locale: locale) } }

    func promotionLine(_ debtId: String) -> String? { record(debtId).flatMap { DebtEdit.shared.promotionLine(debt: $0, locale: locale) } }

    func linkedLine(_ debtId: String) -> String? {
        guard let debt = record(debtId), let name = DebtEdit.shared.linkedAccountName(debt: debt, accounts: accounts) else { return nil }
        return L.t(Strings.shared.debts_linked, name)
    }

    var linkableAccounts: [Account] { DebtEdit.shared.linkable(accounts: accounts) }

    // MARK: The choices

    var choicesDraft: PlanChoicesDraft {
        PlanChoicesDraft(hasBuffer: hasBuffer, buffer: buffer, goalSharePercent: goalShare, rateLinePercent: rateLine, strategy: strategy)
    }

    /// Why Save is off, or nil — the one shared rule (CLAUDE.md → Blocking reasons).
    var choicesBlock: PlanChoiceBlock? {
        guard let plan else { return nil }
        return YourPlan.shared.blockingReason(preferences: plan.preferences, draft: choicesDraft, currency: moneyCurrency)
    }

    var choicesNotice: PlanChoiceBlock? {
        guard let block = choicesBlock, block != .nothingChanged else { return nil }
        return block
    }

    var canSaveChoices: Bool { !savingChoices && plan != nil && choicesBlock == nil }

    var goalNote: String? {
        guard let plan else { return nil }
        return YourPlan.shared.bufferGoalNote(preferences: plan.preferences, draft: choicesDraft, locale: locale)
    }

    func startChoices() {
        guard let plan else { return }
        fill(YourPlan.shared.draftOf(preferences: plan.preferences))
        choosing = true
    }

    private func fill(_ draft: PlanChoicesDraft) {
        hasBuffer = draft.hasBuffer
        buffer = draft.buffer
        goalShare = draft.goalSharePercent
        rateLine = draft.rateLinePercent
        strategy = draft.strategy
        choicesErrorKey = nil
    }

    func cancelChoices() {
        choosing = false
        savingChoices = false
        choicesErrorKey = nil
    }

    func saveChoices() {
        guard let planRepository, canSaveChoices else { return }
        let choices = YourPlan.shared.toSave(draft: choicesDraft, currency: moneyCurrency)
        savingChoices = true
        choicesErrorKey = nil
        Task {
            do {
                _ = try await planRepository.savePreferences(choices: choices)
                savingChoices = false
                choosing = false
                // A buffer is a goal: it may have just been made or changed.
                await settled(goalsMoved: true)
            } catch {
                savingChoices = false
                choicesErrorKey = (error as? ApiException)?.messageKey ?? Strings.shared.error_unexpected
            }
        }
    }

    /// The Debts screen's switch. Once saved, only the strategy changes; before
    /// that the first save would keep the buffer as a goal, so the editor opens
    /// with the strategy chosen and the person confirms the rest.
    func chooseStrategy(_ chosen: PlanStrategy) {
        guard let planRepository, let plan, !switchingStrategy, plan.preferences.strategy != chosen else { return }
        guard let choices = YourPlan.shared.strategyOnly(preferences: plan.preferences, strategy: chosen) else {
            fill(YourPlan.shared.draftOf(preferences: plan.preferences))
            strategy = chosen
            choosing = true
            return
        }
        switchingStrategy = true
        noticeKey = nil
        Task {
            do {
                _ = try await planRepository.savePreferences(choices: choices)
                switchingStrategy = false
                await settled(goalsMoved: false)
            } catch {
                switchingStrategy = false
                noticeKey = (error as? ApiException)?.messageKey ?? Strings.shared.error_unexpected
            }
        }
    }

    // MARK: A debt's details

    var editingDebt: DebtRecord? { record(editingDebtId) }

    var debtDraft: DebtDraft {
        DebtDraft(
            name: debtName,
            kind: debtKind,
            minimumPayment: minimum,
            interestRatePercent: rate,
            promoRatePercent: promoRate,
            promoEndsOn: promoEndsOn,
            accountId: accountId
        )
    }

    var debtBlock: DebtBlock? {
        guard let editingDebt else { return nil }
        return DebtEdit.shared.blockingReason(original: editingDebt, draft: debtDraft, currency: moneyCurrency)
    }

    var debtNotice: DebtBlock? {
        guard let block = debtBlock, block != .nothingChanged else { return nil }
        return block
    }

    var canSaveDebt: Bool { !savingDebt && editingDebt != nil && debtBlock == nil }

    func editDebt(_ debtId: String) {
        guard let debt = record(debtId) else { return }
        let start = DebtEdit.shared.draftOf(debt: debt)
        editingDebtId = debt.id
        debtName = start.name
        debtKind = start.kind
        minimum = start.minimumPayment
        rate = start.interestRatePercent
        promoRate = start.promoRatePercent
        promoEndsOn = start.promoEndsOn
        accountId = start.accountId
        today = ManualEntry.shared.today()
        debtErrorKey = nil
    }

    func cancelDebt() {
        editingDebtId = nil
        savingDebt = false
        debtErrorKey = nil
    }

    func saveDebt() {
        guard let debtsRepository, let debt = editingDebt, canSaveDebt else { return }
        let changes = DebtEdit.shared.changes(original: debt, draft: debtDraft, currency: moneyCurrency)
        savingDebt = true
        Task {
            do {
                _ = try await debtsRepository.update(id: debt.id, changes: changes)
                savingDebt = false
                editingDebtId = nil
                await settled(goalsMoved: false)
            } catch {
                savingDebt = false
                debtErrorKey = (error as? ApiException)?.messageKey ?? Strings.shared.error_unexpected
            }
        }
    }

    // MARK: Update balance

    var balanceDebt: DebtRecord? { record(balanceDebtId) }

    var balanceBlock: BalanceBlock? {
        guard let balanceDebt else { return nil }
        return DebtEdit.shared.blockingReasonForBalance(debt: balanceDebt, amount: balanceAmount, currency: moneyCurrency)
    }

    /// Blank and unchanged are not worth calling out before anything is typed.
    var balanceNotice: BalanceBlock? {
        guard let block = balanceBlock, block != .noBalance, block != .unchanged else { return nil }
        return block
    }

    var canSaveBalance: Bool { !savingBalance && balanceDebt != nil && balanceBlock == nil }

    func startBalance(_ debtId: String) {
        balanceDebtId = debtId
        balanceAmount = ""
        balanceErrorKey = nil
    }

    func cancelBalance() {
        balanceDebtId = nil
        balanceAmount = ""
        savingBalance = false
        balanceErrorKey = nil
    }

    func saveBalance() {
        guard let debtsRepository, let debt = balanceDebt, canSaveBalance else { return }
        let change = DebtEdit.shared.balanceChange(amount: balanceAmount, currency: moneyCurrency)
        savingBalance = true
        Task {
            do {
                _ = try await debtsRepository.update(id: debt.id, changes: change)
                savingBalance = false
                balanceDebtId = nil
                balanceAmount = ""
                await settled(goalsMoved: false)
            } catch {
                savingBalance = false
                balanceErrorKey = (error as? ApiException)?.messageKey ?? Strings.shared.error_unexpected
            }
        }
    }

    // MARK: Surviving the scene going away

    private struct Snapshot: Codable {
        var owner: String
        var page: String
        var choosing: Bool
        var hasBuffer: Bool
        var buffer: String
        var goalShare: String
        var rateLine: String
        var strategy: String
        var editingDebtId: String?
        var debtName: String
        var debtKind: String?
        var minimum: String
        var rate: String
        var promoRate: String
        var promoEndsOn: String?
        var accountId: String?
        var balanceDebtId: String?
        var balanceAmount: String
    }

    /// The page and what was being typed, as one string the scene can keep —
    /// only while a sheet is open, and never the plan, which is re-read on bind.
    var snapshot: String {
        guard let owner, choosing || editingDebtId != nil || balanceDebtId != nil else { return "" }
        let value = Snapshot(
            owner: owner, page: page.rawValue, choosing: choosing,
            hasBuffer: hasBuffer, buffer: buffer, goalShare: goalShare, rateLine: rateLine, strategy: strategy.wire,
            editingDebtId: editingDebtId, debtName: debtName, debtKind: debtKind?.wire,
            minimum: minimum, rate: rate, promoRate: promoRate, promoEndsOn: promoEndsOn, accountId: accountId,
            balanceDebtId: balanceDebtId, balanceAmount: balanceAmount
        )
        guard let data = try? JSONEncoder().encode(value) else { return "" }
        return String(decoding: data, as: UTF8.self)
    }

    /// Put a `snapshot` back. Sets the owner too, so the bind that follows keeps it.
    func restore(from snapshot: String) {
        guard let data = snapshot.data(using: .utf8),
              let value = try? JSONDecoder().decode(Snapshot.self, from: data) else { return }
        owner = value.owner
        page = Page(rawValue: value.page) ?? .plan
        choosing = value.choosing
        hasBuffer = false
        buffer = value.buffer
        goalShare = value.goalShare
        rateLine = value.rateLine
        strategy = PlanStrategy.companion.fromWire(value: value.strategy)
        editingDebtId = value.editingDebtId
        debtName = value.debtName
        debtKind = value.debtKind.map { DebtKind.companion.fromWire(value: $0) }.flatMap { $0 == .unknown ? nil : $0 }
        minimum = value.minimum
        rate = value.rate
        promoRate = value.promoRate
        promoEndsOn = value.promoEndsOn
        accountId = value.accountId
        balanceDebtId = value.balanceDebtId
        balanceAmount = value.balanceAmount
    }
}
