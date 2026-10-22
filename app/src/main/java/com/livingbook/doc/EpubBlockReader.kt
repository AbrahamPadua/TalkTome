package com.livingbook.doc

import com.livingbook.model.BlockKind
import com.livingbook.model.SourceBlock
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/** Walks XHTML once, retaining semantic blocks and fragment anchors without duplicating nested text. */
internal class EpubBlockReader(
    private val href: String,
    private val blocks: MutableList<SourceBlock>,
    private val anchors: MutableMap<String, Int>,
) {
    private val containers = setOf("p", "div", "section", "article", "main", "aside", "header", "footer", "ul", "ol", "dl", "dt", "dd", "address")
    fun read(document: Document) {
        val body = document.getAllElements().firstOrNull { it.localTag() == "body" } ?: document
        walk(body)
    }

    private fun anchor(element: Element) {
        element.id().takeIf(String::isNotBlank)?.let { anchors.putIfAbsent("$href#$it", blocks.size) }
    }

    private fun emit(kind: BlockKind, text: String, element: Element, level: Int = 0) {
        val normalized = text.replace(Regex("[\\s\\u00a0]+"), " ").trim()
        if (normalized.isEmpty()) return
        val id = (sequenceOf(element) + element.parents().asSequence()).firstOrNull { it.id().isNotBlank() }?.id()
        blocks += SourceBlock(blocks.size, kind, normalized, level, location = href + (id?.let { "#$it" } ?: ""))
    }

    private fun walk(element: Element) {
        anchor(element)
        val tag = element.localTag()
        when {
            tag in setOf("head", "script", "style") -> return
            "footnote" in element.attr("epub:type").split(Regex("\\s+")) -> flow(element, BlockKind.FOOTNOTE)
            tag.matches(Regex("h[1-6]")) -> flow(element, BlockKind.HEADING, tag.last().digitToInt())
            tag == "p" -> flow(element, BlockKind.PARAGRAPH)
            tag == "li" -> flow(element, BlockKind.LIST_ITEM)
            tag == "blockquote" -> flow(element, BlockKind.QUOTE)
            tag == "pre" -> flow(element, BlockKind.CODE)
            tag == "figcaption" || tag == "caption" -> flow(element, BlockKind.CAPTION)
            tag == "math" -> {
                markDescendants(element)
                emit(if (isDisplayMath(element)) BlockKind.EQUATION else BlockKind.PARAGRAPH,
                    element.attr("alttext").ifBlank { element.text() }, element)
            }
            tag == "table" -> {
                element.getAllElements().filter { candidate ->
                    candidate.localTag() != "caption" && candidate.parents().none { it.localTag() == "caption" }
                }.forEach { anchor(it) }
                val rows = element.getAllElements().filter { it.localTag() == "tr" }
                    .filter { row -> row.parents().firstOrNull { it.localTag() == "table" } == element }
                    .map { row -> row.children().filter { it.localTag() in setOf("td", "th") }.joinToString(" | ") { readableText(it) } }
                // Keep row boundaries for tables, unlike ordinary prose whitespace.
                val text = rows.joinToString("\n") { it.replace(Regex("[\\s\\u00a0]+"), " ").trim() }.trim()
                if (text.isNotEmpty()) {
                    emit(BlockKind.TABLE, text, element)
                    blocks[blocks.lastIndex] = blocks.last().copy(text = text)
                }
                element.children().filter { it.localTag() == "caption" }.forEach { walk(it) }
            }
            tag in setOf("figure", "img", "svg") -> {
                val images = element.getAllElements().filter { it.localTag() in setOf("img", "svg") }
                val descriptions = images.map { image ->
                    image.attr("alt").ifBlank { image.attr("aria-label") }.ifBlank {
                        image.getAllElements().firstOrNull { it.localTag() in setOf("title", "desc") }?.text().orEmpty()
                    }
                }.filter(String::isNotBlank).distinct().joinToString("; ")
                    .ifBlank { element.attr("aria-label") }
                images.forEach { markDescendants(it) }
                emit(BlockKind.FIGURE, if (descriptions.isBlank()) "Figure (no description)" else "Figure: $descriptions", element)
                if (tag == "figure") flow(element, BlockKind.PARAGRAPH, inFigure = true)
            }
            else -> flow(element, BlockKind.PARAGRAPH)
        }
    }

    private fun markDescendants(element: Element) = element.getAllElements().forEach { anchor(it) }

    private fun isDisplayMath(math: Element): Boolean {
        if (math.attr("display").equals("block", ignoreCase = true)) return true
        val block = math.parents().firstOrNull { it.isBlock || it.localTag() in containers } ?: return false
        fun meaningful(node: org.jsoup.nodes.Node): Boolean = when (node) {
            math -> false
            is TextNode -> node.wholeText.isNotBlank()
            is Element -> when (node.localTag()) {
                "head", "script", "style", "br" -> false
                "math", "img", "svg", "table" -> true
                else -> node.childNodes().any(::meaningful)
            }
            else -> false
        }
        return !block.childNodes().any(::meaningful)
    }

    private fun readableText(element: Element): String = buildString {
        fun appendNode(node: org.jsoup.nodes.Node) {
            when (node) {
                is TextNode -> append(node.wholeText)
                is Element -> when (node.localTag()) {
                    "img" -> append(" Figure: " + node.attr("alt").ifBlank { "(no description)" } + " ")
                    "math" -> append(" " + node.attr("alttext").ifBlank { node.text() } + " ")
                    "script", "style" -> Unit
                    else -> {
                        if (node.isBlock) append(' ')
                        node.childNodes().forEach { appendNode(it) }
                        if (node.isBlock) append(' ')
                    }
                }
            }
        }
        appendNode(element)
    }.trim()

    private fun flow(element: Element, kind: BlockKind, level: Int = 0, inFigure: Boolean = false) {
        val text = StringBuilder()
        fun flush() {
            emit(kind, text.toString(), element, level)
            text.setLength(0)
        }
        fun visit(parent: Element) {
            for (node in parent.childNodes()) when (node) {
                is TextNode -> text.append(node.wholeText)
                is Element -> {
                    val tag = node.localTag()
                    if (tag in setOf("head", "script", "style")) continue
                    if (inFigure && tag in setOf("img", "svg")) continue
                    if (tag == "math" && !isDisplayMath(node)) {
                        markDescendants(node)
                        text.append(node.attr("alttext").ifBlank { node.text() })
                        continue
                    }
                    val foldedParagraph = tag == "p" && kind in setOf(BlockKind.LIST_ITEM, BlockKind.QUOTE, BlockKind.FOOTNOTE)
                    val special = tag in setOf("img", "figure", "svg", "math", "table", "figcaption", "pre", "li", "blockquote") ||
                        tag.matches(Regex("h[1-6]")) || "footnote" in node.attr("epub:type").split(Regex("\\s+"))
                    val block = node.isBlock || tag in containers
                    if (special || (block && !foldedParagraph && kind == BlockKind.PARAGRAPH)) {
                        flush()
                        walk(node)
                    } else {
                        anchor(node)
                        if (block || tag == "br") text.append(' ')
                        visit(node)
                        if (block) text.append(' ')
                    }
                }
            }
        }
        visit(element)
        flush()
    }
}
