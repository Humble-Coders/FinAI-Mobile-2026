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
import java.util.TimeZone

class MainActivity : ComponentActivity() {

    private val viewModel: OnboardingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate: this swaps the launch theme for the app theme.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // No keep-on-screen condition: the system splash leaves at the first
        // frame, and the in-app splash — logo in the same place — plays the
        // intro and holds routing until both it and the first decision are done.

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
