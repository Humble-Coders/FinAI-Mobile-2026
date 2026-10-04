package com.humblesolutions.finai.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.ui.components.AmountField
import com.humblesolutions.finai.ui.components.ErrorText
import com.humblesolutions.finai.ui.components.Field
import com.humblesolutions.finai.ui.components.FinAiIcon
import com.humblesolutions.finai.ui.components.GradientButton
import com.humblesolutions.finai.ui.components.LightStatusBarIcons
import com.humblesolutions.finai.ui.components.ProviderButton
import com.humblesolutions.finai.ui.components.Waves
import com.humblesolutions.finai.ui.components.WizardField
import com.humblesolutions.finai.ui.components.tint
import com.humblesolutions.finai.ui.components.vector
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.ui.theme.FinAiPalette
import com.humblesolutions.finai.usecase.DashboardTrend

/**
 * The dashboard (PRD F3): one month of what happened, against what was
 * expected of it.
 *
 * Every figure arrives already decided — by the server, then by
 * [DashboardUiState]. Nothing here computes money, which is what keeps the two
 * apps from disagreeing about a number somebody is acting on.
 *
 * Laid out as the approved design: a green field holding the month, its four
 * figures and the ways in, and a sheet rising over it with the most recent
 * rows. The icons are Material's, with SF Symbols' equivalents on iOS.
 */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onToggleAmounts: () -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onRetry: () -> Unit,
    onImportStatement: () -> Unit,
    onAddTransaction: () -> Unit,
    onReview: () -> Unit,
    onViewAll: () -> Unit,
    onSignOut: () -> Unit,
    commitments: CommitmentActions = CommitmentActions(),
) {
    val dark = isSystemInDarkTheme()
    if (state.showsCommitmentEditor) CommitmentEditor(state, commitments)
    LightStatusBarIcons(dark)

    val scroll = rememberScrollState()
    val collapseDistance = with(LocalDensity.current) { COLLAPSE_DISTANCE.toPx() }
    // How far the header has shrunk into the bar, 0 to 1. Read while drawing,
    // not composing, so scrolling redraws two layers and recomposes nothing.
    val collapsed = { (scroll.value / collapseDistance).coerceIn(0f, 1f) }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Field.brush(dark)),
            ) {
                Waves(Modifier.matchParentSize())
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        // The field runs under the status bar, as in the design;
                        // its contents do not.
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .padding(horizontal = 20.dp)
                        .padding(top = 12.dp, bottom = SHEET_OVERLAP + 28.dp),
                ) {
                    // Out over the first half of the stretch; the bar comes in over the
                    // second, so the two greetings are never on screen together.
                    Header(state, onReview, Modifier.graphicsLayer { alpha = 1f - (collapsed() * 2f).coerceAtMost(1f) })
                    Spacer(Modifier.height(26.dp))
                    Hero(state, onToggleAmounts, onPreviousMonth, onNextMonth)
                    state.dailyChart?.let { chart ->
                        Spacer(Modifier.height(14.dp))
                        DailyChart(chart, state, Modifier.fillMaxWidth().height(CHART_HEIGHT))
                    }
                    Spacer(Modifier.height(22.dp))
                    when {
                        state.loadFailed -> LoadFailed(state, onRetry)
                        state.showsEmptyState -> Unit
                        else -> Figures(state, dark)
                    }
                    Spacer(Modifier.height(26.dp))
                    Actions(state, dark, onImportStatement, onAddTransaction, onReview)
                }
            }

            Sheet(state, onViewAll, onSignOut, commitments)
        }

        CompactHeader(state, dark, collapsed, onReview)
    }
}

// ── The field ───────────────────────────────────────────────────────────

/** How far the sheet rises over the field. */
private val SHEET_OVERLAP = 28.dp

/** The scroll over which the header shrinks into the bar: about its own height. */
private val COLLAPSE_DISTANCE = 64.dp

// ── The header ──────────────────────────────────────────────────────────

