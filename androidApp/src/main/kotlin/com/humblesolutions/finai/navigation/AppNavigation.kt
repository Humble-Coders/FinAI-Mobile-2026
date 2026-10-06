package com.humblesolutions.finai.navigation

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.humblesolutions.finai.BuildConfig
import com.humblesolutions.finai.auth.GoogleSignIn
import com.humblesolutions.finai.auth.GoogleSignInCancelled
import com.humblesolutions.finai.auth.GoogleSignInNotConfigured
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Capabilities
import com.humblesolutions.finai.model.OnboardingStep
import com.humblesolutions.finai.model.ResetStage
import com.humblesolutions.finai.model.SocialProvider
import com.humblesolutions.finai.ui.budget.BudgetRoute
import com.humblesolutions.finai.ui.components.AppLoader
import com.humblesolutions.finai.ui.components.LoaderHost
import com.humblesolutions.finai.ui.components.LoaderSignal
import com.humblesolutions.finai.ui.components.LoadingCard
import com.humblesolutions.finai.ui.dashboard.DashboardRoute
import com.humblesolutions.finai.ui.goals.GoalsRoute
import com.humblesolutions.finai.ui.manualentry.ManualEntryActions
import com.humblesolutions.finai.ui.manualentry.ManualEntryScreen
import com.humblesolutions.finai.ui.manualentry.ManualEntryViewModel
import com.humblesolutions.finai.ui.money.MoneyDetailRoute
import com.humblesolutions.finai.ui.onboarding.CodeScreen
import com.humblesolutions.finai.ui.onboarding.ConsentScreen
import com.humblesolutions.finai.ui.onboarding.FailedScreen
import com.humblesolutions.finai.ui.onboarding.NewPasswordScreen
import com.humblesolutions.finai.ui.onboarding.NotConfiguredScreen
import com.humblesolutions.finai.ui.onboarding.OnboardingViewModel
import com.humblesolutions.finai.ui.onboarding.PhoneScreen
import com.humblesolutions.finai.ui.onboarding.RegionScreen
import com.humblesolutions.finai.ui.onboarding.ResetRequestScreen
import com.humblesolutions.finai.ui.onboarding.SplashScreen
import com.humblesolutions.finai.ui.onboarding.UpdateRequiredScreen
import com.humblesolutions.finai.ui.onboarding.WelcomeScreen
import com.humblesolutions.finai.ui.review.ReviewRoute
import com.humblesolutions.finai.ui.setup.SetupScreen
import com.humblesolutions.finai.ui.setup.SetupViewModel
import com.humblesolutions.finai.ui.statementimport.StatementImportRoute
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.ui.transactions.TransactionsRoute
import com.humblesolutions.finai.usecase.Destination
import com.humblesolutions.finai.usecase.MoneyKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The router: a state variable, not a navigation framework (kmp-arch-v2).
 *
 * It renders whatever the **shared** rule says, and decides nothing itself. What
 * it checks first are sub-states the server knows nothing about: a password
 * reset, a sent code, and the region override reached from consent.
 */
@Composable
fun AppNavigation(viewModel: OnboardingViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val loader = remember { AppLoader() }

    // One coin loader for the whole app, mounted here so it survives every step
    // change: centred, with everything behind it blurred, for at least two
    // seconds. Signing in or up, the hand-over between steps, and the wizard's
    // first load all use it.
    LoaderHost(
        active = state.busy || state.showLoadingCard || loader.requested,
        loader = loader,
    ) {
        Box(Modifier.fillMaxSize()) { AppContent(viewModel) }
    }
}

