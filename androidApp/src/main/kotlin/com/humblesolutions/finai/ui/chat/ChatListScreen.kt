package com.humblesolutions.finai.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.ConversationSummary
import com.humblesolutions.finai.ui.components.Field
import com.humblesolutions.finai.ui.components.FieldVectors
import com.humblesolutions.finai.ui.components.LightStatusBarIcons
import com.humblesolutions.finai.ui.components.Mint
import com.humblesolutions.finai.ui.components.Waves
import com.humblesolutions.finai.ui.strings

internal data class ChatListActions(
    val onNew: () -> Unit,
    val onOpen: (String) -> Unit,
    val onDelete: (String) -> Unit,
    val onRetry: () -> Unit,
)

/**
 * The person's conversations with the coach, newest first (#54).
 *
 * In Home's language — the green field, a white sheet — because no design was
 * supplied. Swipe a conversation away to delete it; TalkBack offers the same
 * as an action.
 */
@Composable
internal fun ChatListScreen(state: ChatUiState, actions: ChatListActions) {
    val dark = isSystemInDarkTheme()
    LightStatusBarIcons(dark)
    Box(Modifier.fillMaxSize().background(Field.brush(dark))) {
        Waves(Modifier.fillMaxSize())
        FieldVectors(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxSize().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = strings(Strings.chat_title),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = Field.ink(),
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    NewChatPill(onClick = actions.onNew)
                }
                if (!(state.listLoading && !state.listLoaded)) Sheet(state, actions)
            }
        }
    }
}

@Composable
private fun NewChatPill(onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(Field.Glass)
            .border(1.dp, Field.GlassEdge, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = Field.ink(), modifier = Modifier.size(18.dp))
        Text(strings(Strings.chat_new), color = Field.ink(), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Sheet(state: ChatUiState, actions: ChatListActions) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Mint.panel())
            .padding(vertical = 8.dp),
    ) {
        state.listErrorKey?.let {
            Text(strings(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            TextButton(onClick = actions.onRetry, modifier = Modifier.padding(horizontal = 8.dp)) { Text(strings(Strings.chat_retry)) }
        }
        if (state.listEmpty) {
            Text(
                strings(Strings.chat_list_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn {
            items(state.conversations, key = { it.id }) { conversation ->
                SwipeAway(conversation, actions)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeAway(conversation: ConversationSummary, actions: ChatListActions) {
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) actions.onDelete(conversation.id)
            value == SwipeToDismissBoxValue.EndToStart
        },
    )
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        ConversationRow(conversation, actions)
    }
}

@Composable
private fun ConversationRow(conversation: ConversationSummary, actions: ChatListActions) {
    val delete = strings(Strings.chat_delete)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Mint.panel())
            .heightIn(min = 56.dp)
            .clickable { actions.onOpen(conversation.id) }
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(delete) {
                        actions.onDelete(conversation.id)
                        true
                    },
                )
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = conversation.title ?: strings(Strings.chat_untitled),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        conversation.preview?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
