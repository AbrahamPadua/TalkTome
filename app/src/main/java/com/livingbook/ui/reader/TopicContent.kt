package com.livingbook.ui.reader

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livingbook.data.AppSettings
import com.livingbook.data.db.BlockEntity
import com.livingbook.model.BlockKind
import com.livingbook.model.GuidedKind
import com.livingbook.model.GuidedSegment
import com.livingbook.model.ReadMode
import com.livingbook.model.Rendition
import com.livingbook.session.GuidedState
import com.livingbook.session.SessionState
import com.livingbook.ui.theme.family
import com.livingbook.ui.theme.readingStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull

/** A passage the user long-pressed: the block it anchors to and the text it shows. */
data class PassageTarget(val blockIndex: Int, val text: String)

/** Item 0 is the topic header; blocks or guided segments follow. */
private const val HEADER_ITEMS = 1

/**
 * The body of one topic: header, then original blocks or guided segments. Owns its scroll position, so each
 * topic starts at the top, and handles focus jumps and keeping the narrated sentence in view.
 */
@Composable
fun TopicContent(
    state: SessionState,
    settings: AppSettings,
    highlightedBlocks: Set<Int>,
    onRendition: (Rendition) -> Unit,
    onLongPress: (PassageTarget) -> Unit,
    onViewPage: (Int) -> Unit,
    onShowSource: (List<Int>) -> Unit,
    onConsumeFocus: () -> Unit,
    onVisibleBlock: (Int?) -> Unit,
    footer: LazyListScope.() -> Unit = {},
) {
    val book = state.book ?: return
    val topic = state.topic ?: return
    val listState = rememberLazyListState()
    val style = readingStyle(settings.font, settings.textScale)
    val guided = (state.guided as? GuidedState.Ready)?.content?.takeIf { state.rendition == Rendition.GUIDED }
    val guidedRanges = remember(guided) { guided?.let(::guidedSentenceRanges).orEmpty() }
    val listening = state.mode == ReadMode.LISTEN
    val narratedGuided = if (listening && guided != null) guidedRanges.getOrNull(state.sentenceIndex) else null
    var flashBlock by remember { mutableStateOf<Int?>(null) }

    val latest by rememberUpdatedState(state)
    fun itemOf(blockIndex: Int): Int {
        val g = (latest.guided as? GuidedState.Ready)?.content?.takeIf { latest.rendition == Rendition.GUIDED }
        val i = g?.segments?.indexOfFirst { blockIndex in it.sourceBlocks } ?: latest.blocks.indexOfFirst { it.idx == blockIndex }
        return if (i < 0) -1 else i + HEADER_ITEMS
    }

    // Focus requests (citation jumps, resume, mode switch): scroll there and flash the block briefly.
    LaunchedEffect(listState) {
        snapshotFlow { latest.focusBlock }.filterNotNull().collect { target ->
            onConsumeFocus()
            val item = itemOf(target)
            if (item >= 0) {
                listState.animateScrollToItem(item)
                flashBlock = target
                delay(1_500)
                flashBlock = null
            }
        }
    }

    // Listen mode: bring the narrated block on screen; the sentence itself is scrolled into view by NarratedText.
    val narratedItem = when {
        !listening -> -1
        guided != null -> narratedGuided?.first?.plus(HEADER_ITEMS) ?: -1
        else -> state.currentSentence?.let { itemOf(it.blockIndex) } ?: -1
    }
    LaunchedEffect(narratedItem) {
        if (narratedItem >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == narratedItem }) {
            listState.animateScrollToItem(narratedItem)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex - HEADER_ITEMS }.collect { i ->
            val g = (latest.guided as? GuidedState.Ready)?.content?.takeIf { latest.rendition == Rendition.GUIDED }
            onVisibleBlock(if (g != null) g.segments.getOrNull(i)?.sourceBlocks?.firstOrNull() else latest.blocks.getOrNull(i)?.idx)
        }
    }

    val flashColor = MaterialTheme.colorScheme.secondaryContainer
    val highlightColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = listState,
            modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
        ) {
            item(key = "header") { TopicHeader(state, settings, guided?.isMock == true, onRendition) }
            when {
                state.rendition == Rendition.ORIGINAL -> items(state.blocks, key = { it.idx }) { block ->
                    BlockView(
                        block = block,
                        style = style,
                        scale = settings.textScale,
                        range = if (listening) sentenceRange(state.currentSentence, block) else null,
                        background = when (block.idx) {
                            flashBlock -> flashColor
                            in highlightedBlocks -> highlightColor
                            else -> Color.Transparent
                        },
                        page = originalPage(block, book.format),
                        onLongPress = onLongPress,
                        onViewPage = onViewPage,
                    )
                }
                guided == null -> item(key = "guided-status") {
                    GuidedStatus(state.guided, onReadOriginal = { onRendition(Rendition.ORIGINAL) })
                }
                else -> itemsIndexed(guided.segments, key = { i, _ -> "segment-$i" }) { i, segment ->
                    GuidedSegmentView(
                        segment = segment,
                        style = style,
                        range = narratedGuided?.takeIf { it.first == i }?.second,
                        flash = flashBlock != null && flashBlock in segment.sourceBlocks,
                        anchorBlock = segment.sourceBlocks.firstOrNull() ?: topic.startBlock,
                        onShowSource = onShowSource,
                        onLongPress = onLongPress,
                    )
                }
            }
            footer()
        }
    }
}

