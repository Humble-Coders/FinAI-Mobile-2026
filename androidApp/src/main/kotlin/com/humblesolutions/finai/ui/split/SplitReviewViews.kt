package com.humblesolutions.finai.ui.split

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.Split
import com.humblesolutions.finai.model.SplitMode
import com.humblesolutions.finai.model.SplitSummary
import com.humblesolutions.finai.ui.components.ErrorText
import com.humblesolutions.finai.ui.components.FinAiIcon
import com.humblesolutions.finai.ui.components.GradientButton
import com.humblesolutions.finai.ui.components.Mint
import com.humblesolutions.finai.ui.components.MintPanel
import com.humblesolutions.finai.ui.components.MintTitle
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.usecase.SplitExpense
import com.humblesolutions.finai.usecase.SplitShareRow
import kotlinx.coroutines.delay

// ── The review ──────────────────────────────────────────────────────────

/**
 * Before saving, the shares the shared preview works out; once saved — or
 * opened from the history — the server's, with WhatsApp, "Mark paid" and the
 * group message. Every message is built from the SAVED split.
 */
@Composable
internal fun ReviewStep(state: SplitUiState, actions: SplitActions) {
    MintTitle(strings(Strings.split_review_title), strings(Strings.split_review_detail))
    if (!state.reviewingSaved) return DraftReview(state)
    val split = state.saved
    if (split == null) {
        state.openErrorKey?.let {
            MintPanel {
                ErrorText(it)
                TextButton(onClick = actions.onRetryOpen) { Text(strings(Strings.action_retry)) }
            }
        }
        return
    }
    SavedReview(state, split, actions)
}

@Composable
private fun DraftReview(state: SplitUiState) {
    val draft = state.draft
    BillCard(
        title = draft.title,
        date = state.dateText(draft.occurredOn),
        items = draft.items.size.takeIf { state.byItems },
        total = state.draftTotal,
    )
    MintPanel {
        SectionTitle(strings(Strings.split_summary))
        state.previewRows.forEachIndexed { index, row -> PreviewLine(row, index) }
    }
}

@Composable
private fun SavedReview(state: SplitUiState, split: Split, actions: SplitActions) {
    BillCard(
        title = split.title,
        date = state.dateText(split.occurredOn),
        items = split.items.size.takeIf { split.mode == SplitMode.ITEMS.wire },
        total = state.money(split.total),
    )
    MintPanel {
        SectionTitle(strings(Strings.split_summary))
        state.shareRows.forEach { row ->
            val participant = split.participants.getOrNull(row.index) ?: return@forEach
            ShareRow(
                row = row,
                settled = participant.settled,
                settling = participant.id in state.settling,
                canSend = !participant.isSelf && state.whatsAppLinkFor(participant) != null,
                onSend = { actions.onSendWhatsApp(participant) },
                onToggleSettled = { actions.onToggleSettled(participant.id) },
            )
            if (row.index < split.participants.lastIndex) HorizontalDivider(color = Mint.edge())
        }
        ErrorText(state.settleErrorKey)
    }
    state.groupMessage?.let { message ->
        MintPanel {
            SectionTitle(strings(Strings.split_message_preview))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Mint.card())
                    .border(1.dp, Mint.edge(), RoundedCornerShape(14.dp))
                    .padding(14.dp),
            )
            CopyButton(message, actions.onCopy)
        }
    }
}

/** A person's share, with — for everyone but the person themselves — WhatsApp and "Mark paid". */
@Composable
private fun ShareRow(
    row: SplitShareRow,
    settled: Boolean,
    settling: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onToggleSettled: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).describedAs(row.accessibility),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Monogram(row.initials, row.index, ringed = row.isSelf)
            Column(Modifier.weight(1f)) {
                Text(row.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                row.detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(row.share, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        if (row.isSelf) return@Column
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (canSend) {
                val sendTo = strings(Strings.split_send_to, row.label)
                OutlinedButton(
                    onClick = onSend,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = sendTo },
                ) {
                    FinAiIcon(Icons.AutoMirrored.Filled.Send, tint = Mint.greenText(), size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(strings(Strings.split_send_whatsapp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            FilterChip(
                selected = settled,
                onClick = onToggleSettled,
                enabled = !settling,
                label = { Text(strings(if (settled) Strings.split_paid else Strings.split_mark_paid)) },
                leadingIcon = if (settled) {
                    { Icon(Icons.Filled.Check, contentDescription = null) }
                } else {
                    null
                },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Mint.tile(Mint.greenText())),
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

/** "Copy message", saying "Copied" for a moment after — heard as well as seen. */
@Composable
internal fun CopyButton(text: String, onCopy: (String) -> Unit) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    OutlinedButton(
        onClick = {
            onCopy(text)
            copied = true
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        FinAiIcon(if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy, tint = Mint.greenText(), size = 18.dp)
        Spacer(Modifier.width(8.dp))
        Text(strings(if (copied) Strings.split_copied else Strings.split_copy_message))
    }
}

/** Save, pinned under the review, with what still stops it said above. */
@Composable
internal fun SaveBar(state: SplitUiState, actions: SplitActions) {
    BottomBar {
        state.saveBlock?.let { Notice(SplitExpense.blockText(it, state.locale)) }
        ErrorText(state.saveErrorKey)
        GradientButton(text = strings(Strings.split_save), onClick = actions.onSave, enabled = state.saveBlock == null, busy = state.saving)
    }
}

@Composable
internal fun NoWhatsAppDialog(message: String, onCopy: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(strings(Strings.split_no_whatsapp)) },
        confirmButton = {
            TextButton(onClick = {
                onCopy(message)
                onDismiss()
            }) { Text(strings(Strings.split_copy_message)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings(Strings.action_close)) } },
    )
}

// ── The history ─────────────────────────────────────────────────────────

/** Every split, newest first: what it was, when, and what is still to come. */
@Composable
internal fun HistoryStep(state: SplitUiState, actions: SplitActions) {
    LaunchedEffect(Unit) { actions.onHistoryShown() }
    MintTitle(strings(Strings.split_history), null)
    MintPanel {
        state.historyErrorKey?.let {
            ErrorText(it)
            TextButton(onClick = actions.onRetryHistory) { Text(strings(Strings.action_retry)) }
        }
        if (state.historyLoaded && state.history.isEmpty()) Notice(strings(Strings.split_history_empty))
        state.history.forEachIndexed { index, summary ->
            HistoryRow(state, summary, actions)
            if (index < state.history.lastIndex) HorizontalDivider(color = Mint.edge())
        }
    }
}

@Composable
private fun HistoryRow(state: SplitUiState, summary: SplitSummary, actions: SplitActions) {
    val date = state.dateText(summary.occurredOn)
    val people = strings(Strings.split_people_count, summary.people.toString())
    val line = state.summaryLine(summary)
    val total = state.money(summary.total)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { actions.onOpenSplit(summary.id) }
                .describedAs(listOf(summary.title, date, people, total, line).joinToString(", "))
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(summary.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$date · $people", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(
                    line,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (summary.settled) Mint.greenText() else MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(total, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        IconButton(onClick = { actions.onAskDeleteSplit(summary) }) {
            Icon(Icons.Outlined.Delete, contentDescription = strings(Strings.split_delete), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Deleting cannot be undone, so it is asked first. */
@Composable
internal fun DeleteSplitDialog(deleting: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text(strings(Strings.split_delete)) },
        text = { Text(strings(Strings.split_delete_confirm)) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !deleting) {
                Text(strings(Strings.split_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !deleting) { Text(strings(Strings.action_cancel)) } },
    )
}
