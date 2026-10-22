package com.livingbook.session

import com.livingbook.data.db.BlockEntity
import com.livingbook.model.BlockKind
import com.livingbook.model.GuidedContent
import com.livingbook.model.ReadingPosition
import com.livingbook.speech.Sentence
import java.text.BreakIterator
import java.util.Locale

/** Turns topic content into narration sentences that keep their source position. */
object Sentences {

    fun fromBlocks(blocks: List<BlockEntity>): List<Sentence> = blocks.flatMap { block ->
        when (block.kind) {
            // Read aloud as a short pointer instead of mangled symbols.
            BlockKind.EQUATION -> listOf(Sentence("An equation appears here in the text.", block.idx, 0))
            BlockKind.TABLE -> listOf(Sentence("A table appears here in the text.", block.idx, 0))
            BlockKind.FIGURE -> listOf(Sentence("A figure appears here in the text.", block.idx, 0))
            BlockKind.CODE -> listOf(Sentence("A code listing appears here in the text.", block.idx, 0))
            else -> split(block.text).map { (offset, text) -> Sentence(text, block.idx, offset) }
        }
    }

    /** Guided sentences map back to the first source block of their segment. */
    fun fromGuided(content: GuidedContent, fallbackBlock: Int): List<Sentence> = content.segments.flatMap { seg ->
        val block = seg.sourceBlocks.firstOrNull() ?: fallbackBlock
        split(seg.text).map { (_, text) -> Sentence(text, block, 0) }
    }

    /** The sentence at or just before [position]; 0 if none. */
    fun indexAt(sentences: List<Sentence>, position: ReadingPosition): Int {
        var best = 0
        sentences.forEachIndexed { i, s ->
            if (s.blockIndex < position.blockIndex || (s.blockIndex == position.blockIndex && s.charOffset <= position.charOffset)) best = i
        }
        return best
    }

    /** Sentences of [text] with their start offsets. Blank pieces are dropped. */
    fun split(text: String): List<Pair<Int, String>> {
        val it = BreakIterator.getSentenceInstance(Locale.getDefault())
        it.setText(text)
        val out = mutableListOf<Pair<Int, String>>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            val piece = text.substring(start, end)
            val trimmed = piece.trim()
            if (trimmed.isNotEmpty()) out += (start + piece.indexOf(trimmed[0])) to trimmed
            start = end
            end = it.next()
        }
        return out
    }
}
