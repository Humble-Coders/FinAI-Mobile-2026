package com.humblesolutions.finai.ui.budget

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.ui.components.Mint
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.ui.theme.FinAiPalette
import com.humblesolutions.finai.usecase.BoardDonut
import com.humblesolutions.finai.usecase.BoardFigure
import com.humblesolutions.finai.usecase.BoardForecast
import com.humblesolutions.finai.usecase.BoardSoFar
import com.humblesolutions.finai.usecase.DonutSlice
import com.humblesolutions.finai.usecase.NextStep
import com.humblesolutions.finai.usecase.NextStepAction
import com.humblesolutions.finai.usecase.OverviewCommitment

// The Budget screen's cards, in the language of the reference design: white
// cards on the green field, a soft glow in each card's corner, tinted icon
// tiles, and the figures large. Every figure and label comes from the shared
// BudgetBoard; these only draw them.

/** The board's five category colours, in slice order; the iOS board uses the same. */
internal object BoardPalette {
    private val light = listOf(Color(0xFF3B82F6), Color(0xFFF97316), Color(0xFFA855F7), Color(0xFF22C55E), Color(0xFF06B6D4))
    private val dark = listOf(Color(0xFF60A5FA), Color(0xFFFB923C), Color(0xFFC084FC), Color(0xFF4ADE80), Color(0xFF22D3EE))

    fun color(index: Int, isDark: Boolean): Color = (if (isDark) dark else light)[index.coerceIn(0, light.lastIndex)]

    val Violet = Color(0xFF7C3AED)
    val VioletSoft = Color(0xFFA78BFA)
    val Orange = Color(0xFFF97316)
}

// ── The card ────────────────────────────────────────────────────────────

/** A white card with a soft glow of [glow] in its top-right corner. */
@Composable
internal fun BoardCard(
    modifier: Modifier = Modifier,
    glow: Color = FinAiPalette.Green,
    background: Brush? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(28.dp)
    val panel = Mint.panel()
    Column(
        modifier
            .fillMaxWidth()
            .shadow(if (dark) 0.dp else 10.dp, shape, ambientColor = Color(0x22000000), spotColor = Color(0x22000000))
            .clip(shape)
            .then(if (background != null) Modifier.background(background) else Modifier.background(panel))
            .drawBehind {
                // The corner glow: a soft circle, mostly off the card.
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(glow.copy(alpha = if (dark) 0.16f else 0.12f), Color.Transparent),
                        center = Offset(size.width - 24.dp.toPx(), 12.dp.toPx()),
                        radius = 150.dp.toPx(),
                    ),
                    radius = 150.dp.toPx(),
                    center = Offset(size.width - 24.dp.toPx(), 12.dp.toPx()),
                )
            }
            .border(1.dp, if (dark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.7f), shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

/** A tinted rounded-square tile with an icon in it. */
@Composable
internal fun IconTile(icon: ImageVector, accent: Color, size: Dp = 40.dp, iconSize: Dp = 20.dp, filled: Brush? = null) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.32f))
            .then(if (filled != null) Modifier.background(filled) else Modifier.background(Mint.tile(accent))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (filled != null) Color.White else accent, modifier = Modifier.size(iconSize))
    }
}

/** A card's heading: tile, title and subtitle, and anything on the right. */
@Composable
internal fun BoardHeader(
    icon: ImageVector,
    accent: Color,
    title: String,
    subtitle: String?,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(icon, accent)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing?.invoke()
    }
}

/** "In progress" with a softly pulsing dot, or "Final" with a still one. */
@Composable
internal fun StatusPill(text: String, live: Boolean) {
    val green = Mint.greenText()
    val pulse = if (live) {
        val transition = rememberInfiniteTransition(label = "livePulse")
        val alpha by transition.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "liveAlpha")
        alpha
    } else {
        1f
    }
    Row(
        Modifier
            .clip(CircleShape)
            .background(Mint.tile(FinAiPalette.Green))
            .border(1.dp, FinAiPalette.Green.copy(alpha = 0.35f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).alpha(pulse).clip(CircleShape).background(if (live) FinAiPalette.Green else MaterialTheme.colorScheme.onSurfaceVariant))
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = green)
    }
}

