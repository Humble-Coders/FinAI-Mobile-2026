package com.humblesolutions.finai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.LearningProgress
import com.humblesolutions.finai.ui.strings

/**
 * "Still learning your patterns" — what stands in for a budget or a score
 * before there is enough history to compute one (PRD F8).
 *
 * Shared between the Budget tab (#47) and Home (#46) rather than written
 * twice: a person who meets this card in two places should meet the same
 * card, with the same numbers and the same way out of it.
 *
 * The progress comes from the server, which owns the threshold. Nothing here
 * decides whether the household is ready — [LearningProgress.ready] is read,
 * never derived from the counts beside it.
 */
@Composable
internal fun LearningCard(
    progress: LearningProgress,
    modifier: Modifier = Modifier,
    onImport: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Mint.card())
            .border(1.dp, Mint.edge(), RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = strings(Strings.learning_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = strings(Strings.learning_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LearningMeter(
            label = monthsLabel(progress),
            fraction = fractionOf(progress.completeMonths, progress.needs.completeMonths),
        )
        LearningMeter(
            label = strings(
                Strings.learning_transactions,
                progress.transactions.toString(),
                progress.needs.transactions.toString(),
            ),
            fraction = fractionOf(progress.transactions, progress.needs.transactions),
        )

        if (onImport != null) {
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onImport) { Text(strings(Strings.learning_import)) }
            }
        }
    }
}

/**
 * "1 of 1 full month" rather than "1 of 1 full months".
 *
 * The threshold is one month today, so the plural form would be wrong every
 * time it was shown; both forms exist because the server owns the number and
 * can raise it without an app release.
 */
@Composable
private fun monthsLabel(progress: LearningProgress): String {
    val have = progress.completeMonths.toString()
    val need = progress.needs.completeMonths
    return if (need == 1) {
        strings(Strings.learning_months_one, have)
    } else {
        strings(Strings.learning_months, have, need.toString())
    }
}

/**
 * How far along one of the two counts is — a bar's length, never a figure.
 * A threshold of zero reads as met rather than dividing by it.
 */
private fun fractionOf(have: Int, need: Int): Float = if (need <= 0) 1f else (have.toFloat() / need.toFloat()).coerceIn(0f, 1f)

@Composable
private fun LearningMeter(label: String, fraction: Float) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        // One sentence for the screen reader: the bar repeats the label.
        modifier = Modifier.clearAndSetSemantics { contentDescription = label },
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)),
        )
    }
}

/** The same card, written on the green field rather than on a white sheet. */
@Composable
internal fun LearningFieldCard(
    progress: LearningProgress,
    modifier: Modifier = Modifier,
    onImport: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Field.Glass)
            .border(1.dp, Field.GlassEdge, RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = strings(Strings.learning_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Field.ink(),
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = strings(Strings.learning_body),
            style = MaterialTheme.typography.bodyMedium,
            color = Field.ink(0.78f),
        )
        Text(
            text = monthsLabel(progress),
            style = MaterialTheme.typography.labelLarge,
            color = Field.ink(0.9f),
        )
        Text(
            text = strings(
                Strings.learning_transactions,
                progress.transactions.toString(),
                progress.needs.transactions.toString(),
            ),
            style = MaterialTheme.typography.labelLarge,
            color = Field.ink(0.9f),
        )
        if (onImport != null) {
            TextButton(onClick = onImport) {
                Text(strings(Strings.learning_import), color = Field.ink())
            }
        }
    }
}
