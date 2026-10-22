package com.livingbook.doc

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.livingbook.model.BlockKind
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PdfDocumentParserTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
    }

    private fun pdf(encrypted: Boolean = false, imageOnly: Boolean = false): File {
        val file = temporary.newFile("sample-${System.nanoTime()}.pdf")
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            document.documentInformation.title = "PDF metadata title"
            document.documentInformation.author = "PDF author"
            PDPageContentStream(document, page).use { stream ->
                if (!imageOnly) {
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA_BOLD, 24f)
                    stream.newLineAtOffset(50f, 680f)
                    stream.showText("Chapter heading")
                    stream.setFont(PDType1Font.HELVETICA, 12f)
                    stream.newLineAtOffset(0f, -35f)
                    stream.showText("This paragraph contains enough readable text to pass scanned detection.")
                    stream.newLineAtOffset(0f, -16f)
                    stream.showText("It continues on another line in the same paragraph.")
                    stream.newLineAtOffset(0f, -16f)
                    stream.showText("One more line supplies normal body font size observations.")
                    stream.endText()
                }
                val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                stream.drawImage(LosslessFactory.createFromImage(document, bitmap), 50f, 400f, 20f, 20f)
                bitmap.recycle()
            }
            val outline = PDDocumentOutline()
            document.documentCatalog.documentOutline = outline
            outline.addLast(PDOutlineItem().apply { title = "Bookmark chapter"; setDestination(page) })
            if (encrypted) document.protect(StandardProtectionPolicy("owner-pass", "reader-pass", AccessPermission()))
            document.save(file)
        }
        return file
    }

    @Test fun extractsTextMetadataBookmarkAndImageEndToEnd() = runBlocking {
        val book = PdfDocumentParser(context).parse(pdf())
        assertEquals("PDF metadata title", book.title)
        assertEquals("PDF author", book.author)
        assertEquals(1, book.pageCount)
        assertEquals(listOf(BlockKind.HEADING, BlockKind.PARAGRAPH, BlockKind.FIGURE), book.blocks.map { it.kind })
        assertTrue(book.blocks[1].text.contains("paragraph. One more line"))
        assertEquals("Bookmark chapter", book.sections.single().title)
        assertEquals(0, book.sections.single().startBlock)
        assertTrue(book.blocks.all { it.page == 1 })
    }

    @Test fun rejectsPasswordProtectedAndScannedPdfWithActionableErrors() {
        val parser = PdfDocumentParser(context)
        val encrypted = assertThrows(DocumentException::class.java) { runBlocking { parser.parse(pdf(encrypted = true)) } }
        assertTrue(encrypted.userMessage.contains("password"))
        val scanned = assertThrows(DocumentException::class.java) { runBlocking { parser.parse(pdf(imageOnly = true)) } }
        assertTrue(scanned.userMessage.contains("OCR"))
    }
}