/** A value that grows from 0 to [target] when it first shows, and moves smoothly after. */
@Composable
private fun animated(target: Float, delayMs: Int = 0): Float {
    val value = remember { Animatable(0f) }
    LaunchedEffect(target) { value.animateTo(target, tween(900, delayMillis = delayMs, easing = FastOutSlowInEasing)) }
    return value.value
}

// ── This month so far ───────────────────────────────────────────────────

@Composable
internal fun SoFarCard(board: BoardSoFar) {
    val dark = isSystemInDarkTheme()
    BoardCard {
        BoardHeader(Icons.AutoMirrored.Filled.TrendingUp, Mint.greenText(), board.title, null) {
            StatusPill(board.badge, board.running)
        }
        // The panel is exactly as tall as the three figures beside it.
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                FigureLine(board.income, Icons.Filled.ArrowDownward, FinAiPalette.Green)
                FigureLine(board.spent, Icons.Filled.ArrowUpward, BoardPalette.Orange)
                FigureLine(board.saved, Icons.Filled.Savings, BoardPalette.Violet)
            }
            LeftPanel(board, dark, Modifier.fillMaxHeight())
        }
    }
}

@Composable
private fun FigureLine(figure: BoardFigure, icon: ImageVector, accent: Color) {
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "${figure.label}, ${figure.amount}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(Mint.tile(accent)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
        }
        // Label above amount: beside the panel there is room for one or the
        // other on a phone, and side by side "Income received" was cut short.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(figure.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(figure.amount, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** What is left to spend: the card's bottom line, on a mint panel as tall as the figures beside it. */
@Composable
private fun LeftPanel(board: BoardSoFar, dark: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(18.dp)
    val green = Mint.greenText()
    Column(
        modifier
            .width(132.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(FinAiPalette.Green.copy(alpha = if (dark) 0.18f else 0.10f), FinAiPalette.Green.copy(alpha = if (dark) 0.06f else 0.03f))))
            .border(1.dp, FinAiPalette.Green.copy(alpha = 0.25f), shape)
            .drawBehind {
                // A faint coin in the corner — decoration, never a figure.
                val r = 26.dp.toPx()
                val c = Offset(size.width - 6.dp.toPx(), 8.dp.toPx())
                drawCircle(FinAiPalette.Green.copy(alpha = 0.10f), r, c)
            }
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .clearAndSetSemantics { contentDescription = "${board.leftLabel}, ${board.left}. ${board.leftCaption}" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(board.leftLabel.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = green, letterSpacing = 1.sp, textAlign = TextAlign.Center)
        Text(
            board.left,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (board.leftNegative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
        )
        Text(board.leftCaption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2)
    }
}

// ── Expected this month ─────────────────────────────────────────────────

/**
 * What is still to come, and on the right — the same height as the figures —
 * the ring for how much of income the month lands on, with "Left at month
 * end" under it. The commitments follow, each tappable to tick off by hand.
 */
@Composable
internal fun ForecastCard(forecast: BoardForecast, commitments: List<OverviewCommitment>, onTick: (String) -> Unit, modifier: Modifier = Modifier) {
    BoardCard(modifier = modifier, glow = Color(0xFF14B8A6)) {
        BoardHeader(Icons.Filled.BarChart, Mint.greenText(), forecast.title, forecast.subtitle)
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                forecast.rows.forEach { SignedLine(it) }
            }
            Column(
                Modifier.width(136.dp).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                RingGauge(forecast)
                Spacer(Modifier.height(8.dp))
                LeftAtEnd(forecast)
            }
        }
        if (commitments.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Mint.card()).border(1.dp, Mint.edge(), RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(strings(Strings.budget_commitments_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                commitments.forEach { CommitmentRow(it, onTick) }
            }
        }
    }
}

/**
 * A commitment: a ticked box when its payment was found, an empty one when
 * not — and the words, always. Tapping it ticks it off, or changes how.
 */
