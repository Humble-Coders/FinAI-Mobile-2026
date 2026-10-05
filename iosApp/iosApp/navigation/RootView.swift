import SwiftUI
import SharedLogic

/// The router: a state switch, not a navigation framework (kmp-arch-v2).
///
/// It renders whatever the **shared** rule says and decides nothing itself. What
/// it checks first are sub-states the server knows nothing about: a password
/// reset, a sent code, and the region override reached from consent.
struct RootView: View {
    @StateObject private var model = OnboardingViewModel()
    /// The wizard owns a repository and a draft nothing else needs, so it has
    /// its own model, built and closed with the screen.
    @StateObject private var setupModel = SetupViewModel()
    /// Manual entry's model, kept here like the wizard's so a draft outlives
    /// the view being rebuilt.
    @StateObject private var entryModel = ManualEntryViewModel()
    /// The statement import's model (#31), kept here for the same reason.
    @StateObject private var importModel = StatementImportViewModel()
    /// The review queue's model (#32), likewise.
    @StateObject private var reviewModel = ReviewViewModel()

    /// The dashboard, which is home (PRD F3).
    @StateObject private var dashboardModel = DashboardViewModel()

    /// Everything the household has (#F3).
    @StateObject private var transactionsModel = TransactionsViewModel()
    /// Where the signed-in, set-up person is: home, or one of the two ways
    /// money gets in. Scene storage, so the app coming back after iOS
    /// reclaimed it reopens that screen rather than dropping them on home.
    @SceneStorage("home.route") private var homeRoute = HomeRoute.home.rawValue
    /// One coin loader for the whole app: centred, everything behind it blurred,
    /// for at least two seconds.
    @StateObject private var loader = AppLoader()
    @State private var changingRegion = false

    var body: some View {
        LoaderHost(active: loaderActive, caption: showingImport ? importModel.loaderCaption : nil, loader: loader) {
        Group {
            if let problemKey = model.configurationProblemKey {
                NotConfiguredView(messageKey: problemKey)
            } else if !model.introFinished {
                // The launch intro plays in full before anything routes, so a
                // signed-in user's fast start still sees it. It hands over to
                // the static splash if routing is still deciding.
                SplashView(slow: false, animate: true) { model.finishIntro() }
            } else if model.showLoadingCard {
                // Between steps, not at launch: the coin carries the wait.
                LoadingCard()
            } else {
                content
            }
        }
        }
        .onAppear { model.bind() }
        .onDisappear { model.unbind() }
        .sheet(isPresented: $changingRegion) {
            // Not an onboarding step: the server is not asking for it, the user
            // chose to correct it (PRD §4.6).
            RegionView(model: model) { region in
                model.setRegion(region)
                changingRegion = false
            }
            .environment(\.appLoader, loader)
        }
    }

    /// Signing in or up, the hand-over between steps, and the wizard's first
    /// load. Continue inside the wizard is not here: it never waits on the coin.
    private var loaderActive: Bool {
        model.busy || model.showLoadingCard || (showingSetup && setupModel.loading)
            || (showingEntry && entryModel.loading)
            || (showingImport && (importModel.working || importModel.accountsLoading))
            || (showingReview && reviewModel.loading)
            // Home's first read: figures, not a screen of zeroes, until they arrive.
            || (showingHome && dashboardModel.loading)
    }

    private var showingHome: Bool { atHome && homeRoute == HomeRoute.home.rawValue }

    private var atHome: Bool {
        model.configurationProblemKey == nil
            && model.introFinished
            && !model.showLoadingCard
            && model.reset == nil
            && model.destination.screen == .home
    }

    private var showingEntry: Bool {
        atHome && (homeRoute == HomeRoute.add.rawValue || homeRoute == HomeRoute.addAfterImport.rawValue)
    }

    private var showingImport: Bool { atHome && homeRoute == HomeRoute.importStatement.rawValue }

    private var showingReview: Bool { atHome && homeRoute == HomeRoute.review.rawValue }

    private var showingSetup: Bool {
        model.configurationProblemKey == nil
            && model.introFinished
            && !model.showLoadingCard
            && model.reset == nil
            && model.destination.screen == .financialSetup
    }

    @ViewBuilder
    private var content: some View {
        // Ahead of the router: once the reset code verifies, the user is signed
        // in, and must choose the new password before going anywhere.
        switch model.reset {
        case .request:
            ResetRequestView(model: model)
        case .code:
            CodeView(
                model: model,
                sentTo: model.email,
                editKey: Strings.shared.code_wrong_email,
                onVerify: { model.verifyResetCode() },
                onResend: { model.requestResetCode() },
                onEdit: { model.startReset() }
            )
        case .newPassword:
            NewPasswordView(model: model)
        default:
            routed
        }
    }

