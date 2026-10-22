package com.livingbook.session

import com.livingbook.data.db.BlockEntity
import com.livingbook.model.BlockKind
import com.livingbook.model.GuidedContent
import com.livingbook.model.GuidedKind
import com.livingbook.model.GuidedSegment
import com.livingbook.model.ReadingPosition
import org.junit.Assert.assertEquals
import org.junit.Test

class SentencesTest {

    private fun block(idx: Int, text: String, kind: BlockKind = BlockKind.PARAGRAPH) =
        BlockEntity(bookId = "b", idx = idx, kind = kind, text = text, headingLevel = 0, page = null, location = null)

    @Test
    fun `split keeps offsets of each sentence`() {
        val text = "First sentence. Second one here!  Third?"
        val parts = Sentences.split(text)
        assertEquals(listOf("First sentence.", "Second one here!", "Third?"), parts.map { it.second })
        parts.forEach { (offset, s) -> assertEquals(s, text.substring(offset, offset + s.length)) }
    }

    @Test
    fun `non-text blocks become short spoken pointers`() {
        val sentences = Sentences.fromBlocks(listOf(block(0, "Intro."), block(1, "E = mc^2", BlockKind.EQUATION), block(2, "a | b", BlockKind.TABLE)))
        assertEquals(3, sentences.size)
        assertEquals("An equation appears here in the text.", sentences[1].text)
        assertEquals(2, sentences[2].blockIndex)
    }

    @Test
    fun `indexAt picks the sentence containing the position`() {
        val sentences = Sentences.fromBlocks(listOf(block(4, "One. Two. Three."), block(5, "Four.")))
        assertEquals(0, Sentences.indexAt(sentences, ReadingPosition(4, 0)))
        assertEquals(1, Sentences.indexAt(sentences, ReadingPosition(4, 6)))
        assertEquals(2, Sentences.indexAt(sentences, ReadingPosition(4, 12)))
        assertEquals(3, Sentences.indexAt(sentences, ReadingPosition(5, 0)))
        // A position before the topic falls back to its first sentence.
        assertEquals(0, Sentences.indexAt(sentences, ReadingPosition(1, 0)))
    }

    @Test
    fun `guided sentences map back to their source block`() {
        val content = GuidedContent(
            segments = listOf(
                GuidedSegment(GuidedKind.RESTATED, "Plainly put. It works.", listOf(7)),
                GuidedSegment(GuidedKind.ADDED, "Think of a thermostat.", emptyList()),
            ),
            isMock = false,
            model = "m",
        )
        val sentences = Sentences.fromGuided(content, fallbackBlock = 3)
        assertEquals(listOf(7, 7, 3), sentences.map { it.blockIndex })
    }
}
