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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.AutoAwesome
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
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
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
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = green)
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
        BoardHeader(Icons.AutoMirrored.Filled.TrendingUp, Mint.greenText(), board.title, board.subtitle) {
            StatusPill(board.badge, board.running)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FigureLine(board.income, Icons.Filled.ArrowDownward, FinAiPalette.Green)
                FigureLine(board.spent, Icons.Filled.ArrowUpward, BoardPalette.Orange)
                FigureLine(board.saved, Icons.Filled.Savings, BoardPalette.Violet)
            }
            LeftPanel(board, dark)
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
        Text(figure.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(figure.amount, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** What is left now: the card's bottom line, on a mint panel with a coin drawn faintly behind it. */
@Composable
private fun LeftPanel(board: BoardSoFar, dark: Boolean) {
    val shape = RoundedCornerShape(20.dp)
    val green = Mint.greenText()
    Column(
        Modifier
            .widthIn(min = 128.dp, max = 160.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(FinAiPalette.Green.copy(alpha = if (dark) 0.18f else 0.10f), FinAiPalette.Green.copy(alpha = if (dark) 0.06f else 0.03f))))
            .border(1.dp, FinAiPalette.Green.copy(alpha = 0.25f), shape)
            .drawBehind {
                // A faint coin in the corner — decoration, never a figure.
                val r = 34.dp.toPx()
                val c = Offset(size.width - 10.dp.toPx(), 14.dp.toPx())
                drawCircle(FinAiPalette.Green.copy(alpha = 0.10f), r, c)
                drawCircle(FinAiPalette.Green.copy(alpha = 0.14f), r * 0.72f, c, style = Stroke(1.5.dp.toPx()))
            }
            .padding(horizontal = 14.dp, vertical = 16.dp)
            .clearAndSetSemantics { contentDescription = "${board.leftLabel}, ${board.left}. ${board.leftCaption}" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Mint.panel()).border(1.dp, FinAiPalette.Green.copy(alpha = 0.25f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.AccountBalanceWallet, contentDescription = null, tint = green, modifier = Modifier.size(18.dp))
        }
        Text(board.leftLabel.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = green, letterSpacing = 1.sp, textAlign = TextAlign.Center)
        Text(
            board.left,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = if (board.leftNegative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
        )
        Text(board.leftCaption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

// ── Expected this month ─────────────────────────────────────────────────

@Composable
internal fun ForecastCard(forecast: BoardForecast, commitments: List<OverviewCommitment>, modifier: Modifier = Modifier) {
    BoardCard(modifier = modifier, glow = Color(0xFF14B8A6)) {
        BoardHeader(Icons.Filled.BarChart, Mint.greenText(), forecast.title, forecast.subtitle)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                forecast.rows.forEach { SignedLine(it) }
                LeftAtEnd(forecast)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RingGauge(forecast)
                Text(forecast.gaugeCaption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 116.dp))
            }
        }
        if (commitments.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Mint.card()).border(1.dp, Mint.edge(), RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(strings(Strings.budget_commitments_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                commitments.forEach { CommitmentRow(it) }
            }
        }
    }
}

/** A commitment: a tick when its payment was seen, an open circle when not — and the words, always. */
@Composable
internal fun CommitmentRow(item: OverviewCommitment) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clearAndSetSemantics { contentDescription = item.description },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (item.seen) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (item.seen) Mint.greenText() else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(item.amount, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
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
        Text(figure.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            "$sign${figure.amount}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (figure.sign > 0) Mint.greenText() else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun LeftAtEnd(forecast: BoardForecast) {
    val shape = RoundedCornerShape(16.dp)
    val tone = if (forecast.surplus) FinAiPalette.Green else MaterialTheme.colorScheme.error
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tone.copy(alpha = 0.09f))
            .border(1.dp, tone.copy(alpha = 0.3f), shape)
            .padding(12.dp)
            .clearAndSetSemantics { contentDescription = "${forecast.leftLabel}, ${forecast.left}, ${forecast.statusLabel}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(forecast.leftLabel.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = if (forecast.surplus) Mint.greenText() else tone)
            Text(forecast.left, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        }
        Text(
            forecast.statusLabel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.clip(CircleShape).background(tone).padding(horizontal = 10.dp, vertical = 4.dp),
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
        Modifier.size(112.dp).clearAndSetSemantics { contentDescription = "${forecast.percentLeft}% ${forecast.gaugeLabel}" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(112.dp)) {
            val stroke = 11.dp.toPx()
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
            Text("${forecast.percentLeft}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            Text(forecast.gaugeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 76.dp))
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
                    Text(strings(Strings.board_ai_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Row(
                        Modifier.clip(RoundedCornerShape(6.dp)).background(BoardPalette.Violet).padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
                        Text(strings(Strings.board_ai_badge).uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 10.sp)
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
            Text(donut.totals, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
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
                    Text("${slice.percent}%", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
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
            Text(donut.centre, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, maxLines = 1)
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
            Text(step.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(step.detail, style = MaterialTheme.typography.bodySmall, color = if (step.emphasized) Mint.greenText() else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = if (step.emphasized) Mint.greenText() else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
