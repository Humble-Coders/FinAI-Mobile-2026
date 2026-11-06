import Foundation
import SharedLogic

/**
 The AI's suggested first budget (product decision, 2026-10-25): shown at the
 end of setup, and from the Budget tab for a household already set up. The
 Android `AiBudgetViewModel` is the same model.

 Opening it shows the suggestion already made this month when there is one —
 asking costs money, and the server allows three — and asks only when there is
 none. The person's edits, lines added and removed included, are kept on the
 phone until Start writes the lines as their own, so coming back shows them
 again without asking the AI.
 */
@MainActor
final class AiBudgetViewModel: ObservableObject {

    @Published private(set) var loading = true
    @Published private(set) var proposal: AiProposal?
    @Published private(set) var draft = AiBudgetDraft(lines: [], savings: "")
    @Published private(set) var errorKey: String?
    /// The AI may not be asked: consent is missing, and is given in Profile
    /// only (2026-11-06). The screen says so instead of asking here.
    @Published private(set) var aiOff = false
    /// In setup only, where there is no Profile yet: the AI policy, asked for
    /// once here so a new household's first budget can still be suggested.
    @Published private(set) var policy: AiPolicy?
    @Published var consentTicked = false
    @Published private(set) var consentBusy = false
    @Published private(set) var consentErrorKey: String?
    @Published private(set) var suggesting = false
    @Published private(set) var starting = false
    /// Budgets are off, or there is no income to divide: nothing to suggest.
    @Published private(set) var unavailable = false
    /// Every category the household has, for "Add a category"; empty until read.
    @Published private(set) var categories: [SharedLogic.Category] = []
    @Published var adding = false

    let locale = "en"

    private var budgets: BudgetRepository?
    private var categoriesRepository: CategoriesRepository?
    private var legal: LegalRepository?
    private let drafts = UserDefaultsAiBudgetDraftStore()
    private var owner: String?

    #if DEBUG
    private let logging = true
    #else
    private let logging = false
    #endif

    func bind(userId: String) {
        guard !userId.isEmpty, owner != userId || budgets == nil else { return }
        unbind()
        owner = userId
        guard let client = Supabase.shared.clientOrNull() else { return }
        let tokens = SupabaseTokenSource(client: client)
        let base = ApiConfig.shared.BASE_URL
        budgets = KtorBudgetRepository(baseUrl: base, tokens: tokens, logging: logging)
        categoriesRepository = KtorCategoriesRepository(baseUrl: base, tokens: tokens, logging: logging)
        legal = KtorLegalRepository(baseUrl: base, tokens: tokens, logging: logging)
        Task { await load() }
    }

    func unbind() {
        budgets?.close()
        categoriesRepository?.close()
        legal?.close()
        budgets = nil
        categoriesRepository = nil
        legal = nil
    }

    deinit {
        budgets?.close()
        categoriesRepository?.close()
        legal?.close()
    }

    // MARK: Asking

    /// The suggestion made this month if there is one; otherwise ask for one.
    func load() async {
        guard let budgets else { return }
        loading = true
        errorKey = nil
        do {
            show(try await budgets.setupSuggestion())
        } catch {
            if Self.kotlin(error) is ApiException.NotFound {
                await suggest()
            } else {
                failed(error)
            }
        }
    }

    /// A new suggestion — "Suggest again", or the first one.
    func suggest() async {
        guard let budgets else { return }
        suggesting = true
        aiOff = false
        errorKey = nil
        do {
            show(try await budgets.suggestSetup())
        } catch {
            let kotlin = Self.kotlin(error)
            if kotlin is ApiException.ConsentRequired {
                aiOff = true
                loading = false
                suggesting = false
                await loadPolicy()
            } else if kotlin is ApiException.AiBudgetLimitReached,
                      let last = try? await budgets.setupSuggestion() {
                show(last)
            } else {
                failed(error)
            }
        }
    }

    /// The policy for setup's one-time ask; quietly, the Skip stays either way.
    private func loadPolicy() async {
        guard let legal else { return }
        do {
            policy = try await legal.aiPolicy()
            consentTicked = false
            consentErrorKey = nil
        } catch {
            consentErrorKey = (Self.kotlin(error) as? ApiException)?.messageKey ?? Strings.shared.error_unexpected
        }
    }

    /// Setup's one-time agree: records it, then asks for the suggestion.
    func agreeToAi() async {
        guard let legal, let shown = policy, consentTicked, !consentBusy else { return }
        consentBusy = true
        consentErrorKey = nil
        do {
            try await legal.consentToAi(version: shown.version)
            consentBusy = false
            policy = nil
            consentTicked = false
            await suggest()
        } catch {
            consentBusy = false
            if Self.kotlin(error) is ApiException.AiPolicyChanged {
                await loadPolicy()
            } else {
                consentErrorKey = (Self.kotlin(error) as? ApiException)?.messageKey ?? Strings.shared.error_unexpected
            }
        }
    }

