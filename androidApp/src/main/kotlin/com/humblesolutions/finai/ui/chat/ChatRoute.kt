package com.humblesolutions.finai.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.humblesolutions.finai.BuildConfig
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.ui.components.LoaderSignal
import com.humblesolutions.finai.ui.components.RetryWhenBack
import com.humblesolutions.finai.ui.components.TabSkeleton
import com.humblesolutions.finai.ui.strings

/** The Coach tab with its own view model (#54). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatRoute(
    userId: String,
    onClose: () -> Unit,
    onConversationOpen: (Boolean) -> Unit = {},
    /** Profile, where AI is turned on. */
    onOpenProfile: () -> Unit = {},
) {
    val model: ChatViewModel = viewModel()
    val state by model.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(userId) { model.bind(userId, logging = BuildConfig.DEBUG) }
    // The tab bar steps aside while a conversation is open: it has its own bar.
    LaunchedEffect(state.openId) { onConversationOpen(state.openId != null) }
    // A conversation's first read earns the coin; the list's draws its shape.
    LoaderSignal(key = "chat", active = state.threadLoading && state.thread == null)
    BackHandler { if (state.openId != null) model.close() else onClose() }
    // Back from Profile with the question refused for want of consent: sent again.
    RetryWhenBack(state.thread?.failure?.needsConsent == true, model::retry)
    if (state.openId == null && state.listLoading && !state.listLoaded) {
        TabSkeleton(strings(Strings.chat_tab))
        return
    }

    if (state.openId == null) {
        ChatListScreen(
            state = state,
            actions = ChatListActions(onNew = model::newChat, onOpen = model::open, onDelete = model::delete, onRetry = model::loadList),
        )
    } else {
        ChatScreen(
            state = state,
            actions = ChatActions(
                onBack = model::close,
                onDraftChange = model::onDraftChange,
                onSend = model::send,
                onSuggestion = model::sendSuggestion,
                onRetry = model::retry,
                onDismissFailure = model::dismissFailure,
                onDisclaimer = { model.showDisclaimer(true) },
                onReload = { state.openId?.let(model::open) },
                onOpenProfile = onOpenProfile,
            ),
        )
    }

    if (state.showingDisclaimer) {
        state.disclaimer?.let { terms ->
            ModalBottomSheet(onDismissRequest = { model.showDisclaimer(false) }) {
                Text(
                    text = terms.body,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .navigationBarsPadding(),
                )
            }
        }
    }
}
