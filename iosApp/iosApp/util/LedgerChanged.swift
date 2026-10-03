import Foundation

/**
 "Money moved" — posted by whatever changed it, observed by whatever shows it
 (kmp-arch-v2, cross-screen refresh; Android's `LedgerChanged`).

 The dashboard reads a month from the server once and then sits there. Import a
 statement, type a transaction in, confirm or delete a row in the queue, and the
 figures behind it are stale the moment that screen closes — a person imports 24
 transactions, comes back, and home still says what it said before, which reads
 as the import not having worked.

 A notification rather than a result threaded back through navigation: four
 screens change the ledger and more will, and passing a result through each
 route means every new writer has to remember to. Posting is one line at the
 point the server said yes.

 **Post only after the server has confirmed the write.** An optimistic signal
 makes the dashboard re-read and show the figures it already had, which looks
 exactly like the write being lost.
 */
enum LedgerChanged {

    static let name = Notification.Name("com.humblesolutions.finai.ledgerChanged")

    /// Called by a writer once the server has confirmed the change.
    static func announce() {
        NotificationCenter.default.post(name: name, object: nil)
    }

    /// The stream a screen showing derived figures watches.
    static var events: NotificationCenter.Notifications {
        NotificationCenter.default.notifications(named: name)
    }
}
