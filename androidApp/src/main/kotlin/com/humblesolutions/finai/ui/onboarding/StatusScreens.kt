package com.humblesolutions.finai.ui.onboarding

import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
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
 * The in-app splash.
 *
 * Logo, wordmark and tagline are centred together as one group, so they sit in
 * the middle third of the screen. The system splash shows only the ground colour
 * (res/values/themes.xml): its icon is always centred on the window, and would
 * jump up to meet this group. The slow-start notice sits in the bottom third, so
 * appearing never moves the group.
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
            // With the first letter, not after the last: by the time the name
            // is spelled the tagline is already there.
            taglineShown = true
            for (next in SplashIntro.frames) {
                frame = next
                delay(next.holdMs)
            }
            delay(SplashIntro.HOLD_AFTER_MS)
        }
        onIntroFinished()
    }

    val appName = strings(Strings.app_name)
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.logo_mark),
                contentDescription = null,
                modifier = Modifier.size(LogoSize),
            )
            Spacer(Modifier.height(24.dp))
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
        }
        if (slow) {
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
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

/** res/drawable-xxhdpi/logo_mark.png is drawn for this size. */
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

