import Foundation

/**
 "A goal moved" — posted when one is created, edited, topped up, reordered or
 deleted (#52), observed by Home's goals card. Android's `GoalsChanged`.

 Its own notification, as `BudgetChanged` is: nothing in the ledger changed,
 so posting `LedgerChanged` would make every figure screen re-read the server
 for rows exactly as they were. **Post only after the server has confirmed.**
 */
enum GoalsChanged {

    static let name = Notification.Name("com.humblesolutions.finai.goalsChanged")

    /// Called by a writer once the server has confirmed the change.
    @MainActor static func announce() {
        NotificationCenter.default.post(name: name, object: nil)
    }

    /// The stream a screen showing goals watches.
    static var events: NotificationCenter.Notifications {
        NotificationCenter.default.notifications(named: name)
    }
}
