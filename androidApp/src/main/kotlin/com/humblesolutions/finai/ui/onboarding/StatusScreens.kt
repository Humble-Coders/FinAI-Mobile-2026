package com.humblesolutions.finai.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.ConfigurationProblem
import com.humblesolutions.finai.ui.components.PrimaryButton
import com.humblesolutions.finai.ui.components.ScreenScaffold
import com.humblesolutions.finai.ui.components.Wordmark
import com.humblesolutions.finai.ui.strings

/**
 * The in-app splash, which continues the system one.
 *
 * No timer anywhere: it lasts exactly as long as the session restore and the
 * first `/me`. If that turns out to be slow it says so, because a motionless
 * logo is indistinguishable from a hang.
 */
@Composable
fun SplashScreen(slow: Boolean) {
    ScreenScaffold(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Wordmark()
        Spacer(Modifier.height(8.dp))
        Text(
            text = strings(Strings.app_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (slow) {
            Spacer(Modifier.height(32.dp))
            CircularProgressIndicator(modifier = Modifier.heightIn(max = 28.dp), strokeWidth = 2.dp)
            Spacer(Modifier.height(12.dp))
            Text(
                text = strings(Strings.splash_slow),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The API named an onboarding step this build has no screen for.
 *
 * Deliberately a real destination rather than a silent pass: an unknown step
 * means something IS outstanding, and letting the user through would put them
 * in an app the server will refuse.
 */
@Composable
fun UpdateRequiredScreen() {
    ScreenScaffold(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = strings(Strings.update_required_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = strings(Strings.update_required_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * `/me` could not be loaded.
 *
 * Retry is the main action, but it cannot be the only one: while the server is
 * down every retry fails, and a signed-in caller who cannot load `/me` has no
 * other screen to be on. Without a way out they are simply stuck, which is what
 * the ticket's "never stuck" rule is about. Signing out returns them to the
 * welcome screen, which always works because it needs nothing from the API.
 */
@Composable
fun FailedScreen(messageKey: String, onRetry: () -> Unit, onSignOut: () -> Unit) {
    ScreenScaffold(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = strings(Strings.error_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = strings(messageKey),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton(text = strings(Strings.action_retry), onClick = onRetry)
        TextButton(onClick = onSignOut) { Text(strings(Strings.action_sign_out)) }
    }
}

/** This build has no backend configured — see the README's setup step. */
@Composable
fun NotConfiguredScreen(problem: ConfigurationProblem) {
    ScreenScaffold(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = strings(problem.messageKey),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The `financial_setup` step, until 2.4 (FinAI-Mobile-2026#17) builds the
 * wizard behind it.
 *
 * A deliberate seam rather than a gap: the step must still block home, because
 * the server refuses everything else until the figures exist
 * (Finance-backend#29). Sign out is the only way off it until then.
 */
@Composable
fun SetupPendingScreen(onSignOut: () -> Unit) {
    ScreenScaffold(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = strings(Strings.setup_pending_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = strings(Strings.setup_pending_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onSignOut) { Text(strings(Strings.action_sign_out)) }
    }
}
