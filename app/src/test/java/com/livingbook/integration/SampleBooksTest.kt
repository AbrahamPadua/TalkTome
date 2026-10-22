package com.livingbook.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.livingbook.doc.EpubDocumentParser
import com.livingbook.doc.HeadingAwareSegmenter
import com.livingbook.doc.PdfDocumentParser
import com.livingbook.model.BlockKind
import com.livingbook.model.Granularity
import com.livingbook.model.ParsedDocument
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Parses the real sample files in /samples end to end, the way an import would. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SampleBooksTest {
    private val samples = File("../samples").takeIf { it.isDirectory } ?: File("samples")
    private val segmenter = HeadingAwareSegmenter()

    @Test
    fun `sample EPUB keeps its chapters, table and equations`() = runBlocking {
        val doc = EpubDocumentParser().parse(File(samples, "senses-primer.epub"))
        report(doc)
        assertEquals("How Senses Become Perception: A Short Primer", doc.title)
        assertTrue(doc.blocks.any { it.kind == BlockKind.TABLE })
        assertEquals(2, doc.blocks.count { it.kind == BlockKind.EQUATION })
        val sourceLines = File(samples, "senses-primer.md").readLines()
        val expectedListItems = sourceLines.filter { it.startsWith("* ") }.map { it.removePrefix("* ").trim() }
        assertEquals(expectedListItems, doc.blocks.filter { it.kind == BlockKind.LIST_ITEM }.map { it.text })
        assertTrue(doc.sections.any { it.title.contains("Sensory transduction") })
        assertTopicsCover(doc)
        val balanced = segmenter.segment(doc, Granularity.BALANCED).map { it.title }
        val sectionTitles = sourceLines.filter { it.startsWith("## ") }.map { it.removePrefix("## ").trim() }
        assertTrue("$balanced should include every section: $sectionTitles", balanced.containsAll(sectionTitles))
    }

    @Test
    fun `sample PDF yields readable paragraphs with pages and sections`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        PDFBoxResourceLoader.init(context)
        val doc = PdfDocumentParser(context).parse(File(samples, "senses-primer.pdf"))
        report(doc)
        assertTrue(doc.blocks.all { it.page != null })
        assertTrue(doc.sections.any { it.title.contains("Receptive fields") })
        assertTrue(doc.blocks.count { it.kind == BlockKind.PARAGRAPH } > 15)
        assertTrue(doc.blocks.count { it.kind == BlockKind.HEADING } >= 12)
        assertTrue(doc.blocks.any { it.kind == BlockKind.TABLE && it.page == 2 })
        assertEquals(12, doc.sections.size)
        for (section in doc.sections) {
            val start = doc.blocks[section.startBlock]
            assertEquals(section.title, BlockKind.HEADING, start.kind)
            assertEquals(section.title, start.text)
        }
        assertTopicsCover(doc)
    }

    private fun assertTopicsCover(doc: ParsedDocument) {
        for (g in Granularity.entries) {
            val covered = segmenter.segment(doc, g).flatMap { (it.startBlock..it.endBlock).toList() }
            assertEquals("$g covers every block once", doc.blocks.indices.toList(), covered)
        }
    }

    private fun report(doc: ParsedDocument) {
        println("== ${doc.title} (${doc.format}) blocks=${doc.blocks.size} sections=${doc.sections.size} warnings=${doc.warnings}")
        println("kinds: " + doc.blocks.groupingBy { it.kind }.eachCount())
        for (g in Granularity.entries) {
            val topics = segmenter.segment(doc, g)
            println("$g: ${topics.size} topics: " + topics.joinToString(" | ") { it.title.take(40) })
        }
        doc.blocks.take(12).forEach { println("  [${it.index}] ${it.kind} p=${it.page} ${it.text.take(90)}") }
    }
}
