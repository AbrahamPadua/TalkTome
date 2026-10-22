package com.livingbook.doc

import com.livingbook.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class HeadingAwareSegmenterTest {
    private val segmenter = HeadingAwareSegmenter()
    private fun book(blocks: List<SourceBlock>, sections: List<ParsedSection> = emptyList()) =
        ParsedDocument("Book", null, BookFormat.EPUB, blocks.mapIndexed { i, b -> b.copy(index = i) }, sections, null)
    private fun paragraph(words: Int = 50) = SourceBlock(0, BlockKind.PARAGRAPH, List(words) { "word" }.joinToString(" "))

    @Test fun headingsSplitAtTheRequestedLevelsAndStayWithFollowingContent() {
        val document = book((1..6).flatMap { level -> listOf(SourceBlock(0, BlockKind.HEADING, "Heading $level", level), paragraph(10)) })
        for ((granularity, expected) in listOf(Granularity.BITE to 6, Granularity.BALANCED to 3, Granularity.DEEP to 2)) {
            val topics = segmenter.segment(document, granularity)
            assertEquals(expected, topics.size)
            assertTrue(topics.all { document.blocks[it.startBlock].kind == BlockKind.HEADING && it.endBlock > it.startBlock })
        }
    }

    @Test fun consecutiveHeadingsNeverCreateAnIsolatedHeadingTopic() {
        val document = book(listOf(paragraph(190), SourceBlock(0, BlockKind.HEADING, "Chapter", 1),
            SourceBlock(0, BlockKind.HEADING, "Subheading", 2), paragraph(190), SourceBlock(0, BlockKind.HEADING, "End", 1)))
        val topics = segmenter.segment(document, Granularity.BITE)
        assertEquals(listOf(0..0, 1..3, 4..4), topics.map { it.startBlock..it.endBlock })
    }

    @Test fun lengthTargetsScaleAndTinyTailsMerge() {
        val document = book(List(100) { paragraph(50) })
        val sizes = Granularity.entries.map { granularity ->
            val topics = segmenter.segment(document, granularity)
            val min = when (granularity) { Granularity.BITE -> 120; Granularity.BALANCED -> 350; Granularity.DEEP -> 1200 }
            val max = when (granularity) { Granularity.BITE -> 250; Granularity.BALANCED -> 800; Granularity.DEEP -> 2500 }
            assertTrue(topics.all { (it.endBlock - it.startBlock + 1) * 50 in min..max })
            topics.size
        }
        assertTrue(sizes[0] > sizes[1] && sizes[1] > sizes[2])
        assertEquals(1, segmenter.segment(book(List(5) { paragraph(40) }), Granularity.BITE).size)
    }

    @Test fun protectsCaptionPairsAndListRuns() {
        for (kind in listOf(BlockKind.FIGURE, BlockKind.TABLE, BlockKind.EQUATION)) {
            val document = book(listOf(paragraph(175), SourceBlock(0, kind, "some figure words here now"),
                SourceBlock(0, BlockKind.CAPTION, "Caption"), paragraph(100)))
            val topics = segmenter.segment(document, Granularity.BITE)
            assertEquals(2, topics.size)
            assertEquals(2, topics.first().endBlock)
        }
        val lists = book(listOf(paragraph(170)) + List(6) { SourceBlock(0, BlockKind.LIST_ITEM, paragraph(30).text) } + paragraph(100))
        assertEquals(6, segmenter.segment(lists, Granularity.BITE).first().endBlock)
    }

    @Test fun titlesAndSectionBoundariesAreMeaningful() {
        val document = book(listOf(SourceBlock(0, BlockKind.HEADING, "Chapter one", 1), paragraph(180), paragraph(100),
            SourceBlock(0, BlockKind.HEADING, "Chapter two", 1), paragraph(10)),
            listOf(ParsedSection("One", 1, 0), ParsedSection("Two", 1, 3)))
        val topics = segmenter.segment(document, Granularity.BITE)
        assertEquals(listOf("Chapter one", "One (continued)", "Chapter two"), topics.map { it.title })
        assertEquals(listOf("One", "One", "Two"), topics.map { it.sectionTitle })
        val sentence = "This is a complete opening sentence. Extra text follows."
        assertEquals("This is a complete opening sentence.", segmenter.segment(book(listOf(paragraph().copy(text = sentence))), Granularity.BITE).single().title)
        val clipped = segmenter.segment(book(listOf(paragraph().copy(text = "Long opening words ".repeat(10)))), Granularity.BITE).single().title
        assertTrue(clipped.length <= 60 && clipped.endsWith("words"))
    }

    @Test fun randomizedTopicsCoverEveryBlockExactlyOnceForEveryGranularity() {
        val random = Random(419)
        repeat(150) {
            val blocks = List(random.nextInt(1, 200)) { index ->
                val kind = BlockKind.entries.random(random)
                SourceBlock(index, kind, paragraph(random.nextInt(1, 120)).text, if (kind == BlockKind.HEADING) random.nextInt(1, 7) else 0)
            }
            val document = book(blocks, blocks.filter { it.kind == BlockKind.HEADING && it.headingLevel <= 2 }
                .map { ParsedSection("Section ${it.index}", it.headingLevel, it.index) })
            for (granularity in Granularity.entries) {
                val topics = segmenter.segment(document, granularity)
                assertEquals(topics.indices.toList(), topics.map { it.index })
                assertTrue(topics.all { it.startBlock <= it.endBlock })
                assertEquals(blocks.indices.toList(), topics.flatMap { (it.startBlock..it.endBlock).toList() })
            }
        }
        assertTrue(segmenter.segment(book(emptyList()), Granularity.BITE).isEmpty())
    }
}
