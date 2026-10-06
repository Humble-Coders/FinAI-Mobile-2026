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

    /// What is known about a gated tab: not yet read, on, or off. Equatable, so
    /// navigation can react to the payload arriving — not only to a tab
    /// flipping, which from "not read" to "off" it never visibly does.
    enum Gate: Equatable { case unknown, on, off }

    /// The capabilities that earn the gated tabs a place on the bar.
    static let budgetFeature = "auto_budget"
    static let goalsFeature = "goals"

    func gate(_ feature: String) -> Gate {
        guard let capabilities else { return .unknown }
        return capabilities.isEnabled(featureKey: feature) ? .on : .off
    }

    /// Both gates together, for navigation to watch with one `onChange`.
    struct Gates: Equatable {
        let budget: Gate
        let goals: Gate
    }

    var gates: Gates { Gates(budget: gate(Self.budgetFeature), goals: gate(Self.goalsFeature)) }

    /**
     Whether the bar draws a gated tab. Android's `tabsFor`, one rule for every
     gated tab — Budget (#47) and Goals (#52).

     **Not known is not the same as off.** After iOS reclaims the app, the
     scene-stored route puts the person back on their tab before capabilities
     have been read. A `TabView` whose selection matches no tab is left to
     SwiftUI to resolve, and it may resolve it by selecting the first tab —
     sending them home and stranding the edit they came back for. So someone
     already on a gated tab keeps it until the payload actually says no.
     */
    func shows(_ feature: String, onTab: Bool) -> Bool {
        let known = gate(feature)
        return known == .on || (known == .unknown && onTab)
    }

    /// True when the person is on a gated tab and the payload has *said* its
    /// feature is off. Never on a payload not yet read. Android's `leavesGatedTab`.
    func leaves(_ feature: String, onTab: Bool) -> Bool { onTab && gate(feature) == .off }

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
