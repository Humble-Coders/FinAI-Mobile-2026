package com.humblesolutions.finai.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.ui.IconCommand
import com.humblesolutions.finai.ui.IconPath

/**
 * One of the shared [com.humblesolutions.finai.ui.Vectors], drawn at [size].
 *
 * The geometry is shared with iOS so the two apps draw the same shape; this
 * only turns it into a Compose [Path]. Even-odd filling, so a hole punched by
 * an inner subpath — the clasp on the wallet, the pupil in the eye — is a hole
 * and not a second filled blob on top.
 *
 * [contentDescription] is null for an icon that repeats a label beside it,
 * which is most of them: a screen reader should hear the row once.
 */
@Composable
fun FinAiIcon(
    icon: IconPath,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    contentDescription: String? = null,
) {
    val path = remember(icon) { icon.toPath() }
    Canvas(
        modifier = modifier
            .size(size)
            .then(
                if (contentDescription == null) {
                    Modifier.clearAndSetSemantics {}
                } else {
                    Modifier.semantics { this.contentDescription = contentDescription }
                },
            ),
    ) {
        // The box is authored at 24; scale to whatever the caller asked for.
        val factor = this.size.minDimension / icon.viewport
        scale(scale = factor, pivot = Offset.Zero) {
            drawPath(path = path, color = tint)
        }
    }
}

/** The shared commands as a Compose path. */
internal fun IconPath.toPath(): Path = Path().also { path ->
    path.fillType = PathFillType.EvenOdd
    commands.forEach { command ->
        when (command) {
            is IconCommand.MoveTo -> path.moveTo(command.x, command.y)

            is IconCommand.LineTo -> path.lineTo(command.x, command.y)

            is IconCommand.CurveTo -> path.cubicTo(
                command.x1,
                command.y1,
                command.x2,
                command.y2,
                command.x,
                command.y,
            )

            IconCommand.Close -> path.close()
        }
    }
}
