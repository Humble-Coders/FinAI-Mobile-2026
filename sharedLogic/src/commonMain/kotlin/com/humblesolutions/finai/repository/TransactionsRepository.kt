package com.humblesolutions.finai.repository

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.ConfirmOutcome
import com.humblesolutions.finai.model.DeleteOutcome
import com.humblesolutions.finai.model.NewTransaction
import com.humblesolutions.finai.model.PatchOutcome
import com.humblesolutions.finai.model.ReviewPage
import com.humblesolutions.finai.model.TransactionPatch
import com.humblesolutions.finai.model.Transaction
import kotlin.coroutines.cancellation.CancellationException

/**
 * Transactions: typed in by hand (#38), and the review queue that holds the
 * ones extraction could not resolve (#32).
 */
interface TransactionsRepository {

    /**
     * Raises [ApiException.DuplicateTransaction] carrying what the entry
     * matched. "You already have this" is information, not a failure: the
     * screen shows the match and lets the user keep theirs anyway, which is a
     * second call with [NewTransaction.allowDuplicate] set.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun create(entry: NewTransaction): Transaction

    /**
     * A page of the review queue (#32). Pass the previous page's
     * [ReviewPage.nextCursor] to continue; null starts at the top.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun review(cursor: String? = null): ReviewPage

    /**
     * Fix a row. Whatever changed, the row has been looked at, so it leaves
     * the queue — unless it still has no category, in which case the server
     * keeps it, asking for one (Finance-backend #48).
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun correct(id: String, patch: TransactionPatch): PatchOutcome

    /** Accept one row exactly as it is. Confirming twice is not an error. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun confirm(id: String): PatchOutcome

    /**
     * Accept many at once — the common case is twenty right rows and two
     * wrong. All or nothing: one id that is not this household's fails the
     * whole request and applies none of it.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun confirmAll(ids: List<String>): ConfirmOutcome

    /** Remove a row that was never a transaction. Permanent: the server has no undo. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun delete(id: String): DeleteOutcome

    fun close()
}
