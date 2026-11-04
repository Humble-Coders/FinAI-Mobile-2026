package com.humblesolutions.finai.repository

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.ReceiptRead
import com.humblesolutions.finai.model.Split
import com.humblesolutions.finai.model.SplitContact
import com.humblesolutions.finai.model.SplitSummary
import com.humblesolutions.finai.model.SplitToSave
import kotlin.coroutines.cancellation.CancellationException

/** Split expense, gated by `split_expenses`. Every share is the server's. */
interface SplitRepository {

    /** The people the household splits with, by name. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun contacts(): List<SplitContact>

    /**
     * Save someone to split with. The same number again updates the name.
     * A number WhatsApp could not reach: [ApiException.InvalidPhone].
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun addContact(name: String, phone: String): SplitContact

    @Throws(ApiException::class, CancellationException::class)
    suspend fun deleteContact(id: String)

    /**
     * A receipt's items, tax, tip and total, read by the AI from [text] the
     * phone already masked (`ReceiptText`). Needs AI-processing consent:
     * [ApiException.ConsentRequired]; past the month's reads,
     * [ApiException.ReceiptLimitReached].
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun readReceipt(text: String): ReceiptRead

    @Throws(ApiException::class, CancellationException::class)
    suspend fun create(split: SplitToSave): Split

    /** Newest first. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun splits(): List<SplitSummary>

    @Throws(ApiException::class, CancellationException::class)
    suspend fun split(id: String): Split

    @Throws(ApiException::class, CancellationException::class)
    suspend fun settle(splitId: String, participantId: String, settled: Boolean): Split

    @Throws(ApiException::class, CancellationException::class)
    suspend fun delete(splitId: String)

    fun close()
}
