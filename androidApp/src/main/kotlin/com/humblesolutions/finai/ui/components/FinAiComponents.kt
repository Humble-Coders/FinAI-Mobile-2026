package com.humblesolutions.finai.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.ui.strings

/** The width past which a form stops stretching. Phones ignore it; tablets and foldables need it. */
private val ContentMaxWidth = 480.dp

/**
 * Every screen's frame: edge to edge, but with content inside the safe area.
 *
 * `safeDrawingPadding` is what keeps text and buttons clear of the status bar,
 * the display cutout and the gesture bar while the background still bleeds
 * underneath.
 *
 * **This scroll is the only one a screen gets.** Nothing placed inside may
 * scroll vertically as well: two vertical scrolls inside each other do not
 * crash the way a lazy list would, so the mistake is silent — the gesture goes
 * to whichever claims it first, and a drag in the inner one moves the page.
 * Content that is too tall simply scrolls, which is also what keeps a screen
 * usable at the largest accessibility font sizes.
 */
@Composable
fun ScreenScaffold(
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = ContentMaxWidth)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = horizontalAlignment,
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/** `FinAI`, with the AI in the accent — the wordmark from the splash design. */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Text(
        modifier = modifier,
        style = MaterialTheme.typography.headlineMedium,
        text = buildAnnotatedString {
            append("Fin")
            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) { append("AI") }
        },
    )
}

/**
 * The one accented control on a screen.
 *
 * Green carries the primary action and nothing else, so a screen never shows
 * two of these.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    Button(
        onClick = onClick,
        // Kept enabled while busy so the label stays legible; the click is what
        // is suppressed. A disabled button on a slow network reads as broken.
        enabled = enabled && !busy,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.widthIn(max = 20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(text, style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** A provider route. Outlined, never accented: the phone route is the primary one. */
@Composable
fun ProviderButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

/** `──── or ────`, between the phone route and the provider routes. */
@Composable
fun OrDivider(modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outline)
        Text(
            text = strings(Strings.welcome_or),
            modifier = Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outline)
    }
}

/** A text field with the right keyboard and IME action wired up. */
@Composable
fun FinAiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    keyboardOptions: KeyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    isError: Boolean = false,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        keyboardOptions = keyboardOptions,
        isError = isError,
        singleLine = singleLine,
    )
}

/** An inline failure, in the error colour, under the control that caused it. */
@Composable
fun ErrorText(messageKey: String?, modifier: Modifier = Modifier) {
    if (messageKey == null) return
    Text(
        text = strings(messageKey),
        modifier = modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}
