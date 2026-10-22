package com.livingbook.doc

import com.livingbook.model.BlockKind
import com.livingbook.model.BookFormat
import com.livingbook.model.ParsedDocument
import com.livingbook.model.ParsedSection
import com.livingbook.model.SourceBlock
import kotlin.math.abs
import kotlin.math.ceil

/** One extracted visual line, with coordinates measured from the top left of its page. */
internal data class PdfLine(val text: String, val page: Int, val fontSize: Float, val x: Float, val y: Float)

internal data class PdfPage(val number: Int, val height: Float, val hasImages: Boolean)
internal data class PdfBookmark(val title: String, val page: Int, val level: Int)

/** Structures extracted PDF lines without depending on Android or PdfBox. */
internal class PdfStructurer {
    fun structure(
        title: String,
        author: String?,
        lines: List<PdfLine>,
        pages: List<PdfPage>,
        outline: List<PdfBookmark> = emptyList(),
    ): ParsedDocument {
        val readable = lines.filter { it.text.isNotBlank() }
        if (pages.isEmpty() || readable.sumOf { line -> line.text.count { !it.isWhitespace() } } < pages.size * 30) {
            throw DocumentException("This PDF looks like scanned images. Text recognition (OCR) is not supported yet, so it can't be read as text.")
        }
        val heights = pages.associate { it.number to it.height }
        fun edge(line: PdfLine): Boolean = line.y < heights.getValue(line.page) * .12f || line.y > heights.getValue(line.page) * .88f
        fun signature(line: PdfLine) = line.text.lowercase().replace(Regex("\\d"), "").replace(Regex("\\s+"), " ").trim()
        val repeats = readable.filter(::edge).groupBy(::signature).filterValues { group ->
            group.map { it.page }.distinct().size >= maxOf(2, ceil(pages.size * .6).toInt())
        }.keys
        val retained = readable.filterNot { edge(it) && (it.text.trim().matches(Regex("\\d+")) || signature(it) in repeats) }
        val bodySize = median(retained.filter { it.fontSize > 0 }.map { it.fontSize }).coerceAtLeast(1f)
        fun isHeading(line: PdfLine) = line.text.trim().length < 100 && !line.text.trim().endsWith('.') &&
            line.fontSize >= bodySize * 1.2f && !isEquation(line.text)
        val headingSizes = retained.filter(::isHeading).map { it.fontSize }.sortedDescending().fold(mutableListOf<Float>()) { sizes, size ->
            if (sizes.none { abs(it - size) < .5f }) sizes += size
            sizes
        }
        val blocks = mutableListOf<SourceBlock>()
        fun emit(kind: BlockKind, text: String, page: Int, level: Int = 0) {
            blocks += SourceBlock(blocks.size, kind, text, level, page)
        }
        for (page in pages) {
            val pageLines = retained.filter { it.page == page.number }
            val gaps = pageLines.zipWithNext().map { (a, b) -> b.y - a.y }.filter { it > 0 }
            val spacing = median(gaps).takeIf { it > 0 }?.coerceAtMost(bodySize * 1.8f) ?: bodySize * 1.2f
            val paragraph = StringBuilder()
            var previous: PdfLine? = null
            fun flush() {
                if (paragraph.isNotEmpty()) emit(BlockKind.PARAGRAPH, paragraph.toString(), page.number)
                paragraph.setLength(0)
            }
            for (line in pageLines) {
                val text = line.text.trim().replace(Regex("\\s+"), " ")
                when {
                    isEquation(line.text) -> {
                        flush()
                        emit(BlockKind.EQUATION, line.text, page.number)
                        previous = null
                    }
                    isHeading(line) -> {
                        flush()
                        emit(BlockKind.HEADING, text, page.number, (headingSizes.indexOfFirst { abs(it - line.fontSize) < .5f } + 1).coerceIn(1, 6))
                        previous = null
                    }
                    else -> {
                        val last = previous
                        if (last != null) {
                            val gap = line.y - last.y
                            val sentenceBreak = last.text.trimEnd().lastOrNull() in listOf('.', '!', '?') &&
                                text.firstOrNull()?.isUpperCase() == true && gap > spacing * 1.1f
                            if (gap > spacing * 1.45f || abs(line.x - last.x) > bodySize * 1.2f || sentenceBreak) flush()
                        }
                        if (paragraph.isNotEmpty()) {
                            val hyphenated = paragraph.last() in listOf('-', '\u00ad') &&
                                paragraph.getOrNull(paragraph.length - 2)?.isLetter() == true && text.firstOrNull()?.isLowerCase() == true
                            if (hyphenated) paragraph.setLength(paragraph.length - 1) else paragraph.append(' ')
                        }
                        paragraph.append(text)
                        previous = line
                    }
                }
            }
            flush()
            if (page.hasImages) emit(BlockKind.FIGURE, "Figure on page ${page.number}", page.number)
        }
        if (blocks.isEmpty()) throw DocumentException("This PDF contains no readable text. Try another copy.")
        val sections = outline.mapNotNull { bookmark ->
            // Prefer the heading the bookmark names; a bookmark often points mid-page.
            val heading = blocks.firstOrNull { it.page == bookmark.page && it.kind == BlockKind.HEADING && it.text.equals(bookmark.title.trim(), ignoreCase = true) }
            (heading ?: blocks.firstOrNull { (it.page ?: 0) >= bookmark.page })?.let { ParsedSection(bookmark.title, bookmark.level, it.index) }
        }.distinct().sortedBy { it.startBlock }.ifEmpty {
            blocks.filter { it.kind == BlockKind.HEADING && it.headingLevel <= 2 }
                .map { ParsedSection(it.text, it.headingLevel, it.index) }
        }
        val emptyImagePages = pages.filter { page -> page.hasImages && retained.none { it.page == page.number } }.map { it.number }
        val warnings = if (emptyImagePages.isEmpty()) emptyList() else {
            listOf("Pages ${pageRanges(emptyImagePages)} contain no extractable text (likely scanned).")
        }
        return ParsedDocument(title, author, BookFormat.PDF, blocks, sections, pages.size, warnings)
    }

    private fun isEquation(text: String): Boolean {
        val significant = text.filterNot(Char::isWhitespace)
        if (significant.length < 3) return false
        val letters = significant.count(Char::isLetter)
        val math = significant.count { Character.getType(it) == Character.MATH_SYMBOL.toInt() || it in "^×÷∑∫√" }
        return letters <= significant.length * .4 || (math > 0 && math >= significant.length * .25)
    }

    private fun pageRanges(pages: List<Int>): String {
        val ranges = mutableListOf<String>()
        var first = pages.first()
        var last = first
        fun add() { ranges += if (first == last) "$first" else "$first-$last" }
        for (page in pages.drop(1)) {
            if (page == last + 1) last = page else { add(); first = page; last = page }
        }
        add()
        return ranges.joinToString(", ")
    }
}

internal fun median(values: List<Float>): Float {
    if (values.isEmpty()) return 0f
    val sorted = values.sorted()
    return if (sorted.size % 2 == 0) (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2 else sorted[sorted.size / 2]
}
