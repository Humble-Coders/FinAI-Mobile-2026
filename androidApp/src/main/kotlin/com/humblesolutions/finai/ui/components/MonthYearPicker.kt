package com.humblesolutions.finai.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.usecase.MonthPicker
import com.humblesolutions.finai.util.Dates
import kotlinx.datetime.LocalDate

/**
 * A month-and-year calendar: the year with arrows either side, and its twelve
 * months to tap.
 *
 * **Not a native component, and said so:** Material 3's date pickers choose a
 * day, and there is no month-only one. This is built from Material's own
 * dialog, icon buttons and buttons, so it themes, scales and reads like the
 * rest of the platform. iOS uses the system's year-and-month picker.
 *
 * Which months can be chosen is [MonthPicker]'s: none that has not begun, none
 * before its earliest year.
 */
@Composable
internal fun MonthYearPickerDialog(
    selected: LocalDate,
    current: LocalDate,
    locale: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val years = MonthPicker.years(current)
    var year by rememberSaveable { mutableIntStateOf(selected.year.coerceIn(years.first(), years.last())) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(strings(Strings.budget_month_pick_title), modifier = Modifier.semantics { heading() })
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { year-- }, enabled = year > years.first()) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = strings(Strings.budget_month_previous_year))
                    }
                    Text(
                        text = year.toString(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { year++ }, enabled = year < years.last()) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = strings(Strings.budget_month_next_year))
                    }
                }
                (1..12).chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { month ->
                            val label = Dates.monthShort(LocalDate(year, month, 1), locale)
                            val isSelected = year == selected.year && month == selected.month.ordinal + 1
                            val enabled = MonthPicker.isSelectable(year, month, current)
                            val pick = { onPick(MonthPicker.wire(year, month)) }
                            val modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .semantics { this.selected = isSelected }
                            if (isSelected) {
                                FilledTonalButton(onClick = pick, enabled = enabled, modifier = modifier) { Text(label, maxLines = 1) }
                            } else {
                                TextButton(onClick = pick, enabled = enabled, modifier = modifier) { Text(label, maxLines = 1) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings(Strings.action_cancel)) }
        },
    )
}
