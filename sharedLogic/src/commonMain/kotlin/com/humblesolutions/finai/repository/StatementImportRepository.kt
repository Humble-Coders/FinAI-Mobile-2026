package com.humblesolutions.finai.repository

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.ParsedStatement
import com.humblesolutions.finai.model.RowsToSave
import com.humblesolutions.finai.model.SaveOutcome
import com.humblesolutions.finai.model.StatementImports
import com.humblesolutions.finai.model.StatementUpload
import kotlin.coroutines.cancellation.CancellationException

/**
 * `POST /statements/parse` — redacted text in, transaction rows back — and
 * saving those rows against an account.
 */
interface StatementImportRepository {

    @Throws(ApiException::class, CancellationException::class)
    suspend fun parse(upload: StatementUpload): ParsedStatement

    /**
     * `POST /statements/{importId}/transactions`: the parsed rows, filed to
     * the account the person chose (#31). One transaction on the server — the
     * import lands whole or not at all.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun save(importId: String, rows: RowsToSave): SaveOutcome

    /**
     * Every statement this household has imported, newest first.
     *
     * So the transactions screen can offer them as things to pick. The free
     * tier allows one a month, so this is a short list and does not page.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun list(): StatementImports

    /** Releases the HTTP client. Built fresh per bind, never shared. */
    fun close()
}
