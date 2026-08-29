import SwiftUI
import SharedLogic

/// Temporary screen so the app has something to launch. Replaced by real
/// screens in M2; deliberately carries no product UI.
///
/// Labels come from the shared i18n registry, never from Swift literals, so
/// Android and iOS cannot diverge (kmp-arch-v2, CLAUDE.md).
struct ContentView: View {
    private let registry = LocalizationRegistry.shared

    var body: some View {
        VStack(spacing: 8) {
            Text(registry.get(key: Strings.shared.placeholder_title, language: "en"))
                .font(.title)
            Text(registry.get(key: Strings.shared.placeholder_body, language: "en"))
                .font(.body)
                .multilineTextAlignment(.center)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

#Preview {
    ContentView()
}
