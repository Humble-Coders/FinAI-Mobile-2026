package com.humblesolutions.finai.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.ui.components.GradientButton
import com.humblesolutions.finai.ui.components.ProviderButton
import com.humblesolutions.finai.ui.components.ScreenScaffold
import com.humblesolutions.finai.ui.strings

/**
 * A placeholder until the dashboard (M4), carrying what can be done from it so
 * far: importing a statement (#31), typing in a transaction (#30), and the
 * review queue (#32).
 */
@Composable
fun HomeScreen(
    onImportStatement: () -> Unit,
    onAddTransaction: () -> Unit,
    onReview: () -> Unit,
    onSignOut: () -> Unit,
) {
    ScreenScaffold(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = strings(Strings.home_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = strings(Strings.home_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        // Importing is the main way in (#31); typing one in is the other.
        GradientButton(text = strings(Strings.import_entry), onClick = onImportStatement)
        Spacer(Modifier.height(8.dp))
        ProviderButton(text = strings(Strings.manual_entry_title), onClick = onAddTransaction)
        Spacer(Modifier.height(8.dp))
        // Always offered rather than counted first: home has no count to ask
        // for, and the queue's own empty state is a good answer either way.
        ProviderButton(text = strings(Strings.review_entry), onClick = onReview)
        Spacer(Modifier.height(8.dp))
        // Not green: the accent belongs to the one primary action above.
        TextButton(onClick = onSignOut) {
            Text(strings(Strings.action_sign_out), color = MaterialTheme.colorScheme.onBackground)
        }
    }
}