@Composable
private fun Header(state: DashboardUiState, onReview: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // A silhouette, not an initial. We deliberately do not hold a name:
        // the requirements forbid collecting one, and a letter would need it.
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Field.ink(0.94f)),
            contentAlignment = Alignment.Center,
        ) {
            FinAiIcon(Icons.Filled.Person, tint = FinAiPalette.GreenDeep, size = 28.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = strings(Strings.dashboard_greeting),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Field.ink(),
            )
            Text(
                text = strings(Strings.dashboard_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = Field.ink(0.82f),
            )
        }
        Bell(state, onReview, 48.dp)
    }
}

/**
 * The header once the page has scrolled: a slim bar pinned over the top, the
 * greeting centred in it and the bell still in reach. It fades in once the big
 * header has faded out. Solid rather than see-through, because figures pass
 * under it.
 */
@Composable
private fun CompactHeader(state: DashboardUiState, dark: Boolean, collapsed: () -> Float, onReview: () -> Unit) {
    val opacity = { ((collapsed() - 0.5f) * 2f).coerceIn(0f, 1f) }
    // Composed only once there is something to show, so a bar at nothing
    // opacity cannot catch a tap meant for the header under it.
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
            text = strings(Strings.dashboard_greeting),
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
        Bell(state, onReview, 40.dp, Modifier.align(Alignment.CenterEnd))
    }
}

/**
 * The bell is the review queue, and its dot means something: rows are
 * waiting. A decorative badge that never changes teaches people to ignore it.
 */
@Composable
private fun Bell(state: DashboardUiState, onReview: () -> Unit, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            // Never under 48dp to touch, however small it is drawn.
            .size(maxOf(size, 48.dp))
            .clip(CircleShape)
            .clickable(onClick = onReview)
            .semantics { contentDescription = state.notificationsLabel },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(Field.Glass)
                .border(1.dp, Field.GlassEdge, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            FinAiIcon(Icons.Filled.Notifications, tint = Field.ink(), size = size * 0.46f)
            if (state.hasPending) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = size * 0.23f, end = size * 0.25f)
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(FinAiPalette.Red)
                        .border(1.5.dp, Field.ink(), CircleShape),
                )
            }
        }
    }
}

// ── The hero ────────────────────────────────────────────────────────────

@Composable
private fun Hero(
    state: DashboardUiState,
    onToggleAmounts: () -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = strings(Strings.dashboard_net_label),
            style = MaterialTheme.typography.titleMedium,
            color = Field.ink(0.9f),
        )
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onToggleAmounts)
                .semantics { contentDescription = state.hideToggleLabel },
            contentAlignment = Alignment.Center,
        ) {
            FinAiIcon(
                icon = if (state.amountsHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                tint = Field.ink(0.85f),
                size = 20.dp,
            )
        }
        Spacer(Modifier.weight(1f))
        MonthPill(state, onPreviousMonth, onNextMonth)
    }
    FittedAmount(
        text = state.net,
        style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold, color = Field.ink()),
        sizes = HERO_SIZES,
        modifier = Modifier.fillMaxWidth(),
    )
    // Absent, not "+0%", when there is no month to compare against.
    state.changeLabel?.let {
        Spacer(Modifier.height(12.dp))
        ChangePill(it, state.netIsPositive)
    }
}

@Composable
private fun MonthPill(state: DashboardUiState, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Field.Glass)
            .border(1.dp, Field.GlassEdge, RoundedCornerShape(20.dp))
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Step(Icons.AutoMirrored.Filled.KeyboardArrowLeft, strings(Strings.dashboard_previous_month), state.canGoBack, onPrevious)
        Text(
            text = state.monthLabel,
            style = MaterialTheme.typography.labelLarge,
            color = Field.ink(),
        )
        // Hidden rather than disabled on the month that is running: a month
        // that has not happened holds nothing to look at.
        if (state.canGoForward) {
            Step(Icons.AutoMirrored.Filled.KeyboardArrowRight, strings(Strings.dashboard_next_month), true, onNext)
        } else {
            Spacer(Modifier.width(14.dp))
        }
    }
}

