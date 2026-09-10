import SwiftUI

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            // Throwaway demo for ticket #6; M2 replaces it with real navigation.
            ApiDemoView()
        }
    }
}