@Composable
private fun TopicHeader(state: SessionState, settings: AppSettings, isMock: Boolean, onRendition: (Rendition) -> Unit) {
    val topic = state.topic ?: return
    val headline = MaterialTheme.typography.headlineSmall
    Column(Modifier.padding(top = 12.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            topic.title,
            style = headline.copy(
                fontFamily = settings.font.family,
                fontSize = headline.fontSize * settings.textScale,
                lineHeight = headline.lineHeight * settings.textScale,
            ),
            modifier = Modifier.semantics { heading() },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Topic ${state.topicIndex + 1} of ${state.topics.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { (state.topicIndex + 1f) / state.topics.size.coerceAtLeast(1) },
                modifier = Modifier.weight(1f).height(2.dp),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(Rendition.ORIGINAL to "Original", Rendition.GUIDED to "Guided").forEachIndexed { i, (rendition, label) ->
                SegmentedButton(
                    selected = state.rendition == rendition,
                    onClick = { onRendition(rendition) },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                ) { Text(label) }
            }
        }
        if (isMock) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                Text(
                    "Demo mode, not AI. Guided text here is assembled from the book without a model.",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun GuidedStatus(guided: GuidedState, onReadOriginal: () -> Unit) {
    Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (guided is GuidedState.Failed) {
            Text(guided.message, style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(onClick = onReadOriginal) { Text("Read the original") }
        } else {
            Text(
                "Writing a guided version of this topic.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = onReadOriginal) { Text("Read the original instead") }
        }
    }
}

@Composable
private fun BlockView(
    block: BlockEntity,
    style: TextStyle,
    scale: Float,
    range: IntRange?,
    background: Color,
    page: Int?,
    onLongPress: (PassageTarget) -> Unit,
    onViewPage: (Int) -> Unit,
) {
    val bg by animateColorAsState(background, label = "block background")
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(bg, MaterialTheme.shapes.extraSmall)
            .passageActions(PassageTarget(block.idx, block.text), onLongPress)
    ) {
        when (block.kind) {
            BlockKind.HEADING -> {
                val factor = when (block.headingLevel) { 1 -> 1.4f; 2 -> 1.25f; 3 -> 1.12f; else -> 1.05f }
                NarratedText(
                    block.text,
                    range,
                    style.copy(fontSize = style.fontSize * factor, lineHeight = style.lineHeight * factor, fontWeight = FontWeight.SemiBold),
                    Modifier.padding(top = 12.dp).semantics { heading() },
                )
            }
            BlockKind.PARAGRAPH -> NarratedText(block.text, range, style)
            BlockKind.FOOTNOTE -> NarratedText(block.text, range, style.copy(fontSize = style.fontSize * 0.8f, lineHeight = style.lineHeight * 0.8f), color = muted)
            BlockKind.CAPTION -> NarratedText(
                block.text,
                range,
                style.copy(fontSize = style.fontSize * 0.85f, lineHeight = style.lineHeight * 0.85f, fontStyle = FontStyle.Italic),
                color = muted,
            )
            BlockKind.LIST_ITEM -> Row {
                Text("•", style = style, modifier = Modifier.padding(start = 4.dp, end = 12.dp))
                NarratedText(block.text, range, style)
            }
            BlockKind.QUOTE -> Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                Spacer(Modifier.width(14.dp))
                NarratedText(block.text, range, style.copy(fontStyle = FontStyle.Italic))
            }
            BlockKind.CODE, BlockKind.TABLE, BlockKind.EQUATION -> Verbatim(block.text, narrated = range != null, scale = scale)
            BlockKind.FIGURE -> Surface(
                color = if (range != null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Image, contentDescription = null, tint = muted)
                    Text(block.text.ifBlank { "Figure" }, style = MaterialTheme.typography.bodyMedium, color = muted)
                }
            }
        }
        page?.let {
            TextButton(onClick = { onViewPage(it) }) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("View original page $it")
            }
        }
    }
}

/** Code, tables and equations: verbatim monospace, scrolled sideways instead of reflowed or re-rendered. */
@Composable
private fun Verbatim(text: String, narrated: Boolean, scale: Float) {
    Surface(
        color = if (narrated) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp * scale, lineHeight = 14.sp * scale * 1.45f),
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
        )
    }
}