@Composable
private fun Step(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        FinAiIcon(icon, tint = Field.ink(if (enabled) 1f else 0.4f), size = 18.dp)
    }
}

@Composable
private fun ChangePill(label: String, rose: Boolean) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Field.Glass)
            .border(1.dp, Field.GlassEdge, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FinAiIcon(
            icon = if (rose) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
            tint = Field.ink(),
            size = 14.dp,
        )
        Spacer(Modifier.width(6.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = Field.ink())
    }
}

/**
 * The month day by day: the running balance of everything in minus
 * everything out, from the 1st (PRD F3).
 *
 * The geometry is shared ([DashboardTrend.daily]). The axis is the whole
 * calendar month, so a month still running stops part-way with the rest of
 * it ahead; the dashed line is zero, which the scale always includes, so a
 * stretch below it reads as behind for the month. The latest day is marked,
 * with its figure on a pill — the hero's figure, where the line ends. Days
 * along the bottom: the 1st, the weeks, the last.
 */
@Composable
private fun DailyChart(chart: DashboardTrend.DailyChart, state: DashboardUiState, modifier: Modifier) {
    val ticks = state.dailyTicks
    val value = state.dailyMarkerValue
    val description = state.dailyDescription
    Layout(
        modifier = modifier.semantics { contentDescription = description },
        content = {
            Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
                val area = ChartArea(size.width, size.height, this)
                // A faint rule at each named day, so the weeks can be read off.
                chart.ticks.forEach { tick ->
                    val x = area.x(tick.x)
                    drawLine(Field.ink(0.07f), Offset(x, area.top), Offset(x, area.bottom), strokeWidth = 1.dp.toPx())
                }
                val zero = area.y(chart.zero)
                drawLine(
                    Field.ink(0.35f),
                    start = Offset(area.left, zero),
                    end = Offset(area.right, zero),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                )
                val pts = chart.days.map { area.at(it) }
                if (pts.size > 1) {
                    val line = Path().apply {
                        moveTo(pts.first().x, pts.first().y)
                        // Control points level with each end, so the curve
                        // never overshoots a day above or below its value.
                        pts.zipWithNext { a, b ->
                            val mid = (a.x + b.x) / 2
                            cubicTo(mid, a.y, mid, b.y, b.x, b.y)
                        }
                    }
                    val fill = Path().apply {
                        addPath(line)
                        lineTo(pts.last().x, zero)
                        lineTo(pts.first().x, zero)
                        close()
                    }
                    drawPath(
                        fill,
                        Brush.verticalGradient(listOf(Field.ink(0.24f), Field.ink(0.02f)), startY = area.top, endY = area.bottom),
                    )
                    drawPath(line, Field.ink(0.95f), style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
                }
                val dot = pts[chart.markerIndex]
                drawLine(
                    Field.ink(0.5f),
                    start = dot,
                    end = Offset(dot.x, area.bottom),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
                drawCircle(Field.ink(0.3f), radius = 9.dp.toPx(), center = dot)
                drawCircle(Field.ink(), radius = 5.dp.toPx(), center = dot)
            }
            if (value != null) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Field.Glass)
                        .border(1.dp, Field.GlassEdge, RoundedCornerShape(10.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Field.ink(), maxLines = 1, softWrap = false)
                }
            }
            ticks.forEachIndexed { index, tick ->
                Text(
                    tick,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (index == 0 || index == ticks.lastIndex) FontWeight.Medium else FontWeight.Normal,
                    color = Field.ink(0.75f),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        },
    ) { measurables, constraints ->
        val canvas = measurables[0].measure(constraints)
        val loose = Constraints()
        val pill = if (value != null) measurables[1].measure(loose) else null
        val labels = measurables.drop(if (pill != null) 2 else 1).map { it.measure(loose) }
        layout(canvas.width, canvas.height) {
            canvas.place(0, 0)
            val area = ChartArea(canvas.width.toFloat(), canvas.height.toFloat(), this@Layout)
            val gap = 4.dp.roundToPx()
            pill?.let {
                val centre = area.at(chart.days[chart.markerIndex])
                val left = (centre.x - it.width / 2f).toInt().coerceIn(0, (canvas.width - it.width).coerceAtLeast(0))
                // Below a month behind, where the line dipped to, rather than
                // across the line it labels — unless there is no room there,
                // when it goes above rather than over the dot.
                val above = (centre.y - 12.dp.toPx() - it.height).toInt()
                val below = (centre.y + 12.dp.toPx()).toInt()
                val fitsBelow = below + it.height <= area.bottom
                val top = if ((state.dailyMarkerIsLoss && fitsBelow) || (above < 0 && fitsBelow)) below else above.coerceAtLeast(0)
                it.place(left, top)
            }
            // The month's two ends first, so they are never the ones left off.
            val order = listOf(0, labels.lastIndex) + (1 until labels.lastIndex)
            val taken = mutableListOf<IntRange>()
            order.distinct().forEach { index ->
                val label = labels.getOrNull(index) ?: return@forEach
                val x = area.x(chart.ticks[index].x)
                val left = (x - label.width / 2f).toInt().coerceIn(0, (canvas.width - label.width).coerceAtLeast(0))
                val span = (left - gap)..(left + label.width + gap)
                if (taken.any { it.first <= span.last && span.first <= it.last }) return@forEach
                taken += span
                label.place(left, canvas.height - label.height)
            }
        }
    }
}

/** Where the line may go inside the chart's box. */
private class ChartArea(width: Float, height: Float, density: Density) {
    val left = with(density) { 18.dp.toPx() }
    val right = width - with(density) { 18.dp.toPx() }
    val top = with(density) { CHART_TOP.toPx() }
    val bottom = height - with(density) { CHART_BOTTOM.toPx() }

    fun x(fraction: Double): Float = left + (fraction * (right - left)).toFloat()

    fun y(fraction: Double): Float = bottom - (fraction * (bottom - top)).toFloat()

    fun at(day: DashboardTrend.Day) = Offset(x(day.x), y(day.y))
}

/** Short on purpose: the line is a glance, and the cards below are the figures. */
private val CHART_HEIGHT = 128.dp

/** Room above the line for the latest day's figure. */
private val CHART_TOP = 26.dp

/** Room below for the days. */
private val CHART_BOTTOM = 22.dp

// ── The four cards ──────────────────────────────────────────────────────

/** One card's colours: the icon, its tile, and a label dark enough to read. */
private class Accent(val icon: Color, val label: Color, val darkLabel: Color) {
    fun labelFor(dark: Boolean) = if (dark) darkLabel else label
}

// Labels use the deeper tone of each accent on a white card: the bright ones
// read at under 3:1 there (amber at 1.7:1). Dark cards take the bright tone.
private val IncomeAccent = Accent(FinAiPalette.Green, Color(0xFF15803D), FinAiPalette.Green)
private val ExpensesAccent = Accent(FinAiPalette.Red, Color(0xFFDC2626), Color(0xFFF87171))
private val InvestmentsAccent = Accent(FinAiPalette.Blue, Color(0xFF2563EB), Color(0xFF60A5FA))
private val DebtsAccent = Accent(FinAiPalette.Amber, Color(0xFFB45309), FinAiPalette.Amber)

@Composable
private fun Figures(state: DashboardUiState, dark: Boolean) {
    EqualGrid(spacing = 12.dp) {
        FigureCard(Icons.Filled.AccountBalanceWallet, IncomeAccent, dark, strings(Strings.dashboard_income), state.incomeAmount, state.incomeExpectation)
        FigureCard(
            Icons.Filled.CreditCard,
            ExpensesAccent,
            dark,
            strings(Strings.dashboard_expenses),
            state.expensesAmount,
            state.expensesExpectation,
            detailIsWarning = state.expensesAreOver,
        )
        FigureCard(Icons.Filled.Savings, InvestmentsAccent, dark, strings(Strings.dashboard_investments), state.investmentsAmount, state.investmentsMovement)
        FigureCard(Icons.AutoMirrored.Filled.ReceiptLong, DebtsAccent, dark, strings(Strings.dashboard_debts), state.debtsAmount, state.debtsMovement)
    }
}

/**
 * Two columns of cells that are all the same size: the tallest card's height
 * is every card's height, so the four read as one set however their text
 * wraps — including at a large font size, where a fixed height would clip.
 *
 * Measured, not estimated: each card is composed once to learn its real
 * height and once more at the shared one. Intrinsic heights were tried first
 * and came out short for wrapped text at a large font size, which clipped the
 * last line of a card.
 */
@Composable
private fun EqualGrid(spacing: Dp, content: @Composable () -> Unit) {
    SubcomposeLayout { constraints ->
        val gap = spacing.roundToPx()
        val cell = (constraints.maxWidth - gap) / 2
        val height = subcompose("measure", content)
            .maxOf { it.measure(Constraints(maxWidth = cell)).height }
        val placeables = subcompose("place", content).map { it.measure(Constraints.fixed(cell, height)) }
        val rows = (placeables.size + 1) / 2
        layout(constraints.maxWidth, rows * height + (rows - 1).coerceAtLeast(0) * gap) {
            placeables.forEachIndexed { index, placeable ->
                placeable.place((index % 2) * (cell + gap), (index / 2) * (height + gap))
            }
        }
    }
}

@Composable
private fun FigureCard(
    icon: ImageVector,
    accent: Accent,
    dark: Boolean,
    label: String,
    amount: String,
    detail: String?,
    detailIsWarning: Boolean = false,
) {
    val surface = if (dark) FinAiPalette.DarkSurface else Color.White
    val amountStyle = MaterialTheme.typography.titleLarge.copy(
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    BoxWithConstraints(
        modifier = Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(surface)
            .background(accent.icon.copy(alpha = if (dark) 0.10f else 0.05f))
            .border(1.dp, Color.White.copy(alpha = if (dark) 0.06f else 0.7f), RoundedCornerShape(22.dp))
            .padding(14.dp),
    ) {
        // The design sets the figure beside the icon, which leaves it about
        // 80dp. When it cannot sit there at a readable size — a large font, or
        // a seven-figure balance — it moves to its own full-width line rather
        // than shrinking past reading or losing digits.
        val measurer = rememberTextMeasurer()
        val besidePx = constraints.maxWidth - with(LocalDensity.current) { BESIDE_TAKEN.roundToPx() }
        val besideSize = if (LocalDensity.current.fontScale >= LARGE_FONT) {
            null
        } else {
            measurer.largestFitting(amount, amountStyle, CARD_BESIDE_SIZES, besidePx)
        }
        val stacked = besideSize == null

        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                IconTile(icon, accent)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    if (!stacked) {
                        CardLabel(label, accent, dark)
                        Spacer(Modifier.height(2.dp))
                        Text(amount, style = amountStyle.copy(fontSize = besideSize), maxLines = 1, softWrap = false)
                    }
                }
                FinAiIcon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    size = 18.dp,
                )
            }
            if (stacked) {
                Spacer(Modifier.height(10.dp))
                CardLabel(label, accent, dark)
                Spacer(Modifier.height(2.dp))
                FittedAmount(amount, amountStyle, CARD_STACKED_SIZES, Modifier.fillMaxWidth(), oneLine = true)
            }
            detail?.let {
                Spacer(Modifier.height(10.dp))
                // One line, as the four cards are: smaller first, then cut short. A
                // wrapped second line would make the cards uneven again.
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (detailIsWarning) accent.labelFor(dark) else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (detailIsWarning) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 12.sp, stepSize = 0.5.sp),
                )
            }
        }
    }
}

