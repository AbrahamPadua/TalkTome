package com.livingbook.ui.reader

import android.Manifest
import android.content.ClipData
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.automirrored.outlined.Toc
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.livingbook.AppContainer
import com.livingbook.ai.TutorSuggestion
import com.livingbook.data.AppSettings
import com.livingbook.data.db.AnnotationKind
import com.livingbook.data.db.BlockEntity
import com.livingbook.model.BlockKind
import com.livingbook.model.ReadMode
import com.livingbook.session.SessionState
import com.livingbook.ui.conversation.ConversationSheet
import com.livingbook.ui.conversation.ConversationViewModel
import com.livingbook.ui.theme.readingStyle
import kotlinx.coroutines.launch

/** The open book: one topic at a time, original or guided, with contents, annotations and the original PDF page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(container: AppContainer, bookId: String, settings: AppSettings, onBack: () -> Unit) {
    val session = container.session
    val state by session.state.collectAsStateWithLifecycle()
    val vm = viewModel { ReaderViewModel(bookId, container.reading) }
    val annotations by vm.annotations.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var contentsOpen by rememberSaveable { mutableStateOf(false) }
    var originalPage by rememberSaveable { mutableStateOf<Int?>(null) }
    var passage by remember { mutableStateOf<PassageTarget?>(null) }
    var sourceBlocks by remember { mutableStateOf<List<Int>?>(null) }
    var visibleBlock by remember { mutableStateOf<Int?>(null) }
    var voicePickerOpen by rememberSaveable { mutableStateOf(false) }
    val narration by container.narration.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Narration works without it; the permission only adds the lock-screen and notification controls.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val chat = viewModel {
        ConversationViewModel(container.session, container.conversations, container.reading, container.settings, container.narration, container.speechInput)
    }
    val chatOpened by chat.opened.collectAsStateWithLifecycle()
    val chatSheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var chatShown by remember { mutableStateOf(false) }
    // Closing from code (citation, "continue reading") animates the sheet away before removing it.
    LaunchedEffect(chatOpened) {
        if (chatOpened != null) chatShown = true else if (chatShown) {
            chatSheet.hide()
            chatShown = false
        }
    }

    LaunchedEffect(bookId) {
        session.open(bookId)
        if (session.state.value.book?.id != bookId) {
            session.consumeMessage()
            onBack()
        }
    }
    val notice = listOfNotNull(state.message, state.resumeHint).joinToString("\n").ifEmpty { null }
    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        session.consumeMessage()
        // Shown from the outer scope: consuming the message restarts this effect, which must not cancel the snackbar.
        scope.launch { snackbar.showSnackbar(notice, withDismissAction = true, duration = SnackbarDuration.Long) }
    }
    val book = state.book
    if (book?.id != bookId) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    fun say(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }
    fun setMode(mode: ReadMode) {
        if (mode == ReadMode.LISTEN && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedForNotifications &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedForNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        session.setMode(mode)
    }
    fun ask(opening: ConversationViewModel.Opening = ConversationViewModel.Opening()) = chat.open(opening)
    fun pickSuggestion(suggestion: TutorSuggestion) =
        ask(ConversationViewModel.Opening(action = suggestion.action, label = suggestion.label, topicIndex = state.suggestionsTopicIndex))
    val highlighted = remember(annotations) {
        annotations.filter { it.kind == AnnotationKind.HIGHLIGHT }.mapTo(HashSet()) { it.blockIndex }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        state.topic?.sectionTitle?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to library") }
                },
                actions = {
                    IconButton(onClick = {
                        val block = if (state.mode == ReadMode.LISTEN) state.position.blockIndex
                        else visibleBlock ?: state.position.blockIndex
                        val text = state.blocks.firstOrNull { it.idx == block }?.text ?: state.topic?.title.orEmpty()
                        vm.annotate(AnnotationKind.BOOKMARK, block, snippet(text))
                        say("Bookmark added")
                    }) { Icon(Icons.Outlined.BookmarkAdd, contentDescription = "Bookmark this place") }
                    IconButton(onClick = { contentsOpen = true }) {
                        Icon(Icons.AutoMirrored.Outlined.Toc, contentDescription = "Contents and bookmarks")
                    }
                },
            )
        },
        bottomBar = {
            ReaderBottomBar(
                state = state,
                onPrevious = session::previousTopic,
                onNext = session::nextTopic,
                onReturn = session::returnToSaved,
                onMode = ::setMode,
                onAsk = { ask() },
                player = {
                    ListenPanel(
                        state = state,
                        narration = narration,
                        rate = settings.ttsRate,
                        onTogglePlay = session::togglePlay,
                        onSkipBack = session::skipBack,
                        onSkipForward = session::skipForward,
                        onRate = session::setRate,
                        onVoice = { voicePickerOpen = true },
                        extra = {
                            if (state.suggestions.isNotEmpty()) {
                                // Offered while narration keeps playing; nothing pauses until a chip is tapped.
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    SuggestionsRow(
                                        state.suggestions,
                                        onPick = ::pickSuggestion,
                                        onDismiss = session::dismissSuggestions,
                                        modifier = Modifier.padding(start = 16.dp, end = 4.dp, bottom = 12.dp),
                                    )
                                }
                            }
                        },
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        AnimatedContent(
            targetState = state,
            contentKey = { Triple(it.topic?.startBlock, it.topic?.endBlock, it.rendition) },
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) },
            modifier = Modifier.padding(padding),
            label = "topic",
        ) { shown ->
            TopicContent(
                state = shown,
                settings = settings,
                highlightedBlocks = highlighted,
                onRendition = session::setRendition,
                onLongPress = { passage = it },
                onViewPage = { originalPage = it },
                onShowSource = { sourceBlocks = it },
                onConsumeFocus = session::consumeFocus,
                onVisibleBlock = { visibleBlock = it },
                footer = {
                    if (shown.mode == ReadMode.READ && shown.suggestions.isNotEmpty()) {
                        item(key = "suggestions") {
                            SuggestionsRow(
                                shown.suggestions,
                                onPick = ::pickSuggestion,
                                onDismiss = session::dismissSuggestions,
                                modifier = Modifier.padding(top = 24.dp),
                            )
                        }
                    }
                },
            )
        }
    }

    if (contentsOpen) {
        ContentsSheet(
            state = state,
            annotations = annotations,
            onDismiss = { contentsOpen = false },
            onTopic = { session.goToTopic(it); contentsOpen = false },
            onGranularity = session::setGranularity,
            onAnnotation = { session.goToBlock(it.blockIndex); contentsOpen = false },
            onDeleteAnnotation = vm::delete,
        )
    }
    passage?.let { target ->
        PassageActionsSheet(
            target = target,
            onDismiss = { passage = null },
            onAsk = { ask(ConversationViewModel.Opening(selectedText = target.text)) },
            onHighlight = { vm.annotate(AnnotationKind.HIGHLIGHT, target.blockIndex, target.text); say("Highlighted") },
            onBookmark = { vm.annotate(AnnotationKind.BOOKMARK, target.blockIndex, snippet(target.text)); say("Bookmark added") },
            onCopy = {
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Passage", target.text))) }
                // Android 13 and later confirm copies themselves.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) say("Copied")
            },
        )
    }
    sourceBlocks?.let { indices ->
        SourceSheet(indices, settings, load = vm::blocks, onDismiss = { sourceBlocks = null })
    }
    originalPage?.let { OriginalPageDialog(book.filePath, it, onDismiss = { originalPage = null }) }
    val opened = chatOpened
    if (chatShown && opened != null) {
        val micAvailable = remember { container.speechInput.isAvailable }
        ConversationSheet(
            vm = chat,
            opened = opened,
            sheetState = chatSheet,
            micAvailable = micAvailable,
            onDismiss = {
                if (chat.dismiss()) {
                    scope.launch {
                        val result = snackbar.showSnackbar("Paused where you left off.", actionLabel = "Resume", duration = SnackbarDuration.Long)
                        if (result == SnackbarResult.ActionPerformed) session.play()
                    }
                }
            },
        )
    }
    if (voicePickerOpen) {
        val voices by container.narration.voices.collectAsStateWithLifecycle()
        VoicePickerDialog(
            voices = voices,
            selected = settings.ttsVoice,
            onSelect = { session.setVoice(it); voicePickerOpen = false },
            onDismiss = { voicePickerOpen = false },
        )
    }
}

// shortcut: asked once per process, not persisted; store a flag in settings if the repeat prompt bothers people.
private var askedForNotifications = false

@Composable
private fun ReaderBottomBar(
    state: SessionState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onReturn: () -> Unit,
    onMode: (ReadMode) -> Unit,
    onAsk: () -> Unit,
    player: @Composable () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp)) {
            if (state.returnTo != null) {
                AssistChip(
                    onClick = onReturn,
                    label = { Text("Back to where you were") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = null) },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            AnimatedVisibility(state.mode == ReadMode.LISTEN) { player() }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onPrevious, enabled = state.hasPrevious) {
                    Icon(Icons.AutoMirrored.Outlined.NavigateBefore, contentDescription = "Previous topic")
                }
                ModeToggle(state.mode, onMode, Modifier.weight(1f))
                Button(onClick = onAsk, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                    Icon(Icons.Outlined.Forum, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Ask")
                }
                IconButton(onClick = onNext, enabled = state.hasNext) {
                    Icon(Icons.AutoMirrored.Outlined.NavigateNext, contentDescription = "Next topic")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PassageActionsSheet(
    target: PassageTarget,
    onDismiss: () -> Unit,
    onAsk: () -> Unit,
    onHighlight: () -> Unit,
    onBookmark: () -> Unit,
    onCopy: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            snippet(target.text, 220),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        SheetAction("Ask about this", Icons.Outlined.Forum) { onAsk(); onDismiss() }
        SheetAction("Highlight", Icons.Outlined.BorderColor) { onHighlight(); onDismiss() }
        SheetAction("Bookmark", Icons.Outlined.BookmarkBorder) { onBookmark(); onDismiss() }
        SheetAction("Copy", Icons.Outlined.ContentCopy) { onCopy(); onDismiss() }
    }
}

@Composable
internal fun SheetAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** The source blocks behind a guided segment, verbatim and selectable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceSheet(indices: List<Int>, settings: AppSettings, load: suspend (List<Int>) -> List<BlockEntity>, onDismiss: () -> Unit) {
    val blocks by produceState<List<BlockEntity>?>(null, indices) { value = load(indices) }
    val style = readingStyle(settings.font, settings.textScale)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Original text", style = MaterialTheme.typography.titleMedium)
            Text(
                "Exactly as it appears in the book.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    blocks?.forEach { block ->
                        block.page?.let {
                            Text("Page $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val verbatim = block.kind in setOf(BlockKind.CODE, BlockKind.TABLE, BlockKind.EQUATION)
                        Text(block.text, style = if (verbatim) style.copy(fontFamily = FontFamily.Monospace) else style)
                    }
                }
            }
        }
    }
}
