package com.humblesolutions.finai.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The AI coach (backend #84, #89, #90). Every figure in a reply is the
// server's — checked against its own tool results before it is sent — so the
// app shows reply text exactly as it arrives and never adds a number.

/** A question the coach can answer now, answered by the server without AI. */
@Serializable
data class ChatSuggestion(
    val id: String = "",
    val text: String = "",
)

@Serializable
internal data class ChatSuggestions(val suggestions: List<ChatSuggestion> = emptyList())

/** One conversation in the list, newest first. */
@Serializable
data class ConversationSummary(
    val id: String = "",
    val title: String? = null,
    val preview: String? = null,
    @SerialName("updated_at")
    val updatedAt: String = "",
)

@Serializable
internal data class ConversationSummaries(val conversations: List<ConversationSummary> = emptyList())

/** Who said a message. */
enum class ChatRole(val wire: String) {
    YOU("user"),
    COACH("assistant"),
    ;

    companion object {
        fun fromWire(value: String?): ChatRole = entries.firstOrNull { it.wire == value } ?: COACH
    }
}

/** A message as the server stores it — text only; the tools behind it stay there. */
@Serializable
data class ChatMessage(
    val role: String = "",
    val text: String = "",
    val at: String = "",
    /** Assistant turns: true when the answer is about what is to come. */
    val disclaimer: Boolean? = null,
) {
    val sender: ChatRole get() = ChatRole.fromWire(role)
}

/** A conversation and its messages, oldest first. */
@Serializable
data class Conversation(
    val id: String = "",
    val title: String? = null,
    val messages: List<ChatMessage> = emptyList(),
)

/** What `POST …/messages` sends: typed text, or a chip's [suggestionId]. */
@Serializable
internal data class ChatMessageBody(
    val text: String,
    @SerialName("suggestion_id")
    val suggestionId: String? = null,
)

/** The coach's reply. */
@Serializable
data class ChatReply(
    val text: String = "",
    /** The answer is about what is to come: show the inline note. */
    val disclaimer: Boolean = false,
    /** `template` | `cache` | `model` — for analytics; rendered the same. */
    @SerialName("answered_by")
    val answeredBy: String = "",
    /** While the household is still learning, its progress; null once ready. */
    val learning: LearningProgress? = null,
)
