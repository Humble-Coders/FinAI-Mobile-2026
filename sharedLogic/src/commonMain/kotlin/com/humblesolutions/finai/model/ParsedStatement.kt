package com.humblesolutions.finai.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What `POST /statements/parse` gives back (Finance-backend#34).
 *
 * The rows are what a model read, not what the user has agreed to. Nothing is
 * stored until they confirm them on the review screen (3.7) — a model is not
 * authority over somebody's financial records.
 */
@Serializable
data class ParsedStatement(
    @SerialName("import_id")
    val importId: String = "",
    /** The currency the amounts below are in, so no second call is needed. */
    val currency: String = "",
    val rows: List<ParsedRow> = emptyList(),
    /**
     * Lines the model offered that the server refused — most often an amount
     * that was not on the page. Shown, not swallowed: it is the difference
     * between "3 transactions" and "3 of the ones we could read".
     */
    @SerialName("unparsed_line_count")
    val unparsedLineCount: Int = 0,
    val model: String = "",
)

@Serializable
data class ParsedRow(
    @SerialName("occurred_on")
    val occurredOn: String = "",
    val description: String = "",
    /** A decimal string, always — never a Double (PRD §4.4). */
    val amount: String = "",
    val direction: String = "",
    val confidence: Int = 0,
)

/**
 * What the device sends. Text, never the document.
 *
 * [statementPeriodStart] and [statementPeriodEnd] carry the year the redactor
 * strips: statements print it once, in the block that also holds the name and
 * address (see [com.humblesolutions.finai.usecase.StatementPeriod]).
 */
@Serializable
data class StatementUpload(
    @SerialName("source_kind")
    val sourceKind: String = "",
    @SerialName("page_count")
    val pageCount: Int? = null,
    val text: String = "",
    @SerialName("account_id")
    val accountId: String? = null,
    @SerialName("statement_period_start")
    val statementPeriodStart: String? = null,
    @SerialName("statement_period_end")
    val statementPeriodEnd: String? = null,
)
