package com.humblesolutions.finai.repository

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.ParsedStatement
import com.humblesolutions.finai.model.StatementUpload
import kotlin.coroutines.cancellation.CancellationException

/** `POST /statements/parse` — redacted text in, transaction rows back. */
interface StatementImportRepository {

    @Throws(ApiException::class, CancellationException::class)
    suspend fun parse(upload: StatementUpload): ParsedStatement

    /** Releases the HTTP client. Built fresh per bind, never shared. */
    fun close()
}
