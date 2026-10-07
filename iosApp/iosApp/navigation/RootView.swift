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

    /// The month's budget (#47). Gated by `auto_budget`, so the tab is drawn
    /// only once `features` says the household has it.
    @StateObject private var budgetModel = BudgetViewModel()

    /// The household's goals (#52), on the tab gated by `goals`.
    @StateObject private var goalsModel = GoalsViewModel()

    /// What the tab bar may offer. Read once per signed-in user.
    @StateObject private var features = FeaturesViewModel()
    /// Income, Expenses, Investments and Debts, opened from Home's cards.
    @StateObject private var moneyModel = MoneyDetailViewModel()
    /// The quick entry those screens open — its own model, so a draft there
    /// and one on the full manual entry screen never meet.
    @StateObject private var moneyEntryModel = ManualEntryViewModel()
    /// Where the signed-in, set-up person is: home, or one of the two ways
    /// money gets in. Scene storage, so the app coming back after iOS
    /// reclaimed it reopens that screen rather than dropping them on home.
    @SceneStorage("home.route") private var homeRoute = HomeRoute.home.rawValue
    /// Where Review's back arrow goes: the tab it was opened from. Kept with the
    /// route, so a restored Review still knows its way out.
    @SceneStorage("home.reviewFrom") private var reviewFrom = HomeRoute.home.rawValue
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
            // The Budget tab's first read, and each change of month, as Android.
            || (showingBudget && (budgetModel.loading || budgetModel.switchingMonth))
    }

    private var showingHome: Bool { atHome && homeRoute == HomeRoute.home.rawValue }

    private var showingBudget: Bool { atHome && homeRoute == HomeRoute.budget.rawValue }

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
            case .home, .transactions, .budget, .goals:
                tabs
            case .review:
                ReviewView(
                    model: reviewModel,
                    userId: model.me?.user.id ?? "",
                    onClose: { withAnimation(.easeOut(duration: 0.25)) { homeRoute = reviewFrom } },
                    showsBack: true
                )
                .transition(.opacity.combined(with: .offset(y: 30)))
            case .income, .expenses, .investments, .debts:
                MoneyDetailView(
                    model: moneyModel,
                    entry: moneyEntryModel,
                    userId: model.me?.user.id ?? "",
                    kind: moneyKind(homeRoute),
                    onBack: { withAnimation(.easeOut(duration: 0.25)) { homeRoute = HomeRoute.home.rawValue } }
                )
                .transition(.opacity.combined(with: .offset(y: 30)))
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
                    onReview: { openReview() }
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
                onReview: { openReview() },
                onViewAll: { homeRoute = HomeRoute.transactions.rawValue },
                onSignOut: { model.signOut() },
                onOpenMoney: { kind in
                    withAnimation(.easeOut(duration: 0.3)) { homeRoute = HomeRoute.route(for: kind).rawValue }
                },
                // "See budget" only where the bar has a Budget tab to go to,
                // and on the month Home is showing. Bound first: a first bind
                // resets the model to the current month.
                onOpenBudget: features.shows(FeaturesViewModel.budgetFeature, onTab: false)
                    ? { month in
                        budgetModel.bind(userId: userId)
                        budgetModel.showMonth(month)
                        homeRoute = HomeRoute.budget.rawValue
                    }
                    : nil,
                onOpenGoals: features.shows(FeaturesViewModel.goalsFeature, onTab: false)
                    ? { homeRoute = HomeRoute.goals.rawValue }
                    : nil
            )
            .tabItem { Label(L.t(Strings.shared.tab_home), systemImage: "house.fill") }
            .tag(HomeRoute.home.rawValue)

            TransactionsView(model: transactionsModel, userId: userId, onClose: goHome, showsBack: false)
                .tabItem { Label(L.t(Strings.shared.tab_transactions), systemImage: "list.bullet.rectangle.fill") }
                .tag(HomeRoute.transactions.rawValue)

            if features.shows(FeaturesViewModel.budgetFeature, onTab: homeRoute == HomeRoute.budget.rawValue) {
                BudgetView(
                    model: budgetModel,
                    userId: userId,
                    onReview: { openReview() },
                    onImport: { homeRoute = HomeRoute.importStatement.rawValue }
                )
                .tabItem { Label(L.t(Strings.shared.tab_budget), systemImage: "chart.pie.fill") }
                .tag(HomeRoute.budget.rawValue)
            }

            if features.shows(FeaturesViewModel.goalsFeature, onTab: homeRoute == HomeRoute.goals.rawValue) {
                GoalsView(model: goalsModel, userId: userId)
                    .tabItem { Label(L.t(Strings.shared.tab_goals), systemImage: "flag.fill") }
                    .tag(HomeRoute.goals.rawValue)
            }
        }
        .task(id: userId) { features.bind(userId: userId) }
        // A tab that goes away under the person — the feature turned off
        // between reads — leaves them on a screen with no way back to it.
        // Keyed on what is known rather than on the tab: from "not read" to
        // "off" the tab never changes, so watching it would miss exactly the
        // case where a restored person must be moved.
        .onChange(of: features.gates) { _, _ in
            let onBudget = homeRoute == HomeRoute.budget.rawValue
            let onGoals = homeRoute == HomeRoute.goals.rawValue
            if features.leaves(FeaturesViewModel.budgetFeature, onTab: onBudget)
                || features.leaves(FeaturesViewModel.goalsFeature, onTab: onGoals) {
                goHome()
            }
        }
        .tint(Brand.greenDeep)
        // Leaving the tabs for good — for the import, an entry, signing out —
        // closes their clients. Switching between them does not: a tab keeps
        // its connections and its figures, and stays current through
        // LedgerChanged while it is out of sight.
        .onDisappear {
            dashboardModel.unbind()
            transactionsModel.unbind()
            reviewModel.unbind()
            budgetModel.unbind()
            goalsModel.unbind()
        }
    }

    /// Open Review, remembering the tab to go back to — Home when it is opened
    /// at the end of an import, which is finished by then. Android's `reviewReturnsTo`.
    private func openReview() {
        let tabs: [HomeRoute] = [.home, .transactions, .budget, .goals]
        let from = HomeRoute(rawValue: homeRoute) ?? .home
        reviewFrom = (tabs.contains(from) ? from : .home).rawValue
        homeRoute = HomeRoute.review.rawValue
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
    case budget
    /// The household's savings goals (#52) — the tab that took Review's place.
    case goals
    case add
    case importStatement = "import"
    /// Manual entry opened because a statement could not be read (#31).
    case addAfterImport = "add_after_import"
    /// The review queue (#32), from Home, from an import that left rows, and from
    /// the Budget tab's unfiled note. A flow over the bar since #52.
    case review
    /// Everything the household has, by statement or by month (#F3).
    case transactions
    /// The four money screens, from Home's cards.
    case income
    case expenses
    case investments
    case debts

    static func route(for kind: MoneyKind) -> HomeRoute {
        switch kind {
        case .income: .income
        case .expenses: .expenses
        case .investments: .investments
        case .debts: .debts
        }
    }
}

private func moneyKind(_ raw: String) -> MoneyKind {
    switch HomeRoute(rawValue: raw) {
    case .income: .income
    case .investments: .investments
    case .debts: .debts
    default: .expenses
    }
}
