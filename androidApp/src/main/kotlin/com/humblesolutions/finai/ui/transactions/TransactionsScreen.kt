package com.humblesolutions.finai.ui.transactions

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.R
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.ui.components.ErrorText
import com.humblesolutions.finai.ui.components.Field
import com.humblesolutions.finai.ui.components.FieldVectors
import com.humblesolutions.finai.ui.components.FinAiIcon
import com.humblesolutions.finai.ui.components.LightStatusBarIcons
import com.humblesolutions.finai.ui.components.SheetRow
import com.humblesolutions.finai.ui.components.SheetTitle
import com.humblesolutions.finai.ui.components.Waves
import com.humblesolutions.finai.ui.components.vector
import com.humblesolutions.finai.ui.edit.TransactionEditorActions
import com.humblesolutions.finai.ui.edit.TransactionEditorSheet
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.ui.theme.FinAiPalette
import com.humblesolutions.finai.usecase.TransactionBrowsing
import kotlin.math.min

/**
 * Everything the household has, one month or one statement at a time (#F3),
 * on home's green field so opening it from "View all" keeps one ground.
 *
 * The rows are a stack, as in Apple Wallet: each card overlaps the one before
 * it, and a card that reaches the top stops there and stacks — a little
 * smaller and a little higher with each card that arrives over it — rather
 * than leaving the screen. Scrolling back unstacks them. The stacking is drawn
 * in each card's layer from the scroll position, so scrolling recomposes
 * nothing.
 *
 * Which filter each mode sends is [TransactionBrowsing]'s to decide; this
 * screen only draws the answer. Tapping a card opens the same editor the
 * review queue uses.
 */