@Composable
private fun GuidedSegmentView(
    segment: GuidedSegment,
    style: TextStyle,
    range: IntRange?,
    flash: Boolean,
    anchorBlock: Int,
    onShowSource: (List<Int>) -> Unit,
    onLongPress: (PassageTarget) -> Unit,
) {
    val bg by animateColorAsState(if (flash) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, label = "segment background")
    val modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 6.dp)
        .background(bg, MaterialTheme.shapes.extraSmall)
        .passageActions(PassageTarget(anchorBlock, segment.text), onLongPress)
    when (segment.kind) {
        GuidedKind.ADDED -> Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium, modifier = modifier) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Added explanation", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                NarratedText(segment.text, range, style, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        GuidedKind.RESTATED -> Column(modifier) {
            NarratedText(segment.text, range, style)
            SourceLink(segment.sourceBlocks, onShowSource)
        }
        GuidedKind.QUOTED -> Column(modifier) {
            // The quotation marks shift the narrated range by one character.
            NarratedText("“${segment.text}”", range?.let { it.first + 1..it.last + 1 }, style.copy(fontStyle = FontStyle.Italic))
            SourceLink(segment.sourceBlocks, onShowSource)
        }
    }
}

@Composable
private fun SourceLink(sourceBlocks: List<Int>, onShowSource: (List<Int>) -> Unit) {
    if (sourceBlocks.isEmpty()) return
    TextButton(
        onClick = { onShowSource(sourceBlocks) },
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = Modifier.semantics { contentDescription = "Show the original text for this passage" },
    ) { Text("Original", style = MaterialTheme.typography.labelLarge) }
}

/** Text with the narrated sentence highlighted and scrolled into view while it is read aloud. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NarratedText(text: String, range: IntRange?, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val highlight = MaterialTheme.colorScheme.secondaryContainer
    val visibleRange = range?.takeIf { !it.isEmpty() && it.last < text.length }
    val annotated = remember(text, visibleRange, highlight) {
        buildAnnotatedString {
            append(text)
            visibleRange?.let { addStyle(SpanStyle(background = highlight), it.first, it.last + 1) }
        }
    }
    val requester = remember { BringIntoViewRequester() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    LaunchedEffect(visibleRange, layout) {
        val result = layout ?: return@LaunchedEffect
        visibleRange?.let { requester.bringIntoView(result.getPathForRange(it.first, it.last + 1).getBounds()) }
    }
    Text(annotated, style = style, color = color, modifier = modifier.bringIntoViewRequester(requester), onTextLayout = { layout = it })
}

/** Long-press (and the accessibility long-click action) opens the passage actions. */
@Composable
private fun Modifier.passageActions(target: PassageTarget, onLongPress: (PassageTarget) -> Unit): Modifier {
    val haptics = LocalHapticFeedback.current
    val latest by rememberUpdatedState(onLongPress)
    return this
        .pointerInput(target) {
            detectTapGestures(onLongPress = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                latest(target)
            })
        }
        .semantics { onLongClick(label = "Passage actions") { latest(target); true } }
}
