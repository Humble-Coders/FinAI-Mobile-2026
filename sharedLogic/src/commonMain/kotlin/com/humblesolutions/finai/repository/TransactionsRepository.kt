package com.humblesolutions.finai.repository

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.NewTransaction
import com.humblesolutions.finai.model.Transaction
import kotlin.coroutines.cancellation.CancellationException

/** Transactions typed in by hand (Finance-backend #38). */
interface TransactionsRepository {

    /**
     * Raises [ApiException.DuplicateTransaction] carrying what the entry
     * matched. "You already have this" is information, not a failure: the
     * screen shows the match and lets the user keep theirs anyway, which is a
     * second call with [NewTransaction.allowDuplicate] set.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun create(entry: NewTransaction): Transaction

    fun close()
}
