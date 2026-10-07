package com.humblesolutions.finai.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The scroll over which a field's big title shrinks into the bar: about its own height. */
internal val TITLE_COLLAPSE_DISTANCE = 64.dp

/**
 * How far a screen's big title has shrunk into [CollapsingTitleBar], 0 to 1,
 * as a lambda over [scroll]: read while drawing, not composing, so scrolling
 * redraws two layers and recomposes nothing.
 */
@Composable
internal fun rememberTitleCollapse(scroll: ScrollState): () -> Float {
    val distance = with(LocalDensity.current) { TITLE_COLLAPSE_DISTANCE.toPx() }
    return remember(scroll, distance) { { (scroll.value / distance).coerceIn(0f, 1f) } }
}

/** The big title's own fade: out over the first half of the collapse. */
internal fun Modifier.fadesAsTitleCollapses(collapsed: () -> Float): Modifier = graphicsLayer { alpha = 1f - (collapsed() * 2f).coerceAtMost(1f) }

/**
 * The slim bar a field screen's big title shrinks into once the page has
 * scrolled — the screen's name centred, solid because content passes under it,
 * reaching up under the status bar. It fades in over the second half of the
 * collapse, once the big title has faded out, so the two are never on screen
 * together. Home's, shared (Home adds its bell as [trailing]).
 */
@Composable
internal fun CollapsingTitleBar(
    title: String,
    dark: Boolean,
    collapsed: () -> Float,
    trailing: (@Composable BoxScope.() -> Unit)? = null,
) {
    val opacity = { ((collapsed() - 0.5f) * 2f).coerceIn(0f, 1f) }
    // Composed only once there is something to show, so a bar at nothing
    // opacity cannot catch a tap meant for the title under it.
    val shown by remember { derivedStateOf { opacity() > 0f } }
    if (!shown) return
    Box(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = opacity()
                shadowElevation = 6.dp.toPx() * opacity()
            }
            .background(Field.top(dark))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .height(56.dp)
            .padding(horizontal = 12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Field.ink(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 52.dp)
                .semantics { heading() },
        )
        trailing?.invoke(this)
    }
}
