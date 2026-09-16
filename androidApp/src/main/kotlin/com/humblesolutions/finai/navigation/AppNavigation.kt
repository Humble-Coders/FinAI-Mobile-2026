package com.humblesolutions.finai.navigation

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.humblesolutions.finai.BuildConfig
import com.humblesolutions.finai.auth.GoogleSignIn
import com.humblesolutions.finai.auth.GoogleSignInCancelled
import com.humblesolutions.finai.auth.GoogleSignInNotConfigured
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.OnboardingStep
import com.humblesolutions.finai.model.ResetStage
import com.humblesolutions.finai.model.SocialProvider
import com.humblesolutions.finai.ui.components.LoadingCard
import com.humblesolutions.finai.ui.components.LoadingCoin
import com.humblesolutions.finai.ui.home.HomeScreen
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
import com.humblesolutions.finai.ui.setup.SetupScreen
import com.humblesolutions.finai.ui.setup.SetupViewModel
import com.humblesolutions.finai.usecase.Destination
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

    Box(Modifier.fillMaxSize()) {
        AppContent(viewModel)
        // One loader for the whole app: mounted here, it survives every step
        // change instead of being rebuilt with each screen.
        // Only between steps: while a card is up, its own coin travels to the
        // middle rather than a second one appearing there.
        LoadingCoin(visible = state.showLoadingCard)
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
        Destination.Home -> HomeScreen(onSignOut = viewModel::signOut)
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

    SetupScreen(
        state = state,
        onBack = model::back,
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
