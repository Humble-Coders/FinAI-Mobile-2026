package com.humblesolutions.finai.ui.money

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.humblesolutions.finai.R
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.ui.components.AmountField
import com.humblesolutions.finai.ui.components.ErrorText
import com.humblesolutions.finai.ui.components.FinAiIcon
import com.humblesolutions.finai.ui.components.GradientButton
import com.humblesolutions.finai.ui.components.IconTile
import com.humblesolutions.finai.ui.components.Mint
import com.humblesolutions.finai.ui.components.WizardField
import com.humblesolutions.finai.ui.components.tint
import com.humblesolutions.finai.ui.components.vector
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.ui.theme.FinAiPalette
import com.humblesolutions.finai.usecase.CommitmentBlock
import com.humblesolutions.finai.usecase.CommitmentDraft
import com.humblesolutions.finai.usecase.MoneyDetail
import com.humblesolutions.finai.usecase.MoneyKind
import kotlinx.datetime.LocalDate

/** What the screen can ask its model, and the app, to do. */
class MoneyDetailActions(
    val onBack: () -> Unit,
    val onRetry: () -> Unit,
    val onMonth: (LocalDate) -> Unit,
    val onTab: (ExpensesTab) -> Unit,
    val onOpenSearch: () -> Unit,
    val onCloseSearch: () -> Unit,
    val onQuery: (String) -> Unit,
    val onLoadMore: () -> Unit,
    val onAdd: () -> Unit,
    val onOpenObligation: () -> Unit,
    val onObligationChange: (CommitmentDraft) -> Unit,
    val onSaveObligation: () -> Unit,
    val onCancelObligation: () -> Unit,
    val onDismissNotice: () -> Unit,
)

/**
 * Income, Expenses, Investments or Debts: one month of one kind of money, from
 * Home's four cards. One frame for all four, tinted in the card's colour, so
 * moving between them feels like one place; each adds its own sections.
 *
 * Every figure is the server's. What the screen decides is only which of them
 * goes where, and that is [MoneyDetail]'s, shared with iOS.
 */
@Composable
fun MoneyDetailScreen(state: MoneyDetailUiState, actions: MoneyDetailActions) {
    val accent = accentOf(state.kind)
    val dark = isSystemInDarkTheme()
    Box(Modifier.fillMaxSize()) {
        Backdrop(accent.wash(dark), accent.color)
        Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            TopBar(actions.onBack)
            if (state.loading) return@Column
            if (state.loadFailed) {
                LoadFailed(state.errorKey, actions.onRetry)
                return@Column
            }
            Column(
                Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Title(state, actions.onMonth)
                SummaryCard(state, accent)
                when (state.kind) {
                    MoneyKind.EXPENSES -> ExpensesBody(state, actions, accent)

                    MoneyKind.INCOME -> {
                        TrendCard(state, accent)
                        TransactionsSection(state, actions)
                    }

                    MoneyKind.INVESTMENTS -> InvestmentsBody(state, actions, accent)

                    MoneyKind.DEBTS -> DebtsBody(state, actions, accent)
                }
                // Room for the + button over the last row.
                Spacer(Modifier.height(96.dp))
            }
        }
        if (!state.loading && !state.loadFailed && state.kind != MoneyKind.INVESTMENTS) {
            FloatingActionButton(
                onClick = actions.onAdd,
                containerColor = FinAiPalette.Green,
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .safeDrawingPadding()
                    .padding(20.dp)
                    .size(64.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = strings(Strings.money_add_transaction), modifier = Modifier.size(30.dp))
            }
        }
    }

    state.obligationDraft?.let { ObligationSheet(state, it, actions) }

    state.noticeKey?.let { key ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = actions.onDismissNotice,
            text = { Text(strings(key), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) },
            confirmButton = { TextButton(onClick = actions.onDismissNotice) { Text(strings(Strings.action_done)) } },
        )
    }
}

private val ContentMaxWidth = 560.dp

// ── Colour ──────────────────────────────────────────────────────────────

