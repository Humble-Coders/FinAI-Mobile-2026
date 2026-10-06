import Foundation
import SharedLogic

/**
 Which features the tab bar may offer (PRD §4.6). Android's
 `FeaturesViewModel`.

 The capabilities payload decides what a client *shows*; the API decides
 independently what it *allows*, so a tab hidden here is a courtesy, not a
 lock (CLAUDE.md → Region & feature gating).

 This sits beside navigation rather than in a screen's model because a tab
 has to be absent before anything behind it is opened — by the time the
 Budget screen could ask, its tab is already drawn.

 **Nil means not known yet.** The bar starts without the gated tabs and gains
 them when the answer arrives, because appearing is less jarring than a tab
 vanishing under a thumb — and because a feature wrongly shown would be a 403
 the person did not ask for.
 */
@MainActor
final class FeaturesViewModel: ObservableObject {

    @Published private(set) var capabilities: Capabilities?

    private var repository: CapabilitiesRepository?
    private var owner: String?

    #if DEBUG
    private let logging = true
    #else
    private let logging = false
    #endif

    /// What is known about the Budget tab (#47): not yet read, on, or off. Equatable,
    /// so navigation can react to the payload arriving — not only to the tab
    /// flipping, which from "not read" to "off" it never visibly does.
    enum BudgetGate: Equatable { case unknown, on, off }

    var budgetGate: BudgetGate {
        guard let capabilities else { return .unknown }
        return capabilities.isEnabled(featureKey: "auto_budget") ? .on : .off
    }

    /**
     Whether the bar draws Budget. Android's `tabsFor`.

     **Not known is not the same as off.** After iOS reclaims the app, the
     scene-stored route puts the person back on Budget before capabilities
     have been read. A `TabView` whose selection matches no tab is left to
     SwiftUI to resolve, and it may resolve it by selecting the first tab —
     sending them home and stranding the edit they came back for. So someone
     already on Budget keeps the tab until the payload actually says no.
     */
    func showsBudget(onBudget: Bool) -> Bool {
        budgetGate == .on || (budgetGate == .unknown && onBudget)
    }

    /// True when the person is on Budget and the payload has *said* it is off.
    /// Never on a payload not yet read. Android's `leavesBudget`.
    func leavesBudget(onBudget: Bool) -> Bool { onBudget && budgetGate == .off }

    func bind(userId: String) {
        guard !userId.isEmpty else { return }
        if owner != userId {
            unbind()
            capabilities = nil
            owner = userId
        }
        guard repository == nil, let client = Supabase.shared.clientOrNull() else { return }
        repository = KtorCapabilitiesRepository(
            baseUrl: ApiConfig.shared.BASE_URL,
            tokens: SupabaseTokenSource(client: client),
            logging: logging
        )
        Task { await load() }
    }

    func unbind() {
        repository?.close()
        repository = nil
    }

    private func load() async {
        guard let repository else { return }
        // Leave it unknown on a failure. The gated tabs stay hidden, the rest
        // of the app is unaffected, and the next bind tries again.
        capabilities = try? await repository.fetch()
    }
}
