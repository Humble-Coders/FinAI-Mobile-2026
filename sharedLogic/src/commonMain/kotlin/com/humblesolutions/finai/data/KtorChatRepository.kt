package com.humblesolutions.finai.data

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.ChatMessageBody
import com.humblesolutions.finai.model.ChatReply
import com.humblesolutions.finai.model.ChatSuggestion
import com.humblesolutions.finai.model.ChatSuggestions
import com.humblesolutions.finai.model.Conversation
import com.humblesolutions.finai.model.ConversationSummaries
import com.humblesolutions.finai.model.ConversationSummary
import com.humblesolutions.finai.model.Terms
import com.humblesolutions.finai.repository.ChatRepository
import com.humblesolutions.finai.repository.SessionTokenSource
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import kotlin.coroutines.cancellation.CancellationException

/** `/chat/…` over Ktor — one implementation for both platforms. */
class KtorChatRepository internal constructor(
    private val http: HttpClient,
) : ChatRepository {

    constructor(baseUrl: String, tokens: SessionTokenSource, logging: Boolean) :
        this(FinAiHttpClient.create(baseUrl, tokens, logging))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun suggestions(): List<ChatSuggestion> = http.getJson<ChatSuggestions>("chat/suggestions").suggestions

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun conversations(): List<ConversationSummary> = http.getJson<ConversationSummaries>("chat/conversations").conversations

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun start(): ConversationSummary = http.postEmpty("chat/conversations")

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun conversation(id: String): Conversation = http.getJson("chat/conversations/$id")

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun send(conversationId: String, text: String, suggestionId: String?): ChatReply = http.postJson("chat/conversations/$conversationId/messages", ChatMessageBody(text, suggestionId))

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun delete(conversationId: String) {
        // 204, no body to decode.
        http.sendMapped { delete("chat/conversations/$conversationId") }
    }

    @Throws(ApiException::class, CancellationException::class)
    override suspend fun disclaimer(): Terms? = try {
        http.getJson<Terms>("legal/disclaimer")
    } catch (e: ApiException.NotFound) {
        // `no_disclaimer`: the region has none. Shown as no link, not an error.
        null
    }

    override fun close() = http.close()
}
