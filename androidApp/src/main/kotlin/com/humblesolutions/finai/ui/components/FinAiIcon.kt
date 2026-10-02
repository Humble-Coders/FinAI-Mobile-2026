package com.humblesolutions.finai.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A Material icon at [size], scaled with the reader's text size.
 *
 * The scaling is the point of this wrapper. A fixed `dp` icon stays put while
 * the label beside it grows, so at the larger accessibility text sizes a row
 * ends up as big words next to a tiny mark. Multiplying by the font scale
 * keeps the pair in proportion; the cap stops a 200% setting turning a chevron
 * into a slab.
 *
 * [contentDescription] is null for an icon that repeats a label beside it,
 * which is most of them — a screen reader should hear the row once, and
 * Material's own `Icon` already treats null as decorative.
 */
@Composable
fun FinAiIcon(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    contentDescription: String? = null,
) {
    val scale = LocalDensity.current.fontScale.coerceIn(1f, MAX_ICON_SCALE)
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size * scale),
    )
}

/** Beyond this an icon stops being an icon and starts being a block. */
private const val MAX_ICON_SCALE = 1.6f
