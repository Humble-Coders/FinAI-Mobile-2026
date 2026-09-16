package com.humblesolutions.finai.ui.onboarding

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.ui.components.ErrorText
import com.humblesolutions.finai.ui.components.PrimaryButton
import com.humblesolutions.finai.ui.components.CardScreen
import com.humblesolutions.finai.ui.strings

/**
 * Six digits, and the way back to a mistyped address or number.
 *
 * One screen for every code — SMS for the phone step, email for signup and a
 * password reset — so they cannot drift apart.
 *
 * @param sentTo the number or address, shown so a typo is noticed.
 * @param hintKey extra guidance under the heading, or null.
 * @param editKey the label for going back to fix [sentTo].
 */
@Composable
fun CodeScreen(
    state: OnboardingUiState,
    sentTo: String,
    editKey: String,
    onCodeChange: (String) -> Unit,
    onVerify: () -> Unit,
    onResend: () -> Unit,
    onEdit: () -> Unit,
    hintKey: String? = null,
) {
    CardScreen(busy = state.busy) {
        Spacer(Modifier.height(20.dp))
        Text(
            text = strings(Strings.code_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = strings(Strings.code_sent_to, sentTo),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hintKey != null) {
            Text(
                text = strings(hintKey),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        CodeCells(code = state.code, onCodeChange = onCodeChange, isError = state.errorKey != null)

        ErrorText(state.errorKey)

        PrimaryButton(
            text = strings(Strings.code_verify),
            onClick = onVerify,
            // No spinner here: the coin on the card's edge is the indicator.
            enabled = state.canVerify && !state.busy,
        )

        if (state.canResend) {
            TextButton(onClick = onResend) { Text(strings(Strings.code_resend)) }
        } else {
            Text(
                text = strings(Strings.code_resend_in, state.resendCountdown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TextButton(onClick = onEdit) { Text(strings(editKey)) }
    }
}

/**
 * Six boxes over one real field.
 *
 * A single field is what the keyboard, autofill and the SMS one-tap suggestion
 * all understand; the cells are decoration over it. Six separate fields would
 * break paste and fight every autofill on the platform.
 */
@Composable
private fun CodeCells(code: String, onCodeChange: (String) -> Unit, isError: Boolean) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    BasicTextField(
        value = code,
        onValueChange = onCodeChange,
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done,
        ),
        // The real text is never drawn: the cells below render it.
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Transparent),
        decorationBox = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(OnboardingUiState.CODE_LENGTH) { index ->
                    val filled = index < code.length
                    val active = index == code.length
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(
                                width = if (active || isError) 2.dp else 1.dp,
                                color = when {
                                    isError -> MaterialTheme.colorScheme.error
                                    active -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.outline
                                },
                                shape = RoundedCornerShape(8.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (filled) code[index].toString() else "",
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        },
    )
}
