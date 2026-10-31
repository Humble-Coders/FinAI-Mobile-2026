import Foundation
import SharedLogic

/**
 Your plan's diagnostics: "Debt, goals or savings?" and "Should I invest?". The
 Android `DiagnosticViewModel` is the same model.

 The allocation form is one figure — what they save each month, starting at
 the Budget's savings target, which a run also sets. The invest form starts
 from the savings figures the person last ran with. Running
 with the same figures returns the stored answer at no cost; the server allows
 four fresh runs of each a month.
 */
@MainActor
final class DiagnosticViewModel: ObservableObject {

    @Published private(set) var kind: String
    @Published private(set) var loading = true
    @Published private(set) var diagnostics: PlanDiagnostics?
    /// The answer on screen: this visit's run, else the last one.
    @Published private(set) var result: PlanDiagnostic?
    @Published var savingsTarget = ""
    @Published var savingsNow = ""
    /// The allocation form's one figure: what they save each month.
    @Published var monthlySavings = ""
    @Published private(set) var running = false
    @Published private(set) var errorKey: String?
    @Published private(set) var policy: AiPolicy?
    @Published var consentTicked = false
    @Published private(set) var consentBusy = false
    /// "Pay debt before goals" is being saved, or was.
    @Published private(set) var applying = false
    @Published private(set) var applied = false
    /// The plan's choices, for "Pay debt before goals"; nil until read or with no debt choice.
    @Published private(set) var preferences: PlanPreferences?

    let locale = "en"

    private var plan: PlanRepository?
    private var consent: AiConsentRepository?

    #if DEBUG
    private let logging = true
    #else
    private let logging = false
    #endif

    init(kind: String) {
        self.kind = kind
    }

    func bind() {
        guard plan == nil, let client = Supabase.shared.clientOrNull() else { return }
        let tokens = SupabaseTokenSource(client: client)
        let base = ApiConfig.shared.BASE_URL
        plan = KtorPlanRepository(baseUrl: base, tokens: tokens, logging: logging)
        consent = KtorAiConsentRepository(baseUrl: base, tokens: tokens, logging: logging)
        Task { await load() }
    }

    deinit {
        plan?.close()
        consent?.close()
    }

    func load() async {
        guard let plan else { return }
        loading = true
        errorKey = nil
        do {
            let found = try await plan.diagnostics()
            diagnostics = found
            result = found.last(kind: kind)
            savingsTarget = found.savingsTarget
            savingsNow = found.savingsNow
            monthlySavings = found.monthlySavings
            loading = false
            // Quiet on failure: only "Pay debt before goals" needs it.
            if isAllocation, let answer = try? await plan.plan(month: nil) { preferences = answer.preferences }
        } catch {
            loading = false
            errorKey = Self.messageKey(error)
        }
    }

    // MARK: Words

    var currency: String { diagnostics?.currency ?? "" }
    var symbol: String { Money.shared.symbol(currency: currency) }
    var title: String { PlanDiagnosticWords.shared.title(kind: kind, locale: locale) }
    var question: String { PlanDiagnosticWords.shared.question(kind: kind, locale: locale) }
    var isAllocation: Bool { kind == PlanDiagnostics.companion.ALLOCATION }
    var monthlyLabel: String { PlanDiagnosticWords.shared.monthlyLabel(locale: locale) }
    var monthlyHint: String { PlanDiagnosticWords.shared.monthlyHint(locale: locale) }
    var block: DiagnosticBlock? {
        isAllocation
            ? PlanDiagnosticWords.shared.monthlyBlockingReason(monthlySavings: monthlySavings, currency: currency)
            : PlanDiagnosticWords.shared.blockingReason(savingsTarget: savingsTarget, savingsNow: savingsNow, currency: currency)
    }
    var canRun: Bool { diagnostics != nil && block == nil && !running && !consentBusy }
    var runsLeftLabel: String? {
        diagnostics.map { PlanDiagnosticWords.shared.runsLeft(diagnostics: $0, kind: kind, locale: locale) }
    }
    var rows: [DiagnosticRow] {
        guard let result else { return [] }
        return result.isAllocation
            ? PlanDiagnosticWords.shared.allocationRows(d: result, locale: locale)
            : PlanDiagnosticWords.shared.investRows(d: result, locale: locale)
    }
    var checks: [DiagnosticCheck] {
        guard let result, !result.isAllocation else { return [] }
        return PlanDiagnosticWords.shared.investChecks(d: result, locale: locale)
    }
    var comparison: String? {
        guard let result, result.isAllocation else { return nil }
        return PlanDiagnosticWords.shared.comparison(d: result, locale: locale)
    }
    var payoff: String? {
        guard let result, result.isAllocation else { return nil }
        return PlanDiagnosticWords.shared.payoff(d: result, locale: locale)
    }
    func stepLabel(_ key: String) -> String { PlanDiagnosticWords.shared.stepLabel(key: key, locale: locale) }

