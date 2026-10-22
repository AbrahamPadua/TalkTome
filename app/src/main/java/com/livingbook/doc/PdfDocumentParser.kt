package com.livingbook.doc

import android.content.Context
import com.livingbook.model.ParsedDocument
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.StringWriter

/** Text-based PDF parsing with PdfBox-Android. Detects scanned/image-only PDFs. */
class PdfDocumentParser(private val context: Context) : DocumentParser {
    override fun canParse(fileName: String, mimeType: String?): Boolean =
        mimeType == "application/pdf" || fileName.endsWith(".pdf", ignoreCase = true)

    override suspend fun parse(file: File): ParsedDocument = try {
        PDDocument.load(file).use { document ->
            if (!document.currentAccessPermission.canExtractContent()) {
                throw DocumentException("This PDF restricts text extraction. Open an unrestricted copy to read it in Living Book.")
            }
            val stripper = LineStripper()
            stripper.writeText(document, StringWriter())
            val pages = document.pages.mapIndexed { i, page ->
                val height = if (page.rotation % 180 == 0) page.cropBox.height else page.cropBox.width
                PdfPage(i + 1, height, hasImages(page.resources, mutableSetOf()))
            }
            val outline = mutableListOf<PdfBookmark>()
            val visitedOutline = mutableSetOf<com.tom_roush.pdfbox.cos.COSDictionary>()
            fun visit(node: PDOutlineNode, level: Int) {
                var item = node.firstChild
                while (item != null && visitedOutline.add(item.cosObject)) {
                    val current = item
                    val target = current.findDestinationPage(document)
                    val page = target?.let { document.pages.indexOf(it) + 1 }
                    if (page != null && page > 0 && !current.title.isNullOrBlank()) outline += PdfBookmark(current.title, page, level)
                    visit(current, level + 1)
                    item = current.nextSibling
                }
            }
            document.documentCatalog.documentOutline?.let { visit(it, 1) }
            val info = document.documentInformation
            PdfStructurer().structure(info.title?.takeIf(String::isNotBlank) ?: file.nameWithoutExtension,
                info.author?.takeIf(String::isNotBlank), stripper.lines, pages, outline)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: DocumentException) {
        throw e
    } catch (e: InvalidPasswordException) {
        throw DocumentException("This PDF is encrypted or needs a password. Open an unlocked copy to read it in Living Book.", e)
    } catch (e: Exception) {
        throw DocumentException("This PDF couldn't be read. It may be damaged. Try another copy.", e)
    }

    private fun hasImages(resources: PDResources?, visited: MutableSet<com.tom_roush.pdfbox.cos.COSDictionary>): Boolean {
        if (resources == null || !visited.add(resources.cosObject)) return false
        return resources.xObjectNames.any { name ->
            when (val item = resources.getXObject(name)) {
                is PDImageXObject -> true
                is PDFormXObject -> hasImages(item.resources, visited)
                else -> false
            }
        }
    }

    private class LineStripper : PDFTextStripper() {
        val lines = mutableListOf<PdfLine>()
        private val text = StringBuilder()
        private val positions = mutableListOf<TextPosition>()
        private val boldFontName = Regex("Bold|Black|Heavy|Semibold", RegexOption.IGNORE_CASE)

        // Content-stream order keeps two-column papers readable; sorting by position would interleave the columns.
        init { sortByPosition = false }

        override fun writeString(value: String, textPositions: MutableList<TextPosition>) {
            text.append(value)
            positions += textPositions
        }

        override fun writeWordSeparator() { text.append(' ') }
        override fun writeLineSeparator() { flush() }
        override fun endPage(page: PDPage) { flush() }

        private fun flush() {
            if (text.isNotBlank() && positions.isNotEmpty()) {
                val glyphs = positions.filter { it.unicode.isNotBlank() }
                val bold = glyphs.isNotEmpty() && glyphs.all {
                    val descriptor = it.font.fontDescriptor
                    boldFontName.containsMatchIn(it.font.name.orEmpty()) || descriptor?.isForceBold == true ||
                        (descriptor?.fontWeight ?: 0f) >= 600f
                }
                lines += PdfLine(text.toString(), currentPageNo, median(positions.map { it.fontSizeInPt }),
                    positions.minOf { it.xDirAdj }, median(positions.map { it.yDirAdj }), bold)
            }
            text.setLength(0)
            positions.clear()
        }
    }
}