@Composable
private fun IconTile(icon: ImageVector, accent: Accent) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(accent.icon.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        FinAiIcon(icon, tint = accent.icon, size = 22.dp)
    }
}

/** "Investments" is the longest; it shrinks a little rather than losing its end. */
@Composable
private fun CardLabel(label: String, accent: Accent, dark: Boolean) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = accent.labelFor(dark),
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 14.sp, stepSize = 0.5.sp),
    )
}

/**
 * A figure at the largest of [sizes] that fits on one line, or — when even the
 * smallest does not — wrapped at that smallest size.
 *
 * Never clipped. Automatic text sizing stops at its minimum and cuts off
 * whatever is left, and an amount with its last digits missing is a different
 * amount. A wrapped figure is ugly; a truncated one is wrong.
 */
@Composable
private fun FittedAmount(
    text: String,
    style: TextStyle,
    sizes: List<TextUnit>,
    modifier: Modifier = Modifier,
    oneLine: Boolean = false,
) {
    BoxWithConstraints(modifier) {
        val size = rememberTextMeasurer().largestFitting(text, style, sizes, constraints.maxWidth)
        when {
            size != null -> Text(text, style = style.copy(fontSize = size), maxLines = 1, softWrap = false)

            // Inside a card, one line is the rule: at the smallest size, then
            // cut short. Only reachable at the very largest font sizes with a
            // figure of seven digits or more.
            oneLine -> Text(text, style = style.copy(fontSize = sizes.last()), maxLines = 1, overflow = TextOverflow.Ellipsis)

            else -> Text(text, style = style.copy(fontSize = sizes.last()))
        }
    }
}

