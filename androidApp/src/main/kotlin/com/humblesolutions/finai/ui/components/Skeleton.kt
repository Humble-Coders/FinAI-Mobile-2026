package com.humblesolutions.finai.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.ui.strings

/**
 * A tab's first read, drawn as the shape of the page — its title, a large
 * card, then rows — in grey blocks that pulse gently, instead of the coin over
 * an empty screen (PM decision, 2026-11-05). The iOS `TabSkeleton` is the same.
 */
@Composable
internal fun TabSkeleton(title: String) {
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "skeletonPulse",
    )
    val loading = strings(Strings.loading_body)
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .semantics {
                contentDescription = loading
                liveRegion = LiveRegionMode.Polite
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Column(Modifier.alpha(pulse), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Block(height = 150.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Block(height = 76.dp, modifier = Modifier.weight(1f))
                    Block(height = 76.dp, modifier = Modifier.weight(1f))
                }
                repeat(5) { SkeletonRow() }
            }
        }
    }
}

@Composable
private fun SkeletonRow() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Block(height = 40.dp, width = 40.dp, shape = CircleShape)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Block(height = 14.dp, modifier = Modifier.fillMaxWidth(0.6f))
            Block(height = 12.dp, modifier = Modifier.fillMaxWidth(0.35f))
        }
        Block(height = 16.dp, width = 64.dp)
    }
}

@Composable
private fun Block(height: Dp, modifier: Modifier = Modifier, width: Dp? = null, shape: Shape = RoundedCornerShape(16.dp)) {
    Box(
        modifier
            .then(if (width != null) Modifier.size(width, height) else Modifier.fillMaxWidth().height(height))
            .background(MaterialTheme.colorScheme.surfaceVariant, shape),
    )
}
