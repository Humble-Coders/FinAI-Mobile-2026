package com.humblesolutions.finai.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.ui.strings

/**
 * What a screen shows when the server says AI processing needs consent
 * (PM decision, 2026-11-06): consents live in Profile, and nowhere else asks
 * in full. One line saying so, and the way to Profile.
 *
 * [onOpenProfile] is null where Profile cannot be reached — setup, before
 * there is a home — and the button is then left out.
 */
@Composable
internal fun AiOffNotice(onOpenProfile: (() -> Unit)?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = Mint.greenText())
            Text(strings(Strings.ai_off_notice), style = MaterialTheme.typography.bodyMedium)
            onOpenProfile?.let { open ->
                TextButton(onClick = open, contentPadding = PaddingValues(0.dp)) {
                    Text(strings(Strings.ai_off_action), color = Mint.greenText(), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Coming back from Profile with the notice still up: tries again once, in
 * case AI was turned on there. A screen entering composition with [aiOff]
 * already set can only be one returning to it.
 */
@Composable
internal fun RetryWhenBack(aiOff: Boolean, retry: () -> Unit) {
    LaunchedEffect(Unit) { if (aiOff) retry() }
}

/**
 * Where a screen used to carry its disclaimer: one small line that opens
 * Profile, which holds the full text (PM decision, 2026-11-06). [color] for
 * a line on the green field, where the theme's colours cannot be read.
 */
@Composable
internal fun DisclaimerLink(onOpenProfile: () -> Unit, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    TextButton(
        onClick = onOpenProfile,
        contentPadding = PaddingValues(0.dp),
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Text(
            strings(Strings.disclaimer_link),
            style = MaterialTheme.typography.labelMedium,
            color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color,
            textDecoration = TextDecoration.Underline,
        )
    }
}
