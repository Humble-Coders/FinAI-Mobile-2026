import SharedLogic
import SwiftUI

/// One tab of the bottom bar: its route, its words and its picture.
struct HomeTab: Identifiable {
    let id: String
    let titleKey: String
    let symbol: String
}

/**
 Home, every transaction and the review queue, with the bar beneath them.

 Not the system `TabView`, for one reason: it switches tabs with a cut and
 offers no way to animate it, so moving between Home and Transactions jumped.
 Here the tabs share one stack and cross-fade, and a tab once visited stays
 built — returning to Home shows Home as it was, with its figures refreshed
 behind it, rather than rebuilding it from nothing. A tab is built on its first
 visit, not at launch, so opening the app still reads only Home.

 The bar is drawn to the system's measure — material, hairline, 49pt, SF
 Symbols — so it reads as the iOS tab bar it replaces.
 */
struct HomeTabs<Content: View>: View {
    @Binding var selection: String
    let tabs: [HomeTab]
    @ViewBuilder let content: (String) -> Content

    @State private var visited: Set<String> = []
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            ForEach(tabs) { tab in
                page(tab)
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) { bar }
        .onAppear { visited.insert(selection) }
        .onChange(of: selection) { _, next in visited.insert(next) }
    }

    @ViewBuilder
    private func page(_ tab: HomeTab) -> some View {
        let shown = selection == tab.id
        Group {
            if visited.contains(tab.id) || shown {
                content(tab.id)
            }
        }
        .opacity(shown ? 1 : 0)
        // A small lift on the way in; none at all with Reduce Motion.
        .offset(y: shown || reduceMotion ? 0 : 10)
        .allowsHitTesting(shown)
        .accessibilityHidden(!shown)
        .zIndex(shown ? 1 : 0)
        .animation(.easeInOut(duration: reduceMotion ? 0.12 : 0.24), value: selection)
    }

    private var bar: some View {
        HStack(spacing: 0) {
            ForEach(tabs) { tab in
                let selected = selection == tab.id
                Button {
                    selection = tab.id
                } label: {
                    VStack(spacing: 3) {
                        Image(systemName: tab.symbol)
                            .font(.system(size: 22, weight: selected ? .semibold : .regular))
                            .symbolVariant(selected ? .fill : .none)
                        Text(L.t(tab.titleKey))
                            .font(.caption2.weight(selected ? .semibold : .medium))
                            .lineLimit(1)
                    }
                    .foregroundColor(selected ? Brand.greenDeep : Color(.secondaryLabel))
                    .frame(maxWidth: .infinity, minHeight: 49)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? [.isSelected, .isButton] : .isButton)
                .accessibilityLabel(L.t(tab.titleKey))
            }
        }
        .padding(.top, 2)
        .background(.bar, ignoresSafeAreaEdges: .bottom)
        .overlay(alignment: .top) { Divider() }
    }
}

/**
 Stops a scroll view being pulled down past its top, keeping the bounce at the
 bottom. Home's header is the top of the page: dragged down, it came away from
 the status bar and showed what was behind it.

 Placed inside the scroll view's content, it finds the `UIScrollView` hosting
 it and watches its offset — SwiftUI on iOS 17 has no switch for one end alone.
 */
struct TopBounceStopper: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView { Probe() }

    func updateUIView(_ uiView: UIView, context: Context) {}

    final class Probe: UIView {
        private var watching: NSKeyValueObservation?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            isUserInteractionEnabled = false
            guard watching == nil, let scroll = enclosingScrollView() else { return }
            watching = scroll.observe(\.contentOffset, options: [.new]) { scroll, _ in
                let top = -scroll.adjustedContentInset.top
                // No bounce while at the top, so a pull down does nothing; the
                // bounce comes back once scrolled, for the bottom.
                let atTop = scroll.contentOffset.y <= top + 0.5
                if scroll.bounces == atTop { scroll.bounces = !atTop }
                // A fling back up can still overshoot before that takes hold.
                if scroll.contentOffset.y < top { scroll.contentOffset.y = top }
            }
        }

        private func enclosingScrollView() -> UIScrollView? {
            var view = superview
            while let current = view {
                if let scroll = current as? UIScrollView { return scroll }
                view = current.superview
            }
            return nil
        }
    }
}
