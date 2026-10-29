package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.ChatMessage
import com.humblesolutions.finai.model.ChatReply
import com.humblesolutions.finai.model.ChatRole
import com.humblesolutions.finai.model.ChatSuggestion
import com.humblesolutions.finai.model.Conversation
import com.humblesolutions.finai.model.LearningProgress

/**
 * One conversation with the coach, as both platforms show it (#54).
 *
 * The decisions live here so Android and iOS cannot disagree about them:
 * what the person sees while a reply is on its way, what happens to the text
 * they typed when sending fails, when the suggestion chips show, which error
 * to show, and when consent is what is missing. The view models only call
 * these and render the result.
 *
 * Nothing here computes a figure: the coach's text is shown exactly as the
 * server sent it.
 */
data class ChatThreadState(
    val conversationId: String = "",
    val title: String? = null,
    /** The conversation, oldest first, as the server stored it. */
    val history: List<ChatMessage> = emptyList(),
    val suggestions: List<ChatSuggestion> = emptyList(),
    /** What the person is typing. Kept through every failure. */
    val draft: String = "",
    /** What is on its way now: shown at once, as the person's own bubble. */
    val sending: ChatOutgoing? = null,
    val failure: ChatFailure? = null,
    /** While the household is still learning, its progress (backend #90). */
    val learning: LearningProgress? = null,
) {
    val isSending: Boolean get() = sending != null

    /** Longer than the server accepts (backend `MAX_MESSAGE_CHARS`), counted as it is sent. */
    val tooLong: Boolean get() = draft.trim().length > ChatThread.MAX_CHARS

    /** Send is enabled only with something to send, short enough, and nothing on its way. */
    val canSend: Boolean get() = !isSending && draft.isNotBlank() && !tooLong

    /**
     * Why Send is off, to show under the field — or null. Only said when the
     * person can act on it: an empty field or a reply on its way speak for themselves.
     */
    val draftNoticeKey: String? get() = if (tooLong) Strings.chat_too_long else null

    /** The messages to draw, newest last: the history, then what is on its way. */
    val bubbles: List<ChatBubble>
        get() = history.map { ChatBubble.of(it) } +
            listOfNotNull(sending?.let { ChatBubble(ChatRole.YOU, it.text, disclaimerNote = false) })

    /** Where the chips go: filling an empty chat, or in a row above the input. */
    val chips: ChipPlacement
        get() = when {
            suggestions.isEmpty() || isSending -> ChipPlacement.HIDDEN
            history.isEmpty() -> ChipPlacement.EMPTY_CHAT
            else -> ChipPlacement.ABOVE_INPUT
        }

    /** The learning card shows above the input while the household is learning. */
    val showsLearning: Boolean get() = learning != null && !learning.ready
}

/** What is being sent: typed [text], or a chip ([suggestionId] set). */
data class ChatOutgoing(val text: String, val suggestionId: String? = null) {
    val typed: Boolean get() = suggestionId == null
}

enum class ChipPlacement { HIDDEN, EMPTY_CHAT, ABOVE_INPUT }

/**
 * A message to draw. [disclaimerNote] puts the short "not financial advice"
 * note under a coach reply that says one applies.
 */
data class ChatBubble(
    val role: ChatRole,
    val text: String,
    val disclaimerNote: Boolean,
) {
    /** "You said …" / "Coach said …", with [text] as `{0}`. */
    val accessibilityKey: String
        get() = if (role == ChatRole.YOU) Strings.chat_you_said else Strings.chat_coach_said

    companion object {
        fun of(message: ChatMessage): ChatBubble = ChatBubble(
            role = message.sender,
            text = message.text,
            disclaimerNote = message.sender == ChatRole.COACH && message.disclaimer == true,
        )
    }
}

/**
 * Why sending failed, as the screen shows it. [needsConsent] opens the
 * AI-consent screen instead of a message; after consenting, [retry] sends the
 * same thing again without retyping.
 */
