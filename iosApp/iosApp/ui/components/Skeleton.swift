import SwiftUI
import SharedLogic

/**
 A tab's first read, drawn as the shape of the page — its title, a large card,
 then rows — in grey blocks that pulse gently, instead of the coin over an
 empty screen (PM decision, 2026-11-05). Laid over the tab, so the tab still
 appears and starts its read underneath. The Android `TabSkeleton` is the same.
 */
struct TabSkeleton: View {
    let title: String
    @State private var dim = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text(title)
                    .font(.title2.weight(.bold))
                    .accessibilityAddTraits(.isHeader)
                VStack(alignment: .leading, spacing: 14) {
                    block(height: 150)
                    HStack(spacing: 12) {
                        block(height: 76)
                        block(height: 76)
                    }
                    ForEach(0..<5, id: \.self) { _ in row }
                }
                .opacity(dim ? 0.45 : 0.9)
                .accessibilityHidden(true)
            }
            .frame(maxWidth: 560, alignment: .leading)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
        }
        .scrollDisabled(true)
        .background(Color(.systemBackground).ignoresSafeArea())
        .accessibilityElement(children: .contain)
        .accessibilityLabel(L.t(Strings.shared.loading_body))
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) { dim = true }
        }
    }

    private var row: some View {
        HStack(spacing: 12) {
            Circle().fill(Color(.secondarySystemFill)).frame(width: 40, height: 40)
            VStack(alignment: .leading, spacing: 8) {
                capsule(width: 160, height: 14)
                capsule(width: 90, height: 12)
            }
            Spacer(minLength: 8)
            capsule(width: 64, height: 16)
        }
    }

    private func block(height: CGFloat) -> some View {
        RoundedRectangle(cornerRadius: 16)
            .fill(Color(.secondarySystemFill))
            .frame(maxWidth: .infinity)
            .frame(height: height)
    }

    private func capsule(width: CGFloat, height: CGFloat) -> some View {
        RoundedRectangle(cornerRadius: height / 2)
            .fill(Color(.secondarySystemFill))
            .frame(width: width, height: height)
    }
}

extension View {
    /// The tab's skeleton over it while [loading]: its first read, not a refresh.
    func tabSkeleton(_ loading: Bool, title: String) -> some View {
        overlay {
            if loading { TabSkeleton(title: title) }
        }
    }
}