/** The first of [sizes] at which [text] fits in [widthPx] on one line, or null. */
private fun TextMeasurer.largestFitting(text: String, style: TextStyle, sizes: List<TextUnit>, widthPx: Int): TextUnit? = sizes.firstOrNull { size ->
    measure(text, style.copy(fontSize = size), maxLines = 1, softWrap = false).size.width <= widthPx
}

/** Icon tile, the gap after it, and the chevron: what sits beside a figure. */
private val BESIDE_TAKEN = 42.dp + 10.dp + 18.dp

/** Beside the icon, nothing smaller than this: below it, stack instead. */
private val CARD_BESIDE_SIZES = listOf(20.sp, 19.sp, 18.sp, 17.sp, 16.sp, 15.sp, 14.sp)

private val CARD_STACKED_SIZES = listOf(22.sp, 20.sp, 18.sp, 16.sp, 14.sp, 12.sp, 10.sp)

private val HERO_SIZES = listOf(38.sp, 34.sp, 30.sp, 27.sp, 24.sp, 21.sp, 18.sp)

/** From here up the cards stack their figures; see [FigureCard]. */
private const val LARGE_FONT = 1.3f

// ── The ways in ─────────────────────────────────────────────────────────

@Composable
private fun Actions(
    state: DashboardUiState,
    dark: Boolean,
    onImportStatement: () -> Unit,
    onAddTransaction: () -> Unit,
    onReview: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Field.ink(0.94f)),
            contentAlignment = Alignment.Center,
        ) {
            FinAiIcon(Icons.Filled.Bolt, tint = FinAiPalette.GreenDeep, size = 24.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = strings(if (state.showsEmptyState) Strings.dashboard_empty_title else Strings.dashboard_quick_actions_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Field.ink(),
                modifier = Modifier.semantics { heading() },
            )
            if (state.showsEmptyState) {
                Text(
                    text = strings(Strings.dashboard_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Field.ink(0.85f),
                )
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    val surface = if (dark) FinAiPalette.DarkSurface else Color.White
    ActionRow(
        tile = FinAiPalette.Green.copy(alpha = 0.16f),
        icon = Icons.Filled.UploadFile,
        iconTint = if (dark) FinAiPalette.Green else FinAiPalette.GreenDeep,
        label = strings(Strings.import_entry),
        surface = surface,
        onClick = onImportStatement,
    )
    Spacer(Modifier.height(10.dp))
    ActionRow(
        tile = MaterialTheme.colorScheme.onSurface,
        icon = Icons.Filled.Add,
        iconTint = MaterialTheme.colorScheme.surface,
        label = strings(Strings.manual_entry_title),
        surface = surface,
        onClick = onAddTransaction,
    )
    Spacer(Modifier.height(10.dp))
    ActionRow(
        tile = Color.Transparent,
        icon = Icons.AutoMirrored.Filled.List,
        iconTint = MaterialTheme.colorScheme.onSurface,
        label = strings(Strings.review_entry),
        surface = surface,
        onClick = onReview,
    )
}

@Composable
private fun ActionRow(
    tile: Color,
    icon: ImageVector,
    iconTint: Color,
    label: String,
    surface: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tile),
            contentAlignment = Alignment.Center,
        ) {
            FinAiIcon(icon, tint = iconTint, size = 22.dp)
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        FinAiIcon(Icons.AutoMirrored.Filled.ArrowForward, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 20.dp)
    }
}

@Composable
private fun LoadFailed(state: DashboardUiState, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.background)
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ErrorText(state.errorKey)
        Spacer(Modifier.height(8.dp))
        ProviderButton(text = strings(Strings.dashboard_retry), onClick = onRetry)
    }
}

