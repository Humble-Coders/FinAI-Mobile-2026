package com.humblesolutions.finai.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.R
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.ui.theme.FinAiPalette

/*
 * The light, minty frame the import and manual-entry screens share, from the
 * approved design: a pale green field with soft hills and one faint picture
 * in the corner, a white panel holding the form, and tinted icon tiles.
 *
 * Purely the look. Every one of these is a container or a row; what goes in
 * them, and what tapping them does, stays with each screen.
 */

/** The field's colours, light and dark. */
internal object Mint {
    @Composable
    fun ground(): Brush = if (isSystemInDarkTheme()) {
        Brush.verticalGradient(listOf(Color(0xFF0C1E14), FinAiPalette.DarkGround))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFE6F6EC), Color(0xFFF5FBF7)))
    }

    @Composable
    fun hill(): Color = if (isSystemInDarkTheme()) Color.White.copy(alpha = 0.03f) else Color(0xFF22C55E).copy(alpha = 0.07f)

    /** The panel the form sits on. White on light; a lifted grey on dark. */
    @Composable
    fun panel(): Color = if (isSystemInDarkTheme()) FinAiPalette.DarkSurface else Color.White

    /** A card inside the panel, a shade off it. */
    @Composable
    fun card(): Color = if (isSystemInDarkTheme()) Color(0xFF242427) else Color(0xFFFBFDFC)

    @Composable
    fun edge(): Color = if (isSystemInDarkTheme()) Color.White.copy(alpha = 0.06f) else Color(0xFFE3EFE8)

    /** Green text: deep on light, bright on dark, where the deep one cannot be read. */
    @Composable
    fun greenText(): Color = if (isSystemInDarkTheme()) FinAiPalette.Green else FinAiPalette.GreenDeep

    /** A tile tint and its icon colour, from one accent. */
    @Composable
    fun tile(accent: Color): Color = accent.copy(alpha = if (isSystemInDarkTheme()) 0.22f else 0.14f)
}

/** The colours a list of accounts cycles through, so neighbouring rows differ. */
internal val AccountTints = listOf(
    FinAiPalette.Green,
    FinAiPalette.Blue,
    Color(0xFFF59E0B),
    FinAiPalette.Red,
)

/**
 * The pale field: gradient, two soft hills, and [decoration] drawn large and
 * faint in the top corner. Decorative only, so hidden from screen readers.
 */
@Composable
internal fun MintBackdrop(decoration: ImageVector?, modifier: Modifier = Modifier) {
    val hill = Mint.hill()
    Box(modifier.fillMaxSize().background(Mint.ground()).clearAndSetSemantics {}) {
        Canvas(Modifier.fillMaxWidth().height(360.dp)) {
            val w = size.width
            val h = size.height
            fun ridge(base: Float, lift: Float, alpha: Float) {
                val path = Path().apply {
                    moveTo(0f, h)
                    lineTo(0f, h * base)
                    cubicTo(w * 0.3f, h * (base - lift), w * 0.6f, h * (base + lift), w, h * (base - lift * 0.6f))
                    lineTo(w, h)
                    close()
                }
                drawPath(path, hill.copy(alpha = hill.alpha * alpha))
            }
            ridge(base = 0.55f, lift = 0.12f, alpha = 1f)
            ridge(base = 0.75f, lift = 0.08f, alpha = 1.4f)
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0xFFFDE68A).copy(alpha = 0.55f), Color.Transparent),
                    center = Offset(w * 0.78f, h * 0.22f),
                    radius = 46.dp.toPx(),
                ),
                radius = 46.dp.toPx(),
                center = Offset(w * 0.78f, h * 0.22f),
            )
        }
        if (decoration != null) {
            Icon(
                imageVector = decoration,
                contentDescription = null,
                tint = FinAiPalette.Green.copy(alpha = 0.16f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-18).dp, y = 132.dp)
                    .size(92.dp)
                    .rotate(-10f),
            )
        }
    }
}

/**
 * Back, the title, and — when given — the dashes that say which step this
 * is. The dashes are read as "Step 2 of 3"; on their own they are drawing.
 */
@Composable
internal fun MintHeader(title: String, onBack: () -> Unit, step: Int? = null, steps: Int = 3) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = strings(Strings.action_back),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp).semantics { heading() },
            )
        }
        if (step != null) {
            val label = strings(Strings.import_step, step.toString(), steps.toString())
            Row(
                Modifier.semantics { contentDescription = label },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(steps) { index ->
                    Box(
                        Modifier
                            .width(if (index + 1 == step) 40.dp else 32.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(
                                if (index + 1 <= step) FinAiPalette.Green else MaterialTheme.colorScheme.outlineVariant,
                            ),
                    )
                }
            }
        }
    }
}

/** A screen's large title and the line under it, as the design sets them. */
@Composable
internal fun MintTitle(title: String, subtitle: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(end = 72.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The white panel the form sits on. */
@Composable
internal fun MintPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Mint.panel())
            .border(1.dp, Mint.edge(), RoundedCornerShape(28.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** A rounded tile holding an icon, tinted from [accent]. */
@Composable
internal fun IconTile(icon: ImageVector, accent: Color, size: Dp = 48.dp, iconSize: Dp = 24.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(Mint.tile(accent)),
        contentAlignment = Alignment.Center,
    ) {
        FinAiIcon(icon, tint = accent, size = iconSize)
    }
}

/**
 * One choice as a card: a tile, a title, a line under it, and either a radio
 * mark ([selected] not null) or a chevron. [tinted] washes the card in its
 * accent, as the design does for the camera.
 */
@Composable
internal fun ChoiceCard(
    icon: ImageVector,
    accent: Color,
    title: String,
    detail: String?,
    onClick: () -> Unit,
    selected: Boolean? = null,
    tinted: Boolean = false,
    trailing: (@Composable BoxScope.() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(18.dp)
    val chosen = selected == true
    val base = Modifier
        .fillMaxWidth()
        .heightIn(min = 72.dp)
        .clip(shape)
        .background(if (chosen || tinted) Mint.tile(accent).copy(alpha = 0.10f) else Mint.card())
        .border(if (chosen) 1.5.dp else 1.dp, if (chosen) FinAiPalette.Green.copy(alpha = 0.6f) else Mint.edge(), shape)
    Row(
        modifier = if (selected != null) {
            base.selectable(selected = chosen, role = Role.RadioButton, onClick = onClick)
        } else {
            base.clickable(role = Role.Button, onClick = onClick)
        }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, accent)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(contentAlignment = Alignment.Center) {
            when {
                trailing != null -> trailing()

                selected != null -> RadioMark(chosen)

                else -> FinAiIcon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 22.dp,
                )
            }
        }
    }
}

/** A filled green tick when chosen, an empty ring when not. Drawing only: the row says which. */
@Composable
private fun RadioMark(chosen: Boolean) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .then(
                if (chosen) {
                    Modifier.background(FinAiPalette.Green)
                } else {
                    Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (chosen) FinAiIcon(Icons.Filled.Check, tint = Color.White, size = 16.dp)
    }
}