@Composable
internal fun CommitmentRow(item: OverviewCommitment, onTick: (String) -> Unit) {
    val open = strings(Strings.tick_a11y_open, item.name)
    val id = item.id
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (id != null) Modifier.clickable(role = Role.Button, onClickLabel = open) { onTick(id) } else Modifier)
            .heightIn(min = 48.dp)
            .clearAndSetSemantics {
                contentDescription = item.description
                if (id != null) {
                    role = Role.Button
                    onClick(label = open) {
                        onTick(id)
                        true
                    }
                }
            }
            // Room between one row's status and the next row's amount.
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (item.seen) Icons.Filled.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
            contentDescription = null,
            tint = if (item.seen) Mint.greenText() else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(item.amount, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(item.status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SignedLine(figure: BoardFigure) {
    val accent = if (figure.sign > 0) FinAiPalette.Green else BoardPalette.Orange
    val sign = if (figure.sign > 0) "+" else "−"
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "${figure.label}, $sign${figure.amount}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(Mint.tile(accent)), contentAlignment = Alignment.Center) {
            Icon(if (figure.sign > 0) Icons.Filled.Add else Icons.Filled.Remove, contentDescription = null, tint = accent, modifier = Modifier.size(13.dp))
        }
        // Label above amount, as on "This month so far": beside the ring there
        // was no room for both, and labels broke mid-word.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(figure.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "$sign${figure.amount}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (figure.sign > 0) Mint.greenText() else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "Left at month end", under the ring: the amount and whether it is a surplus. */
@Composable
private fun LeftAtEnd(forecast: BoardForecast) {
    val shape = RoundedCornerShape(14.dp)
    val tone = if (forecast.surplus) FinAiPalette.Green else MaterialTheme.colorScheme.error
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tone.copy(alpha = 0.09f))
            .border(1.dp, tone.copy(alpha = 0.3f), shape)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .clearAndSetSemantics { contentDescription = "${forecast.leftLabel}, ${forecast.left}, ${forecast.statusLabel}" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(forecast.leftLabel.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp, color = if (forecast.surplus) Mint.greenText() else tone, textAlign = TextAlign.Center, maxLines = 2)
        Text(forecast.left, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(
            forecast.statusLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            modifier = Modifier.clip(CircleShape).background(tone).padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** How much of income the month lands on, as a ring that fills when it first shows. */
@Composable
private fun RingGauge(forecast: BoardForecast) {
    val fill = animated(forecast.fraction)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val tone = if (forecast.surplus) FinAiPalette.Green else MaterialTheme.colorScheme.error
    Box(
        Modifier.size(96.dp).clearAndSetSemantics { contentDescription = "${forecast.percentLeft}% ${forecast.gaugeLabel}" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(96.dp)) {
            val stroke = 9.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(
                Brush.sweepGradient(listOf(tone.copy(alpha = 0.55f), tone, tone.copy(alpha = 0.55f))),
                -90f,
                360f * fill,
                false,
                Offset(inset, inset),
                arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${forecast.percentLeft}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(forecast.gaugeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 68.dp))
        }
    }
}

// ── The savings target ──────────────────────────────────────────────────

/**
 * The savings target, apart from the categories: what is set aside each
 * month, how much of it this month has, and a way to set or change it.
 */
@Composable
internal fun SavingsCard(
    title: String,
    subtitle: String,
    target: String?,
    progress: String?,
    fraction: Float,
    setLabel: String,
    room: String?,
    onOpen: () -> Unit,
) {
    BoardCard(glow = BoardPalette.Violet, onClick = onOpen) {
        BoardHeader(Icons.Filled.Savings, BoardPalette.Violet, title, subtitle) {
            if (target != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(target, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Filled.Edit, contentDescription = null, tint = Mint.greenText(), modifier = Modifier.size(14.dp))
                }
            }
        }
        if (target != null) {
            CategoryBar(fraction, BoardPalette.Violet)
            progress?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, BoardPalette.Violet.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = BoardPalette.Violet, modifier = Modifier.size(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(setLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = BoardPalette.Violet)
                    room?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

// ── The AI ──────────────────────────────────────────────────────────────

/** "Smart Financial AI": the AI's headline about the month, a tap from the whole check. */
@Composable
internal fun InsightCard(headline: String?, detail: String?, onOpen: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val open = strings(Strings.board_ai_open)
    BoardCard(
        glow = BoardPalette.VioletSoft,
        background = Brush.linearGradient(
            if (dark) {
                listOf(Color(0xFF231B3A), Color(0xFF1C1C22))
            } else {
                listOf(Color(0xFFF3EEFF), Color(0xFFFAF8FF))
            },
        ),
        onClick = onOpen,
        modifier = Modifier.semantics { contentDescription = open },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            IconTile(
                Icons.Outlined.AutoAwesome,
                BoardPalette.Violet,
                size = 44.dp,
                iconSize = 22.dp,
                filled = Brush.linearGradient(listOf(BoardPalette.VioletSoft, BoardPalette.Violet)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings(Strings.board_ai_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Row(
                        Modifier.clip(RoundedCornerShape(6.dp)).background(BoardPalette.Violet).padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
                        Text(strings(Strings.board_ai_badge).uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 10.sp)
                    }
                }
                Text(headline ?: strings(Strings.board_ai_prompt), style = MaterialTheme.typography.bodyMedium)
                detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── Categories ──────────────────────────────────────────────────────────

/** The categories card's heading and its donut with the legend beside it. */
@Composable
internal fun DonutHeader(donut: BoardDonut) {
    val dark = isSystemInDarkTheme()
    BoardHeader(Icons.Filled.PieChart, BoardPalette.Orange, donut.title, null) {
        Column(horizontalAlignment = Alignment.End) {
            Text(donut.totalsLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(donut.totals, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Donut(donut, dark)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            donut.slices.forEach { slice ->
                Row(
                    Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "${slice.label}, ${slice.percent}%" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(BoardPalette.color(slice.colorIndex, dark)))
                    Text(slice.label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("${slice.percent}%", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** The donut: one arc per slice with a small gap between, sweeping in when it first shows. */
@Composable
private fun Donut(donut: BoardDonut, dark: Boolean) {
    val sweep = animated(1f)
    Box(
        Modifier.size(116.dp).clearAndSetSemantics { contentDescription = "${donut.centre} ${donut.centreCaption}" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(116.dp)) {
            val stroke = 16.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val gap = if (donut.slices.size > 1) 3f else 0f
            var start = -90f
            donut.slices.forEach { slice: DonutSlice ->
                val angle = 360f * slice.fraction * sweep
                if (angle > gap) {
                    drawArc(BoardPalette.color(slice.colorIndex, dark), start + gap / 2, angle - gap, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                }
                start += angle
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(donut.centre, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(donut.centreCaption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A category row's bar, in its slice's colour — or red once it is over. */
@Composable
internal fun CategoryBar(fraction: Float, color: Color) {
    val fill = animated(fraction, delayMs = 150)
    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth(fill.coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.7f), color))))
    }
}

// ── Next steps ──────────────────────────────────────────────────────────

@Composable
internal fun NextStepsCard(title: String, subtitle: String, steps: List<NextStep>, onStep: (NextStep) -> Unit) {
    BoardCard {
        BoardHeader(Icons.Outlined.CheckCircle, Mint.greenText(), title, subtitle)
        HorizontalDivider(color = Mint.edge())
        steps.forEach { StepRow(it, onStep) }
    }
}

@Composable
private fun StepRow(step: NextStep, onStep: (NextStep) -> Unit) {
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(18.dp)
    val (icon, accent) = when (step.action) {
        NextStepAction.INCOME -> Icons.Filled.Add to FinAiPalette.Green
        NextStepAction.COMMITMENTS -> Icons.Outlined.Assignment to BoardPalette.Orange
        NextStepAction.PLAN -> Icons.Outlined.TrackChanges to FinAiPalette.Green
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (step.emphasized) FinAiPalette.Green.copy(alpha = if (dark) 0.14f else 0.08f) else Mint.card())
            .border(1.dp, if (step.emphasized) FinAiPalette.Green.copy(alpha = 0.45f) else Mint.edge(), shape)
            .clickable(role = Role.Button) { onStep(step) }
            .heightIn(min = 72.dp)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(
            icon,
            accent,
            size = 44.dp,
            iconSize = 20.dp,
            filled = if (step.emphasized) Brush.linearGradient(listOf(FinAiPalette.Green, Color(0xFF15803D))) else null,
        )
        Column(Modifier.weight(1f)) {
            Text(step.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(step.detail, style = MaterialTheme.typography.bodySmall, color = if (step.emphasized) Mint.greenText() else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = if (step.emphasized) Mint.greenText() else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
