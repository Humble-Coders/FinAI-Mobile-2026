package com.humblesolutions.finai.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.humblesolutions.finai.R
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.WelcomeMode
import com.humblesolutions.finai.ui.components.ErrorText
import com.humblesolutions.finai.ui.components.Wordmark
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.ui.theme.FinAiPalette

/**
 * Signed out: the brand, then a sheet that slides up for signing in or creating
 * an account with email and password, Google, or Apple on iOS.
 *
 * One sheet with a mode rather than two screens. Google needs no such
 * distinction, and a person who opened the wrong one is a tap from the other.
 *
 * No Apple button here, by decision (2026-09-11): Apple is iOS only. Someone
 * who signed up with Apple on an iPhone reaches the same account on Android
 * through the email and password or Google they linked to it.
 */
@Composable
fun WelcomeScreen(
    state: OnboardingUiState,
    onModeChange: (WelcomeMode) -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onForgotPassword: () -> Unit,
    onGoogle: () -> Unit,
) {
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    // Closing is Back's job while the sheet is up, so Back never leaves the app
    // from a form the user is still filling in.
    BackHandler(enabled = sheetOpen) { sheetOpen = false }

    fun open(mode: WelcomeMode) {
        onModeChange(mode)
        sheetOpen = true
    }

    Box(Modifier.fillMaxSize()) {
        HeroBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 32.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.logo_mark),
                contentDescription = null,
                modifier = Modifier.size(104.dp),
            )
            Spacer(Modifier.height(20.dp))
            Wordmark(style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(12.dp))
            Text(
                text = strings(Strings.welcome_hero_line),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = strings(Strings.app_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(12.dp))
            // The same animation the sheet carries, filling the space the
            // design's illustration occupies.
            Coin(
                Modifier
                    .weight(1f, fill = false)
                    .heightIn(max = 300.dp)
                    .widthIn(max = 360.dp),
            )
            // Room for the buttons, which sit in their own layer below.
            Spacer(Modifier.height(148.dp))
        }

        // The two ways in, where the design's page dots were.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 32.dp)
                .widthIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GradientButton(
                text = strings(Strings.welcome_sign_up),
                onClick = { open(WelcomeMode.CREATE_ACCOUNT) },
            )
            OutlinedButton(
                onClick = { open(WelcomeMode.SIGN_IN) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(
                    width = 1.5.dp,
                    color = if (isSystemInDarkTheme()) {
                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                    } else {
                        FinAiPalette.GreenDeep
                    },
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ),
            ) {
                Text(strings(Strings.welcome_log_in), style = MaterialTheme.typography.titleSmall)
            }
        }

        if (sheetOpen) {
            val close = strings(Strings.action_close)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    // No ripple: this is the scrim, not a control.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = close,
                    ) { sheetOpen = false },
            )
        }

        AnimatedVisibility(
            visible = sheetOpen,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(stiffness = 220f, dampingRatio = 0.9f)) { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            AuthSheet(
                state = state,
                onModeChange = onModeChange,
                onEmailChange = onEmailChange,
                onPasswordChange = onPasswordChange,
                onSubmit = onSubmit,
                onForgotPassword = onForgotPassword,
                onGoogle = onGoogle,
            )
        }
    }
}

/** The design's soft green wash: a gradient with a few blurred shapes over it. */
@Composable
private fun HeroBackground() {
    val dark = isSystemInDarkTheme()
    val top = if (dark) Color(0xFF0C2418) else Color(0xFFEAF8F0)
    val bottom = if (dark) FinAiPalette.DarkGround else Color(0xFFD3EFDF)
    val blob = FinAiPalette.Green.copy(alpha = if (dark) 0.14f else 0.22f)
    val pale = FinAiPalette.Green.copy(alpha = if (dark) 0.07f else 0.12f)

    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(top, bottom)))
        drawCircle(blob, radius = size.minDimension * 0.45f, center = Offset(size.width * 0.05f, size.height * 0.16f))
        drawCircle(pale, radius = size.minDimension * 0.55f, center = Offset(size.width * 1.02f, size.height * 0.34f))
        drawCircle(pale, radius = size.minDimension * 0.40f, center = Offset(size.width * 0.20f, size.height * 0.92f))
    }
}

/**
 * The sheet itself: the coin animation sits on its top edge, which is why the
 * sheet is inset from the top of this box rather than filling it.
 */
