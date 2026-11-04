package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.ContactBody
import com.humblesolutions.finai.model.ReceiptBody
import com.humblesolutions.finai.model.ReceiptRead
import com.humblesolutions.finai.model.SettleBody
import com.humblesolutions.finai.model.Split
import com.humblesolutions.finai.model.SplitContact
import com.humblesolutions.finai.model.SplitContacts
import com.humblesolutions.finai.model.SplitSummaries
import com.humblesolutions.finai.model.SplitSummary
import com.humblesolutions.finai.model.SplitToSave
import com.humblesolutions.finai.repository.SessionTokenSource
import com.humblesolutions.finai.repository.SplitRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import kotlin.coroutines.cancellation.CancellationException

/** `/splits` over Ktor — one implementation for both platforms. */
class KtorSplitRepository internal constructor(
    private val http: HttpClient,
) : SplitRepository {

    constructor(baseUrl: String, tokens: SessionTokenSource, logging: Boolean) :
        this(FinAiHttpClient.create(baseUrl, tokens, logging))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun contacts(): List<SplitContact> = http.getJson<SplitContacts>("splits/contacts").contacts

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun addContact(name: String, phone: String): SplitContact = http.postJson("splits/contacts", ContactBody(name.trim(), phone.trim()))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun deleteContact(id: String) {
        // 204, no body to decode.
        http.sendMapped { delete("splits/contacts/$id") }
    }

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun readReceipt(text: String): ReceiptRead = http.postJson("splits/receipt", ReceiptBody(text))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun create(split: SplitToSave): Split = http.postJson("splits", split)

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun splits(): List<SplitSummary> = http.getJson<SplitSummaries>("splits").splits

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun split(id: String): Split = http.getJson("splits/$id")

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun settle(splitId: String, participantId: String, settled: Boolean): Split = http.patchJson("splits/$splitId/participants/$participantId", SettleBody(settled))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun delete(splitId: String) {
        http.sendMapped { delete("splits/$splitId") }
    }

    override fun close() = http.close()
}