    var recommendation: String? {
        guard let result, result.isAllocation else { return nil }
        return PlanDiagnosticWords.shared.recommendation(d: result, locale: locale)
    }
    var orders: [DiagnosticRow] {
        guard let result, result.isAllocation else { return [] }
        return PlanDiagnosticWords.shared.orders(d: result, locale: locale)
    }
    var waiting: String? {
        guard let result, result.isAllocation else { return nil }
        return PlanDiagnosticWords.shared.waiting(d: result, locale: locale)
    }
    var canApplyDebtFirst: Bool {
        guard !applied, let result, let preferences, YourPlan.shared.debtFirstChoices(preferences: preferences) != nil else { return false }
        return PlanDiagnosticWords.shared.canApplyDebtFirst(d: result)
    }

    /**
     "Pay debt before goals": debt to the top with the goals' share added to
     it, the goals given none — shared `YourPlan.debtFirstChoices`, on the
     choices as they are now. Nothing is saved without a debt choice.
     */
    func applyDebtFirst() async {
        guard let plan, canApplyDebtFirst, !applying else { return }
        applying = true
        errorKey = nil
        do {
            let now = try await plan.plan(month: nil).preferences
            preferences = now
            guard let choices = YourPlan.shared.debtFirstChoices(preferences: now) else {
                applying = false
                return
            }
            _ = try await plan.savePreferences(choices: choices)
            applying = false
            applied = true
            PlanChanged.announce()
        } catch {
            applying = false
            errorKey = Self.messageKey(error)
        }
    }

    // MARK: Running

    /// Run on what is in the form. The allocation takes one figure — blank
    /// runs on the Budget's savings target; the invest check takes two.
    func run() async {
        guard let plan, canRun else { return }
        let digits = Money.shared.fractionDigits(currency: currency)
        var target: String?
        var now: String?
        var monthly: String?
        if isAllocation {
            if !monthlySavings.isEmpty {
                guard let value = Money.shared.normalize(raw: monthlySavings, fractionDigits: digits) else { return }
                monthly = value
            }
        } else {
            guard let t = Money.shared.normalize(raw: savingsTarget.isEmpty ? "0" : savingsTarget, fractionDigits: digits),
                  let n = Money.shared.normalize(raw: savingsNow.isEmpty ? "0" : savingsNow, fractionDigits: digits) else { return }
            target = t
            now = n
        }
        running = true
        errorKey = nil
        do {
            let answer = try await plan.runDiagnostic(kind: kind, savingsTarget: target, savingsNow: now, monthlySavings: monthly)
            result = answer
            // Re-read the counts: a fresh run used one up, a stored one did not.
            if let counts = try? await plan.diagnostics() { diagnostics = counts }
            running = false
            if isAllocation {
                // The monthly figure is now the Budget's savings target: the
                // Budget tab, Home and Goals re-read on this.
                PlanChanged.announce()
            }
        } catch {
            running = false
            if Self.kotlin(error) is ApiException.ConsentRequired {
                await askForConsent()
            } else {
                errorKey = Self.messageKey(error)
            }
        }
    }

    private func askForConsent() async {
        guard let consent else { return }
        do {
            policy = try await consent.policy()
            consentTicked = false
        } catch {
            errorKey = Self.messageKey(error)
        }
    }

    /// Records consent to the version shown, then runs.
    func agree() async {
        guard let consent, let shown = policy, consentTicked else { return }
        consentBusy = true
        errorKey = nil
        do {
            try await consent.consent(version: shown.version)
            consentBusy = false
            policy = nil
            consentTicked = false
            await run()
        } catch {
            consentBusy = false
            if Self.kotlin(error) is ApiException.AiPolicyChanged {
                await askForConsent()
            } else {
                errorKey = Self.messageKey(error)
            }
        }
    }

    private static func kotlin(_ error: Error) -> KotlinThrowable? {
        (error as NSError).userInfo["KotlinException"] as? KotlinThrowable
    }

    private static func messageKey(_ error: Error) -> String {
        (kotlin(error) as? ApiException)?.messageKey ?? Strings.shared.error_unexpected
    }
}
