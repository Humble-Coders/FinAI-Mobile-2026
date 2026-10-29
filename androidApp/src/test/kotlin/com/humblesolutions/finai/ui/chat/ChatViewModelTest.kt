package com.humblesolutions.finai.ui.chat

import androidx.lifecycle.SavedStateHandle
import com.humblesolutions.finai.model.AiConsentStatus
import com.humblesolutions.finai.model.AiPolicy
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.ChatMessage
import com.humblesolutions.finai.model.ChatReply
import com.humblesolutions.finai.model.ChatSuggestion
import com.humblesolutions.finai.model.Conversation
import com.humblesolutions.finai.model.ConversationSummary
import com.humblesolutions.finai.model.LearningNeeds
import com.humblesolutions.finai.model.LearningProgress
import com.humblesolutions.finai.model.Terms
import com.humblesolutions.finai.repository.AiConsentRepository
import com.humblesolutions.finai.repository.ChatRepository
import com.humblesolutions.finai.usecase.ChipPlacement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val CHIP = ChatSuggestion("spent_last_month", "How much did I spend last month?")

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeChat : ChatRepository {
        var list = mutableListOf(ConversationSummary("c1", "August", "You spent", "2026-09-15T12:00:00Z"))
        var messages = mutableListOf<ChatMessage>()
        val sent = mutableListOf<Pair<String, String?>>()
        val deleted = mutableListOf<String>()
        var reply = ChatReply(text = "You spent \$840.00 in August.", answeredBy = "model")
        var fail: ApiException? = null
        var failDelete: ApiException? = null

        override suspend fun suggestions() = listOf(CHIP)
        override suspend fun conversations() = list.toList()
        override suspend fun start() = ConversationSummary("c2", null, null, "2026-09-15T12:00:00Z").also { list.add(0, it) }
        override suspend fun conversation(id: String) = Conversation(id, list.firstOrNull { it.id == id }?.title, messages.toList())
        override suspend fun send(conversationId: String, text: String, suggestionId: String?): ChatReply {
            sent += text to suggestionId
            fail?.let {
                fail = null
                throw it
            }
            return reply
        }
        override suspend fun delete(conversationId: String) {
            failDelete?.let { throw it }
            deleted += conversationId
        }
        override suspend fun disclaimer() = Terms("ca-v1", "Educational guidance, not financial advice.")
        override fun close() = Unit
    }

    private class FakeConsent : AiConsentRepository {
        val agreed = mutableListOf<String>()
        override suspend fun policy() = AiPolicy(version = "ai-v1", body = "We send your question to an AI service.")
        override suspend fun consent(version: String) {
            agreed += version
        }
        override suspend fun status() = AiConsentStatus()
        override fun close() = Unit
    }

    private fun model(chat: FakeChat = FakeChat(), consent: FakeConsent = FakeConsent(), saved: SavedStateHandle = SavedStateHandle()) = ChatViewModel(saved).also { it.bind("user-1") { ChatRepositories(chat, consent) } }

    @Test
    fun `the list loads newest first with the disclaimer for the header`() {
        val model = model()

        val state = model.uiState.value
        assertEquals(listOf("c1"), state.conversations.map { it.id })
        assertTrue(state.listLoaded)
        assertNotNull(state.disclaimer)
    }

    @Test
    fun `a new chat opens empty with the servers chips`() {
        val model = model()

        model.newChat()

        val thread = model.uiState.value.thread!!
        assertEquals("c2", model.uiState.value.openId)
        assertEquals(ChipPlacement.EMPTY_CHAT, thread.chips)
        assertEquals(listOf(CHIP), thread.suggestions)
    }

    @Test
    fun `a typed question is answered and shown as sent`() {
        val chat = FakeChat()
        val model = model(chat)
        model.open("c1")

        model.onDraftChange("What did I spend in August?")
        model.send()

        assertEquals(listOf<Pair<String, String?>>("What did I spend in August?" to null), chat.sent)
        val thread = model.uiState.value.thread!!
        assertEquals(listOf("What did I spend in August?", "You spent \$840.00 in August."), thread.bubbles.map { it.text })
        assertFalse(thread.isSending)
        assertEquals("", thread.draft)
    }

    @Test
    fun `a chip sends its id with no text`() {
        val chat = FakeChat()
        val model = model(chat)
        model.open("c1")

        model.sendSuggestion(CHIP)

        assertEquals(listOf<Pair<String, String?>>("" to "spent_last_month"), chat.sent)
    }

    @Test
    fun `a network failure keeps the question in the field and the conversation`() {
        val chat = FakeChat().apply {
            messages = mutableListOf(ChatMessage("user", "Hi", "2026-09-15T12:00:00Z"), ChatMessage("assistant", "Hello", "2026-09-15T12:00:01Z"))
            fail = ApiException.Network(RuntimeException("offline"))
        }
        val model = model(chat)
        model.open("c1")
        model.onDraftChange("What did I spend?")

        model.send()

        val thread = model.uiState.value.thread!!
        assertEquals("What did I spend?", thread.draft)
        assertEquals(2, thread.history.size)
        assertNotNull(thread.failure)
        assertNull(model.uiState.value.consent, "not a consent problem")
    }

    @Test
    fun `consent required asks for consent then sends the same question`() {
        val chat = FakeChat().apply { fail = ApiException.ConsentRequired("ai-v1") }
        val consent = FakeConsent()
        val model = model(chat, consent)
        model.open("c1")
        model.onDraftChange("What did I spend?")
        model.send()

        val ask = model.uiState.value.consent!!
        assertEquals("ai-v1", ask.policy?.version)

        model.onConsentTicked(true)
        model.agree()

        assertEquals(listOf("ai-v1"), consent.agreed)
        assertEquals(listOf<Pair<String, String?>>("What did I spend?" to null, "What did I spend?" to null), chat.sent, "sent again without retyping")
        assertNull(model.uiState.value.consent)
        assertEquals("You spent \$840.00 in August.", model.uiState.value.thread!!.bubbles.last().text)
    }

    @Test
    fun `the reply's learning progress shows the card`() {
        val chat = FakeChat().apply {
            reply = ChatReply(text = "You've spent \$50.10 this month.", learning = LearningProgress(false, 0, 5, LearningNeeds(1, 20)))
        }
        val model = model(chat)
        model.open("c1")

        model.sendSuggestion(CHIP)

        assertTrue(model.uiState.value.thread!!.showsLearning)
    }

    @Test
    fun `deleting removes it at once and puts it back if refused`() {
        val chat = FakeChat()
        val model = model(chat)

        model.delete("c1")
        assertTrue(model.uiState.value.conversations.isEmpty())
        assertEquals(listOf("c1"), chat.deleted)

        chat.list = mutableListOf(ConversationSummary("c1", "August", null, "2026-09-15T12:00:00Z"))
        chat.failDelete = ApiException.Network(RuntimeException("offline"))
        model.loadList()
        model.delete("c1")
        assertEquals(listOf("c1"), model.uiState.value.conversations.map { it.id })
    }

    @Test
    fun `the open conversation and a half typed question survive the process`() {
        val saved = SavedStateHandle()
        val first = model(saved = saved)
        first.open("c1")
        first.onDraftChange("What did I")

        val restored = model(saved = saved)

        assertEquals("c1", restored.uiState.value.openId)
        assertEquals("What did I", restored.uiState.value.thread?.draft)
    }

    @Test
    fun `back closes the conversation and refreshes the list`() {
        val model = model()
        model.open("c1")

        model.close()

        assertNull(model.uiState.value.openId)
        assertNull(model.uiState.value.thread)
    }
}
