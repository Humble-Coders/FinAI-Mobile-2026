import Foundation
import SharedLogic

/// The suggested budget's draft in UserDefaults, one entry per user. The
/// Android `PrefsAiBudgetDraftStore` is the same store.
final class UserDefaultsAiBudgetDraftStore: AiBudgetDraftStore {

    private let defaults = UserDefaults.standard

    private func key(_ userId: String) -> String { "ai_budget_draft.\(userId)" }

    func load(userId: String) -> String? { defaults.string(forKey: key(userId)) }

    func save(userId: String, draft: String) { defaults.set(draft, forKey: key(userId)) }

    func clear(userId: String) { defaults.removeObject(forKey: key(userId)) }
}