/** One screen's colour: the card's accent on Home, and the wash behind it. */
private class Accent(val color: Color, val text: Color, val darkText: Color, private val lightWash: Color) {
    fun wash(dark: Boolean): Color = if (dark) color.copy(alpha = 0.16f) else lightWash

    fun label(dark: Boolean): Color = if (dark) darkText else text
}

private fun accentOf(kind: MoneyKind): Accent = when (kind) {
    MoneyKind.INCOME -> Accent(FinAiPalette.Green, Color(0xFF15803D), FinAiPalette.Green, Color(0xFFE3F5EA))
    MoneyKind.EXPENSES -> Accent(FinAiPalette.Red, Color(0xFFDC2626), Color(0xFFF87171), Color(0xFFFDE8E6))
    MoneyKind.INVESTMENTS -> Accent(FinAiPalette.Blue, Color(0xFF2563EB), Color(0xFF60A5FA), Color(0xFFE5EEFD))
    MoneyKind.DEBTS -> Accent(FinAiPalette.Amber, Color(0xFFB45309), FinAiPalette.Amber, Color(0xFFFEF1DC))
}

private fun iconOf(kind: MoneyKind): ImageVector = when (kind) {
    MoneyKind.INCOME -> Icons.Filled.AccountBalanceWallet
    MoneyKind.EXPENSES -> Icons.Filled.CreditCard
    MoneyKind.INVESTMENTS -> Icons.Filled.Savings
    MoneyKind.DEBTS -> Icons.AutoMirrored.Filled.ReceiptLong
}

/** The wash fading down into the ground, with two soft shapes in the corner. Decorative. */
@Composable
private fun Backdrop(wash: Color, accent: Color) {
    val ground = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(wash, ground, ground))).clearAndSetSemantics {}) {
        Canvas(Modifier.fillMaxWidth().height(320.dp)) {
            val w = size.width
            drawCircle(accent.copy(alpha = 0.08f), radius = 120.dp.toPx(), center = Offset(w * 0.92f, 60.dp.toPx()))
            drawCircle(accent.copy(alpha = 0.06f), radius = 70.dp.toPx(), center = Offset(w * 0.62f, 150.dp.toPx()))
        }
    }
}

// ── Frame ───────────────────────────────────────────────────────────────

@Composable
private fun TopBar(onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(
                painter = painterResource(R.drawable.ic_back),
                contentDescription = strings(Strings.action_back),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

@Composable
private fun Title(state: MoneyDetailUiState, onMonth: (LocalDate) -> Unit) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            strings(state.titleKey),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        Box {
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClickLabel = strings(Strings.money_choose_month)) { choosing = true }
                    .heightIn(min = 48.dp)
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(state.monthLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.width(4.dp))
                FinAiIcon(Icons.Filled.KeyboardArrowDown, tint = MaterialTheme.colorScheme.onBackground, size = 22.dp)
            }
            DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                state.months.forEach { month ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                state.monthName(month),
                                fontWeight = if (month == state.month) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        onClick = {
                            choosing = false
                            onMonth(month)
                        },
                    )
                }
            }
        }
    }
}

/** A white card, as every section of these screens sits on. */
@Composable
private fun Card(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Mint.panel())
            .border(1.dp, Mint.edge(), shape)
            .padding(padding),
        content = content,
    )
}

@Composable
private fun SummaryCard(state: MoneyDetailUiState, accent: Accent) {
    val dark = isSystemInDarkTheme()
    Card(padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(iconOf(state.kind), accent.color, size = 64.dp, iconSize = 32.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(strings(state.headlineLabelKey), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    state.headline,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    // Never clipped digits: a figure that does not fit steps down.
                    autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 30.sp, stepSize = 1.sp),
                )
                state.changeLabel?.let { label ->
                    val good = state.changeIsGood
                    val colour = if (good) goodColor(dark) else badColor(dark)
                    Row(
                        Modifier.clearAndSetSemantics { contentDescription = state.changeDescription.orEmpty() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FinAiIcon(
                            if (state.change?.rose == true) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                            tint = colour,
                            size = 16.dp,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = colour)
                        Spacer(Modifier.width(6.dp))
                        Text(strings(Strings.money_vs_last_month), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                state.paidThisMonth?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = accent.label(dark))
                }
            }
        }
    }
}