@Composable
private fun AppContent(viewModel: OnboardingViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onGoogle = { launchGoogle(context, scope, viewModel) }

    state.configurationProblem?.let { problem ->
        NotConfiguredScreen(problem)
        return
    }

    // The launch intro plays in full before anything routes, so a signed-in
    // user's fast start still sees it. It hands over to the static splash below
    // if routing is still deciding, which looks identical minus the motion.
    if (!state.introFinished) {
        SplashScreen(slow = false, animate = true, onIntroFinished = viewModel::onIntroFinished)
        return
    }

    // The region override, reached from consent. Not an onboarding step: the
    // server is not asking for it, the user chose to correct it (PRD §4.6).
    var changingRegion by rememberSaveable { mutableStateOf(false) }
    if (changingRegion) {
        BackHandler { changingRegion = false }
        RegionScreen(state) { region ->
            viewModel.setRegion(region)
            changingRegion = false
        }
        return
    }

    // Ahead of the router: once the reset code verifies, the user is signed in,
    // and must choose the new password before going anywhere.
    when (state.reset) {
        ResetStage.REQUEST -> {
            BackHandler { viewModel.cancelReset() }
            ResetRequestScreen(state, viewModel::onEmailChange, viewModel::requestResetCode, viewModel::cancelReset)
            return
        }

        ResetStage.CODE -> {
            BackHandler { viewModel.startReset() }
            CodeScreen(
                state = state,
                sentTo = state.email,
                editKey = Strings.code_wrong_email,
                onCodeChange = viewModel::onCodeChange,
                onVerify = viewModel::verifyResetCode,
                onResend = viewModel::requestResetCode,
                onEdit = viewModel::startReset,
            )
            return
        }

        ResetStage.NEW_PASSWORD -> {
            BackHandler { viewModel.cancelReset() }
            NewPasswordScreen(state, viewModel::onPasswordChange, viewModel::saveNewPassword, viewModel::cancelReset)
            return
        }

        null -> Unit
    }

    // Between steps, not at launch: the coin carries the wait rather than the
    // brand screen, which would read as the app starting over.
    if (state.showLoadingCard) {
        LoadingCard()
        return
    }

    when (val destination = state.destination) {
        Destination.Splash -> {
            // Re-armed on every entry, so the splash after a code verify gets an
            // indicator too — not just the one at launch.
            LaunchedEffect(Unit) { viewModel.watchForSlowStart() }
            SplashScreen(slow = state.startIsSlow)
        }

        Destination.Welcome -> {
            val sentTo = state.emailCodeFor
            if (sentTo != null) {
                BackHandler { viewModel.editEmail() }
                CodeScreen(
                    state = state,
                    sentTo = sentTo,
                    editKey = Strings.code_wrong_email,
                    hintKey = Strings.email_code_hint,
                    onCodeChange = viewModel::onCodeChange,
                    onVerify = viewModel::verifyEmailCode,
                    onResend = viewModel::resendEmailCode,
                    onEdit = viewModel::editEmail,
                )
            } else {
                WelcomeScreen(
                    state = state,
                    onModeChange = viewModel::onWelcomeModeChange,
                    onEmailChange = viewModel::onEmailChange,
                    onPasswordChange = viewModel::onPasswordChange,
                    onSubmit = viewModel::submitCredentials,
                    onForgotPassword = viewModel::startReset,
                    onGoogle = onGoogle,
                )
            }
        }

        is Destination.Step -> when (destination.step) {
            OnboardingStep.PHONE -> PhoneOrCode(viewModel)

            OnboardingStep.REGION -> RegionScreen(state, viewModel::setRegion)

            OnboardingStep.CONSENT -> ConsentScreen(
                state = state,
                onChangeRegion = { changingRegion = true },
                onAccept = viewModel::acceptTerms,
            )

            OnboardingStep.FINANCIAL_SETUP -> SetupRoute(
                userId = state.me?.user?.id.orEmpty(),
                onFinished = viewModel::loadMe,
            )

            OnboardingStep.UNKNOWN -> UpdateRequiredScreen()
        }

        Destination.UpdateRequired -> UpdateRequiredScreen()

        Destination.Home -> HomeOrEntry(userId = state.me?.user?.id.orEmpty(), onSignOut = viewModel::signOut)

        is Destination.Failed -> FailedScreen(
            messageKey = destination.error.messageKey,
            onRetry = viewModel::retry,
            onSignOut = viewModel::signOut,
        )
    }
}

/**
 * The financial setup wizard, with its own view model: it owns a repository and
 * a draft that nothing else needs.
 *
 * A real `ViewModel`, not something remembered by the composition: a rotation
 * tears the composition down, and a wizard that lost the figure being typed
 * every time the phone turned would fail the standard it is held to. It is
 * cleared with the activity, which closes the clients it opened.
 *
 * Because it outlives a sign-out, the bind is keyed on who is signed in: a
 * different account rebinds and starts from an empty wizard.
 */
