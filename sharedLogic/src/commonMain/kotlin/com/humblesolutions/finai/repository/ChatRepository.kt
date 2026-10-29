package com.humblesolutions.finai.repository

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.ChatReply
import com.humblesolutions.finai.model.ChatSuggestion
import com.humblesolutions.finai.model.Conversation
import com.humblesolutions.finai.model.ConversationSummary
import com.humblesolutions.finai.model.Terms
import kotlin.coroutines.cancellation.CancellationException

/**
 * The AI coach (backend #84, #89, #90), gated by `ai_chat`. Replies arrive
 * whole — no streaming in v1 — and every figure in them is the server's.
 */
interface ChatRepository {

    /** The questions the coach can answer now, without AI; empty when none. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun suggestions(): List<ChatSuggestion>

    /** Newest first. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun conversations(): List<ConversationSummary>

    @Throws(ApiException::class, CancellationException::class)
    suspend fun start(): ConversationSummary

    @Throws(ApiException::class, CancellationException::class)
    suspend fun conversation(id: String): Conversation

    /**
     * Ask [text], or send a chip's [suggestionId] (then [text] may be empty).
     * A typed question can be refused with [ApiException.ConsentRequired], the
     * daily [ApiException.ChatLimitReached], or [ApiException.ImportUnavailable]
     * (the provider's no-training terms are not confirmed); a chip never needs
     * consent.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun send(conversationId: String, text: String, suggestionId: String?): ChatReply

    @Throws(ApiException::class, CancellationException::class)
    suspend fun delete(conversationId: String)

    /** The region's not-financial-advice disclaimer, or null when it has none. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun disclaimer(): Terms?

    fun close()
}
