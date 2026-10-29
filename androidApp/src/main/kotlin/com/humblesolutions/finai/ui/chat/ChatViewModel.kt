package com.humblesolutions.finai.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.finai.config.ApiConfig
import com.humblesolutions.finai.config.Supabase
import com.humblesolutions.finai.data.KtorAiConsentRepository
import com.humblesolutions.finai.data.KtorChatRepository
import com.humblesolutions.finai.data.SupabaseTokenSource
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.ChatSuggestion
import com.humblesolutions.finai.repository.AiConsentRepository
import com.humblesolutions.finai.repository.ChatRepository
import com.humblesolutions.finai.usecase.ChatOutgoing
import com.humblesolutions.finai.usecase.ChatThread
import com.humblesolutions.finai.usecase.ChatThreadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

internal class ChatRepositories(
    val chat: ChatRepository,
    val consent: AiConsentRepository,
) {
    fun close() {
        chat.close()
        consent.close()
    }
}

/**
 * The Coach tab (#54, PRD F7): the conversation list and the one open in it.
 *
 * Every decision about a conversation — what shows while a reply is on its
 * way, what happens to typed text when sending fails, when consent is what is
 * missing — is shared `ChatThread`'s; this sends requests and keeps the result.
 *
 * The open conversation and the half-typed question survive rotation and the
 * process being reclaimed ([SavedStateHandle]), with the owner kept beside
 * them so the bind after a restore recognises the same person (#47).
 */