    @ViewBuilder
    private var routed: some View {
        switch model.destination.screen {
        case .splash:
            // .task is cancelled when the splash goes away and restarted on
            // every entry, so the splash after a code verify gets an indicator
            // too - not just the one at launch.
            SplashView(slow: model.startIsSlow)
                .task { await model.watchForSlowStart() }
        case .welcome:
            if let sentTo = model.emailCodeFor {
                CodeView(
                    model: model,
                    sentTo: sentTo,
                    editKey: Strings.shared.code_wrong_email,
                    hintKey: Strings.shared.email_code_hint,
                    onVerify: { model.verifyEmailCode() },
                    onResend: { model.resendEmailCode() },
                    onEdit: { model.editEmail() }
                )
            } else {
                WelcomeView(model: model)
            }
        case .phone:
            // Signed in, and the account has not verified a number yet.
            phoneOrCode
        case .region:
            RegionView(model: model) { model.setRegion($0) }
        case .consent:
            ConsentView(model: model) { changingRegion = true }
        case .financialSetup:
            SetupView(model: setupModel) { model.loadMe() }
                .onAppear { setupModel.bind() }
                .onDisappear { setupModel.unbind() }
        case .updateRequired:
            UpdateRequiredView()
        case .home:
            switch HomeRoute(rawValue: homeRoute) ?? .home {
            case .home, .transactions, .review:
                tabs
            case .add, .addAfterImport:
                ManualEntryView(
                    model: entryModel,
                    userId: model.me?.user.id ?? "",
                    fromUnreadable: homeRoute == HomeRoute.addAfterImport.rawValue
                ) {
                    homeRoute = HomeRoute.home.rawValue
                }
            case .importStatement:
                StatementImportView(
                    model: importModel,
                    userId: model.me?.user.id ?? "",
                    onClose: { homeRoute = HomeRoute.home.rawValue },
                    onTypeInstead: { homeRoute = HomeRoute.addAfterImport.rawValue },
                    onReview: { homeRoute = HomeRoute.review.rawValue }
                )
            }
        case .failed:
            FailedView(
                messageKey: failureKey,
                onRetry: { model.retry() },
                onSignOut: { model.signOut() }
            )
        default:
            SplashView(slow: model.startIsSlow)
                .task { await model.watchForSlowStart() }
        }
    }

    /**
     Home, every transaction and the review queue, on the system tab bar. The
     import and manual entry are flows with a start and an end, so they cover
     the bar while open and give it back when they close. The selection is the
     scene-stored route itself, so the app coming back reopens the same tab.
     */
    private var tabs: some View {
        let userId = model.me?.user.id ?? ""
        let goHome = { homeRoute = HomeRoute.home.rawValue }
        return TabView(selection: $homeRoute) {
            DashboardView(
                model: dashboardModel,
                userId: userId,
                onImportStatement: { homeRoute = HomeRoute.importStatement.rawValue },
                onAddTransaction: { homeRoute = HomeRoute.add.rawValue },
                onReview: { homeRoute = HomeRoute.review.rawValue },
                onViewAll: { homeRoute = HomeRoute.transactions.rawValue },
                onSignOut: { model.signOut() }
            )
            .tabItem { Label(L.t(Strings.shared.tab_home), systemImage: "house.fill") }
            .tag(HomeRoute.home.rawValue)

            TransactionsView(model: transactionsModel, userId: userId, onClose: goHome, showsBack: false)
                .tabItem { Label(L.t(Strings.shared.tab_transactions), systemImage: "list.bullet.rectangle.fill") }
                .tag(HomeRoute.transactions.rawValue)

            ReviewView(model: reviewModel, userId: userId, onClose: goHome, showsBack: false)
                .tabItem { Label(L.t(Strings.shared.tab_review), systemImage: "checkmark.circle.fill") }
                .tag(HomeRoute.review.rawValue)
        }
        .tint(Brand.greenDeep)
    }

    private var failureKey: String {
        (model.destination as? Destination.Failed)?.error.messageKey
            ?? Strings.shared.error_unexpected
    }

    @ViewBuilder
    private var phoneOrCode: some View {
        if model.codeSent {
            CodeView(
                model: model,
                sentTo: model.e164,
                editKey: Strings.shared.code_wrong_number,
                onVerify: { model.verifyCode() },
                onResend: { model.sendCode() },
                onEdit: { model.editNumber() }
            )
        } else {
            PhoneView(model: model)
        }
    }
}

/// Home, and the screens opened from it.
private enum HomeRoute: String {
    case home
    case add
    case importStatement = "import"
    /// Manual entry opened because a statement could not be read (#31).
    case addAfterImport = "add_after_import"
    /// The review queue (#32), from home or from an import that left rows.
    case review
    /// Everything the household has, by statement or by month (#F3).
    case transactions
}
