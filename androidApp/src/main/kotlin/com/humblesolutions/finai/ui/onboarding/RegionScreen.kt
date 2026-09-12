package com.humblesolutions.finai.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.ui.components.ErrorText
import com.humblesolutions.finai.ui.components.countryName
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.util.DialCodes

/**
 * Asked **only** when the API reports the `region` step — a number
 * libphonenumber could not place.
 *
 * The user picks; nothing is guessed. Signup is never hard-blocked on region,
 * so any country is acceptable here, launched or not (PRD §4.6).
 */
@Composable
fun RegionScreen(state: OnboardingUiState, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val regions = remember(query) {
        val needle = query.trim().lowercase()
        DialCodes.all
            .map { it.region }
            .filter { needle.isEmpty() || countryName(it).lowercase().contains(needle) }
            .sortedBy { countryName(it) }
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(strings(Strings.region_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = strings(Strings.region_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(strings(Strings.region_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorText(state.errorKey)
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(regions, key = { it }) { region ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !state.busy) { onPick(region) }
                        .padding(vertical = 14.dp),
                ) {
                    Text(countryName(region), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
