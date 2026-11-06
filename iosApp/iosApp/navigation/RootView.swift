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

    /// The coach's conversations (#54), on the tab gated by `ai_chat`.
    @StateObject private var chatModel = ChatViewModel()

    /// Your plan and Your debts (#76), gated by `debt_plan`.
    @StateObject private var planModel = PlanViewModel()

    /// Split expense, from Home's "Split a bill", gated by `split_expenses`.
    @StateObject private var splitModel = SplitViewModel()

    /// Profile (PM decision, 2026-11-06), opened over the bar from Home's
    /// monogram and from every "Open Profile".
    @StateObject private var profileModel = ProfileViewModel()

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
    /// Where the plan's back arrow goes: the screen it was opened from (#76).
    @SceneStorage("home.planFrom") private var planFrom = HomeRoute.home.rawValue
    /// Where the money screens' back arrow goes: Home, or the Budget tab's
    /// "Add any expected income".
    @SceneStorage("home.moneyFrom") private var moneyFrom = HomeRoute.home.rawValue
    /// Where Profile's back arrow goes: the tab or screen it was opened from,
    /// so an "Open Profile" in the middle of an import comes back to it.
    @SceneStorage("home.profileFrom") private var profileFrom = HomeRoute.home.rawValue
    /// One coin loader for the whole app: centred, everything behind it blurred.
    /// Signing in or up holds it for two seconds; after that, as long as it takes.
    @StateObject private var loader = AppLoader()
    @State private var changingRegion = false
    /// Setup's step 4: the AI's suggested budget, once steps 1–3 are saved.
    @State private var suggestingBudget = false
    /// The last tab shown, which stays under a screen opened over the bar.
    @State private var lastTab = HomeRoute.home.rawValue
    /// Whether this process has shown the app yet; see `onAppear`.
    @MainActor private static var launched = false

    var body: some View {
        LoaderHost(active: loaderActive, minimumMs: LoaderTiming.shared.minimumMs(signedIn: model.session == .signedIn), caption: loaderCaption, loader: loader) {
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
        .onAppear {
            // Opening the app is a fresh start: Home, not the screen that was
            // open last time — except an import under way, whose file would
            // otherwise be lost. Coming back from the background keeps the screen.
            if !RootView.launched {
                RootView.launched = true
                if homeRoute != HomeRoute.importStatement.rawValue { homeRoute = HomeRoute.home.rawValue }
            }
            model.bind()
        }
        .onDisappear { model.unbind() }
        // Signed out — from Profile, or the account deleted — so whoever signs
        // in next starts on Home, not on the last person's Profile.
        .onChange(of: model.session) { _, session in
            if session == .signedOut { homeRoute = HomeRoute.home.rawValue }
        }
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

    /// What the coin's wait is for, where a screen says: an import's pages, a receipt being read.
    private var loaderCaption: String? {
        if showingImport { return importModel.loaderCaption }
        if showingSplit { return splitModel.loaderCaption }
        return nil
    }

    /// Signing in or up, the hand-over between steps, and the wizard's first
    /// load. Continue inside the wizard is not here: it never waits on the coin.
    private var loaderActive: Bool {
        // Not while the suggested budget shows: SetupView's unbind leaves the
        // wizard's model loading, and the coin would cover the suggestion.
        model.busy || model.showLoadingCard || (showingSetup && !suggestingBudget && setupModel.loading)
            // The answers being read for amending, from Profile.
            || (showingSetupEdit && setupModel.loading)
            || (showingEntry && entryModel.loading)
            || (showingImport && (importModel.working || importModel.accountsLoading))
            || (showingReview && reviewModel.loading)
            // A tab's first read draws the page's shape (`tabSkeleton`); a change
            // of month keeps the coin, with the figures left up, as Android.
            || (showingHome && dashboardModel.switchingMonth)
            || (showingBudget && budgetModel.switchingMonth)
            // The plan's first read (#76); a return refreshes under what is shown.
            || (showingPlan && planModel.loading && planModel.plan == nil)
            // A conversation opening earns the coin; the list's first read is a skeleton.
            || (showingCoach && chatModel.threadLoading && chatModel.thread == nil)
            || (showingTransactions && transactionsModel.switchingMonth)
            // Home's money screens, as Android: a first load, and each change of month.
            || (showingMoney && (moneyModel.loading || moneyModel.switchingMonth))
            // Reading a receipt, opening a saved split, the history's first read.
            || (showingSplit && splitModel.working)
    }

    private var showingSplit: Bool { atHome && homeRoute == HomeRoute.split.rawValue }

    private var showingPlan: Bool {
        atHome && (homeRoute == HomeRoute.plan.rawValue || homeRoute == HomeRoute.planDebts.rawValue)
    }

    private var showingHome: Bool { atHome && homeRoute == HomeRoute.home.rawValue }

    private var showingSetupEdit: Bool { atHome && homeRoute == HomeRoute.editSetup.rawValue }

    private var showingBudget: Bool { atHome && homeRoute == HomeRoute.budget.rawValue }

    private var showingGoals: Bool { atHome && homeRoute == HomeRoute.goals.rawValue }

    private var showingCoach: Bool { atHome && homeRoute == HomeRoute.coach.rawValue }

    private var showingTransactions: Bool { atHome && homeRoute == HomeRoute.transactions.rawValue }

    private var showingMoney: Bool {
        atHome && [HomeRoute.income, .expenses, .investments, .debts].map(\.rawValue).contains(homeRoute)
    }

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
            if suggestingBudget {
                // Start or Skip there is what finishes setup.
                AiBudgetView(
                    userId: model.me?.user.id ?? "",
                    inSetup: true,
                    onDone: {
                        suggestingBudget = false
                        model.loadMe()
                    },
                    // Back to the last step; the suggestion stays kept for
                    // when they come forward again.
                    onBack: { suggestingBudget = false }
                )
            } else {
                SetupView(model: setupModel) { suggestingBudget = true }
                    .onAppear { setupModel.bind(amending: false) }
                    .onDisappear { setupModel.unbind() }
            }
        case .updateRequired:
            UpdateRequiredView()
        case .home:
            // The tabs stay alive under a screen opened over the bar, so each
            // keeps its scroll position and figures for when it comes back.
            ZStack {
                tabs
                    .opacity(onTabs ? 1 : 0)
                    .allowsHitTesting(onTabs)
                    .accessibilityHidden(!onTabs)
                overTabs
            }
            // Every "Open Profile" and disclaimer line under here reaches it.
            .environment(\.openProfile, { openProfile() })
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

    /// The screens that open over the tab bar: Review, the money screens, the
    /// plan, an entry, a split, an import. Nothing while a tab is showing.
    @ViewBuilder
    private var overTabs: some View {
        switch HomeRoute(rawValue: homeRoute) ?? .home {
        case .home, .transactions, .budget, .goals, .coach:
            EmptyView()
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
                onBack: { withAnimation(.easeOut(duration: 0.25)) { homeRoute = moneyFrom } },
                onOpenPlan: homeRoute == HomeRoute.debts.rawValue && features.gate(FeaturesViewModel.planFeature) == .on
                    ? { openPlan(.planDebts) }
                    : nil
            )
            .transition(.opacity.combined(with: .offset(y: 30)))
        case .plan, .planDebts:
            PlanView(
                model: planModel,
                userId: model.me?.user.id ?? "",
                startOnDebts: homeRoute == HomeRoute.planDebts.rawValue,
                onClose: {
                    planModel.unbind()
                    withAnimation(.easeOut(duration: 0.25)) { homeRoute = planFrom }
                },
                onOpenGoals: {
                    planModel.unbind()
                    withAnimation(.easeOut(duration: 0.25)) { homeRoute = HomeRoute.goals.rawValue }
                }
            )
            .transition(.opacity.combined(with: .offset(y: 30)))
            // Turned off between reads: nowhere to be, so home.
            .onChange(of: features.gates) { _, gates in
                if gates.plan == .off { homeRoute = HomeRoute.home.rawValue }
            }
        case .add, .addAfterImport:
            ManualEntryView(
                model: entryModel,
                userId: model.me?.user.id ?? "",
                fromUnreadable: homeRoute == HomeRoute.addAfterImport.rawValue
            ) {
                homeRoute = HomeRoute.home.rawValue
            }
        case .split:
            SplitView(
                model: splitModel,
                userId: model.me?.user.id ?? "",
                onClose: { withAnimation(.easeOut(duration: 0.25)) { homeRoute = HomeRoute.home.rawValue } }
            )
            .transition(.opacity.combined(with: .offset(y: 30)))
            // Turned off between reads: nowhere to be, so home.
            .onChange(of: features.gates) { _, gates in
                if gates.split == .off {
                    splitModel.close()
                    homeRoute = HomeRoute.home.rawValue
                }
            }
        case .importStatement:
            StatementImportView(
                model: importModel,
                userId: model.me?.user.id ?? "",
                onClose: { homeRoute = HomeRoute.home.rawValue },
                onTypeInstead: { homeRoute = HomeRoute.addAfterImport.rawValue },
                onReview: { openReview() }
            )
        case .profile:
            ProfileView(
                model: profileModel,
                account: model,
                onBack: { withAnimation(.easeOut(duration: 0.25)) { homeRoute = profileFrom } },
                onChangeRegion: { changingRegion = true },
                onEditSetup: { withAnimation(.easeOut(duration: 0.3)) { homeRoute = HomeRoute.editSetup.rawValue } },
                onDeleted: { model.accountDeleted() }
            )
            .transition(.opacity.combined(with: .offset(y: 30)))
        case .editSetup:
            // The wizard over answers already given: no suggested budget at
            // the end, and Cancel or Save both go back to Profile.
            SetupView(
                model: setupModel,
                onCancel: { withAnimation(.easeOut(duration: 0.25)) { homeRoute = HomeRoute.profile.rawValue } },
                onFinished: {
                    // Income, commitments, debts and investments feed every
                    // figure screen: each re-reads on its own notification.
                    LedgerChanged.announce()
                    BudgetChanged.announce()
                    GoalsChanged.announce()
                    PlanChanged.announce()
                    profileModel.show(Strings.shared.profile_setup_updated)
                    withAnimation(.easeOut(duration: 0.25)) { homeRoute = HomeRoute.profile.rawValue }
                }
            )
            .onAppear { setupModel.bind(amending: true) }
            .onDisappear { setupModel.unbind() }
            .transition(.opacity.combined(with: .offset(y: 30)))
        }
    }

    private static let tabRoutes: Set<String> = Set(
        [HomeRoute.home, .transactions, .budget, .goals, .coach].map(\.rawValue)
    )

    private var onTabs: Bool { Self.tabRoutes.contains(homeRoute) }

    /// The tab the bar shows: the route itself on a tab, otherwise the tab the
    /// screen over it was opened from, so the bar under it stays where it was.
    private var currentTab: String { onTabs ? homeRoute : lastTab }

    private var tabSelection: Binding<String> {
        Binding(get: { currentTab }, set: { homeRoute = $0 })
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
        return TabView(selection: tabSelection) {
            DashboardView(
                model: dashboardModel,
                userId: userId,
                onImportStatement: { homeRoute = HomeRoute.importStatement.rawValue },
                onAddTransaction: { homeRoute = HomeRoute.add.rawValue },
                onReview: { openReview() },
                onViewAll: { homeRoute = HomeRoute.transactions.rawValue },
                profileInitials: model.me.map { Profile.shared.initials(me: $0) } ?? "",
                onOpenProfile: { openProfile() },
                onOpenMoney: { kind in
                    moneyFrom = HomeRoute.home.rawValue
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
                    : nil,
                onOpenPlan: features.gate(FeaturesViewModel.planFeature) == .on
                    ? { openPlan(.plan) }
                    : nil,
                onSplit: features.gate(FeaturesViewModel.splitFeature) == .on
                    ? { withAnimation(.easeOut(duration: 0.3)) { homeRoute = HomeRoute.split.rawValue } }
                    : nil
            )
            .tabSkeleton(dashboardModel.loading && !dashboardModel.switchingMonth, title: L.t(Strings.shared.tab_home))
            .tabItem { Label(L.t(Strings.shared.tab_home), systemImage: "house.fill") }
            .tag(HomeRoute.home.rawValue)

            TransactionsView(model: transactionsModel, userId: userId, onClose: goHome, showsBack: false)
                .tabSkeleton(transactionsModel.loading && !transactionsModel.switchingMonth, title: L.t(Strings.shared.tab_transactions))
                .tabItem { Label(L.t(Strings.shared.tab_transactions), systemImage: "list.bullet.rectangle.fill") }
                .tag(HomeRoute.transactions.rawValue)

            if features.shows(FeaturesViewModel.budgetFeature, onTab: currentTab == HomeRoute.budget.rawValue) {
                BudgetView(
                    model: budgetModel,
                    userId: userId,
                    onReview: { openReview() },
                    onImport: { homeRoute = HomeRoute.importStatement.rawValue },
                    onOpenPlan: features.gate(FeaturesViewModel.planFeature) == .on
                        ? { openPlan(.plan) }
                        : nil,
                    onOpenIncome: {
                        moneyFrom = HomeRoute.budget.rawValue
                        withAnimation(.easeOut(duration: 0.3)) { homeRoute = HomeRoute.income.rawValue }
                    }
                )
                .tabSkeleton(
                    budgetModel.loading && budgetModel.budget == nil && !budgetModel.switchingMonth,
                    title: L.t(Strings.shared.tab_budget)
                )
                .tabItem { Label(L.t(Strings.shared.tab_budget), systemImage: "chart.pie.fill") }
                .tag(HomeRoute.budget.rawValue)
            }

            if features.shows(FeaturesViewModel.goalsFeature, onTab: currentTab == HomeRoute.goals.rawValue) {
                GoalsView(model: goalsModel, userId: userId)
                    .tabSkeleton(goalsModel.loading && goalsModel.page == nil, title: L.t(Strings.shared.tab_goals))
                    .tabItem { Label(L.t(Strings.shared.tab_goals), systemImage: "flag.fill") }
                    .tag(HomeRoute.goals.rawValue)
            }

            if features.shows(FeaturesViewModel.chatFeature, onTab: currentTab == HomeRoute.coach.rawValue) {
                CoachView(model: chatModel, userId: userId)
                    .tabSkeleton(chatModel.listLoading && !chatModel.listLoaded, title: L.t(Strings.shared.chat_tab))
                    .tabItem { Label(L.t(Strings.shared.chat_tab), systemImage: "bubble.left.and.bubble.right.fill") }
                    .tag(HomeRoute.coach.rawValue)
            }
        }
        .task(id: userId) { features.bind(userId: userId) }
        // A tab that goes away under the person — the feature turned off
        // between reads — leaves them on a screen with no way back to it.
        // Keyed on what is known rather than on the tab: from "not read" to
        // "off" the tab never changes, so watching it would miss exactly the
        // case where a restored person must be moved.
        .onChange(of: features.gates) { _, _ in
            let onBudget = currentTab == HomeRoute.budget.rawValue
            let onGoals = currentTab == HomeRoute.goals.rawValue
            let onCoach = currentTab == HomeRoute.coach.rawValue
            if features.leaves(FeaturesViewModel.budgetFeature, onTab: onBudget)
                || features.leaves(FeaturesViewModel.goalsFeature, onTab: onGoals)
                || features.leaves(FeaturesViewModel.chatFeature, onTab: onCoach) {
                // Under a screen over the bar, only the tab it returns to moves.
                if onTabs { goHome() } else { lastTab = HomeRoute.home.rawValue }
            }
        }
        .onChange(of: homeRoute, initial: true) { _, route in
            if Self.tabRoutes.contains(route) { lastTab = route }
        }
        .tint(Brand.greenDeep)
        // Leaving the tabs for good — signing out — closes their clients. A
        // screen opened over the bar does not: the tabs stay alive under it,
        // keep their connections, figures and scroll, and stay current through
        // LedgerChanged while out of sight.
        .onDisappear {
            dashboardModel.unbind()
            transactionsModel.unbind()
            reviewModel.unbind()
            budgetModel.unbind()
            goalsModel.unbind()
            chatModel.unbind()
        }
    }

    /// Open Profile, remembering where to go back to. From the wizard it
    /// opened, the way back stays the one Profile already had.
    private func openProfile() {
        let from = HomeRoute(rawValue: homeRoute) ?? .home
        if from != .profile && from != .editSetup { profileFrom = from.rawValue }
        withAnimation(.easeOut(duration: 0.3)) { homeRoute = HomeRoute.profile.rawValue }
    }

    /// Open the plan (#76), remembering where to go back to.
    private func openPlan(_ to: HomeRoute) {
        planFrom = homeRoute
        withAnimation(.easeOut(duration: 0.3)) { homeRoute = to.rawValue }
    }

    /// Open Review, remembering the tab to go back to — Home when it is opened
    /// at the end of an import, which is finished by then. Android's `reviewReturnsTo`.
    private func openReview() {
        let tabs: [HomeRoute] = [.home, .transactions, .budget, .goals, .coach]
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
    /// The AI coach (#54), the tab after Goals.
    case coach
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
    /// Your plan and Your debts (#76), from Home, Budget and Debts.
    case plan
    case planDebts = "plan_debts"
    /// Split expense, from Home's "Split a bill".
    case split
    /// Profile, from Home's monogram and every "Open Profile".
    case profile
    /// The setup wizard over answers already given, from Profile.
    case editSetup = "edit_setup"

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
