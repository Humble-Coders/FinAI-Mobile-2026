package com.humblesolutions.finai.ui.chat

import com.humblesolutions.finai.model.ConversationSummary
import com.humblesolutions.finai.model.Terms
import com.humblesolutions.finai.usecase.ChatThreadState

/**
 * The Coach tab (#54): the list of conversations, and the one open in it.
 * What a conversation shows — bubbles, chips, failures, the learning card — is
 * decided in shared `ChatThread`; this only holds it.
 */
internal data class ChatUiState(
    val conversations: List<ConversationSummary> = emptyList(),
    /** A first read of the list; the coin covers it. */
    val listLoading: Boolean = false,
    val listLoaded: Boolean = false,
    val listErrorKey: String? = null,
    /** The conversation open now, or null on the list. */
    val openId: String? = null,
    val thread: ChatThreadState? = null,
    val threadLoading: Boolean = false,
    val threadErrorKey: String? = null,
    /** The region's disclaimer, for the header link; null when it has none. */
    val disclaimer: Terms? = null,
    val showingDisclaimer: Boolean = false,
) {
    val listEmpty: Boolean get() = listLoaded && conversations.isEmpty()
}
