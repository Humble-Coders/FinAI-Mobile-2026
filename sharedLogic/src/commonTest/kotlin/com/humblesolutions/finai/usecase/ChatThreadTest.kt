package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.ChatMessage
import com.humblesolutions.finai.model.ChatReply
import com.humblesolutions.finai.model.ChatRole
import com.humblesolutions.finai.model.ChatSuggestion
import com.humblesolutions.finai.model.Conversation
import com.humblesolutions.finai.model.LearningNeeds
import com.humblesolutions.finai.model.LearningProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val CHIP = ChatSuggestion("spent_last_month", "How much did I spend last month?")
private val EMPTY = ChatThread.opened(Conversation(id = "c1"), listOf(CHIP))
private val LEARNING = LearningProgress(ready = false, completeMonths = 0, transactions = 5, needs = LearningNeeds(1, 20))
private val AT = "2026-09-15T12:00:00Z"

class ChatThreadTest {

    @Test
    fun `an empty chat fills with the chips`() {
        assertEquals(ChipPlacement.EMPTY_CHAT, EMPTY.chips)
        assertTrue(EMPTY.bubbles.isEmpty())
    }

    @Test
    fun `once there is history the chips move above the input`() {
        val state = ChatThread.opened(Conversation("c1", messages = listOf(ChatMessage("user", "Hi", AT))), listOf(CHIP))
        assertEquals(ChipPlacement.ABOVE_INPUT, state.chips)
    }

    @Test
    fun `no chips while a reply is on its way or when there are none`() {
        val sending = ChatThread.sendSuggestion(EMPTY, CHIP)
        assertEquals(ChipPlacement.HIDDEN, sending.chips)
        assertEquals(ChipPlacement.HIDDEN, ChatThread.opened(Conversation("c1"), emptyList()).chips)
    }

    @Test
    fun `sending shows the question at once and empties the field`() {
        val typed = ChatThread.typed(EMPTY, "  What did I spend?  ")
        val sending = ChatThread.sendDraft(typed)

        assertEquals("", sending.draft)
        assertTrue(sending.isSending)
        assertFalse(sending.canSend, "send is disabled while waiting")
        assertEquals(ChatBubble(ChatRole.YOU, "What did I spend?", disclaimerNote = false), sending.bubbles.last())
    }

    @Test
    fun `a blank draft is not sent`() {
        assertEquals(EMPTY, ChatThread.sendDraft(ChatThread.typed(EMPTY, "   ")).copy(draft = ""))
        assertFalse(ChatThread.typed(EMPTY, "   ").canSend)
    }

    @Test
    fun `a chip sends its id and its question shows as the persons own`() {
        val sending = ChatThread.sendSuggestion(EMPTY, CHIP)

        assertEquals("spent_last_month", sending.sending?.suggestionId)
        assertEquals("How much did I spend last month?", sending.bubbles.last().text)
    }

    @Test
    fun `the reply joins the history as sent with its note and the learning progress`() {
        val sending = ChatThread.sendDraft(ChatThread.typed(EMPTY, "Can I afford a trip?"))
        val reply = ChatReply(text = "You have \$242.65 left this month.", disclaimer = true, answeredBy = "model", learning = LEARNING)

        val after = ChatThread.replied(sending, reply, AT)

        assertNull(after.sending)
        assertEquals(listOf("Can I afford a trip?", "You have \$242.65 left this month."), after.bubbles.map { it.text })
        assertTrue(after.bubbles.last().disclaimerNote)
        assertFalse(after.bubbles.first().disclaimerNote, "never under the person's own")
        assertTrue(after.showsLearning)
        assertEquals("Can I afford a trip?", after.title)
    }

    @Test
    fun `a ready household shows no learning card`() {
        val sending = ChatThread.sendSuggestion(EMPTY, CHIP)
        val after = ChatThread.replied(sending, ChatReply(text = "You spent \$840.00 in August."), AT)
        assertFalse(after.showsLearning)
    }

    @Test
    fun `a failed typed question goes back into the field and the history is kept`() {
        val withHistory = ChatThread.opened(Conversation("c1", messages = listOf(ChatMessage("user", "Hi", AT), ChatMessage("assistant", "Hello", AT))), listOf(CHIP))
        val sending = ChatThread.sendDraft(ChatThread.typed(withHistory, "What did I spend?"))

        val failed = ChatThread.failed(sending, ApiException.Network(RuntimeException("offline")))

        assertEquals("What did I spend?", failed.draft)
        assertEquals(2, failed.history.size, "the conversation is never lost")
        assertEquals(Strings.chat_error_network, failed.failure?.messageKey)
        assertFalse(failed.failure!!.needsConsent)
    }

    @Test
    fun `each refusal has its own words`() {
        val outgoing = ChatOutgoing("What did I spend?")
        assertEquals(Strings.chat_error_unavailable, ChatThread.failure(ApiException.ImportUnavailable(), outgoing).messageKey)
        assertEquals(Strings.chat_error_limit, ChatThread.failure(ApiException.ChatLimitReached(30), outgoing).messageKey)
        assertEquals(Strings.chat_error_generic, ChatThread.failure(ApiException.Server(500), outgoing).messageKey)
    }

    @Test
    fun `a typed question needing consent opens consent and can be sent again without retyping`() {
        val sending = ChatThread.sendDraft(ChatThread.typed(EMPTY, "What did I spend?"))
        val failed = ChatThread.failed(sending, ApiException.ConsentRequired("ai-v1"))
        assertTrue(failed.failure!!.needsConsent)

        val again = ChatThread.retry(failed)

        assertEquals(ChatOutgoing("What did I spend?"), again.sending)
        assertEquals("", again.draft, "not left in the field to be sent twice")
        assertNull(again.failure)
    }

    @Test
    fun `a chip never asks for consent`() {
        val failed = ChatThread.failed(ChatThread.sendSuggestion(EMPTY, CHIP), ApiException.ConsentRequired(null))
        assertFalse(failed.failure!!.needsConsent)
        assertEquals("", failed.draft, "a chip's question is not put in the field")
    }

    @Test
    fun `a question longer than the server takes cannot be sent and says why`() {
        val long = ChatThread.typed(EMPTY, "a".repeat(ChatThread.MAX_CHARS + 1))

        assertFalse(long.canSend)
        assertEquals(Strings.chat_too_long, long.draftNoticeKey)
        assertEquals(long, ChatThread.sendDraft(long), "Send does nothing")
    }

    @Test
    fun `a question at the limit goes and spaces around it do not count`() {
        val atLimit = ChatThread.typed(EMPTY, "  " + "a".repeat(ChatThread.MAX_CHARS) + "\n")

        assertTrue(atLimit.canSend)
        assertNull(atLimit.draftNoticeKey)
        assertEquals(ChatThread.MAX_CHARS, ChatThread.sendDraft(atLimit).sending?.text?.length)
    }
}
