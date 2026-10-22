package com.livingbook.ui.library

import androidx.compose.ui.graphics.luminance
import com.livingbook.data.db.LibraryRow
import com.livingbook.model.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryLogicTest {
    private fun row(id: String, lastOpenedAt: Long? = null, blockIndex: Int? = null, blockCount: Int = 200, warnings: String = "") =
        LibraryRow(id, "Title $id", null, BookFormat.PDF, blockCount, warnings, importedAt = 1, lastOpenedAt = lastOpenedAt, blockIndex = blockIndex)

    @Test
    fun coverColorIsStableAndReadableUnderPaperInitials() {
        assertEquals(coverColor("Thinking, Fast and Slow"), coverColor("Thinking, Fast and Slow"))
        val paperLuminance = 0.93f
        (0 until 500).map { "Book number $it" }.forEach { title ->
            val contrast = (paperLuminance + 0.05f) / (coverColor(title).luminance() + 0.05f)
            assertTrue("contrast $contrast for $title", contrast >= 4.5f)
        }
    }

    @Test
    fun initialsSkipLeadingArticleAndSymbols() {
        assertEquals("AO", coverInitials("The Art of War"))
        assertEquals("DD", coverInitials("deep   dive"))
        assertEquals("T", coverInitials("The"))
        assertEquals("2B", coverInitials("2001 (a) Beginning"))
        assertEquals("?", coverInitials("  "))
    }

    @Test
    fun progressIsBlockIndexOverCountClamped() {
        assertEquals(0f, progressFraction(null, 100))
        assertEquals(0f, progressFraction(5, 0))
        assertEquals(0.25f, progressFraction(25, 100))
        assertEquals(1f, progressFraction(120, 100))
        assertEquals("Not started", progressLabel(row("a")))
        assertEquals("43%", progressLabel(row("a", blockIndex = 86)))
    }

    @Test
    fun continueReadingPicksMostRecentlyOpenedBook() {
        assertNull(continueBook(listOf(row("a"), row("b"))))
        assertEquals("c", continueBook(listOf(row("a"), row("b", lastOpenedAt = 10), row("c", lastOpenedAt = 30)))?.id)
    }

    @Test
    fun warningsSplitOnLinesAndDropBlanks() {
        assertEquals(listOf("Pages 4-6 were skipped", "No outline"), row("a", warnings = "Pages 4-6 were skipped\n\nNo outline\n").warningList())
        assertTrue(row("a").warningList().isEmpty())
    }
}
