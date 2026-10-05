import SwiftUI
import UIKit

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
