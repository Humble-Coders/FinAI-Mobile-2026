package com.humblesolutions.finai.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.humblesolutions.finai.model.OnboardingStep
import com.humblesolutions.finai.ui.home.HomeScreen
import com.humblesolutions.finai.ui.onboarding.CodeScreen
import com.humblesolutions.finai.ui.onboarding.ConsentScreen
import com.humblesolutions.finai.ui.onboarding.FailedScreen
import com.humblesolutions.finai.ui.onboarding.NotConfiguredScreen
import com.humblesolutions.finai.ui.onboarding.OnboardingViewModel
import com.humblesolutions.finai.ui.onboarding.PhoneScreen
import com.humblesolutions.finai.ui.onboarding.RegionScreen
import com.humblesolutions.finai.ui.onboarding.SetupPendingScreen
import com.humblesolutions.finai.ui.onboarding.SplashScreen
import com.humblesolutions.finai.ui.onboarding.UpdateRequiredScreen
import com.humblesolutions.finai.usecase.Destination

/**
 * The router: a state variable, not a navigation framework (kmp-arch-v2).
 *
 * It renders whatever the **shared** rule says, and decides nothing itself. The
 * only two things it owns are genuinely UI: whether a code has been sent (a
 * sub-state of the phone step), and whether the user tapped Change on the
 * consent screen to revisit their region.
 */
@Composable
fun AppNavigation(viewModel: OnboardingViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    state.configurationProblem?.let { problem ->
        NotConfiguredScreen(problem)
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

    when (val destination = state.destination) {
        Destination.Splash -> SplashScreen(slow = state.startIsSlow)

        Destination.Welcome -> PhoneOrCode(viewModel, state.codeSent, showProviders = true)

        is Destination.Step -> when (destination.step) {
            // Signed in but no number yet: the Google or Apple route, mid-way.
            OnboardingStep.PHONE -> PhoneOrCode(viewModel, state.codeSent, showProviders = false)
            OnboardingStep.REGION -> RegionScreen(state, viewModel::setRegion)
            OnboardingStep.CONSENT -> ConsentScreen(
                state = state,
                onChangeRegion = { changingRegion = true },
                onAccept = viewModel::acceptTerms,
            )
            // 2.4 replaces this with the wizard. Until then it still blocks
            // home, which is the behaviour the server requires.
            OnboardingStep.FINANCIAL_SETUP -> SetupPendingScreen()
            OnboardingStep.UNKNOWN -> UpdateRequiredScreen()
        }

        Destination.UpdateRequired -> UpdateRequiredScreen()
        Destination.Home -> HomeScreen(onSignOut = viewModel::signOut)
        is Destination.Failed -> FailedScreen(destination.error.messageKey, viewModel::retry)
    }
}

/**
 * Phone entry, or the code once one has been sent.
 *
 * Back from the code screen returns to the number rather than leaving the flow:
 * a mistyped digit is the commonest reason to press it.
 */
@Composable
private fun PhoneOrCode(viewModel: OnboardingViewModel, codeSent: Boolean, showProviders: Boolean) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (codeSent) {
        BackHandler { viewModel.editNumber() }
        CodeScreen(
            state = state,
            onCodeChange = viewModel::onCodeChange,
            onVerify = viewModel::verifyCode,
            onResend = viewModel::sendCode,
            onEditNumber = viewModel::editNumber,
        )
    } else {
        PhoneScreen(
            state = state,
            showProviders = showProviders,
            onPhoneChange = viewModel::onPhoneChange,
            onDialCodeSelected = viewModel::onDialCodeSelected,
            onContinue = viewModel::sendCode,
            // Wired in phase 4, once Supabase has the providers configured.
            onGoogle = {},
            onApple = {},
        )
    }
}