@Composable
private fun AuthSheet(
    state: OnboardingUiState,
    onModeChange: (WelcomeMode) -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onForgotPassword: () -> Unit,
    onGoogle: () -> Unit,
) {
    val creating = state.creatingAccount

    Box(Modifier.fillMaxWidth().padding(top = CoinSize / 2)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(top = CoinSize / 2 + 12.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(width = 44.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outline),
                )
                Spacer(Modifier.height(20.dp))

                Text(
                    text = strings(if (creating) Strings.welcome_create_title else Strings.welcome_sign_in_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = strings(
                        if (creating) Strings.welcome_create_subtitle else Strings.welcome_sign_in_subtitle,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))

                SheetField(
                    value = state.email,
                    onValueChange = onEmailChange,
                    placeholder = strings(Strings.welcome_email_hint),
                    label = strings(Strings.welcome_email_label),
                    leading = R.drawable.ic_field_email,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    ),
                    isError = state.errorKey != null,
                )
                Spacer(Modifier.height(12.dp))
                PasswordSheetField(
                    value = state.password,
                    onValueChange = onPasswordChange,
                    onDone = onSubmit,
                    isError = state.errorKey != null,
                )

                if (creating) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = strings(Strings.welcome_password_rule),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    // Prominent on purpose: with email as a way in, a forgotten
                    // password is the commonest reason someone cannot get back.
                    TextButton(onClick = onForgotPassword, modifier = Modifier.align(Alignment.End)) {
                        Text(strings(Strings.welcome_forgot_password))
                    }
                }

                ErrorText(state.errorKey)
                Spacer(Modifier.height(16.dp))

                GradientButton(
                    text = strings(if (creating) Strings.welcome_create_action else Strings.welcome_sign_in_action),
                    onClick = onSubmit,
                    enabled = state.canSubmitCredentials,
                    busy = state.busy,
                )

                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outline)
                    Text(
                        text = strings(Strings.welcome_or_continue),
                        modifier = Modifier.padding(horizontal = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outline)
                }
                Spacer(Modifier.height(16.dp))

                // A circle, as the design shows the providers. Android offers
                // only Google (2026-09-11), so the row holds one.
                ProviderCircle(
                    label = strings(Strings.welcome_google),
                    icon = R.drawable.ic_google_g,
                    onClick = onGoogle,
                    enabled = !state.busy,
                )
                // Under the button it belongs to, not under the form: a provider
                // failing says nothing about what the user typed.
                ErrorText(state.providerErrorKey)

                TextButton(
                    onClick = {
                        onModeChange(if (creating) WelcomeMode.SIGN_IN else WelcomeMode.CREATE_ACCOUNT)
                    },
                ) {
                    Text(strings(if (creating) Strings.welcome_have_account else Strings.welcome_need_account))
                }
            }
        }

        Coin(Modifier.align(Alignment.TopCenter), size = CoinSize)
    }
}

/** The coin, looping on the sheet's top edge. The same Lottie file iOS plays. */
@Composable
private fun Coin(modifier: Modifier = Modifier, size: Dp? = null) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.coin_animation))
    val progress by animateLottieCompositionAsState(
        composition = composition,
        iterations = LottieConstants.IterateForever,
    )
    LottieAnimation(
        composition = composition,
        progress = { progress },
        modifier = if (size != null) modifier.size(size) else modifier.fillMaxWidth(),
    )
}

/** The one accented control on a screen, in the design's green gradient. */
@Composable
private fun GradientButton(
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
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (enabled) 1f else 0.5f)
                .background(
                    Brush.horizontalGradient(listOf(FinAiPalette.Green, FinAiPalette.GreenDeep)),
                    RoundedCornerShape(16.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = FinAiPalette.OnGreen,
                )
            } else {
                Text(text, style = MaterialTheme.typography.titleSmall, color = FinAiPalette.OnGreen)
            }
        }
    }
}

@Composable
private fun SheetField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    label: String,
    leading: Int,
    keyboardOptions: KeyboardOptions,
    modifier: Modifier = Modifier,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder) },
        label = { Text(label) },
        leadingIcon = {
            Icon(
                painter = painterResource(leading),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = trailing,
        singleLine = true,
        isError = isError,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            errorContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            errorIndicatorColor = MaterialTheme.colorScheme.error,
        ),
    )
}

/** A password, hidden until the user asks to see it. */
@Composable
private fun PasswordSheetField(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    isError: Boolean,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    val reveal = strings(if (visible) Strings.action_hide else Strings.action_show)
    SheetField(
        value = value,
        onValueChange = onValueChange,
        placeholder = strings(Strings.welcome_password_label),
        label = strings(Strings.welcome_password_label),
        leading = R.drawable.ic_field_lock,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        isError = isError,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailing = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    painter = painterResource(
                        if (visible) R.drawable.ic_field_eye_off else R.drawable.ic_field_eye,
                    ),
                    contentDescription = reveal,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/** A provider as a circle, the way the design shows them. */
@Composable
private fun ProviderCircle(
    label: String,
    icon: Int,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    Box(
        modifier = Modifier
            .size(60.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Image(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(26.dp))
    }
}

private val CoinSize = 88.dp
