package com.humblesolutions.finai.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.humblesolutions.finai.R
import com.humblesolutions.finai.ui.theme.FinAiPalette

/** The coin that sits on the card's top edge. */
val CoinBadgeSize = 88.dp

/** Half its own speed: a slow turn rather than a spin. */
private const val COIN_SPEED = 0.5f

/** The green wash behind every signed-out screen, from the design. */
@Composable
fun HeroBackground(modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val top = if (dark) androidx.compose.ui.graphics.Color(0xFF0C2418) else androidx.compose.ui.graphics.Color(0xFFEAF8F0)
    val bottom = if (dark) FinAiPalette.DarkGround else androidx.compose.ui.graphics.Color(0xFFD3EFDF)
    val blob = FinAiPalette.Green.copy(alpha = if (dark) 0.14f else 0.22f)
    val pale = FinAiPalette.Green.copy(alpha = if (dark) 0.07f else 0.12f)

    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(top, bottom)))
        drawCircle(blob, radius = size.minDimension * 0.45f, center = Offset(size.width * 0.05f, size.height * 0.16f))
        drawCircle(pale, radius = size.minDimension * 0.55f, center = Offset(size.width * 1.02f, size.height * 0.34f))
        drawCircle(pale, radius = size.minDimension * 0.40f, center = Offset(size.width * 0.20f, size.height * 0.92f))
    }
}

/** The spinning coin, drawn from the same Lottie file iOS plays. */
@Composable
fun CoinBadge(modifier: Modifier = Modifier) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.coin_3d))
    val progress by animateLottieCompositionAsState(
        composition = composition,
        iterations = LottieConstants.IterateForever,
        speed = COIN_SPEED,
    )
    LottieAnimation(
        composition = composition,
        progress = { progress },
        modifier = modifier.size(CoinBadgeSize),
    )
}

/**
 * The white card every signed-out screen sits in.
 *
 * It is **as tall as its content**: the column wraps what it holds and scrolls
 * only when that outgrows the screen, so a short form gets a short card. It
 * runs to the bottom edge, and the coin straddles its top edge.
 */
@Composable
fun AuthCard(
    modifier: Modifier = Modifier,
    spacing: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth().padding(top = CoinBadgeSize / 2)) {
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
                    .padding(top = CoinBadgeSize / 2 + 4.dp, bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing),
            ) {
                Box(
                    Modifier
                        .size(width = 44.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outline),
                )
                content()
            }
        }

        CoinBadge(Modifier.align(Alignment.TopCenter))
    }
}

/**
 * The wash plus the card: the frame the code and phone steps share with the
 * welcome sheet.
 */
@Composable
fun CardScreen(spacing: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        HeroBackground()
        AuthCard(Modifier.align(Alignment.BottomCenter), spacing = spacing, content = content)
    }
}
