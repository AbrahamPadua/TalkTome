package com.livingbook.doc

import com.livingbook.model.Granularity
import com.livingbook.model.BlockKind
import com.livingbook.model.ParsedDocument
import com.livingbook.model.Topic

/** Splits at headings first, then at paragraph boundaries near a per-granularity length target. */
class HeadingAwareSegmenter : TopicSegmenter {
    override fun segment(document: ParsedDocument, granularity: Granularity): List<Topic> {
        val blocks = document.blocks
        if (blocks.isEmpty()) return emptyList()
        require(blocks.withIndex().all { (index, block) -> index == block.index }) { "Block indices must be contiguous from zero." }
        val (target, headingLimit) = when (granularity) {
            Granularity.BITE -> 180 to 6
            Granularity.BALANCED -> 550 to 3
            Granularity.DEEP -> 1800 to 2
        }
        val sections = document.sections.sortedBy { it.startBlock }
        val sectionAt = arrayOfNulls<com.livingbook.model.ParsedSection>(blocks.size)
        var sectionIndex = 0
        var section: com.livingbook.model.ParsedSection? = null
        for (i in blocks.indices) {
            while (sectionIndex < sections.size && sections[sectionIndex].startBlock <= i) section = sections[sectionIndex++]
            sectionAt[i] = section
        }
        val sectionStarts = sections.filter { it.level <= headingLimit }.map { it.startBlock }.toSet()
        fun semanticStart(i: Int) = i in sectionStarts || (blocks[i].kind == BlockKind.HEADING && blocks[i].headingLevel <= headingLimit)
        val words = blocks.map { Regex("\\S+").findAll(it.text).count() }
        val ranges = mutableListOf<IntRange>()
        var start = 0
        var count = words[0]
        var hasContent = blocks[0].kind != BlockKind.HEADING
        val media = setOf(BlockKind.FIGURE, BlockKind.TABLE, BlockKind.EQUATION, BlockKind.CAPTION)
        for (i in 1..blocks.lastIndex) {
            val previous = blocks[i - 1].kind
            val current = blocks[i].kind
            val attached = previous == BlockKind.HEADING ||
                (current == BlockKind.CAPTION && previous in media) ||
                (current == BlockKind.LIST_ITEM && previous == BlockKind.LIST_ITEM)
            if (!attached && hasContent && (semanticStart(i) || count >= target)) {
                ranges += start until i
                start = i
                count = 0
                hasContent = false
            }
            count += words[i]
            hasContent = hasContent || current != BlockKind.HEADING
        }
        ranges += start..blocks.lastIndex
        val merged = mutableListOf<IntRange>()
        for (range in ranges) {
            val previous = merged.lastOrNull()
            if (previous != null && !semanticStart(range.first) && sectionAt[previous.first] == sectionAt[range.first] &&
                range.sumOf { words[it] } < target / 4) {
                merged[merged.lastIndex] = previous.first..range.last
            } else merged += range
        }
        return merged.mapIndexed { index, range ->
            val first = blocks[range.first]
            val heading = range.asSequence().map { blocks[it] }.takeWhile { it.kind == BlockKind.HEADING }.lastOrNull()
            val sectionTitle = sectionAt[range.first]?.title
            val title = when {
                heading != null -> heading.text
                sectionTitle != null -> clipAtWord(sectionTitle, 60 - " (continued)".length) + " (continued)"
                else -> clippedSentence(first.text)
            }
            Topic(index, title, range.first, range.last, sectionTitle)
        }
    }

    private fun clippedSentence(text: String): String {
        val sentence = Regex("[.!?](?:\\s|$)").find(text)?.let { text.take(it.range.first + 1) } ?: text
        return clipAtWord(sentence, 60)
    }

    private fun clipAtWord(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val prefix = text.take(limit)
        if (text[limit].isWhitespace()) return prefix.trimEnd()
        return prefix.substringBeforeLast(' ', prefix).ifBlank { prefix }
    }
}