data class ChatFailure(
    val messageKey: String,
    val needsConsent: Boolean,
    val retry: ChatOutgoing,
)

object ChatThread {

    /** A conversation just opened. */
    fun opened(conversation: Conversation, suggestions: List<ChatSuggestion>): ChatThreadState = ChatThreadState(
        conversationId = conversation.id,
        title = conversation.title,
        history = conversation.messages,
        suggestions = suggestions,
    )

    fun typed(state: ChatThreadState, draft: String): ChatThreadState = state.copy(draft = draft)

    /** The person pressed Send: the draft goes, shown at once as theirs. */
    fun sendDraft(state: ChatThreadState): ChatThreadState {
        if (!state.canSend) return state
        return state.copy(sending = ChatOutgoing(state.draft.trim()), draft = "", failure = null)
    }

    /** A chip tapped: its question goes, as the person's own bubble. */
    fun sendSuggestion(state: ChatThreadState, suggestion: ChatSuggestion): ChatThreadState {
        if (state.isSending) return state
        return state.copy(sending = ChatOutgoing(suggestion.text, suggestion.id), failure = null)
    }

    /** Send again what failed — after consenting, say — without retyping. */
    fun retry(state: ChatThreadState): ChatThreadState {
        val again = state.failure?.retry ?: return state
        if (state.isSending) return state
        val draft = if (again.typed && state.draft == again.text) "" else state.draft
        return state.copy(sending = again, draft = draft, failure = null)
    }

    /** The reply arrived: both turns join the history. */
    fun replied(state: ChatThreadState, reply: ChatReply, at: String): ChatThreadState {
        val outgoing = state.sending ?: return state
        val turns = listOf(
            ChatMessage(role = ChatRole.YOU.wire, text = outgoing.text, at = at),
            ChatMessage(role = ChatRole.COACH.wire, text = reply.text, at = at, disclaimer = reply.disclaimer),
        )
        return state.copy(
            history = state.history + turns,
            sending = null,
            learning = reply.learning,
            title = state.title ?: outgoing.text.take(TITLE_CHARS),
        )
    }

    /**
     * Sending failed. The conversation is never lost, and a typed question
     * goes back into the field so nothing has to be retyped.
     */
    fun failed(state: ChatThreadState, error: Throwable): ChatThreadState {
        val outgoing = state.sending ?: return state
        val draft = if (outgoing.typed && state.draft.isEmpty()) outgoing.text else state.draft
        return state.copy(sending = null, draft = draft, failure = failure(error, outgoing))
    }

    /** The message closed (or the consent screen dismissed). */
    fun dismissed(state: ChatThreadState): ChatThreadState = state.copy(failure = null)

    fun failure(error: Throwable, outgoing: ChatOutgoing): ChatFailure = ChatFailure(
        messageKey = messageFor(error),
        // Only a typed question needs consent: a chip is answered without AI.
        needsConsent = outgoing.typed && (error is ApiException.ConsentRequired || error is ApiException.AiPolicyChanged),
        retry = outgoing,
    )

    /** The words for a failure — the coach's own, not import's. */
    fun messageFor(error: Throwable): String = when (error) {
        is ApiException.ConsentRequired, is ApiException.AiPolicyChanged -> Strings.chat_error_consent
        is ApiException.ImportUnavailable -> Strings.chat_error_unavailable
        is ApiException.ChatLimitReached -> Strings.chat_error_limit
        is ApiException.Network -> Strings.chat_error_network
        is ApiException.FeatureUnavailable -> Strings.chat_error_off
        else -> Strings.chat_error_generic
    }

    /**
     * The longest question the server takes (backend `MAX_MESSAGE_CHARS`). It
     * counts code points and this counts UTF-16 units, so an emoji-heavy
     * question is held back a little early rather than refused.
     */
    const val MAX_CHARS = 1_000

    private const val TITLE_CHARS = 60
}
