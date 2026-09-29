package com.humblesolutions.finai.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
    /**
     * Until when the server keeps this import's redacted text — set only when
     * the person offered it after a failed import (#31) and the server agreed
     * the import went badly. Null otherwise, which is the normal case.
     */
    @SerialName("text_retained_until")
    val textRetainedUntil: String? = null,
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
    /**
     * "Keep this text so the parser can be fixed" — the person's answer to an
     * explicit, unticked offer shown after a failed import, and only then
     * (#31). Asked per import, never remembered. Left off the wire when false,
     * which is the server's default; the server also refuses to keep text from
     * an import that went well, whatever this says.
     */
    @SerialName("keep_text_for_diagnostics")
    val keepTextForDiagnostics: Boolean = false,
)

/** One parsed row, sent back to be saved against the chosen account (#31). */
@Serializable
data class RowToSave(
    @SerialName("occurred_on")
    val occurredOn: String = "",
    val description: String = "",
    /** A decimal string, exactly as parsed. */
    val amount: String = "",
    val direction: String = "",
    val confidence: Int = 0,
)

/** `POST /statements/{import_id}/transactions`. Both fields are required, so both are always sent. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RowsToSave(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("account_id")
    val accountId: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val rows: List<RowToSave> = emptyList(),
)

/** What saving an import came to. */
@Serializable
data class SaveOutcome(
    @SerialName("import_id")
    val importId: String = "",
    val saved: Int = 0,
    /** Rows already recorded — refused by the dedup index, silently and rightly. */
    val duplicates: Int = 0,
    /** Saved, but flagged as possibly the same as a different row. */
    val flagged: Int = 0,
    /** Rows from this import waiting for a person, for any reason. */
    @SerialName("needs_review")
    val needsReview: Int = 0,
)
