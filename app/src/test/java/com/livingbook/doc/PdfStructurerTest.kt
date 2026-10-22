package com.livingbook.doc

import com.livingbook.model.BlockKind
import org.junit.Assert.*
import org.junit.Test

class PdfStructurerTest {
    private val structurer = PdfStructurer()
    private fun line(text: String, y: Float, size: Float = 12f, page: Int = 1, x: Float = 40f, bold: Boolean = false) = PdfLine(text, page, size, x, y, bold)
    private val prose = "This is enough readable prose to keep this synthetic document above the scanned text threshold."
    private fun structure(lines: List<PdfLine>, pages: List<PdfPage> = listOf(PdfPage(1, 800f, false)), outline: List<PdfBookmark> = emptyList()) =
        structurer.structure("Title", "Author", lines, pages, outline)

    @Test fun detectsHeadingsAndJoinsHyphenationAndParagraphLines() {
        val document = structure(listOf(line("Chapter", 100f, 24f), line("Subsection", 130f, 18f),
            line("A long paragraph with a hyphenated exam-", 160f), line("ple continues on this next line", 174f),
            line("and ends here.", 188f), line(prose, 220f)))
        assertEquals(listOf(BlockKind.HEADING, BlockKind.HEADING, BlockKind.PARAGRAPH, BlockKind.PARAGRAPH), document.blocks.map { it.kind })
        assertEquals(listOf(1, 2), document.sections.map { it.level })
        assertEquals("A long paragraph with a hyphenated example continues on this next line and ends here.", document.blocks[2].text)
        assertTrue(document.blocks.all { it.page == 1 })
    }

    @Test fun indentationAndSentenceGapsStartParagraphs() {
        val document = structure(listOf(line(prose, 120f), line("Continuation of the same sentence.", 134f),
            line("A new sentence with a modest gap.", 150f), line("Indented paragraph content.", 164f, x = 65f),
            line("Continuation.", 178f, x = 65f)))
        assertEquals(3, document.blocks.size)
    }

    @Test fun shortBoldHeadingsRankBelowLargerHeadingsAndExcludeSentencesAndEquations() {
        val document = structure(listOf(line("Chapter", 100f, 24f), line("Subsection", 130f, 13.1f, bold = true),
            line("Body size heading", 155f, bold = true), line(prose, 180f),
            line("A complete bold sentence.", 210f, bold = true), line("x + y = 4", 240f, bold = true),
            line("Long ".repeat(19), 270f, bold = true), line(prose, 300f)))
        assertEquals(listOf("Chapter", "Subsection", "Body size heading"), document.blocks.filter { it.kind == BlockKind.HEADING }.map { it.text })
        assertEquals(listOf(1, 2, 3), document.blocks.filter { it.kind == BlockKind.HEADING }.map { it.headingLevel })
        assertTrue(document.blocks.any { it.kind == BlockKind.EQUATION })
    }

    @Test fun groupsTableFragmentsAndPreservesSurroundingParagraphsAndPages() {
        val cells = listOf("Receptor class", "Adequate stimulus", "Mechanoreceptor", "Mechanical displacement", "Photoreceptor", "Visible light")
        val document = structure(listOf(line(prose, 100f), line(prose, 100f, page = 2)) +
            cells.mapIndexed { i, cell -> line(cell, 150f + (i / 2) * 14f, page = 2, x = 40f + (i % 2) * 150f) } +
            line(prose, 240f, page = 2), (1..2).map { PdfPage(it, 800f, false) })
        assertEquals(listOf(BlockKind.PARAGRAPH, BlockKind.PARAGRAPH, BlockKind.TABLE, BlockKind.PARAGRAPH), document.blocks.map { it.kind })
        assertEquals(cells.joinToString("\n"), document.blocks[2].text)
        assertEquals(2, document.blocks[2].page)
        assertEquals(document.blocks.indices.toList(), document.blocks.map { it.index })
    }

