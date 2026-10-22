package com.livingbook.ui.reader

import com.livingbook.data.db.BlockEntity
import com.livingbook.data.db.TopicEntity
import com.livingbook.model.BlockKind
import com.livingbook.model.BookFormat
import com.livingbook.model.GuidedContent
import com.livingbook.session.Sentences
import com.livingbook.speech.Sentence

/** Blocks narrated as a short pointer ("An equation appears here"), so the whole block is highlighted. */
private val POINTER_KINDS = setOf(BlockKind.CODE, BlockKind.TABLE, BlockKind.EQUATION, BlockKind.FIGURE)

/** Characters of [block] covered by the narrated [sentence], or null when narration is in another block. */
fun sentenceRange(sentence: Sentence?, block: BlockEntity): IntRange? {
    if (sentence == null || sentence.blockIndex != block.idx) return null
    if (block.kind in POINTER_KINDS) return block.text.indices
    val start = sentence.charOffset.coerceIn(0, block.text.length)
    return start until (start + sentence.text.length).coerceAtMost(block.text.length)
}

/**
 * For each narration sentence of a guided topic: the segment it belongs to and its character range there.
 * Mirrors [Sentences.fromGuided], which gives guided sentences no usable character offset of their own.
 */
fun guidedSentenceRanges(content: GuidedContent): List<Pair<Int, IntRange>> =
    content.segments.flatMapIndexed { i, segment ->
        Sentences.split(segment.text).map { (offset, text) -> i to (offset until offset + text.length) }
    }

/** Page to offer as "View original page N": only for PDF figures, equations and tables with a known page. */
fun originalPage(block: BlockEntity, format: BookFormat): Int? =
    block.page.takeIf { format == BookFormat.PDF && block.kind in setOf(BlockKind.FIGURE, BlockKind.EQUATION, BlockKind.TABLE) }

/** Table of contents rows: a section header wherever the section changes, then its topics. */
sealed interface ContentsRow {
    data class Section(val title: String) : ContentsRow
    data class Entry(val topicIndex: Int, val topic: TopicEntity) : ContentsRow
}

fun contentsRows(topics: List<TopicEntity>): List<ContentsRow> = buildList {
    topics.forEachIndexed { i, topic ->
        val section = topic.sectionTitle
        if (section != null && section != topics.getOrNull(i - 1)?.sectionTitle) add(ContentsRow.Section(section))
        add(ContentsRow.Entry(i, topic))
    }
}

/** Short text for a bookmark or a preview line. */
fun snippet(text: String, max: Int = 160): String =
    if (text.length <= max) text else text.take(max).trimEnd() + "…"
