package com.humblesolutions.finai.ui.chat

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.ChatRole
import com.humblesolutions.finai.model.ChatSuggestion
import com.humblesolutions.finai.ui.components.LearningCard
import com.humblesolutions.finai.ui.components.Mint
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.usecase.ChatBubble
import com.humblesolutions.finai.usecase.ChatThreadState
import com.humblesolutions.finai.usecase.ChipPlacement

internal data class ChatActions(
    val onBack: () -> Unit,
    val onDraftChange: (String) -> Unit,
    val onSend: () -> Unit,
    val onSuggestion: (ChatSuggestion) -> Unit,
    val onRetry: () -> Unit,
    val onDismissFailure: () -> Unit,
    val onDisclaimer: () -> Unit,
    val onReload: () -> Unit,
)

/**
 * One conversation with the coach (#54): the messages, the suggestion chips,
 * the learning card while advice is held back, and the field to ask in.
 *
 * The coach's text is shown exactly as it arrived — every figure in it is the
 * server's. What shows when is shared `ChatThread`'s.
 */
@Composable
internal fun ChatScreen(state: ChatUiState, actions: ChatActions) {
    val thread = state.thread
    Box(
        Modifier
            .fillMaxSize()
            .background(Mint.ground()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            TopBar(state, actions)
            when {
                thread != null -> Conversation(thread, actions, Modifier.weight(1f))

                state.threadErrorKey != null -> Column(Modifier.weight(1f).padding(20.dp)) {
                    Text(strings(state.threadErrorKey), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = actions.onReload) { Text(strings(Strings.chat_retry)) }
                }

                else -> Box(Modifier.weight(1f))
            }
            thread?.let { InputBar(it, actions) }
        }
    }
}

@Composable
private fun TopBar(state: ChatUiState, actions: ChatActions) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions.onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings(Strings.chat_back))
        }
        Text(
            text = state.thread?.title ?: strings(Strings.chat_untitled),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (state.disclaimer != null) {
            TextButton(onClick = actions.onDisclaimer) {
                Text(strings(Strings.chat_disclaimer_link), color = Mint.greenText(), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Conversation(thread: ChatThreadState, actions: ChatActions, modifier: Modifier) {
    val focus = LocalFocusManager.current
    val list = rememberLazyListState()
    val bubbles = thread.bubbles
    // The newest message is always in view, the typing indicator included.
    LaunchedEffect(bubbles.size, thread.isSending) {
        val last = bubbles.size + (if (thread.isSending) 1 else 0) - 1
        if (last >= 0) list.animateScrollToItem(last)
    }
    LazyColumn(
        state = list,
        modifier = modifier
            .fillMaxWidth()
            // Tapping outside the field puts the keyboard away.
            .pointerInput(Unit) { detectTapGestures { focus.clearFocus() } },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (thread.chips == ChipPlacement.EMPTY_CHAT) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        strings(Strings.chat_suggestions_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        thread.suggestions.forEach { chip -> Chip(chip, actions.onSuggestion) }
                    }
                }
            }
        }
        items(bubbles.size) { index -> Bubble(bubbles[index]) }
        if (thread.isSending) item { TypingIndicator() }
    }
}

@Composable
private fun Bubble(bubble: ChatBubble) {
    val mine = bubble.role == ChatRole.YOU
    val spoken = strings(bubble.accessibilityKey, bubble.text)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Text(
            text = bubble.text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .widthIn(max = 440.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (mine) MaterialTheme.colorScheme.primary else Mint.panel())
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .clearAndSetSemantics { contentDescription = spoken },
        )
        if (bubble.disclaimerNote) {
            Text(
                strings(Strings.chat_disclaimer_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, top = 4.dp).widthIn(max = 440.dp),
            )
        }
    }
}

/** Three dots that breathe — still, when the system asks for less motion. */
@Composable
private fun TypingIndicator() {
    val context = LocalContext.current
    val reduceMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val label = strings(Strings.chat_typing)
    Row(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Mint.panel())
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val pulse = if (reduceMotion) {
            null
        } else {
            rememberInfiniteTransition(label = "typing").animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                label = "dot",
            )
        }
        repeat(3) { index ->
            val shown = pulse?.value?.let { if (index % 2 == 0) it else 1.3f - it } ?: 0.6f
            Box(
                Modifier
                    .size(8.dp)
                    .alpha(shown.coerceIn(0.3f, 1f))
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
    }
}

@Composable
private fun Chip(chip: ChatSuggestion, onSuggestion: (ChatSuggestion) -> Unit) {
    SuggestionChip(onClick = { onSuggestion(chip) }, label = { Text(chip.text, maxLines = 2, overflow = TextOverflow.Ellipsis) })
}

@Composable
private fun InputBar(thread: ChatThreadState, actions: ChatActions) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        thread.learning?.takeIf { thread.showsLearning }?.let { LearningCard(progress = it) }
        if (thread.chips == ChipPlacement.ABOVE_INPUT) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(thread.suggestions, key = { it.id }) { chip -> Chip(chip, actions.onSuggestion) }
            }
        }
        thread.failure?.takeIf { !it.needsConsent }?.let { failure ->
            Row(
                Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(strings(failure.messageKey), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = actions.onRetry) { Text(strings(Strings.chat_retry)) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = thread.draft,
                onValueChange = actions.onDraftChange,
                placeholder = { Text(strings(Strings.chat_input_hint)) },
                modifier = Modifier.weight(1f),
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (thread.canSend) actions.onSend() }),
            )
            IconButton(onClick = actions.onSend, enabled = thread.canSend, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = strings(Strings.chat_send), tint = if (thread.canSend) Mint.greenText() else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
