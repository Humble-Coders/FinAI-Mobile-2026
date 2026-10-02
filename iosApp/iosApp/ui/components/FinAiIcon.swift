import SwiftUI

/**
 An SF Symbol at a given size, scaled with the reader's text size.

 The scaling is the point of this wrapper. A fixed-point icon stays put while
 the label beside it grows, so at the larger Dynamic Type sizes a row ends up
 as big words next to a tiny mark. `@ScaledMetric` keeps the pair in
 proportion; the cap stops a 200% setting turning a chevron into a slab.

 `label` is nil for an icon that repeats a label beside it, which is most of
 them — VoiceOver should hear the row once.
 */
struct FinAiIcon: View {
    let symbol: String
    let tint: Color
    var size: CGFloat = 20
    var label: String?

    /// Scales with the body text style, which is what the labels beside these
    /// use, so the two move together.
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * min(scale, Self.maxScale)))
            .foregroundColor(tint)
            .modifier(IconAccessibility(label: label))
    }

    /// Beyond this an icon stops being an icon and starts being a block.
    private static let maxScale: CGFloat = 1.6
}

/// Hidden when it has no label of its own, rather than announced as an image.
private struct IconAccessibility: ViewModifier {
    let label: String?

    func body(content: Content) -> some View {
        if let label {
            content.accessibilityLabel(label)
        } else {
            content.accessibilityHidden(true)
        }
    }
}