@Composable
private fun SetupRoute(userId: String, onFinished: () -> Unit) {
    val model: SetupViewModel = viewModel()
    val state by model.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(userId) { model.bind(userId, logging = BuildConfig.DEBUG) }
    // The first load is the only wait here that uses the coin; Continue does not.
    LoaderSignal(key = "setup", active = state.loading)

    SetupScreen(
        state = state,
        onBack = model::back,
        onCancel = model::cancel,
        onResume = model::resume,
        onIncomeChange = model::onIncomeChange,
        onExpenseChange = model::onExpenseChange,
        onOpenList = model::openList,
        onContinue = { model.continueStep(onFinished) },
        onSkip = { model.skip(onFinished) },
        onGoTo = model::goTo,
        onRowChange = model::onRowChange,
        onAddRow = model::addRow,
        onRemoveRow = model::removeRow,
        onKeepRows = model::keepRows,
        onDiscardRows = model::discardRows,
    )
}

/** Where the signed-in, set-up person is: home, or one of the two ways money gets in. */
internal enum class HomeRoute { HOME, ADD, IMPORT, ADD_AFTER_IMPORT, REVIEW, TRANSACTIONS, BUDGET, GOALS, INCOME, EXPENSES, INVESTMENTS, DEBTS }

/**
 * The places the bar always offers; everything else is a flow over them.
 *
 * Review is not among them since #52: Goals took its place on the bar. It is
 * reached from Home, from an import that left rows waiting, and from the
 * Budget tab's unfiled note, and opens over the bar with a way back.
 */
private val BASE_TABS = listOf(HomeRoute.HOME, HomeRoute.TRANSACTIONS)

/** A tab the bar offers only when capabilities enable its feature. */
private class GatedTab(val route: HomeRoute, val feature: String)

/** In bar order, after [BASE_TABS]: Budget (#47), then Goals (#52). */
private val GATED_TABS = listOf(
    GatedTab(HomeRoute.BUDGET, "auto_budget"),
    GatedTab(HomeRoute.GOALS, "goals"),
)

/** Every route the bar can hold, gated or not — what counts as a tab switch. */
private val ALL_TABS = BASE_TABS + GATED_TABS.map { it.route }

/**
 * The bar for a household: Home · Transactions, then Budget and Goals when
 * capabilities allow them.
 *
 * Absent rather than disabled: a tab that cannot be opened is a worse answer
 * than no tab, and the payload is the one place that decides (CLAUDE.md →
 * Region & feature gating).
 *
 * **Not known is not the same as off.** Capabilities are null until the first
 * read lands — which, after Android reclaims the process, is *after* the
 * saved route has already put the person back on their tab. Hiding it then
 * would send them home and strand the edit they came back for, so a person
 * already on a gated tab keeps it until the payload actually says no. One rule
 * for every gated tab, so the next one cannot be added without it.
 */
internal fun tabsFor(capabilities: Capabilities?, current: HomeRoute): List<HomeRoute> = BASE_TABS + GATED_TABS.filter { shows(it, capabilities, current) }.map { it.route }

private fun shows(tab: GatedTab, capabilities: Capabilities?, current: HomeRoute): Boolean = capabilities?.isEnabled(tab.feature) == true || (capabilities == null && current == tab.route)

/**
 * True when the person is on a gated tab and the payload has *said* its
 * feature is off — turned off between reads. Never on a payload not yet
 * read; see [tabsFor].
 */
internal fun leavesGatedTab(capabilities: Capabilities?, current: HomeRoute): Boolean = capabilities != null && GATED_TABS.any { it.route == current && !capabilities.isEnabled(it.feature) }

/**
 * Where Review's back arrow goes: the tab it was opened from. Opened at the
 * end of an import, that flow is finished, so home.
 */
internal fun reviewReturnsTo(from: HomeRoute): HomeRoute = if (from in ALL_TABS) from else HomeRoute.HOME

