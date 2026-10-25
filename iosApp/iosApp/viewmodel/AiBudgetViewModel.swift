import Foundation
import SharedLogic

/**
 The AI's suggested first budget (product decision, 2026-10-25): shown at the
 end of setup, and from the Budget tab for a household already set up. The
 Android `AiBudgetViewModel` is the same model.

 Opening it shows the suggestion already made this month when there is one —
 asking costs money, and the server allows three — and asks only when there is
 none. Start writes the lines the person kept as their own.
 */
@MainActor
final class AiBudgetViewModel: ObservableObject {

    @Published private(set) var loading = true
    @Published private(set) var proposal: AiProposal?
    @Published private(set) var draft = AiBudgetDraft(lines: [], savings: "")
    @Published private(set) var errorKey: String?
    /// Shown before the AI may be asked, when consent is missing.
    @Published private(set) var policy: AiPolicy?
    @Published var consentTicked = false
    @Published private(set) var consentBusy = false
    @Published private(set) var suggesting = false
    @Published private(set) var starting = false
    /// Budgets are off, or there is no income to divide: nothing to suggest.
    @Published private(set) var unavailable = false

    let locale = "en"

    private var budgets: BudgetRepository?
    private var consent: AiConsentRepository?
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
        consent = KtorAiConsentRepository(baseUrl: base, tokens: tokens, logging: logging)
        Task { await load() }
    }

    func unbind() {
        budgets?.close()
        consent?.close()
        budgets = nil
        consent = nil
    }

    deinit {
        budgets?.close()
        consent?.close()
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
        errorKey = nil
        do {
            show(try await budgets.suggestSetup())
        } catch {
            let kotlin = Self.kotlin(error)
            if kotlin is ApiException.ConsentRequired {
                await askForConsent()
            } else if kotlin is ApiException.AiBudgetLimitReached,
                      let last = try? await budgets.setupSuggestion() {
                show(last)
            } else {
                failed(error)
            }
        }
    }

    private func show(_ answer: AiProposal) {
        loading = false
        suggesting = false
        proposal = answer
        draft = AiBudgetEdit.shared.draftOf(proposal: answer)
        errorKey = nil
        policy = nil
    }

    private func failed(_ error: Error) {
        loading = false
        suggesting = false
        starting = false
        let kotlin = Self.kotlin(error)
        errorKey = (kotlin as? ApiException)?.messageKey ?? Strings.shared.error_unexpected
        unavailable = kotlin is ApiException.FeatureUnavailable || kotlin is ApiException.SetupIncomeMissing
    }

    // MARK: Consent

    private func askForConsent() async {
        guard let consent else { return }
        do {
            policy = try await consent.policy()
            consentTicked = false
            loading = false
            suggesting = false
        } catch {
            failed(error)
        }
    }

    /// Records consent to the version shown, then asks for the suggestion.
    func agree() async {
        guard let consent, let shown = policy, consentTicked else { return }
        consentBusy = true
        errorKey = nil
        do {
            try await consent.consent(version: shown.version)
            consentBusy = false
            policy = nil
            consentTicked = false
            await suggest()
        } catch {
            consentBusy = false
            if Self.kotlin(error) is ApiException.AiPolicyChanged {
                await askForConsent()
            } else {
                errorKey = (Self.kotlin(error) as? ApiException)?.messageKey ?? Strings.shared.error_unexpected
            }
        }
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

    func lineBlock(_ index: Int) -> AiBudgetBlock? {
        guard draft.lines.indices.contains(index) else { return nil }
        return AiBudgetEdit.shared.lineBlock(line: draft.lines[index], currency: currency)
    }

    func setAmount(_ index: Int, _ amount: String) {
        draft = AiBudgetEdit.shared.withAmount(draft: draft, index: Int32(index), amount: amount)
        errorKey = nil
    }

    /// SKIE gives Swift no `copy()`: the one place the draft's savings is rebuilt.
    func setSavings(_ amount: String) {
        draft = AiBudgetDraft(lines: draft.lines, savings: amount)
        errorKey = nil
    }

    /// Start the budget from what is on screen; [onDone] once it is written.
    func start(onDone: @escaping () -> Void) async {
        guard let budgets, canStart else { return }
        starting = true
        errorKey = nil
        do {
            _ = try await budgets.acceptSetup(accept: AiBudgetEdit.shared.accept(draft: draft, currency: currency))
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