internal class ChatViewModel(
    private val saved: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState(openId = saved[KEY_OPEN]))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var repositories: ChatRepositories? = null

    private var boundTo: String?
        get() = saved[KEY_OWNER]
        set(value) {
            saved[KEY_OWNER] = value
        }

    /** Rises on every bind and every conversation opened, so a late answer cannot land elsewhere. */
    private var generation = 0

    fun bind(userId: String, logging: Boolean) = bind(userId) {
        Supabase.clientOrNull()?.let { client ->
            val tokens = SupabaseTokenSource(client)
            ChatRepositories(
                chat = KtorChatRepository(ApiConfig.BASE_URL, tokens, logging),
                consent = KtorAiConsentRepository(ApiConfig.BASE_URL, tokens, logging),
            )
        }
    }

    internal fun bind(userId: String, build: () -> ChatRepositories?) {
        if (userId.isBlank()) return
        if (userId != boundTo) {
            repositories?.close()
            repositories = null
            generation++
            boundTo = userId
            saved.remove<String>(KEY_OPEN)
            saved.remove<String>(KEY_DRAFT)
            _uiState.value = ChatUiState()
        }
        if (repositories != null) return
        repositories = build() ?: return
        loadList()
        loadDisclaimer()
        _uiState.value.openId?.let { open(it) }
    }

    override fun onCleared() {
        repositories?.close()
        repositories = null
    }

    // ── The list ────────────────────────────────────────────────────────

    fun loadList() {
        val repos = repositories ?: return
        val started = generation
        update { it.copy(listLoading = !it.listLoaded, listErrorKey = null) }
        viewModelScope.launch {
            try {
                val list = repos.chat.conversations()
                if (started != generation) return@launch
                update { it.copy(conversations = list, listLoading = false, listLoaded = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (started != generation) return@launch
                // A refresh that fails keeps what it had.
                update { it.copy(listLoading = false, listErrorKey = e.messageKey) }
            }
        }
    }

    fun newChat() {
        val repos = repositories ?: return
        update { it.copy(threadLoading = true, threadErrorKey = null) }
        viewModelScope.launch {
            try {
                val started = repos.chat.start()
                update { it.copy(conversations = listOf(started) + it.conversations) }
                open(started.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                update { it.copy(threadLoading = false, listErrorKey = e.messageKey) }
            }
        }
    }

    fun delete(id: String) {
        val repos = repositories ?: return
        // Gone at once; put back if the server refuses.
        val before = _uiState.value.conversations
        update { it.copy(conversations = before.filterNot { c -> c.id == id }) }
        if (_uiState.value.openId == id) close()
        viewModelScope.launch {
            try {
                repos.chat.delete(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                update { it.copy(conversations = before, listErrorKey = e.messageKey) }
            }
        }
    }

    // ── A conversation ──────────────────────────────────────────────────

    fun open(id: String) {
        val repos = repositories ?: return
        val started = ++generation
        saved[KEY_OPEN] = id
        update { it.copy(openId = id, threadLoading = true, threadErrorKey = null, consent = null) }
        viewModelScope.launch {
            try {
                val conversation = repos.chat.conversation(id)
                // Chips are the server's; a chat without them still works.
                val chips = suggestionsOrNone(repos)
                if (started != generation) return@launch
                val draft = saved.get<String>(KEY_DRAFT).orEmpty()
                update {
                    it.copy(
                        thread = ChatThread.typed(ChatThread.opened(conversation, chips), draft),
                        threadLoading = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (started != generation) return@launch
                update { it.copy(threadLoading = false, threadErrorKey = e.messageKey) }
            }
        }
    }

    /** Back to the list. */
    fun close() {
        generation++
        saved.remove<String>(KEY_OPEN)
        saved.remove<String>(KEY_DRAFT)
        update { it.copy(openId = null, thread = null, threadLoading = false, threadErrorKey = null, consent = null) }
        loadList()
    }

    fun onDraftChange(text: String) {
        saved[KEY_DRAFT] = text
        updateThread { ChatThread.typed(it, text) }
    }

    fun send() {
        val thread = _uiState.value.thread ?: return
        val next = ChatThread.sendDraft(thread)
        val outgoing = next.sending ?: return
        saved.remove<String>(KEY_DRAFT)
        updateThread { next }
        deliver(outgoing)
    }

    fun sendSuggestion(suggestion: ChatSuggestion) {
        val thread = _uiState.value.thread ?: return
        val next = ChatThread.sendSuggestion(thread, suggestion)
        val outgoing = next.sending ?: return
        updateThread { next }
        deliver(outgoing)
    }

    /** Send again what failed, without retyping. */
    fun retry() {
        val thread = _uiState.value.thread ?: return
        val next = ChatThread.retry(thread)
        val outgoing = next.sending ?: return
        saved.remove<String>(KEY_DRAFT)
        updateThread { next }
        deliver(outgoing)
    }

    fun dismissFailure() = updateThread { ChatThread.dismissed(it) }

    private fun deliver(outgoing: ChatOutgoing) {
        val repos = repositories ?: return
        val id = _uiState.value.openId ?: return
        val started = generation
        viewModelScope.launch {
            try {
                val reply = repos.chat.send(id, if (outgoing.typed) outgoing.text else "", outgoing.suggestionId)
                if (started != generation) return@launch
                updateThread { ChatThread.replied(it, reply, Instant.now().toString()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (started != generation) return@launch
                updateThread { ChatThread.failed(it, e) }
                if (_uiState.value.thread?.failure?.needsConsent == true) askForConsent()
            }
        }
    }

    // ── Consent ─────────────────────────────────────────────────────────

    private fun askForConsent() {
        val repos = repositories ?: return
        update { it.copy(consent = ConsentAsk(busy = true)) }
        viewModelScope.launch {
            try {
                val policy = repos.consent.policy()
                update { it.copy(consent = it.consent?.copy(policy = policy, busy = false)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                update { it.copy(consent = it.consent?.copy(busy = false, errorKey = e.messageKey)) }
            }
        }
    }

    fun onConsentTicked(ticked: Boolean) = update { it.copy(consent = it.consent?.copy(ticked = ticked)) }

    /** Records consent to the version shown, then sends the question it was asked for. */
    fun agree() {
        val repos = repositories ?: return
        val ask = _uiState.value.consent ?: return
        val policy = ask.policy ?: return
        if (!ask.ticked || ask.busy) return
        update { it.copy(consent = ask.copy(busy = true, errorKey = null)) }
        viewModelScope.launch {
            try {
                repos.consent.consent(policy.version)
                update { it.copy(consent = null) }
                retry()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                update { it.copy(consent = it.consent?.copy(busy = false, errorKey = e.messageKey)) }
            }
        }
    }

    /** "Not now": the question stays in the field; chips still work. */
    fun declineConsent() = update { it.copy(consent = null) }

    // ── The disclaimer ──────────────────────────────────────────────────

    private fun loadDisclaimer() {
        val repos = repositories ?: return
        viewModelScope.launch {
            val terms = try {
                repos.chat.disclaimer()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                return@launch
            }
            update { it.copy(disclaimer = terms) }
        }
    }

    fun showDisclaimer(showing: Boolean) = update { it.copy(showingDisclaimer = showing) }

    // ── Helpers ─────────────────────────────────────────────────────────

    private suspend fun suggestionsOrNone(repos: ChatRepositories): List<ChatSuggestion> = try {
        repos.chat.suggestions()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        emptyList()
    }

    private fun update(change: (ChatUiState) -> ChatUiState) = _uiState.update(change)

    private fun updateThread(change: (ChatThreadState) -> ChatThreadState) = _uiState.update { state ->
        state.thread?.let { state.copy(thread = change(it)) } ?: state
    }

    private companion object {
        const val KEY_OWNER = "chat_owner"
        const val KEY_OPEN = "chat_open"
        const val KEY_DRAFT = "chat_draft"
    }
}