    @Test fun doesNotTurnShortProseBulletsEquationsOrIsolatedFragmentsIntoTables() {
        val wrapped = listOf("An ordinary paragraph may wrap", "across several short visual lines", "while still describing a single", "coherent thought without a break", "until its final sentence ends.")
        val cases = listOf(
            wrapped.mapIndexed { i, text -> line(text, 100f + i * 14f) },
            wrapped.mapIndexed { i, text -> line(text, 100f + i * 14f, x = if (i == 0) 65f else 40f) },
            (0..5).map { line("A short paragraph.", 100f + it * 30f, x = 40f + (it % 2) * 20f) },
            (0..5).map { line("- Bullet fragment", 100f + it * 14f, x = 40f + (it % 2) * 20f) },
            (0..5).map { line("${it + 1}. Numbered fragment", 100f + it * 14f, x = 40f + (it % 2) * 20f) },
            listOf(line("x + y = 4", 100f), line("Summation", 114f, x = 80f),
                line("i=1", 110f, x = 100f), line("r c", 124f, x = 80f)),
            (0..2).map { line("Table fragment", 100f + it * 14f, x = 40f + (it % 2) * 100f) },
        )
        for (lines in cases) {
            val document = structure(lines + line(prose, 400f))
            assertFalse(document.blocks.toString(), document.blocks.any { it.kind == BlockKind.TABLE })
        }
        assertTrue(structure(cases.first()).blocks.single().text.contains("lines while still"))
    }

    @Test fun removesRepeatedRunningTextAndPageNumbersOnlyAtPageEdges() {
        val lines = (1..4).flatMap { page -> listOf(line("Running book $page", 20f, page = page),
            line(prose + " Page content $page", 200f, page = page), line("Footer", 760f, page = page), line("$page", 790f, page = page)) }
        val document = structure(lines, (1..4).map { PdfPage(it, 800f, false) })
        assertEquals(4, document.blocks.size)
        assertTrue(document.blocks.all { it.text.startsWith(prose) })
        assertEquals((0..3).toList(), document.blocks.map { it.index })
    }

    @Test fun bookmarksAttachToTheHeadingTheyName() {
        val document = structure(listOf(line(prose, 100f), line("Receptive fields", 300f, 18f), line(prose, 330f)),
            outline = listOf(PdfBookmark("Receptive fields", 1, 1)))
        assertEquals(listOf(1), document.sections.map { it.startBlock })
    }

    @Test fun retainsRawEquationsAndPlacesFiguresAtTheirPage() {
        val raw = "  x² + y² = 4  "
        val document = structure(listOf(line(prose, 120f), line(raw, 160f), line(prose, 140f, page = 2)),
            listOf(PdfPage(1, 800f, true), PdfPage(2, 800f, false)))
        assertEquals(raw, document.blocks[1].text)
        assertEquals(BlockKind.EQUATION, document.blocks[1].kind)
        assertEquals("Figure on page 1", document.blocks[2].text)
        assertEquals(listOf(1, 1, 1, 2), document.blocks.map { it.page })
    }

    @Test fun outlineTakesPrecedenceOverDetectedHeadings() {
        val document = structure(listOf(line("Heading", 100f, 24f), line(prose, 140f), line(prose, 140f, page = 2)),
            listOf(PdfPage(1, 800f, false), PdfPage(2, 800f, false)), listOf(PdfBookmark("Bookmark", 2, 1)))
        assertEquals("Bookmark", document.sections.single().title)
        assertEquals(2, document.sections.single().startBlock)
    }

    @Test fun rejectsScannedDocumentsButWarnsAndRetainsPartiallyScannedPages() {
        val error = assertThrows(DocumentException::class.java) { structure(listOf(line("Short", 100f)), listOf(PdfPage(1, 800f, true))) }
        assertTrue(error.userMessage.contains("OCR"))
        val pages = (1..6).map { PdfPage(it, 800f, it >= 4) }
        val document = structure((1..3).map { line(prose, 200f, page = it) }, pages)
        assertEquals("Pages 4-6 contain no extractable text (likely scanned).", document.warnings.single())
        assertEquals(listOf(4, 5, 6), document.blocks.filter { it.kind == BlockKind.FIGURE }.map { it.page })
    }
}
