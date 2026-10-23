package com.humblesolutions.finai.repository

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.DebtChanges
import com.humblesolutions.finai.model.DebtRecord
import com.humblesolutions.finai.model.DebtsPage
import kotlin.coroutines.cancellation.CancellationException

/**
 * The household's debts, editable outside the setup wizard (backend #76).
 * Bounded — a handful — so a screen reads them whole.
 */
interface DebtsRepository {

    @Throws(ApiException::class, CancellationException::class)
    suspend fun list(): DebtsPage

    /**
     * Apply [changes]. A new balance is dated today and marked typed by hand.
     * Another household's debt, or an account that is not the household's own
     * card or loan, is [ApiException.NotFound].
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun update(id: String, changes: DebtChanges): DebtRecord

    fun close()
}
