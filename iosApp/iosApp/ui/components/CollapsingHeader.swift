import SwiftUI

/*
 The collapsing header Home has had since #45, shared so other field screens
 (the Budget tab) pin their name in the same bar, the same way. Mirrors
 Android's `CollapsingTitleBar`.
 */

// MARK: - Collapsing header

/**
 How far a field screen's big header has shrunk into the bar, 0 to 1 — observed by the
 header's fade and the bar alone.

 Kept out of `DashboardView`'s state on purpose. As `@State` there, every point
 scrolled re-ran the whole screen's body — chart, cards, rows — sixty times a
 second, which is the lag scrolling home had. Here only the two views that read
 `progress` redraw, and only while it changes: it is clamped and stepped, so
 past the header's own height scrolling changes nothing at all.
 */
@Observable
final class HeaderCollapse {
    /// The scroll over which the header shrinks into the bar: about its own height.
    static let distance: CGFloat = 64

    private(set) var progress: CGFloat = 0

    /// Where the header sat before any scrolling: the first position seen.
    @ObservationIgnored private var restingTop: CGFloat?

    func track(top: CGFloat) {
        let rest = restingTop ?? top
        if restingTop == nil { restingTop = top }
        let raw = min(max((rest - top) / Self.distance, 0), 1)
        // Thirty-two steps: smooth to the eye, and no update for a change too
        // small to see.
        let stepped = (raw * 32).rounded() / 32
        if stepped != progress { progress = stepped }
    }
}

/// The big header, fading out over the first half of the collapse.
struct HeaderFade: ViewModifier {
    let collapse: HeaderCollapse

    func body(content: Content) -> some View {
        content.opacity(Double(1 - min(collapse.progress * 2, 1)))
    }
}

/**
 The slim bar the header shrinks into, fading in over the second half. Solid,
 because figures pass under it, and reaching up under the status bar. Mirrors
 Android's `CompactHeader`.
 */
struct CollapsingBar<Content: View>: View {
    let collapse: HeaderCollapse
    let dark: Bool
    @ViewBuilder let content: () -> Content

    var body: some View {
        let opacity = Double(min(max((collapse.progress - 0.5) * 2, 0), 1))
        content()
            .background(
                Field.top(dark)
                    .shadow(color: .black.opacity(0.18 * opacity), radius: 6, y: 2)
                    .ignoresSafeArea(edges: .top)
            )
            .opacity(opacity)
            // A bar at nothing opacity must not catch a tap meant for the header.
            .allowsHitTesting(opacity > 0)
            .accessibilityHidden(opacity == 0)
    }
}


/// A screen's name, centred in the collapsed bar — the bar's usual content.
struct CompactTitle: View {
    let title: String

    var body: some View {
        Text(title)
            .font(.headline.weight(.bold))
            .foregroundColor(Field.ink())
            .lineLimit(1)
            .padding(.horizontal, 56)
            .frame(height: 52)
            .frame(maxWidth: .infinity)
            .accessibilityAddTraits(.isHeader)
    }
}
