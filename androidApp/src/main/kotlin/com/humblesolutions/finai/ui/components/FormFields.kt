package com.humblesolutions.finai.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.ui.theme.FinAiPalette

/*
 * The form fields the setup wizard introduced, shared with every screen that
 * takes figures or text the same way (manual entry, #30), so the boxes look
 * and behave alike wherever money is typed.
 */

internal val FieldShape = RoundedCornerShape(16.dp)

/**
 * The box every field and list row sits in: the surface colour, a hairline
 * border, green and thicker while typing, red while its figure will not do.
 */
@Composable
internal fun Modifier.fieldFrame(focused: Boolean, isError: Boolean): Modifier {
    val colour by animateColorAsState(
        targetValue = when {
            isError -> MaterialTheme.colorScheme.error
            focused -> FinAiPalette.Green
            else -> MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = tween(150),
        label = "fieldBorder",
    )
    val width by animateDpAsState(if (focused || isError) 2.dp else 1.dp, tween(150), label = "fieldBorderWidth")
    return this
        .clip(FieldShape)
        .background(MaterialTheme.colorScheme.surface)
        .border(width, colour, FieldShape)
}

/**
 * An amount, the way finance apps take one: a label above, the figure large and
 * bold beside the currency the server named — never a hardcoded symbol — a faint
 * zero while it is empty, and what the figure is per, when it is per anything.
 * The whole box is the text field, so tapping anywhere on it starts typing.
 */
@Composable
internal fun AmountField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    symbol: String,
    placeholder: String,
    suffix: String? = null,
    isError: Boolean = false,
    large: Boolean = true,
    imeAction: ImeAction = ImeAction.Next,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val figure = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(label)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            interactionSource = interaction,
            textStyle = figure.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold),
            cursorBrush = SolidColor(FinAiPalette.Green),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
            modifier = Modifier
                .fillMaxWidth()
                .height(if (large) 68.dp else 56.dp)
                .fieldFrame(focused, isError)
                .semantics { contentDescription = label },
            decorationBox = { inner ->
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = symbol,
                        style = figure,
                        fontWeight = FontWeight.Medium,
                        color = muted,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) {
                            Text(text = placeholder, style = figure, color = muted.copy(alpha = 0.4f))
                        }
                        inner()
                    }
                    if (suffix != null) {
                        Text(
                            text = suffix,
                            style = MaterialTheme.typography.labelLarge,
                            color = muted,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            },
        )
    }
}

/** A plain text field in the same box as the amounts, red while its text will not do. */
@Composable
internal fun WizardField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Words,
    isError: Boolean = false,
    placeholder: String? = null,
    onDone: (() -> Unit)? = null,
    leading: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(label)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            interactionSource = interaction,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(FinAiPalette.Green),
            keyboardOptions = KeyboardOptions(
                capitalization = capitalization,
                keyboardType = keyboardType,
                imeAction = imeAction,
            ),
            keyboardActions = KeyboardActions(onDone = onDone?.let { done -> { done() } }),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .fieldFrame(focused, isError)
                .semantics { contentDescription = label },
            decorationBox = { inner ->
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (leading != null) {
                        FinAiIcon(leading, tint = muted, size = 22.dp)
                        Spacer(Modifier.width(12.dp))
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = muted.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                }
            },
        )
    }
}

@Composable
internal fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * A field that opens a chooser rather than taking typing — an account, a
 * date, a category — in the same box as the typed ones. Empty, it shows a
 * faint [placeholder], never a value nobody picked.
 */
@Composable
internal fun PickerField(
    label: String,
    value: String?,
    placeholder: String,
    onClick: () -> Unit,
    isError: Boolean = false,
    enabled: Boolean = true,
    leading: ImageVector? = null,
    trailing: ImageVector? = null,
    end: (@Composable () -> Unit)? = null,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(label)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // At least, not exactly: at a large font size the value takes
                // a second line rather than being cut off beside the extras.
                .heightIn(min = 56.dp)
                .fieldFrame(focused = false, isError = isError)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                FinAiIcon(leading, tint = muted, size = 22.dp)
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = value ?: placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = if (value == null) muted.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            end?.invoke()
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                FinAiIcon(trailing, tint = muted, size = 20.dp)
            }
        }
    }
}