// ── The sheet ───────────────────────────────────────────────────────────

/** Rising over the field with the newest rows, and the month's commitments. */
@Composable
private fun Sheet(state: DashboardUiState, onViewAll: () -> Unit, onSignOut: () -> Unit, commitments: CommitmentActions) {
    Box(
        Modifier
            .fillMaxWidth()
            .pullUp(SHEET_OVERLAP)
            .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                .padding(horizontal = 20.dp)
                .padding(top = 24.dp, bottom = 16.dp),
        ) {
            val rows = state.recentRows
            // Shown once there is anything at all: its header carries View all,
            // which is the way into every transaction.
            val showsRecent = rows.isNotEmpty() || !state.showsEmptyState
            if (showsRecent) Recent(rows, onViewAll)
            // Shown once the month is read, even with none: it is where one is added.
            if (state.showsCommitments) {
                if (showsRecent) Spacer(Modifier.height(28.dp))
                Commitments(state, commitments)
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onSignOut, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(strings(Strings.action_sign_out), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Moves a child up over what is above it, and takes that much off its height. */
private fun Modifier.pullUp(by: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val dy = by.roundToPx()
    layout(placeable.width, (placeable.height - dy).coerceAtLeast(0)) { placeable.place(0, -dy) }
}

@Composable
private fun Recent(rows: List<RecentRow>, onViewAll: () -> Unit) {
    val dark = isSystemInDarkTheme()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(IncomeAccent.icon.copy(alpha = if (dark) 0.2f else 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            FinAiIcon(Icons.AutoMirrored.Filled.ReceiptLong, tint = IncomeAccent.labelFor(dark), size = 20.dp)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = strings(Strings.dashboard_recent_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        // A pill rather than bare words, so it reads as the way in that it is.
        Box(
            Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(24.dp))
                .clickable(onClick = onViewAll),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(IncomeAccent.icon.copy(alpha = if (dark) 0.18f else 0.1f))
                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings(Strings.dashboard_recent_view_all),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = IncomeAccent.labelFor(dark),
                )
                FinAiIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, tint = IncomeAccent.labelFor(dark), size = 18.dp)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { RecentLine(it, dark) }
    }
}

/**
 * One row as a soft card: the category's picture on a tile in its colour, with
 * a small arrow on the tile's corner for the direction — into the account or
 * out of it — beside the amount that says it again in words.
 */
@Composable
private fun RecentLine(row: RecentRow, dark: Boolean) {
    val tint = row.icon.tint(dark)
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(shape)
            .background(if (dark) MaterialTheme.colorScheme.surfaceContainer else Color.White)
            .border(1.dp, if (dark) Color.White.copy(alpha = 0.06f) else Color(0xFFE8F1EC), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = row.description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp)) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(tint.copy(alpha = if (dark) 0.2f else 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                FinAiIcon(row.icon.vector(), tint = tint, size = 24.dp)
            }
            val direction = if (row.isCredit) IncomeAccent.labelFor(dark) else ExpensesAccent.labelFor(dark)
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (dark) MaterialTheme.colorScheme.surfaceContainer else Color.White)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(direction),
                contentAlignment = Alignment.Center,
            ) {
                FinAiIcon(
                    if (row.isCredit) Icons.Filled.SouthWest else Icons.Filled.NorthEast,
                    tint = Color.White,
                    size = 11.dp,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = row.category,
                style = MaterialTheme.typography.bodyMedium,
                color = if (row.isFiled) MaterialTheme.colorScheme.onSurfaceVariant else DebtsAccent.labelFor(dark),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = row.amount,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                // Green for money in; money out stays plain, and the sign says
                // it in words, so colour is never the only cue.
                color = if (row.isCredit) IncomeAccent.labelFor(dark) else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(row.date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

// ── Commitments ─────────────────────────────────────────────────────────

@Composable
private fun Commitments(state: DashboardUiState, actions: CommitmentActions) {
    val onEdit = actions.onEdit
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = strings(Strings.dashboard_commitments_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = strings(Strings.commitments_add_hint), onClick = actions.onAdd)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FinAiIcon(Icons.Filled.Add, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp)
            Spacer(Modifier.width(4.dp))
            Text(
                text = strings(Strings.commitments_add),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    state.commitmentsSummary?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (state.commitments.isEmpty()) {
        Text(
            text = strings(Strings.commitments_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Spacer(Modifier.height(4.dp))
    state.commitments.forEachIndexed { index, row ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = row.editLabel) { onEdit(index) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Decorative: `detail` already says this in words.
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        if (row.wasSeen) FinAiPalette.Green.copy(alpha = 0.18f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                    )
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                if (row.wasSeen) FinAiIcon(Icons.Filled.Check, tint = FinAiPalette.GreenDeep, size = 16.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium)
                // Not red when unseen: we do not know it is unpaid, only that
                // we did not find it.
                Text(row.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(row.expected, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            FinAiIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp)
        }
        if (index != state.commitments.lastIndex) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
        }
    }
}

// ── Editing a commitment ────────────────────────────────────────────────

/** What the commitment editor can ask of the dashboard's model. */
class CommitmentActions(
    val onEdit: (Int) -> Unit = {},
    val onAdd: () -> Unit = {},
    val onName: (String) -> Unit = {},
    val onAmount: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onAskDelete: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onKeep: () -> Unit = {},
)

/** Adding or editing a commitment: its name and monthly amount, which is all one has. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommitmentEditor(state: DashboardUiState, actions: CommitmentActions) {
    val focus = LocalFocusManager.current
    ModalBottomSheet(
        onDismissRequest = actions.onCancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = strings(state.commitmentTitleKey),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            WizardField(
                value = state.commitmentDraft.name,
                onValueChange = actions.onName,
                label = strings(Strings.commitment_edit_name),
                capitalization = KeyboardCapitalization.Sentences,
            )
            AmountField(
                value = state.commitmentDraft.amount,
                onValueChange = actions.onAmount,
                label = strings(Strings.commitment_edit_amount),
                symbol = state.commitmentCurrencySymbol,
                placeholder = state.commitmentAmountPlaceholder,
                large = false,
                imeAction = ImeAction.Done,
            )
            ErrorText(state.commitmentNotice)
            GradientButton(
                text = strings(Strings.commitment_edit_save),
                onClick = {
                    focus.clearFocus()
                    actions.onSave()
                },
                enabled = state.canSaveCommitment,
                busy = state.commitmentSaving,
            )
            TextButton(
                onClick = actions.onCancel,
                enabled = !state.commitmentSaving,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text(strings(Strings.commitment_edit_cancel)) }
            // Only for one that exists. Asked again before anything is sent.
            if (state.editingCommitment != null) {
                TextButton(
                    onClick = actions.onAskDelete,
                    enabled = !state.commitmentSaving,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) { Text(strings(Strings.commitment_delete), color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (state.confirmingCommitmentDelete) {
        AlertDialog(
            onDismissRequest = actions.onKeep,
            title = { Text(state.deleteCommitmentTitle) },
            text = { Text(strings(Strings.commitment_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = actions.onDelete) {
                    Text(strings(Strings.commitment_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = actions.onKeep) { Text(strings(Strings.commitment_edit_cancel)) } },
        )
    }
}