/** Income, Expenses, Investments and Debts, opened from Home's cards. */
private val MONEY = listOf(HomeRoute.INCOME, HomeRoute.EXPENSES, HomeRoute.INVESTMENTS, HomeRoute.DEBTS)

/**
 * Home and what opens from it: the statement import (#31), manual entry — from
 * home (#30) or from an import that could not be read — and the review queue
 * (#32), reached from home or from an import that left rows waiting.
 *
 * Home, every transaction and the review queue are tabs on a bottom bar. The
 * import and manual entry are flows with a start and an end, so they cover
 * the bar while open and give it back when they close.
 *
 * Saveable, so the app coming back after Android reclaimed it reopens the
 * screen the person was on rather than dropping them on home.
 */
@Composable
private fun HomeOrEntry(userId: String, onSignOut: () -> Unit) {
    var route by rememberSaveable { mutableStateOf(HomeRoute.HOME) }
    // The month Home's "See budget" asked the Budget tab to open on; cleared
    // once shown, so tapping the tab later opens wherever the person left it.
    var budgetMonth by rememberSaveable { mutableStateOf<String?>(null) }
    val features: FeaturesViewModel = viewModel()
    val capabilities by features.capabilities.collectAsStateWithLifecycle()
    LaunchedEffect(userId) { features.bind(userId, logging = BuildConfig.DEBUG) }
    val tabs = tabsFor(capabilities, route)
    // A tab that goes away under the person — the feature turned off between
    // reads — leaves them on a screen with no way back to it, so home. Keyed
    // on the payload rather than the tabs, and only once it has been read:
    // the first version keyed on the tabs and sent everyone restored onto
    // Budget home before capabilities arrived.
    LaunchedEffect(capabilities) { if (leavesGatedTab(capabilities, route)) route = HomeRoute.HOME }
    // Where Review goes back to. Saveable with the route, so a restored Review
    // still knows its way out.
    var reviewFrom by rememberSaveable { mutableStateOf(HomeRoute.HOME) }
    val openReview = {
        reviewFrom = reviewReturnsTo(route)
        route = HomeRoute.REVIEW
    }
    val tabbed = route in tabs
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                // The bar sits over the navigation bar's inset, so the screen
                // above it must not leave room for that inset a second time.
                .then(if (tabbed) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier),
        ) {
            AnimatedContent(
                targetState = route,
                transitionSpec = { homeTransition(initialState, targetState) },
                label = "homeRoute",
            ) { shown ->
                when (shown) {
                    HomeRoute.HOME -> DashboardRoute(
                        userId = userId,
                        onImportStatement = { route = HomeRoute.IMPORT },
                        onReview = openReview,
                        onAddTransaction = { route = HomeRoute.ADD },
                        onViewAll = { route = HomeRoute.TRANSACTIONS },
                        onSignOut = onSignOut,
                        onOpenMoney = { kind ->
                            route = when (kind) {
                                MoneyKind.INCOME -> HomeRoute.INCOME
                                MoneyKind.EXPENSES -> HomeRoute.EXPENSES
                                MoneyKind.INVESTMENTS -> HomeRoute.INVESTMENTS
                                MoneyKind.DEBTS -> HomeRoute.DEBTS
                            }
                        },
                        // "See budget" only where the bar has a Budget tab to go to,
                        // and on the month Home is showing.
                        onOpenBudget = if (HomeRoute.BUDGET in tabs) {
                            { month ->
                                budgetMonth = month
                                route = HomeRoute.BUDGET
                            }
                        } else {
                            null
                        },
                        onOpenGoals = if (HomeRoute.GOALS in tabs) {
                            { route = HomeRoute.GOALS }
                        } else {
                            null
                        },
                    )

                    HomeRoute.ADD -> ManualEntryRoute(userId = userId, fromUnreadable = false, onClose = { route = HomeRoute.HOME })

                    HomeRoute.ADD_AFTER_IMPORT ->
                        ManualEntryRoute(userId = userId, fromUnreadable = true, onClose = { route = HomeRoute.HOME })

                    HomeRoute.IMPORT -> StatementImportRoute(
                        userId = userId,
                        onClose = { route = HomeRoute.HOME },
                        onTypeInstead = { route = HomeRoute.ADD_AFTER_IMPORT },
                        onReview = openReview,
                    )

                    // A flow over the bar since #52, with a back arrow to the
                    // tab it was opened from.
                    HomeRoute.REVIEW -> ReviewRoute(userId = userId, onClose = { route = reviewFrom }, showsBack = true)

                    HomeRoute.TRANSACTIONS ->
                        TransactionsRoute(userId = userId, onClose = { route = HomeRoute.HOME }, showsBack = false)

                    HomeRoute.BUDGET -> BudgetRoute(
                        userId = userId,
                        onClose = { route = HomeRoute.HOME },
                        onReview = openReview,
                        onImport = { route = HomeRoute.IMPORT },
                        requestedMonth = budgetMonth,
                        onRequestedMonthShown = { budgetMonth = null },
                    )

                    HomeRoute.GOALS -> GoalsRoute(userId = userId, onClose = { route = HomeRoute.HOME })

                    // The four money screens open over the bar, like a flow,
                    // and back returns home.
                    HomeRoute.INCOME -> MoneyDetailRoute(userId, MoneyKind.INCOME, onBack = { route = HomeRoute.HOME })

                    HomeRoute.EXPENSES -> MoneyDetailRoute(userId, MoneyKind.EXPENSES, onBack = { route = HomeRoute.HOME })

                    HomeRoute.INVESTMENTS -> MoneyDetailRoute(userId, MoneyKind.INVESTMENTS, onBack = { route = HomeRoute.HOME })

                    HomeRoute.DEBTS -> MoneyDetailRoute(userId, MoneyKind.DEBTS, onBack = { route = HomeRoute.HOME })
                }
            }
        }
        if (tabbed) HomeBar(tabs, route) { route = it }
    }
}

