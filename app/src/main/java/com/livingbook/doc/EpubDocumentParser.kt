package com.livingbook.doc

import com.livingbook.model.BlockKind
import com.livingbook.model.BookFormat
import com.livingbook.model.ParsedDocument
import com.livingbook.model.ParsedSection
import com.livingbook.model.SourceBlock
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/** DRM-free EPUB 2/3 parsing: container.xml -> OPF spine -> XHTML blocks. Rejects DRM-protected books. */
class EpubDocumentParser : DocumentParser {
    override fun canParse(fileName: String, mimeType: String?): Boolean =
        mimeType == "application/epub+zip" || fileName.endsWith(".epub", ignoreCase = true)

    override suspend fun parse(file: File): ParsedDocument = try {
        ZipFile(file).use { zip -> readBook(zip, file.nameWithoutExtension) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: DocumentException) {
        throw e
    } catch (e: Exception) {
        throw DocumentException("This EPUB couldn't be read. It may be damaged. Try another DRM-free copy.", e)
    }

    private fun readBook(zip: ZipFile, fallbackTitle: String): ParsedDocument {
        checkDrm(zip)
        val container = zip.xml("META-INF/container.xml")
        val opfPath = container.getAllElements().firstOrNull { it.localTag() == "rootfile" }
            ?.attr("full-path")?.takeIf(String::isNotBlank)
            ?: throw DocumentException("This EPUB is missing its book package (OPF). Try another copy.")
        val opf = zip.xml(opfPath)
        val metadata = opf.getAllElements().firstOrNull { it.localTag() == "metadata" }
        val title = metadata?.getAllElements()?.firstOrNull { it.localTag() == "title" }?.text()
            ?.takeIf(String::isNotBlank) ?: fallbackTitle
        val author = metadata?.getAllElements()?.filter { it.localTag() == "creator" }
            ?.map { it.text() }?.filter(String::isNotBlank)?.joinToString(", ")?.takeIf(String::isNotBlank)
        val manifest = opf.getAllElements().filter { it.localTag() == "item" }.associate {
            it.attr("id") to Item(epubTarget(opfPath, it.attr("href")), it.attr("media-type"), it.attr("properties").split(Regex("\\s+")))
        }
        val coverPaths = opf.getAllElements().filter { it.localTag() == "reference" && it.attr("type") == "cover" }
            .map { epubTarget(opfPath, it.attr("href")).substringBefore('#') }.toSet()
        val toc = readToc(zip, opf, manifest)
        val blocks = mutableListOf<SourceBlock>()
        val anchors = mutableMapOf<String, Int>()
        for (ref in opf.getAllElements().filter { it.localTag() == "itemref" }) {
            val item = manifest[ref.attr("idref")]
                ?: throw DocumentException("This EPUB has a missing reading section. Try another copy.")
            val path = item.path.substringBefore('#')
            val clearlyAuxiliary = "nav" in item.properties || "cover-image" in item.properties || path in coverPaths
            if (ref.attr("linear").equals("no", true) && clearlyAuxiliary) continue
            if (zip.getEntry(path) == null) throw DocumentException("This EPUB is missing $path. Try another copy.")
            val start = blocks.size
            when {
                item.mediaType.startsWith("image/") -> blocks += SourceBlock(blocks.size, BlockKind.FIGURE, "Figure (no description)", location = path)
                item.mediaType in setOf("application/xhtml+xml", "text/html", "") -> {
                    val document = zip.xml(path, html = item.mediaType == "text/html")
                    EpubBlockReader(path, blocks, anchors).read(document)
                }
                else -> throw DocumentException("This EPUB contains an unsupported reading section. Try another copy.")
            }
            anchors.entries.removeAll { it.key.startsWith("$path#") && it.value >= blocks.size }
            if (blocks.size > start) anchors[path] = start
        }
        if (blocks.none { it.kind != BlockKind.FIGURE && it.text.any(Char::isLetterOrDigit) }) {
            throw DocumentException("This EPUB contains no readable text. Try another copy.")
        }
        val sections = toc.mapNotNull { entry ->
            anchors[entry.path]?.takeIf { it in blocks.indices }?.let { ParsedSection(entry.title, entry.level, it) }
        }.distinct().sortedBy { it.startBlock }.ifEmpty {
            blocks.filter { it.kind == BlockKind.HEADING && it.headingLevel <= 2 }
                .map { ParsedSection(it.text, it.headingLevel, it.index) }
        }
        val warnings = if (toc.any { anchors[it.path] !in blocks.indices }) {
            listOf("Some table of contents entries have no readable target. Use the detected text to navigate those sections.")
        } else emptyList()
        return ParsedDocument(title, author, BookFormat.EPUB, blocks, sections, null, warnings)
    }

    private fun readToc(zip: ZipFile, opf: Document, manifest: Map<String, Item>): List<TocEntry> {
        val navItem = manifest.values.firstOrNull { "nav" in it.properties }
        if (navItem != null) {
            val navDoc = zip.xml(navItem.path)
            val nav = navDoc.getAllElements().firstOrNull {
                it.localTag() == "nav" && ("toc" in it.attr("epub:type").split(Regex("\\s+")) || it.attr("role") == "doc-toc")
            }
            if (nav != null) return nav.getAllElements().filter { it.localTag() == "a" && it.hasAttr("href") }
                .filter { it.text().isNotBlank() }.map {
                    TocEntry(epubTarget(navItem.path, it.attr("href")), it.text(),
                        it.parents().takeWhile { parent -> parent != nav }.count { parent -> parent.localTag() == "ol" }.coerceAtLeast(1))
                }
        }
        val ncxId = opf.getAllElements().firstOrNull { it.localTag() == "spine" }?.attr("toc")
        val ncx = manifest[ncxId] ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
            ?: return emptyList()
        return zip.xml(ncx.path).getAllElements().filter { it.localTag() == "navpoint" }.mapNotNull { point ->
            val label = point.children().firstOrNull { it.localTag() == "navlabel" }?.text().orEmpty()
            val src = point.children().firstOrNull { it.localTag() == "content" }?.attr("src").orEmpty()
            if (label.isBlank() || src.isBlank()) null else TocEntry(epubTarget(ncx.path, src), label,
                point.parents().count { it.localTag() == "navpoint" } + 1)
        }
    }

    private fun checkDrm(zip: ZipFile) {
        val message = "This EPUB is DRM-protected. Living Book can only open DRM-free books."
        if (zip.getEntry("META-INF/rights.xml") != null) throw DocumentException(message)
        if (zip.getEntry("META-INF/encryption.xml") == null) return
        val allowed = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")
        val encrypted = zip.xml("META-INF/encryption.xml").getAllElements().filter { it.localTag() == "encrypteddata" }
        if (encrypted.any { data ->
            val methods = data.getAllElements().filter { it.localTag() == "encryptionmethod" }
            methods.isEmpty() || methods.any { it.attr("Algorithm") !in allowed }
        }) throw DocumentException(message)
    }

    private fun ZipFile.xml(path: String, html: Boolean = false): Document {
        val entry = getEntry(path) ?: throw DocumentException("This EPUB is missing $path. Try another copy.")
        return getInputStream(entry).use { Jsoup.parse(it, null, "", if (html) Parser.htmlParser() else Parser.xmlParser()) }
    }

    private data class Item(val path: String, val mediaType: String, val properties: List<String>)
    private data class TocEntry(val path: String, val title: String, val level: Int)
}

internal fun Element.localTag(): String = tagName().substringAfter(':').lowercase()

internal fun epubTarget(base: String, href: String): String {
    val parts = href.split('#', limit = 2)
    fun decode(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    val relative = decode(parts[0].substringBefore('?'))
    val path = if (relative.isEmpty()) base.substringBefore('#') else {
        val result = mutableListOf<String>()
        val joined = if (relative.startsWith('/')) relative else base.substringBeforeLast('/', "") + "/" + relative
        for (part in joined.split('/')) when (part) {
            "", "." -> Unit
            ".." -> if (result.isNotEmpty()) result.removeAt(result.lastIndex)
            else -> result += part
        }
        result.joinToString("/")
    }
    return path + if (parts.size == 2 && parts[1].isNotEmpty()) "#" + decode(parts[1]) else ""
}