@Composable
fun TransactionsScreen(
    state: TransactionsUiState,
    onMode: (TransactionBrowsing.Mode) -> Unit,
    onStatement: (String) -> Unit,
    onMonth: (kotlinx.datetime.LocalDate) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onEdit: (String) -> Unit,
    editor: TransactionEditorActions,
    scroll: ScrollState = rememberScrollState(),
) {
    val dark = isSystemInDarkTheme()
    LightStatusBarIcons(dark)
    var picking by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Field.brush(dark))) {
        Waves(Modifier.fillMaxSize())
        FieldVectors(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp)) {
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_back),
                        contentDescription = strings(Strings.action_back),
                        tint = Field.ink(),
                    )
                }
            }
            Box(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxSize()
                    .verticalScroll(scroll),
            ) {
                Wallet(state, scroll, onMode, onStatement, onMonth, onLoadMore, onRetry, onEdit) { picking = true }
            }
        }
    }

    state.editor?.let { TransactionEditorSheet(it, editor) }
    if (picking) {
        SlicePickerSheet(
            state = state,
            onStatement = {
                onStatement(it)
                picking = false
            },
            onMonth = {
                onMonth(it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

// ── The stack ───────────────────────────────────────────────────────────

/** What each child of the stack is, which decides how it is placed. */
private enum class Piece { BLOCK, HEADING, CARD }

/** How much of each card the next one covers: its bottom padding, never its words. */
private val OVERLAP = 14.dp

/** Where cards stop and stack, below the top of the scrolling area. */
private val PIN = 22.dp

/** How far up each card further into the stack peeks out. */
private val PEEK = 6.dp

/** How many cards deep the stack shows before a card fades out. */
private const val DEPTH = 3f

@Composable
private fun Wallet(
    state: TransactionsUiState,
    scroll: ScrollState,
    onMode: (TransactionBrowsing.Mode) -> Unit,
    onStatement: (String) -> Unit,
    onMonth: (kotlinx.datetime.LocalDate) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onEdit: (String) -> Unit,
    onPick: () -> Unit,
) {
    val sections = if (!state.loading && !state.loadFailed && !state.showsEmpty) state.sections else emptyList()
    val pieces = buildList {
        add(Piece.BLOCK)
        sections.forEach { section ->
            add(Piece.HEADING)
            repeat(section.rows.size) { add(Piece.CARD) }
        }
        add(Piece.BLOCK)
    }

    Layout(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        content = {
            Top(state, onMode, onStatement, onMonth, onPick)
            sections.forEach { section ->
                Heading(section)
                section.rows.forEach { row -> Card(state, row) { onEdit(row.id) } }
            }
            Bottom(state, onLoadMore, onRetry)
        },
    ) { measurables, constraints ->
        val loose = Constraints(maxWidth = constraints.maxWidth)
        val placeables = measurables.map { it.measure(loose) }
        val overlap = OVERLAP.roundToPx()
        val gap = 12.dp.roundToPx()

        // Each card's place in the content, overlapping the card before it.
        val tops = IntArray(placeables.size)
        var y = 0
        placeables.forEachIndexed { index, placeable ->
            val piece = pieces[index]
            val previous = pieces.getOrNull(index - 1)
            if (index > 0) {
                y += when {
                    piece == Piece.CARD && previous == Piece.CARD -> -overlap
                    piece == Piece.HEADING -> gap * 2
                    previous == Piece.HEADING -> gap / 2
                    else -> gap
                }
            }
            tops[index] = y
            y += placeable.height
        }

        val pin = PIN.toPx()
        val peek = PEEK.toPx()
        val fadeFrom = 72.dp.toPx()
        val fadeOver = 32.dp.toPx()
        layout(constraints.maxWidth, y + 24.dp.roundToPx()) {
            placeables.forEachIndexed { index, placeable ->
                val top = tops[index]
                when (pieces[index]) {
                    Piece.CARD -> {
                        val step = (placeable.height - overlap).coerceAtLeast(1).toFloat()
                        placeable.placeWithLayer(0, top, zIndex = 1f + index) {
                            // Read here, in the layer, so scrolling redraws
                            // the cards without composing anything again.
                            val onScreen = top - scroll.value.toFloat()
                            if (onScreen < pin) {
                                val depth = (pin - onScreen) / step
                                val shown = min(depth, DEPTH)
                                translationY = (pin - onScreen) - peek * shown
                                scaleX = 1f - 0.04f * shown
                                scaleY = scaleX
                                transformOrigin = TransformOrigin(0.5f, 0f)
                                alpha = if (depth > DEPTH) (1f - (depth - DEPTH)).coerceIn(0f, 1f) else 1f
                            } else {
                                translationY = 0f
                                scaleX = 1f
                                scaleY = 1f
                                alpha = 1f
                            }
                        }
                    }

                    // A heading goes as the stack reaches it — the stacked
                    // card in front is about a card tall — rather than
                    // showing beneath it.
                    Piece.HEADING -> placeable.placeWithLayer(0, top, zIndex = 0f) {
                        val onScreen = top - scroll.value.toFloat()
                        alpha = ((onScreen - pin - fadeFrom) / fadeOver).coerceIn(0f, 1f)
                    }

                    Piece.BLOCK -> placeable.place(0, top)
                }
            }
        }
    }
}

// ── Above the stack ─────────────────────────────────────────────────────

@Composable
private fun Top(
    state: TransactionsUiState,
    onMode: (TransactionBrowsing.Mode) -> Unit,
    onStatement: (String) -> Unit,
    onMonth: (kotlinx.datetime.LocalDate) -> Unit,
    onPick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = strings(Strings.transactions_title),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = Field.ink(),
            modifier = Modifier.semantics { heading() },
        )
        ModeToggle(state, onMode)
        // By category there is nothing to choose between: it is everything.
        if (!state.browsingByCategory) SliceRow(state, onStatement, onMonth, onPick)
        Totals(state)
    }
}

/** The two ways of slicing, as a pill with the chosen half lit. */
@Composable
private fun ModeToggle(state: TransactionsUiState, onMode: (TransactionBrowsing.Mode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(Field.Glass)
            .border(1.dp, Field.GlassEdge, CircleShape)
            .padding(4.dp),
    ) {
        Segment(strings(Strings.transactions_by_month), state.mode == TransactionBrowsing.Mode.BY_MONTH, Modifier.weight(1f)) {
            onMode(TransactionBrowsing.Mode.BY_MONTH)
        }
        Segment(strings(Strings.transactions_by_statement), state.browsingByStatement, Modifier.weight(1f)) {
            onMode(TransactionBrowsing.Mode.BY_STATEMENT)
        }
        Segment(strings(Strings.transactions_by_category), state.browsingByCategory, Modifier.weight(1f)) {
            onMode(TransactionBrowsing.Mode.BY_CATEGORY)
        }
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(if (selected) Color.White else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) FinAiPalette.GreenDeep else Field.ink(0.9f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

/** The months or statements to choose from, and the button that lists them all. */
@Composable
private fun SliceRow(
    state: TransactionsUiState,
    onStatement: (String) -> Unit,
    onMonth: (kotlinx.datetime.LocalDate) -> Unit,
    onPick: () -> Unit,
) {
    val pickLabel = strings(
        if (state.browsingByStatement) Strings.transactions_pick_statement else Strings.transactions_pick_month,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.browsingByStatement) {
                state.statements.forEach { statement ->
                    Chip(state.statementChip(statement), statement.id == state.statementId) { onStatement(statement.id) }
                }
            } else {
                state.months.forEach { month ->
                    Chip(state.monthLabel(month), month == state.month) { onMonth(month) }
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .clickable(onClickLabel = pickLabel, role = Role.Button, onClick = onPick)
                .semantics { contentDescription = pickLabel },
            contentAlignment = Alignment.Center,
        ) {
            FinAiIcon(Icons.Filled.CalendarMonth, tint = FinAiPalette.GreenDeep, size = 22.dp)
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(CircleShape)
            .background(if (selected) Color(0xFF0B5E2E) else Field.Glass)
            .border(1.dp, if (selected) Color.White.copy(alpha = 0.25f) else Field.GlassEdge, CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = Field.ink(),
        )
    }
}

/** Money in and money out for the slice, side by side. */
@Composable
private fun Totals(state: TransactionsUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Total(
            label = strings(Strings.transactions_total_in),
            // Absent, not "$0.00", for a slice with nothing in it: zero would
            // be a claim about the statement rather than an absence of rows.
            value = state.totalIn ?: "—",
            icon = Icons.Filled.ArrowUpward,
            accent = FinAiPalette.Green,
            label2 = FinAiPalette.GreenDeep,
            modifier = Modifier.weight(1f),
        )
        Total(
            label = strings(Strings.transactions_total_out),
            value = state.totalOut ?: "—",
            icon = Icons.Filled.ArrowDownward,
            accent = FinAiPalette.Red,
            label2 = Color(0xFFB91C1C),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Total(label: String, value: String, icon: ImageVector, accent: Color, label2: Color, modifier: Modifier) {
    val dark = isSystemInDarkTheme()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(if (dark) FinAiPalette.DarkSurface else Color.White)
            .background(accent.copy(alpha = if (dark) 0.14f else 0.10f))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) { FinAiIcon(icon, tint = if (dark) accent else label2, size = 22.dp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (dark) accent else label2)
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── In the stack ────────────────────────────────────────────────────────

/** A month, or a category with what went through it. */
@Composable
private fun Heading(section: Section) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = section.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = Field.ink(0.92f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        section.total?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (section.totalIsIn) Color(0xFF86EFAC) else Field.ink(),
            )
        }
    }
}

/** One transaction as a card; its bottom padding is what the next card covers. */
@Composable
private fun Card(state: TransactionsUiState, row: Transaction, onEdit: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
            .clip(shape)
            .background(if (dark) FinAiPalette.DarkSurface else Color.White)
            .border(1.dp, if (dark) Color.White.copy(alpha = 0.06f) else Color(0xFFE8F1EC), shape)
            .clickable(onClickLabel = state.editLabel(row), onClick = onEdit)
            .semantics(mergeDescendants = true) { contentDescription = state.rowDescription(row) }
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp + OVERLAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val filed = state.isFiled(row)
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background((if (filed) FinAiPalette.Green else FinAiPalette.Amber).copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            FinAiIcon(
                state.iconFor(row).vector(),
                tint = if (filed) FinAiPalette.GreenDeep.takeUnless { dark } ?: FinAiPalette.Green else Color(0xFFB45309),
                size = 26.dp,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                state.dateLabel(row.occurredOn),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                state.titleOf(row),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                state.categoryLabel(row),
                style = MaterialTheme.typography.bodyMedium,
                color = if (filed) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFB45309),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            state.amountLabel(row),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            // Green for money in; money out stays plain, so the direction
            // registers instead of every row shouting.
            color = if (state.isCredit(row)) FinAiPalette.GreenDeep.takeUnless { dark } ?: FinAiPalette.Green else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(4.dp))
        FinAiIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 20.dp)
    }
}

// ── Below the stack ─────────────────────────────────────────────────────

/** Loading, a failure, an empty slice, or the next page — whichever applies. */
@Composable
private fun Bottom(state: TransactionsUiState, onLoadMore: () -> Unit, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            state.loading || state.loadingMore -> CircularProgressIndicator(
                color = Field.ink(),
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )

            state.loadFailed -> {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) { ErrorText(state.errorKey) }
                Spacer(Modifier.height(10.dp))
                GlassButton(strings(Strings.dashboard_retry), onRetry)
            }

            state.showsEmpty -> Text(
                state.emptyMessage,
                style = MaterialTheme.typography.bodyLarge,
                color = Field.ink(0.9f),
                modifier = Modifier.padding(vertical = 24.dp),
            )

            state.canLoadMore -> GlassButton(strings(Strings.transactions_load_more), onLoadMore)
        }
    }
}

@Composable
private fun GlassButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Field.Glass)
            .border(1.dp, Field.GlassEdge, RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Field.ink())
    }
}

/** Every month, or every statement, to choose from when the chips run off the edge. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlicePickerSheet(
    state: TransactionsUiState,
    onStatement: (String) -> Unit,
    onMonth: (kotlinx.datetime.LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        SheetTitle(
            strings(if (state.browsingByStatement) Strings.transactions_pick_statement else Strings.transactions_pick_month),
        )
        LazyColumn(Modifier.fillMaxWidth()) {
            if (state.browsingByStatement) {
                items(state.statements, key = { it.id }) { statement ->
                    SheetRow(
                        title = state.statementLabel(statement),
                        detail = null,
                        selected = statement.id == state.statementId,
                        onClick = { onStatement(statement.id) },
                    )
                }
            } else {
                items(state.months, key = { it.toString() }) { month ->
                    SheetRow(
                        title = state.monthLabel(month),
                        detail = null,
                        selected = month == state.month,
                        onClick = { onMonth(month) },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
