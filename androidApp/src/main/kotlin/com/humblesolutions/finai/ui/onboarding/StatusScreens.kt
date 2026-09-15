package com.humblesolutions.finai.ui.onboarding

import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.R
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.ConfigurationProblem
import com.humblesolutions.finai.ui.components.PrimaryButton
import com.humblesolutions.finai.ui.components.ScreenScaffold
import com.humblesolutions.finai.ui.components.Wordmark
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.usecase.SplashIntro
import kotlinx.coroutines.delay

/**
 * The in-app splash, which continues the system one.
 *
 * The logo sits at the exact size and centre of the system splash icon, so the
 * hand-over is still; the wordmark and tagline sit below it. That is why this
 * screen does not use [ScreenScaffold]: the system splash centres its icon on
 * the whole window, not on the safe area.
 *
 * With [animate], the launch intro plays once ([SplashIntro]) and then calls
 * [onIntroFinished]. Every later splash — while the first `/me` loads after a
 * code is verified — shows the finished wordmark at once, and so does the launch
 * splash when "Remove animations" is on. No timer otherwise: it lasts exactly
 * as long as routing needs, and says so if that is slow.
 */
@Composable
fun SplashScreen(slow: Boolean, animate: Boolean = false, onIntroFinished: () -> Unit = {}) {
    val context = LocalContext.current
    val reduceMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val play = animate && !reduceMotion
    var frame by remember { mutableStateOf(if (play) SplashIntro.frames.first() else SplashIntro.finalFrame) }
    var taglineShown by remember { mutableStateOf(!play) }
    val taglineAlpha by animateFloatAsState(
        targetValue = if (taglineShown) 1f else 0f,
        animationSpec = tween(SplashIntro.TAGLINE_FADE_MS.toInt()),
        label = "tagline",
    )

    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        if (play) {
            for (next in SplashIntro.frames) {
                frame = next
                delay(next.holdMs)
            }
            taglineShown = true
            delay(SplashIntro.TAGLINE_FADE_MS)
        }
        onIntroFinished()
    }

    val appName = strings(Strings.app_name)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.logo_mark),
            contentDescription = null,
            modifier = Modifier.align(Alignment.Center).size(LogoSize),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = maxHeight / 2 + LogoSize / 2 + 24.dp)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Wordmark(
                frame = frame,
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                // Read once as the name, never letter by letter as it animates.
                modifier = Modifier.clearAndSetSemantics { contentDescription = appName },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = strings(Strings.app_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                // Faded rather than added, so nothing above it moves.
                modifier = Modifier.alpha(taglineAlpha),
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
}

/** The system splash draws its icon circle at this size (res/drawable-xxhdpi/ic_splash_logo.png). */
private val LogoSize = 120.dp

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
