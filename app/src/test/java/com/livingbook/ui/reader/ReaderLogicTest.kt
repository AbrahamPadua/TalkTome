package com.livingbook.ui.reader

import com.livingbook.data.db.BlockEntity
import com.livingbook.data.db.TopicEntity
import com.livingbook.model.BlockKind
import com.livingbook.model.BookFormat
import com.livingbook.model.Granularity
import com.livingbook.model.GuidedContent
import com.livingbook.model.GuidedKind
import com.livingbook.model.GuidedSegment
import com.livingbook.session.Sentences
import com.livingbook.speech.Sentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderLogicTest {
    private fun block(idx: Int, text: String, kind: BlockKind = BlockKind.PARAGRAPH, page: Int? = null) =
        BlockEntity(bookId = "b", idx = idx, kind = kind, text = text, headingLevel = 0, page = page, location = null)

    private fun topic(idx: Int, section: String?) = TopicEntity("b", Granularity.BALANCED, idx, "Topic $idx", idx * 10, idx * 10 + 9, section)

    @Test
    fun sentenceRangeMatchesNarratedSentenceInsideItsBlock() {
        val text = "First sentence here. Second one follows."
        val b = block(3, text)
        val second = Sentences.fromBlocks(listOf(b))[1]
        val range = sentenceRange(second, b)!!
        assertEquals("Second one follows.", text.substring(range.first, range.last + 1))
        assertNull(sentenceRange(second.copy(blockIndex = 4), b))
        assertNull(sentenceRange(null, b))
    }

    @Test
    fun pointerBlocksHighlightWhole() {
        val eq = block(5, "E = mc^2", BlockKind.EQUATION)
        assertEquals(eq.text.indices, sentenceRange(Sentence("An equation appears here in the text.", 5, 0), eq))
    }

    @Test
    fun sentenceRangeIsClampedToBlockText() {
        val b = block(1, "Short.")
        val range = sentenceRange(Sentence("A much longer sentence than the block", 1, 2), b)!!
        assertEquals(2, range.first)
        assertEquals(b.text.length - 1, range.last)
    }

    @Test
    fun guidedRangesFollowNarrationSentenceOrder() {
        val content = GuidedContent(
            segments = listOf(
                GuidedSegment(GuidedKind.RESTATED, "One. Two.", listOf(10)),
                GuidedSegment(GuidedKind.ADDED, "Think of a river."),
                GuidedSegment(GuidedKind.QUOTED, "Three.", listOf(11)),
            ),
            isMock = true,
            model = "demo",
        )
        val ranges = guidedSentenceRanges(content)
        assertEquals(Sentences.fromGuided(content, 10).size, ranges.size)
        assertEquals(listOf(0, 0, 1, 2), ranges.map { it.first })
        assertEquals("Two.", content.segments[0].text.substring(ranges[1].second.first, ranges[1].second.last + 1))
        assertEquals(0 until 6, ranges[3].second)
    }

    @Test
    fun originalPageOnlyForPdfVisualBlocksWithPage() {
        assertEquals(4, originalPage(block(1, "Figure 2", BlockKind.FIGURE, page = 4), BookFormat.PDF))
        assertEquals(7, originalPage(block(1, "x^2", BlockKind.EQUATION, page = 7), BookFormat.PDF))
        assertNull(originalPage(block(1, "x^2", BlockKind.EQUATION, page = 7), BookFormat.EPUB))
        assertNull(originalPage(block(1, "Text", BlockKind.PARAGRAPH, page = 7), BookFormat.PDF))
        assertNull(originalPage(block(1, "Table", BlockKind.TABLE), BookFormat.PDF))
    }

    @Test
    fun contentsInsertSectionHeaderWhenSectionChanges() {
        val rows = contentsRows(listOf(topic(0, null), topic(1, "Part I"), topic(2, "Part I"), topic(3, "Part II")))
        assertEquals(
            listOf("entry 0", "section Part I", "entry 1", "entry 2", "section Part II", "entry 3"),
            rows.map { if (it is ContentsRow.Section) "section ${it.title}" else "entry ${(it as ContentsRow.Entry).topicIndex}" },
        )
    }

    @Test
    fun rateLabelsDropTrailingZero() {
        assertEquals(listOf("0.8×", "1×", "1.25×", "1.5×", "2×"), SPEEDS.map(::rateLabel))
    }

    @Test
    fun listenSuggestionsOnlyAfterATopicFinishes() {
        val offer = listOf(com.livingbook.ai.TutorSuggestion(com.livingbook.ai.TutorAction.EXPLAIN, "Explain"))
        val base = com.livingbook.session.SessionState(topicIndex = 2, suggestions = offer, suggestionsTopicIndex = 2, phase = com.livingbook.session.SessionPhase.LISTENING)
        assertFalse("not for the topic about to be read", showListenSuggestions(base))
        assertTrue("for the topic autoplay just left", showListenSuggestions(base.copy(suggestionsTopicIndex = 1)))
        assertTrue("at the end, when playback stopped", showListenSuggestions(base.copy(phase = com.livingbook.session.SessionPhase.OPTIONAL_GUIDANCE)))
        assertFalse(showListenSuggestions(base.copy(suggestions = emptyList(), suggestionsTopicIndex = 1)))
    }

    @Test
    fun snippetTruncatesWithEllipsis() {
        assertEquals("short", snippet("short"))
        assertEquals("abc…", snippet("abc def", max = 4))
    }
}