/** The bottom bar: Material's own, so it looks and behaves as Android's do. */
@Composable
private fun HomeBar(tabs: List<HomeRoute>, current: HomeRoute, onSelect: (HomeRoute) -> Unit) {
    NavigationBar {
        tabs.forEach { tab ->
            val selected = tab == current
            val (filled, outlined, label) = when (tab) {
                HomeRoute.HOME -> Triple(Icons.Filled.Home, Icons.Outlined.Home, Strings.tab_home)

                HomeRoute.TRANSACTIONS -> Triple(
                    Icons.AutoMirrored.Filled.ReceiptLong,
                    Icons.AutoMirrored.Outlined.ReceiptLong,
                    Strings.tab_transactions,
                )

                HomeRoute.BUDGET -> Triple(Icons.Filled.PieChart, Icons.Outlined.PieChart, Strings.tab_budget)

                else -> Triple(Icons.Filled.Flag, Icons.Outlined.Flag, Strings.tab_goals)
            }
            NavigationBarItem(
                selected = selected,
                onClick = { if (!selected) onSelect(tab) },
                icon = { Icon(if (selected) filled else outlined, contentDescription = null) },
                label = { Text(strings(label)) },
            )
        }
    }
}

/**
 * Home and "Your transactions" share one green field, so between them only
 * the content moves: it fades and lifts while the ground carries across, as
 * if the list rose out of home. Other tab changes cross-fade, quickly; a flow
 * opening or closing changes at once, as it always has.
 */
private fun homeTransition(from: HomeRoute, to: HomeRoute): ContentTransform = when {
    from == HomeRoute.HOME && to == HomeRoute.TRANSACTIONS ->
        (fadeIn(tween(280)) + slideInVertically(tween(320)) { it / 12 }) togetherWith fadeOut(tween(200))

    from == HomeRoute.TRANSACTIONS && to == HomeRoute.HOME ->
        fadeIn(tween(240)) togetherWith (fadeOut(tween(220)) + slideOutVertically(tween(260)) { it / 12 })

    from in ALL_TABS && to in ALL_TABS -> fadeIn(tween(220)) togetherWith fadeOut(tween(160))

    // Review opens over the bar like the money screens do (#52).
    to == HomeRoute.REVIEW && from in ALL_TABS ->
        (fadeIn(tween(260)) + slideInVertically(tween(300)) { it / 14 }) togetherWith fadeOut(tween(180))

    from == HomeRoute.REVIEW && to in ALL_TABS ->
        fadeIn(tween(220)) togetherWith (fadeOut(tween(200)) + slideOutVertically(tween(240)) { it / 14 })

    // Out of a card on Home: rises in, and settles back down on the way out.
    from == HomeRoute.HOME && to in MONEY ->
        (fadeIn(tween(260)) + slideInVertically(tween(300)) { it / 14 }) togetherWith fadeOut(tween(180))

    from in MONEY && to == HomeRoute.HOME ->
        fadeIn(tween(220)) togetherWith (fadeOut(tween(200)) + slideOutVertically(tween(240)) { it / 14 })

    else -> EnterTransition.None togetherWith ExitTransition.None
}

