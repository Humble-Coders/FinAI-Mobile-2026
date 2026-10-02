package com.humblesolutions.finai.repository

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.ConfirmOutcome
import com.humblesolutions.finai.model.DeleteOutcome
import com.humblesolutions.finai.model.NewTransaction
import com.humblesolutions.finai.model.PatchOutcome
import com.humblesolutions.finai.model.ReviewPage
import com.humblesolutions.finai.model.Transaction
import com.humblesolutions.finai.model.TransactionPatch
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
     * A page of this household's rows — **filed ones included** (#F3).
     *
     * [review] answers "what still needs me". This answers "what did you do",
     * which the import result screen asks the moment a statement lands: a row
     * the model filed with confidence is saved and otherwise shown to nobody,
     * so "imported 24" could not be opened to see the 24.
     *
     * @param statementImportId scopes to one import, which is how the result
     *   screen asks for exactly the rows it just created.
     * @param needsReview filters within that; null returns both kinds, and
     *   each row says which it is.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun list(
        statementImportId: String? = null,
        needsReview: Boolean? = null,
        cursor: String? = null,
    ): ReviewPage

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
