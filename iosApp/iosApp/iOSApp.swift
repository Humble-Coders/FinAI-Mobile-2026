import SwiftUI

@main
struct iOSApp: App {
    init() {
        // Done above every keyboard, number pads included (they have no return key).
        KeyboardDoneBar.install()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}
