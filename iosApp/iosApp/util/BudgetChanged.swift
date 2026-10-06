import Foundation

/**
 "An allocation moved" — posted when a budget line is set or reset (#47),
 observed by whatever shows one. Android's `BudgetChanged`.

 Separate from `LedgerChanged` rather than folded into it, because the two
 point opposite ways and carry different news:

 - `LedgerChanged` means the **spending** moved. The Budget tab observes it:
   an import changes what every line has spent against it.
 - This means the **allocation** moved. Nothing in the ledger changed, so
   posting it as `LedgerChanged` would make the transaction list and every
   figure screen re-read the server for rows that are exactly as they were —
   several calls and a visible refresh, to show what is already on screen.

 Home's budget card (#46) observes both, which is the one screen that cares
 about either.

 **Post only after the server has confirmed the write**, for `LedgerChanged`'s
 reason.
 */
enum BudgetChanged {

    static let name = Notification.Name("com.humblesolutions.finai.budgetChanged")

    /// How many allocation changes have been announced since launch — so a
    /// screen that was away can tell, on coming back, whether it missed one.
    @MainActor private(set) static var version = 0

    /// Called by a writer once the server has confirmed the change.
    @MainActor static func announce() {
        version += 1
        NotificationCenter.default.post(name: name, object: nil)
    }

    /// The stream a screen showing a budget watches.
    static var events: NotificationCenter.Notifications {
        NotificationCenter.default.notifications(named: name)
    }
}
