import SwiftUI
import SharedLogic

/// The app's one coin loader, for every signed-out screen and the wizard.
///
/// Whenever it is up, it is in the middle of the screen with everything behind it
/// blurred, and it stays for at least `LoaderTiming.MIN_VISIBLE_MS` — or as long
/// as the work really takes. A card with a coin resting on its edge tells the
/// loader where that coin is, so the loader's coin sets off from there and goes
/// back there: one coin moving, rather than one vanishing while another appears.
final class AppLoader: ObservableObject {
    /// True while the loader's coin is away from its card; the card hides its own
    /// meanwhile.
    @Published fileprivate(set) var coinAway = false

    /// Where a card's coin rests, in global coordinates. Not published: it is only
    /// read as a trip starts, and publishing every layout pass would redraw the app.
    fileprivate(set) var resting: CGPoint?
    private var restingOwner: UUID?

    fileprivate func place(_ owner: UUID, at point: CGPoint) {
        restingOwner = owner
        resting = point
    }

    fileprivate func release(_ owner: UUID) {
        guard restingOwner == owner else { return }
        restingOwner = nil
        resting = nil
    }
}

private struct AppLoaderKey: EnvironmentKey {
    static let defaultValue = AppLoader()
}

extension EnvironmentValues {
    var appLoader: AppLoader {
        get { self[AppLoaderKey.self] }
        set { self[AppLoaderKey.self] = newValue }
    }
}

/// Wraps the whole app: blurs it and puts the coin over it while `active`, held
/// for the minimum however quickly the work finishes.
struct LoaderHost<Content: View>: View {
    let active: Bool
    @ObservedObject var loader: AppLoader
    @ViewBuilder var content: () -> Content

    @State private var shown = false
    @State private var shownAt = Date.distantPast
    @State private var coinVisible = false
    @State private var offset: CGSize = .zero
    @State private var scale: CGFloat = LoaderHost.loadingScale
    @State private var opacity: Double = 0

    private static var travel: Double { 0.42 }
    private static var fade: Double { 0.2 }
    /// The resting coin is 88pt; in the middle of the screen it reads at 112pt.
    private static var loadingScale: CGFloat { 112 / 88 }

    var body: some View {
        ZStack {
            content()
                .environment(\.appLoader, loader)
                .blur(radius: shown ? 18 : 0)
                .animation(.easeInOut(duration: Self.travel), value: shown)
                // Nothing behind the loader can be pressed while it is up.
                .allowsHitTesting(!shown)

            Color(.systemBackground)
                .opacity(shown ? 0.2 : 0)
                .animation(.easeInOut(duration: Self.travel), value: shown)
                .ignoresSafeArea()
                .allowsHitTesting(false)

            if coinVisible {
                CoinBadge()
                    .scaleEffect(scale)
                    .opacity(opacity)
                    .offset(offset)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
            }
        }
        .task(id: active) { await follow(active) }
    }

    @MainActor
    private func follow(_ active: Bool) async {
        if active {
            guard !shown else { return }
            shownAt = Date()
            shown = true
            // Typing is over for now, and a keyboard would cover the coin.
            dismissKeyboard()
            await travelOut()
        } else if shown {
            let elapsed = Int64(Date().timeIntervalSince(shownAt) * 1000)
            let wait = LoaderTiming.shared.remainingMs(shownAtMs: 0, nowMs: elapsed)
            if wait > 0 { try? await Task.sleep(for: .milliseconds(wait)) }
            guard !Task.isCancelled else { return }
            shown = false
            await travelHome()
        }
    }

    /// From the card's resting coin, or from nothing, to the middle.
    @MainActor
    private func travelOut() async {
        if !coinVisible {
            if let home = homeOffset {
                offset = home
                scale = 1
                opacity = 1
            } else {
                offset = .zero
                scale = Self.loadingScale
                opacity = 0
            }
            coinVisible = true
            loader.coinAway = true
            // Let the coin appear where it starts before it is asked to move.
            try? await Task.sleep(for: .milliseconds(16))
        }
        withAnimation(.easeInOut(duration: Self.travel)) {
            offset = .zero
            scale = Self.loadingScale
        }
        withAnimation(.easeInOut(duration: Self.fade)) { opacity = 1 }
    }

    /// Back to whichever card's coin is resting now, or a fade where there is none.
    @MainActor
    private func travelHome() async {
        guard coinVisible else { return }
        if let home = homeOffset {
            withAnimation(.easeInOut(duration: Self.travel)) {
                offset = home
                scale = 1
            }
            try? await Task.sleep(for: .milliseconds(420))
        } else {
            withAnimation(.easeInOut(duration: Self.fade)) { opacity = 0 }
            try? await Task.sleep(for: .milliseconds(200))
        }
        guard !Task.isCancelled else { return }
        coinVisible = false
        loader.coinAway = false
    }

    private var homeOffset: CGSize? {
        guard let resting = loader.resting else { return nil }
        let screen = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first?.screen.bounds ?? UIScreen.main.bounds
        return CGSize(width: resting.x - screen.midX, height: resting.y - screen.midY)
    }
}

/// The coin resting on a card's edge. It tells the app's loader where it is, and
/// steps aside while the loader's own coin is away.
struct CardCoin: View {
    @Environment(\.appLoader) private var loader

    var body: some View {
        CardCoinBody(loader: loader)
    }
}

private struct CardCoinBody: View {
    @ObservedObject var loader: AppLoader
    @State private var owner = UUID()

    var body: some View {
        CoinBadge()
            .opacity(loader.coinAway ? 0 : 1)
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { frame in
                loader.place(owner, at: CGPoint(x: frame.midX, y: frame.midY))
            }
            .onDisappear { loader.release(owner) }
    }
}
