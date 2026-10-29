package com.humblesolutions.finai.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.AiPolicy
import com.humblesolutions.finai.ui.strings

/**
 * Consent to AI processing, wherever something needs it first: a statement
 * import (#31), or a question typed to the coach (#54).
 *
 * The policy's words are the server's, so they can change without an app
 * release, and agreeing records exactly the version shown. Nothing is
 * pre-ticked: agreeing is a deliberate act.
 */
@Composable
internal fun AiConsentPanel(
    title: String,
    policy: AiPolicy?,
    ticked: Boolean,
    busy: Boolean,
    errorKey: String?,
    onTicked: (Boolean) -> Unit,
    onAgree: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        ErrorText(errorKey)
        policy?.let {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
            ) {
                Text(text = it.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
            }
            TickRow(text = strings(Strings.import_consent_agree), ticked = ticked, onTicked = onTicked)
        }
        GradientButton(
            text = strings(Strings.import_consent_continue),
            onClick = onAgree,
            enabled = policy != null && ticked && !busy,
            busy = busy,
        )
        TextButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth()) {
            Text(strings(Strings.import_consent_not_now))
        }
    }
}

/** A box nothing pre-ticks, with its label as the tap target. */
@Composable
internal fun TickRow(text: String, ticked: Boolean, onTicked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = ticked, role = Role.Checkbox, onValueChange = onTicked),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = ticked, onCheckedChange = null)
        Text(text = text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}