    private func show(_ answer: AiProposal) {
        loading = false
        suggesting = false
        proposal = answer
        let kept = owner.flatMap { drafts.load(userId: $0) }
        draft = AiBudgetEdit.shared.restore(saved: kept, proposal: answer) ?? AiBudgetEdit.shared.draftOf(proposal: answer)
        errorKey = nil
        aiOff = false
        Task { await loadCategories() }
    }

    /// For "Add a category"; quietly, since the screen works without it.
    private func loadCategories() async {
        guard let categoriesRepository, categories.isEmpty else { return }
        if let read = try? await categoriesRepository.list() {
            categories = read
        }
    }

    /// Every edit is kept at once, so leaving by any way loses nothing.
    private func keep() {
        guard let proposal, let owner else { return }
        drafts.save(userId: owner, draft: AiBudgetEdit.shared.encode(draft: draft, proposal: proposal))
    }

    private func failed(_ error: Error) {
        loading = false
        suggesting = false
        starting = false
        let kotlin = Self.kotlin(error)
        errorKey = (kotlin as? ApiException)?.messageKey ?? Strings.shared.error_unexpected
        unavailable = kotlin is ApiException.FeatureUnavailable || kotlin is ApiException.SetupIncomeMissing
    }

    // MARK: Editing

    var currency: String { proposal?.currency ?? "" }
    var symbol: String { Money.shared.symbol(currency: currency) }
    var block: AiBudgetBlock? { proposal.flatMap { AiBudgetEdit.shared.blockingReason(draft: draft, proposal: $0) } }
    var canStart: Bool { proposal != nil && block == nil && !starting && !suggesting }
    var canSuggestAgain: Bool { (proposal?.attemptsLeft ?? 0) > 0 && !suggesting && !starting }
    var shareLabel: String? { proposal.map { AiBudgetEdit.shared.shareLabel(proposal: $0, locale: locale) } }
    var leftLabel: String? { proposal.flatMap { AiBudgetEdit.shared.leftLabel(draft: draft, proposal: $0, locale: locale) } }
    var attemptsLabel: String? { proposal.flatMap { AiBudgetEdit.shared.attemptsLabel(proposal: $0, locale: locale) } }

    var addable: [SharedLogic.Category] { AiBudgetEdit.shared.addable(categories: categories, draft: draft) }

    func canRemove(_ index: Int) -> Bool {
        guard !starting, draft.lines.indices.contains(index) else { return false }
        return AiBudgetEdit.shared.canRemove(line: draft.lines[index], currency: currency)
    }

    func add(_ category: SharedLogic.Category) {
        draft = AiBudgetEdit.shared.withLineAdded(draft: draft, category: category)
        adding = false
        errorKey = nil
        keep()
    }

    func remove(_ index: Int) {
        draft = AiBudgetEdit.shared.withoutLine(draft: draft, index: Int32(index), currency: currency)
        errorKey = nil
        keep()
    }

    func lineBlock(_ index: Int) -> AiBudgetBlock? {
        guard draft.lines.indices.contains(index) else { return nil }
        return AiBudgetEdit.shared.lineBlock(line: draft.lines[index], currency: currency)
    }

    func setAmount(_ index: Int, _ amount: String) {
        draft = AiBudgetEdit.shared.withAmount(draft: draft, index: Int32(index), amount: amount)
        errorKey = nil
        keep()
    }

    /// SKIE gives Swift no `copy()`: the one place the draft's savings is rebuilt.
    func setSavings(_ amount: String) {
        draft = AiBudgetDraft(lines: draft.lines, savings: amount)
        errorKey = nil
        keep()
    }

    /// Start the budget from what is on screen; [onDone] once it is written.
    func start(onDone: @escaping () -> Void) async {
        guard let budgets, canStart else { return }
        starting = true
        errorKey = nil
        do {
            _ = try await budgets.acceptSetup(accept: AiBudgetEdit.shared.accept(draft: draft, currency: currency))
            if let owner { drafts.clear(userId: owner) }
            starting = false
            BudgetChanged.announce()
            onDone()
        } catch {
            starting = false
            errorKey = (Self.kotlin(error) as? ApiException)?.messageKey ?? Strings.shared.error_unexpected
        }
    }

    private static func kotlin(_ error: Error) -> KotlinThrowable? {
        (error as NSError).userInfo["KotlinException"] as? KotlinThrowable
    }
}
