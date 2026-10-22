package com.livingbook.ai

import com.livingbook.data.db.BlockEntity
import com.livingbook.model.AnswerSegment
import com.livingbook.model.BlockKind
import com.livingbook.model.GuidedKind
import com.livingbook.model.GuidedSegment
import com.livingbook.model.SegmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseParserTest {

    private val sent = setOf(12, 14)

    @Test fun validJson() {
        val raw = """{"segments":[{"kind":"book","text":"Priors encode belief.","citations":[12,14]},
            {"kind":"interpretation","text":"So the prior matters.","citations":[]},
            {"kind":"general","text":"Bayes lived in the 1700s."}],"assessment":null}"""

        val parsed = ResponseParser.parseAnswer(raw, sent)

        assertEquals(
            listOf(
                AnswerSegment(SegmentKind.BOOK, "Priors encode belief.", listOf(12, 14)),
                AnswerSegment(SegmentKind.INTERPRETATION, "So the prior matters."),
                AnswerSegment(SegmentKind.GENERAL, "Bayes lived in the 1700s."),
            ),
            parsed.segments,
        )
        assertNull(parsed.assessment)
    }

    @Test fun fencedJsonWithLeadingProse() {
        val raw = "Here you go:\n```json\n{\"segments\":[{\"kind\":\"BOOK\",\"text\":\"Quoted.\",\"citations\":[\"B12\"]}]}\n```"
        assertEquals(listOf(AnswerSegment(SegmentKind.BOOK, "Quoted.", listOf(12))), ResponseParser.parseAnswer(raw, sent).segments)
    }

    @Test fun invalidCitationsAreDroppedAndBookIsDowngraded() {
        val raw = """{"segments":[{"kind":"book","text":"Real.","citations":[12,99]},{"kind":"book","text":"Made up.","citations":[99,100]}]}"""
        assertEquals(
            listOf(
                AnswerSegment(SegmentKind.BOOK, "Real.", listOf(12)),
                AnswerSegment(SegmentKind.INTERPRETATION, "Made up.", emptyList()),
            ),
            ResponseParser.parseAnswer(raw, sent).segments,
        )
    }

    @Test fun bookWithoutCitationsIsDowngraded() {
        val raw = """{"segments":[{"kind":"book","text":"No source given."}]}"""
        assertEquals(SegmentKind.INTERPRETATION, ResponseParser.parseAnswer(raw, sent).segments.single().kind)
    }

    @Test fun assessmentIsRead() {
        val raw = """{"segments":[{"kind":"interpretation","text":"Close."}],"assessment":{"understood":false,"concept":"Bayes rule"}}"""
        assertEquals(Assessment(understood = false, concept = "Bayes rule"), ResponseParser.parseAnswer(raw, sent).assessment)
    }

    @Test fun garbageFallsBackToOneInterpretationSegment() {
        val parsed = ResponseParser.parseAnswer("The model just talked in prose.", sent)
        assertEquals(listOf(AnswerSegment(SegmentKind.INTERPRETATION, "The model just talked in prose.")), parsed.segments)
    }

    @Test fun truncatedJsonSalvagesTextFields() {
        val raw = """{"segments":[{"kind":"book","text":"First \"part\".","citations":[12]},{"kind":"general","text":"Cut off mid"""
        assertEquals(listOf(AnswerSegment(SegmentKind.INTERPRETATION, "First \"part\".")), ResponseParser.parseAnswer(raw, sent).segments)
    }

    @Test fun blankReplyHasNoSegments() {
        assertTrue(ResponseParser.parseAnswer("  ", sent).segments.isEmpty())
    }

    @Test fun decodedJsonWithoutUsableSegmentsIsNotShownRaw() {
        listOf("""{"segments":[]}""", """{"segments":[{"kind":"book","text":"  "}]}""", """{"answer":"Priors."}""").forEach {
            assertTrue(it, ResponseParser.parseAnswer(it, sent).segments.isEmpty())
        }
    }

    @Test fun onlyPlainBlockIdsAreCitations() {
        val raw = """{"segments":[{"kind":"book","text":"x","citations":[" b12 ",14,"-1","page 2, block 9","B12-B15","14.5"]}]}"""
        assertEquals(listOf(12, 14), ResponseParser.parseAnswer(raw, setOf(1, 2, 9, 12, 14, 15)).segments.single().citations)
    }

    private val guidedSource = listOf(
        BlockEntity(bookId = "b", idx = 10, kind = BlockKind.PARAGRAPH, text = "A prior is what you believe\nbefore seeing data.", headingLevel = 0, page = 3, location = null),
        BlockEntity(bookId = "b", idx = 11, kind = BlockKind.PARAGRAPH, text = "Evidence updates the prior.", headingLevel = 0, page = 3, location = null),
    )

    @Test fun guidedQuotedMustBeVerbatimAndBlocksSent() {
        val raw = """{"segments":[
            {"kind":"quoted","text":"“A prior is what you believe before seeing data.”","sourceBlocks":[10,42]},
            {"kind":"quoted","text":"A prior is a guess.","sourceBlocks":[10]},
            {"kind":"added","text":"Think of a weather forecast.","sourceBlocks":[]},
            {"kind":"restated","text":"You start with a belief.","sourceBlocks":["B10", 12]}]}"""

        assertEquals(
            listOf(
                GuidedSegment(GuidedKind.QUOTED, "A prior is what you believe before seeing data.", listOf(10)),
                GuidedSegment(GuidedKind.RESTATED, "A prior is a guess.", listOf(10)),
                GuidedSegment(GuidedKind.ADDED, "Think of a weather forecast."),
                GuidedSegment(GuidedKind.RESTATED, "You start with a belief.", listOf(10)),
            ),
            ResponseParser.parseGuided(raw, guidedSource),
        )
    }

    @Test fun guidedQuoteMustBeInItsOwnCitedBlock() {
        val raw = """{"segments":[
            {"kind":"quoted","text":"Evidence updates the prior.","sourceBlocks":[10]},
            {"kind":"quoted","text":"Evidence updates the prior.","sourceBlocks":[]}]}"""

        assertEquals(
            listOf(
                GuidedSegment(GuidedKind.RESTATED, "Evidence updates the prior.", listOf(10)),
                GuidedSegment(GuidedKind.QUOTED, "Evidence updates the prior.", listOf(11)),
            ),
            ResponseParser.parseGuided(raw, guidedSource),
        )
    }

    @Test fun guidedGarbageIsEmpty() {
        assertTrue(ResponseParser.parseGuided("not json", emptyList()).isEmpty())
    }
}
