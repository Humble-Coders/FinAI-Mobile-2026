package com.humblesolutions.finai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.humblesolutions.finai.navigation.AppNavigation
import com.humblesolutions.finai.ui.onboarding.OnboardingViewModel
import com.humblesolutions.finai.ui.theme.FinAiTheme
import com.humblesolutions.finai.usecase.Destination
import java.util.TimeZone

class MainActivity : ComponentActivity() {

    private val viewModel: OnboardingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate: this swaps the launch theme for the app theme,
        // and it is what keeps the system splash on screen below.
        val splash = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // The system splash stays up for exactly as long as the shared rule
        // says we are still deciding — no timer, and no gap where a signed-in
        // user would see the welcome screen flash before home.
        splash.setKeepOnScreenCondition { viewModel.uiState.value.destination == Destination.Splash }

        viewModel.bind(
            logging = BuildConfig.DEBUG,
            // For pre-selecting a dialling code only. The user's REGION comes
            // from the server reading their verified number (PRD §4.6).
            deviceRegion = resources.configuration.locales[0].country,
            timeZoneId = TimeZone.getDefault().id,
        )

        setContent {
            FinAiTheme {
                AppNavigation(viewModel)
            }
        }
    }
}