/**
 * The manual entry screen with its own view model, which holds the draft
 * across rotation and — through its saved state — the process being killed.
 *
 * Internal so the import flow can open it when a statement cannot be read
 * (#31), passing [fromUnreadable] so the screen says why it is there.
 */
@Composable
internal fun ManualEntryRoute(userId: String, fromUnreadable: Boolean, onClose: () -> Unit) {
    val model: ManualEntryViewModel = viewModel()
    val state by model.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(userId) { model.bind(userId, logging = BuildConfig.DEBUG) }
    // Back to the front after a while away — possibly on another day.
    LifecycleResumeEffect(Unit) {
        model.refreshToday()
        onPauseOrDispose { }
    }
    LoaderSignal(key = "manual_entry", active = state.loading)

    val close = {
        model.discard()
        onClose()
    }
    BackHandler(onBack = close)

    ManualEntryScreen(
        state = state,
        fromUnreadable = fromUnreadable,
        actions = ManualEntryActions(
            onClose = close,
            onRetry = model::load,
            onAccountChosen = model::onAccountChosen,
            onDateChosen = model::onDateChosen,
            onToday = model::onToday,
            onRefreshToday = model::refreshToday,
            onAmountChange = model::onAmountChange,
            onDirectionChosen = model::onDirectionChosen,
            onDescriptionChange = model::onDescriptionChange,
            onCategoryChosen = model::onCategoryChosen,
            onSave = model::save,
            onKeepDuplicate = model::keepDuplicate,
            onDismissDuplicate = model::dismissDuplicate,
            onOpenNewAccount = model::openNewAccount,
            onNewAccountName = model::onNewAccountName,
            onNewAccountKind = model::onNewAccountKind,
            onCreateAccount = model::createAccount,
            onCancelNewAccount = model::cancelNewAccount,
        ),
    )
}

/**
 * Phone entry, or the code once one has been sent.
 *
 * Back from the code screen returns to the number rather than leaving the flow:
 * a mistyped digit is the commonest reason to press it.
 */
@Composable
private fun PhoneOrCode(viewModel: OnboardingViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (state.codeSent) {
        BackHandler { viewModel.editNumber() }
        CodeScreen(
            state = state,
            sentTo = state.e164,
            editKey = Strings.code_wrong_number,
            onCodeChange = viewModel::onCodeChange,
            onVerify = viewModel::verifyCode,
            onResend = viewModel::sendCode,
            onEdit = viewModel::editNumber,
        )
    } else {
        PhoneScreen(
            state = state,
            onPhoneChange = viewModel::onPhoneChange,
            onDialCodeSelected = viewModel::onDialCodeSelected,
            onContinue = viewModel::sendCode,
            onSignOut = viewModel::signOut,
        )
    }
}

/** Google's sheet; the view model decides whether the token signs in or links. */
private fun launchGoogle(context: Context, scope: CoroutineScope, viewModel: OnboardingViewModel) {
    viewModel.onProviderStarted()
    scope.launch {
        try {
            val idToken = GoogleSignIn.idToken(context)
            // Credential Manager supplies no nonce, so none is sent.
            viewModel.signInWithProvider(SocialProvider.GOOGLE, idToken, null)
        } catch (e: GoogleSignInCancelled) {
            viewModel.onProviderCancelled()
        } catch (e: GoogleSignInNotConfigured) {
            viewModel.onProviderFailed(Strings.error_provider_not_configured)
        } catch (e: Exception) {
            viewModel.onProviderFailed(Strings.error_provider_failed)
        }
    }
}