private fun goodColor(dark: Boolean) = if (dark) FinAiPalette.Green else Color(0xFF15803D)

private fun badColor(dark: Boolean) = if (dark) Color(0xFFF87171) else Color(0xFFDC2626)

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier, end: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        end?.invoke()
    }
}

@Composable
private fun LoadFailed(errorKey: String?, onRetry: () -> Unit) {
    Column(
        Modifier.widthIn(max = ContentMaxWidth).fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(strings(Strings.money_load_failed), style = MaterialTheme.typography.bodyLarge)
        if (errorKey != null && errorKey != Strings.error_network) ErrorText(errorKey)
        GradientButton(text = strings(Strings.dashboard_retry), onClick = onRetry)
    }
}

// ── Trend ───────────────────────────────────────────────────────────────

@Composable
private fun TrendCard(state: MoneyDetailUiState, accent: Accent) {
    val bars = state.bars
    if (bars.none { it.value != null }) return
    val dark = isSystemInDarkTheme()
    Card {
        Text(
            strings(Strings.money_monthly_trend),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clearAndSetSemantics { contentDescription = state.trendDescription },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            bars.forEach { bar ->
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    if (bar.isCurrent && bar.value != null) {
                        Text(
                            state.barValue(bar).orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(accent.color)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        // A month with nothing recorded is no bar at all, not a zero-height one.
                        if (bar.value != null) {
                            Box(
                                Modifier
                                    .fillMaxWidth(0.72f)
                                    .height((maxHeight * bar.height.toFloat()).coerceAtLeast(6.dp))
                                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                    .background(if (bar.isCurrent) accent.color else accent.color.copy(alpha = if (dark) 0.35f else 0.25f)),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(state.barMonth(bar), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

// ── Transactions ────────────────────────────────────────────────────────

@Composable
private fun TransactionsSection(state: MoneyDetailUiState, actions: MoneyDetailActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.searching) {
            val focus = LocalFocusManager.current
            OutlinedTextField(
                value = state.query,
                onValueChange = actions.onQuery,
                placeholder = { Text(strings(Strings.money_search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = actions.onCloseSearch) { Icon(Icons.Filled.Close, contentDescription = strings(Strings.money_close_search)) }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            SectionHeader(strings(state.listTitleKey)) {
                CircleButton(Icons.Filled.Search, strings(Strings.money_search), actions.onOpenSearch)
            }
        }
        val days = state.days
        if (days.isEmpty()) {
            Text(state.emptyMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        days.forEach { day ->
            Text(state.dayLabel(day), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            day.rows.forEach { row -> TransactionRow(state, row) }
        }
        if (state.nextCursor != null && state.query.isBlank()) {
            TextButton(onClick = actions.onLoadMore, enabled = !state.loadingMore, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(strings(Strings.transactions_load_more))
            }
        }
    }
}

@Composable
private fun TransactionRow(state: MoneyDetailUiState, row: Transaction) {
    val dark = isSystemInDarkTheme()
    val icon = state.iconFor(row)
    val title = state.titleOf(row)
    val category = state.categoryLabel(row)
    val amount = state.amountLabel(row)
    ListRow(
        icon = icon.vector(),
        tint = icon.tint(dark),
        title = title,
        detail = category,
        trailing = {
            Text(
                amount,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                color = if (state.isCredit(row)) goodColor(dark) else MaterialTheme.colorScheme.onSurface,
            )
        },
        description = "$title, $category, $amount",
    )
}

/** A row on a white card: tile, title and line under it, and whatever goes on the right. */
@Composable
private fun ListRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    detail: String?,
    trailing: @Composable () -> Unit,
    description: String,
    detailColor: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clip(shape)
            .background(Mint.panel())
            .border(1.dp, Mint.edge(), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint, size = 44.dp, iconSize = 22.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = detailColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

@Composable
private fun CircleButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Mint.panel()).border(1.dp, Mint.edge(), CircleShape),
            contentAlignment = Alignment.Center,
        ) { FinAiIcon(icon, tint = MaterialTheme.colorScheme.onSurface, size = 20.dp) }
    }
}

// ── Expenses ────────────────────────────────────────────────────────────

@Composable
private fun ExpensesBody(state: MoneyDetailUiState, actions: MoneyDetailActions, accent: Accent) {
    val dark = isSystemInDarkTheme()
    Row(Modifier.fillMaxWidth()) {
        ExpensesTab.entries.forEach { tab ->
            val selected = state.tab == tab
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Tab) { actions.onTab(tab) }
                    .semantics { this.selected = selected },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    strings(if (tab == ExpensesTab.TRANSACTIONS) Strings.money_tab_transactions else Strings.money_tab_obligations),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) accent.label(dark) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(if (selected) 2.dp else 1.dp)
                        .background(if (selected) accent.color else MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
    }
    when (state.tab) {
        ExpensesTab.TRANSACTIONS -> TransactionsSection(state, actions)
        ExpensesTab.OBLIGATIONS -> Obligations(state, actions, accent)
    }
}

@Composable
private fun Obligations(state: MoneyDetailUiState, actions: MoneyDetailActions, accent: Accent) {
    val dark = isSystemInDarkTheme()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(strings(Strings.money_monthly_obligations)) {
            if (state.commitments.isNotEmpty()) {
                Text(state.metLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.commitments.isEmpty()) {
            Text(strings(Strings.money_no_obligations), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.commitments.forEach { item ->
            val icon = MoneyDetail.iconFor(item.name)
            val due = state.dueLabel(item.dueDay)
            val amount = state.amount(item.expected)
            val status = strings(if (item.wasSeen) Strings.money_met else Strings.money_not_seen)
            ListRow(
                icon = icon.vector(),
                tint = icon.tint(dark),
                title = item.name,
                detail = due,
                trailing = {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(amount, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                        StatusPill(status, seen = item.wasSeen)
                    }
                },
                description = "${item.name}, $amount, $due, $status",
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(accent.color.copy(alpha = if (dark) 0.16f else 0.10f))
                .clickable(role = Role.Button, onClick = actions.onOpenObligation),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            FinAiIcon(Icons.Filled.Add, tint = accent.label(dark), size = 20.dp)
            Spacer(Modifier.width(8.dp))
            Text(strings(Strings.money_add_obligation), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = accent.label(dark))
        }
        Spacer(Modifier.height(8.dp))
        TransactionsSection(state, actions)
    }
}

/** "Met" in green, "Not seen" quietly — never red: not seen is not unpaid. */
@Composable
private fun StatusPill(text: String, seen: Boolean) {
    val dark = isSystemInDarkTheme()
    val colour = if (seen) goodColor(dark) else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = colour,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colour.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

// ── Debts ───────────────────────────────────────────────────────────────

@Composable
private fun DebtsBody(state: MoneyDetailUiState, actions: MoneyDetailActions, accent: Accent) {
    val dark = isSystemInDarkTheme()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(strings(Strings.money_my_debts))
        if (state.debts.isEmpty()) {
            Text(strings(Strings.money_no_debts), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.debts.forEach { debt ->
            val icon = MoneyDetail.iconFor(debt.name)
            val balance = state.amount(debt.balance)
            val detail = state.debtDetail(debt.minimumPayment, debt.interestRatePercent)
            ListRow(
                icon = icon.vector(),
                tint = icon.tint(dark),
                title = debt.name,
                detail = detail,
                trailing = { Text(balance, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1) },
                description = listOfNotNull(debt.name, balance, detail).joinToString(", "),
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(strings(Strings.money_upcoming_payments))
        val upcoming = state.upcoming
        if (upcoming.isEmpty()) {
            Text(strings(Strings.money_no_upcoming), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        upcoming.forEach { item ->
            val amount = state.amount(item.amount)
            val due = state.upcomingDue(item)
            ListRow(
                icon = Icons.Filled.CalendarMonth,
                tint = FinAiPalette.Blue,
                title = item.name,
                detail = due,
                trailing = { Text(amount, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1) },
                description = "${item.name}, $amount, $due",
            )
        }
    }
    TransactionsSection(state, actions)
}

// ── Investments ─────────────────────────────────────────────────────────

@Composable
private fun InvestmentsBody(state: MoneyDetailUiState, actions: MoneyDetailActions, accent: Accent) {
    val dark = isSystemInDarkTheme()
    TrendCard(state, accent)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MiniFigure(Modifier.weight(1f), Icons.Filled.ArrowUpward, goodColor(dark), strings(Strings.money_invested), state.invested)
        MiniFigure(Modifier.weight(1f), Icons.Filled.ArrowDownward, badColor(dark), strings(Strings.money_withdrawn), state.withdrawn)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(strings(Strings.money_your_investments))
        if (state.shares.isEmpty()) {
            Text(strings(Strings.money_no_investments), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.shares.forEach { share ->
            val icon = MoneyDetail.iconFor(share.name)
            val amount = state.amount(share.amount)
            val percent = state.shareLabel(share)
            ListRow(
                icon = icon.vector(),
                tint = accent.color,
                title = share.name,
                detail = null,
                trailing = {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(amount, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(percent, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                description = "${share.name}, $amount, $percent",
            )
        }
        Text(strings(Strings.money_holdings_later), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(accent.color)
            .clickable(role = Role.Button, onClick = actions.onAdd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        FinAiIcon(Icons.Filled.Add, tint = Color.White, size = 22.dp)
        Spacer(Modifier.width(8.dp))
        Text(strings(Strings.money_add_investment), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
    TransactionsSection(state, actions)
}

@Composable
private fun MiniFigure(modifier: Modifier, icon: ImageVector, tint: Color, label: String, value: String) {
    Card(modifier, padding = 14.dp) {
        Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, tint, size = 40.dp, iconSize = 22.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(
                    value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 18.sp, stepSize = 1.sp),
                )
            }
        }
    }
}

// ── Adding an obligation ────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ObligationSheet(state: MoneyDetailUiState, draft: CommitmentDraft, actions: MoneyDetailActions) {
    val focus = LocalFocusManager.current
    ModalBottomSheet(onDismissRequest = actions.onCancelObligation, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(strings(Strings.money_add_obligation), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            val notice = state.obligationNotice
            WizardField(
                value = draft.name,
                onValueChange = { actions.onObligationChange(draft.copy(name = it)) },
                label = strings(Strings.money_name_label),
                placeholder = strings(Strings.money_merchant_hint),
                isError = notice == CommitmentBlock.NO_NAME || notice == CommitmentBlock.NAME_TOO_LONG,
                capitalization = KeyboardCapitalization.Words,
            )
            AmountField(
                value = draft.amount,
                onValueChange = { actions.onObligationChange(draft.copy(amount = it)) },
                label = strings(Strings.money_amount_monthly),
                symbol = state.currencySymbol,
                placeholder = state.amountPlaceholder,
                isError = notice in setOf(CommitmentBlock.NO_AMOUNT, CommitmentBlock.AMOUNT_NOT_MONEY, CommitmentBlock.AMOUNT_ZERO),
                large = false,
            )
            WizardField(
                value = draft.dueDay,
                onValueChange = { actions.onObligationChange(draft.copy(dueDay = it.filter(Char::isDigit).take(2))) },
                label = strings(Strings.money_due_day_label),
                placeholder = strings(Strings.money_due_day_hint),
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
                onDone = { focus.clearFocus() },
                isError = notice == CommitmentBlock.DUE_DAY_INVALID,
            )
            ErrorText(state.obligationErrorKey ?: notice?.messageKey)
            GradientButton(
                text = strings(Strings.money_obligation_save),
                onClick = {
                    focus.clearFocus()
                    actions.onSaveObligation()
                },
                enabled = state.canSaveObligation,
                busy = state.savingObligation,
            )
            TextButton(onClick = actions.onCancelObligation, enabled = !state.savingObligation, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(strings(Strings.action_cancel))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
