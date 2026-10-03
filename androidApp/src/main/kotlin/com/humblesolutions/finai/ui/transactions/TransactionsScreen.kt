package com.humblesolutions.finai.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.model.TransactionDirection
import com.humblesolutions.finai.ui.components.ErrorText
import com.humblesolutions.finai.ui.components.ProviderButton
import com.humblesolutions.finai.ui.components.ScreenScaffold
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.ui.theme.FinAiPalette
import com.humblesolutions.finai.usecase.TransactionBrowsing

/**
 * Everything the household has, one statement or one month at a time (#F3).
 *
 * The two modes exist because people hold two different things in mind: a
 * statement they are checking against the paper, and a month they are
 * reasoning about. Which filter each sends is [TransactionBrowsing]'s to
 * decide, not this screen's.
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
) {
    ScreenScaffold {
        Text(
            text = strings(Strings.transactions_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(14.dp))
        ModeToggle(state, onMode)

        Spacer(Modifier.height(10.dp))
        SlicePicker(state, onStatement, onMonth)

        Spacer(Modifier.height(12.dp))
        when {
            state.loading -> Loading()

            state.loadFailed -> Failed(state, onRetry)

            state.showsEmpty -> Text(
                text = state.emptyMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> Rows(state, onLoadMore)
        }

        Spacer(Modifier.height(20.dp))
        ProviderButton(text = strings(Strings.transactions_close), onClick = onClose)
    }
}

/** The two ways of slicing, as a segmented pair. */
@Composable
private fun ModeToggle(state: TransactionsUiState, onMode: (TransactionBrowsing.Mode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Segment(
            label = strings(Strings.transactions_by_month),
            selected = !state.browsingByStatement,
            modifier = Modifier.weight(1f),
        ) { onMode(TransactionBrowsing.Mode.BY_MONTH) }
        Segment(
            label = strings(Strings.transactions_by_statement),
            selected = state.browsingByStatement,
            modifier = Modifier.weight(1f),
        ) { onMode(TransactionBrowsing.Mode.BY_STATEMENT) }
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.onBackground
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** Which statement, or which month — whichever the mode is asking for. */
@Composable
private fun SlicePicker(
    state: TransactionsUiState,
    onStatement: (String) -> Unit,
    onMonth: (kotlinx.datetime.LocalDate) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.browsingByStatement) {
            state.statements.forEach { statement ->
                Chip(
                    label = state.statementLabel(statement),
                    selected = statement.id == state.statementId,
                ) { onStatement(statement.id) }
            }
        } else {
            state.months.forEach { month ->
                Chip(label = state.monthLabel(month), selected = month == state.month) {
                    onMonth(month)
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) FinAiPalette.OnGreen else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) FinAiPalette.Green else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(onClick = onClick)
            .heightIn(min = 36.dp)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

@Composable
private fun Rows(state: TransactionsUiState, onLoadMore: () -> Unit) {
    Totals(state)
    state.days.forEach { day ->
        Text(
            text = state.dateLabel(day.date),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            day.rows.forEachIndexed { index, row ->
                TransactionRow(state, row)
                if (index != day.rows.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }

    if (state.canLoadMore) {
        Spacer(Modifier.height(12.dp))
        ProviderButton(text = strings(Strings.transactions_load_more), onClick = onLoadMore)
    }
    if (state.loadingMore) {
        Spacer(Modifier.height(12.dp))
        Loading()
    }
}

@Composable
private fun Totals(state: TransactionsUiState) {
    val chips = listOfNotNull(
        state.totalOut?.let { strings(Strings.import_extracted_out, it) },
        state.totalIn?.let { strings(Strings.import_extracted_in, it) },
    )
    if (chips.isEmpty()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun TransactionRow(state: TransactionsUiState, row: Transaction) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = state.rowDescription(row) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = state.titleOf(row),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            val filed = state.isFiled(row)
            Text(
                text = state.categoryLabel(row),
                style = MaterialTheme.typography.labelSmall,
                color = if (filed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (filed) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
                        },
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = state.amountLabel(row),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            // Green for money in; money out stays plain, so the direction
            // registers instead of every row shouting.
            color = if (row.direction == TransactionDirection.CREDIT) {
                FinAiPalette.Green
            } else {
                MaterialTheme.colorScheme.onBackground
            },
        )
    }
}

@Composable
private fun Loading() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun Failed(state: TransactionsUiState, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        ErrorText(state.errorKey)
        Spacer(Modifier.height(8.dp))
        ProviderButton(text = strings(Strings.dashboard_retry), onClick = onRetry)
    }
}
