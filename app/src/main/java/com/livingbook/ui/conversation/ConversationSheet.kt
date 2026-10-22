package com.livingbook.ui.conversation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.livingbook.model.AnswerSegment
import com.livingbook.model.ChatMessage
import com.livingbook.model.ChatRole
import com.livingbook.model.SegmentKind
import com.livingbook.ui.reader.snippet

/** Conversation over the reader. The reader stays visible above it; closing returns to the exact sentence. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationSheet(
    vm: ConversationViewModel,
    opened: ConversationViewModel.Opened,
    sheetState: SheetState,
    micAvailable: Boolean,
    onDismiss: () -> Unit,
) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val pages by vm.citationPages.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val heard by vm.heard.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startListening() else vm.microphoneDenied()
    }
    val canSend = ready && !busy
    fun send() {
        if (!canSend || input.isBlank()) return
        vm.submit(input)
        input = ""
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxHeight(0.85f)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ask about this topic", style = MaterialTheme.typography.titleMedium)
                    Text(
                        opened.topicTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = vm::backToBook) {
                    Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Back to the book", modifier = Modifier.padding(start = 8.dp))
                }
            }
            opened.quote?.let {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        "“${snippet(it, 240)}”",
                        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            val listState = rememberLazyListState()
            val itemCount = messages.size + (if (messages.isEmpty() && !busy) 1 else 0) + (if (busy) 1 else 0) + (if (notice != null) 1 else 0)
            LaunchedEffect(itemCount) { if (itemCount > 0) listState.animateScrollToItem(itemCount - 1) }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (messages.isEmpty() && !busy) {
                    item(key = "hint") {
                        QuietNote("Ask anything about what you are reading. Each part of an answer says whether it comes from the book.")
                    }
                }
                items(messages, key = { it.id }) { MessageView(it, pages, vm::openCitation) }
                if (busy) item(key = "typing") { Typing() }
                notice?.let { item(key = "notice") { QuietNote(it) } }
            }

            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QUICK_ACTIONS.forEach { (label, action) ->
                    SuggestionChip(onClick = { vm.quickAction(label, action) }, label = { Text(label) }, enabled = canSend)
                }
            }

            val listening = heard
            if (listening != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        listening.ifEmpty { "Listening…" },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    // Cancelling discards the recognizer's result; keep what was already heard so it can be sent by hand.
                    FilledTonalIconButton(onClick = { vm.stopListening()?.let { input = it } }) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel voice input")
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Ask a question") },
                        maxLines = 4,
                        enabled = ready,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                    )
                    if (micAvailable) {
                        IconButton(
                            enabled = canSend,
                            onClick = {
                                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                if (granted) vm.startListening() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            },
                        ) { Icon(Icons.Outlined.Mic, contentDescription = "Speak your question") }
                    }
                    FilledIconButton(onClick = ::send, enabled = canSend && input.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageView(message: ChatMessage, pages: Map<Int, Int?>, onCitation: (Int) -> Unit) {
    when (message.role) {
        ChatRole.USER -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp),
                modifier = Modifier.padding(start = 48.dp).widthIn(max = 520.dp),
            ) {
                Text(message.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
        ChatRole.NOTE -> QuietNote(message.text)
        ChatRole.ASSISTANT -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (message.isMock) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Text("Demo mode, not AI", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            if (message.segments.isEmpty()) {
                SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyLarge) }
            } else {
                message.segments.forEach { SegmentView(it, pages, onCitation) }
            }
        }
    }
}

@Composable
private fun SegmentView(segment: AnswerSegment, pages: Map<Int, Int?>, onCitation: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            segmentLabel(segment.kind),
            style = MaterialTheme.typography.labelMedium,
            color = when (segment.kind) {
                SegmentKind.BOOK -> MaterialTheme.colorScheme.primary
                SegmentKind.INTERPRETATION -> MaterialTheme.colorScheme.secondary
                SegmentKind.GENERAL -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        SelectionContainer { Text(segment.text, style = MaterialTheme.typography.bodyLarge) }
        if (segment.citations.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                segment.citations.distinct().forEach { block ->
                    val page = pages[block]
                    AssistChip(
                        onClick = { onCitation(block) },
                        label = { Text(citationLabel(page)) },
                        modifier = Modifier.semantics {
                            contentDescription = page?.let { "Go to page $it in the book" } ?: "Go to this passage in the book"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Typing() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text("Looking through the book…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QuietNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
    )
}
