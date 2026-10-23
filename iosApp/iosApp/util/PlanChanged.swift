import Foundation

/**
 "The plan moved" — posted when its choices are saved or a debt is edited
 (#76), observed by Home's plan card and the Goals tab's suggested amounts.
 Android's `PlanChanged`. **Post only after the server has confirmed.**
 */
enum PlanChanged {

    static let name = Notification.Name("com.humblesolutions.finai.planChanged")

    @MainActor static func announce() {
        NotificationCenter.default.post(name: name, object: nil)
    }

    static var events: NotificationCenter.Notifications {
        NotificationCenter.default.notifications(named: name)
    }
}
