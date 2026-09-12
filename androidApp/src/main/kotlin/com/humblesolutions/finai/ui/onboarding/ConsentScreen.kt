package com.humblesolutions.finai.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.ui.components.ErrorText
import com.humblesolutions.finai.ui.components.PrimaryButton
import com.humblesolutions.finai.ui.components.ScreenScaffold
import com.humblesolutions.finai.ui.components.countryName
import com.humblesolutions.finai.ui.strings

/**
 * The account terms, and the region the server derived.
 *
 * The region row is here rather than on a screen of its own because the PRD
 * requires the override to be reachable *during* onboarding: a misdetected
 * Canadian fixes it on the spot instead of being locked out. What it shows is
 * always the API's answer from the verified number — never the dialling code
 * the user picked earlier.
 */
@Composable
fun ConsentScreen(
    state: OnboardingUiState,
    onChangeRegion: () -> Unit,
    onAccept: () -> Unit,
) {
    ScreenScaffold(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(24.dp))
        Text(strings(Strings.consent_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = strings(Strings.consent_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Laid out in full, with no scroll of its own — the page scrolls. Long
        // terms push the button below the fold, which for a consent screen is
        // the right way round anyway.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(16.dp),
        ) {
            val terms = state.terms
            if (terms == null) {
                CircularProgressIndicator(modifier = Modifier.heightIn(max = 24.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    text = terms.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val region = state.me?.household?.countryCode
        if (region != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onChangeRegion)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = strings(Strings.consent_region_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(countryName(region), style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    text = strings(Strings.action_change),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        ErrorText(state.errorKey)

        PrimaryButton(
            text = strings(Strings.consent_agree),
            onClick = onAccept,
            enabled = state.terms != null,
            busy = state.busy,
        )
    }
}
