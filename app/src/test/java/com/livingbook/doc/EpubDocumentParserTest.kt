package com.livingbook.doc

import com.livingbook.model.BlockKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubDocumentParserTest {
    @get:Rule val temporary = TemporaryFolder()
    private val parser = EpubDocumentParser()

    private fun epub(
        body: String = "<h1 id='start'>Chapter</h1><p>Readable text.</p>",
        nav: String? = "<nav epub:type='toc'><ol><li><a href='chapter.xhtml#start'>First chapter</a></li></ol></nav>",
        ncx: String? = null,
        extra: Map<String, String> = emptyMap(),
        spine: String = "<itemref idref='chapter'/>",
        items: String = "",
    ): File {
        val entries = linkedMapOf(
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>",
            "OPS/book.opf" to """<package xmlns:dc="http://purl.org/dc/elements/1.1/">
                <metadata><dc:title>Test book</dc:title><dc:creator>Author</dc:creator></metadata><manifest>
                <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                ${if (nav != null) "<item id='nav' href='nav.xhtml' media-type='application/xhtml+xml' properties='nav'/>" else ""}
                ${if (ncx != null) "<item id='ncx' href='toc.ncx' media-type='application/x-dtbncx+xml'/>" else ""}
                $items</manifest><spine toc="ncx">$spine</spine></package>""",
            "OPS/chapter.xhtml" to "<html xmlns:epub='http://www.idpf.org/2007/ops'><body>$body</body></html>",
        )
        if (nav != null) entries["OPS/nav.xhtml"] = "<html xmlns:epub='http://www.idpf.org/2007/ops'><body>$nav</body></html>"
        if (ncx != null) entries["OPS/toc.ncx"] = ncx
        entries.putAll(extra)
        return temporary.newFile("book-${System.nanoTime()}.epub").also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach { (name, text) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
    }

    @Test fun epub3MapsNestedTocFragmentsAndSpineOrder() = runBlocking {
        val book = parser.parse(epub(
            body = "<h1 id='start'>Chapter</h1><section id='sub'><p>Second part.</p></section>",
            nav = "<nav epub:type='landmarks'><a href='chapter.xhtml'>Wrong</a></nav>" +
                "<nav epub:type='toc'><ol><li><a href='chapter.xhtml#start'>First</a><ol><li><a href='chapter.xhtml#sub'>Second</a></li></ol></li></ol></nav>",
            items = "<item id='two' href='two.xhtml' media-type='application/xhtml+xml'/>",
            spine = "<itemref idref='two'/><itemref idref='chapter'/>",
            extra = mapOf("OPS/two.xhtml" to "<html><body><p>Earlier in spine.</p></body></html>"),
        ))
        assertEquals("Test book", book.title)
        assertEquals("Author", book.author)
        assertEquals(listOf("Earlier in spine.", "Chapter", "Second part."), book.blocks.map { it.text })
        assertEquals(listOf(0, 1, 2), book.blocks.map { it.index })
        assertEquals(listOf(1, 2), book.sections.map { it.startBlock })
        assertEquals(listOf(1, 2), book.sections.map { it.level })
        assertEquals("OPS/chapter.xhtml#sub", book.blocks.last().location)
    }

    @Test fun epub2UsesNcxAndDecodesRelativeFragments() = runBlocking {
        val book = parser.parse(epub(nav = null, body = "<div id='a b'><p>Contents.</p></div>", ncx =
            "<ncx><navMap><navPoint><navLabel><text>Chapter from NCX</text></navLabel><content src='./chapter.xhtml#a%20b'/></navPoint></navMap></ncx>"))
        assertEquals("Chapter from NCX", book.sections.single().title)
        assertEquals(0, book.sections.single().startBlock)
    }

    @Test fun preservesTablesFiguresMathFootnotesAndLooseTextWithoutDuplicates() = runBlocking {
        val book = parser.parse(epub(nav = null, body = """
            <section id="section">Loose <em>opening</em> text.
            <ul><li><p>List paragraph.</p><p>More list text.</p></li><li>Next item.</li></ul>
            <blockquote><p>A quotation.</p></blockquote><pre>val x = 1</pre>
            <table><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr><caption id="tc">Table caption.</caption></table>
            <figure><img alt="A useful diagram"/><figcaption id="fc">Figure caption.</figcaption></figure>
            <p>Before <math alttext="x squared"><mi>x</mi></math> after.</p>
            <aside epub:type="footnote"><p>Footnote text.</p></aside>
            <svg><title>Vector drawing</title><path/></svg><img/>
            </section>"""))
        assertEquals(listOf(BlockKind.PARAGRAPH, BlockKind.LIST_ITEM, BlockKind.LIST_ITEM, BlockKind.QUOTE,
            BlockKind.CODE, BlockKind.TABLE, BlockKind.CAPTION, BlockKind.FIGURE, BlockKind.CAPTION,
            BlockKind.PARAGRAPH, BlockKind.EQUATION, BlockKind.PARAGRAPH, BlockKind.FOOTNOTE, BlockKind.FIGURE, BlockKind.FIGURE),
            book.blocks.map { it.kind })
        assertEquals("List paragraph. More list text.", book.blocks[1].text)
        assertEquals("A | B\n1 | 2", book.blocks[5].text)
        assertEquals("Figure: A useful diagram", book.blocks[7].text)
        assertEquals("x squared", book.blocks[10].text)
        assertEquals("Figure: Vector drawing", book.blocks[13].text)
        assertEquals("Figure (no description)", book.blocks.last().text)
        assertEquals(book.blocks.indices.toList(), book.blocks.map { it.index })
    }

    @Test fun usesHeadingsWithoutTocAndRetainsNonLinearContent() = runBlocking {
        val book = parser.parse(epub(nav = null, spine = "<itemref idref='chapter' linear='no'/>"))
        assertEquals("Chapter", book.sections.single().title)
        assertEquals(2, book.blocks.size)
    }

    @Test fun skipsClearlyAuxiliaryNonLinearNav() = runBlocking {
        val book = parser.parse(epub(spine = "<itemref idref='nav' linear='no'/><itemref idref='chapter'/>"))
        assertEquals(2, book.blocks.size)
    }

    @Test fun rejectsRightsAndNonFontEncryption() = runBlocking {
        for (extra in listOf(
            mapOf("META-INF/rights.xml" to "<rights/>"),
            mapOf("META-INF/encryption.xml" to "<encryption><EncryptedData><EncryptionMethod Algorithm='aes'/></EncryptedData></encryption>"),
        )) {
            val error = assertThrows(DocumentException::class.java) { runBlocking { parser.parse(epub(extra = extra)) } }
            assertEquals("This EPUB is DRM-protected. Living Book can only open DRM-free books.", error.userMessage)
        }
    }

    @Test fun allowsFontObfuscation() = runBlocking {
        val book = parser.parse(epub(extra = mapOf("META-INF/encryption.xml" to
            "<encryption><EncryptedData><EncryptionMethod Algorithm='http://www.idpf.org/2008/embedding'/></EncryptedData></encryption>")))
        assertEquals(2, book.blocks.size)
    }

    @Test fun rejectsCorruptZipMissingPackageAndNoText() {
        val corrupt = temporary.newFile("broken.epub").apply { writeText("not a zip") }
        val missing = epub(extra = mapOf("META-INF/container.xml" to "<container/>"))
        val empty = epub(body = "<p> </p><img/>", nav = null)
        for (file in listOf(corrupt, missing, empty)) {
            assertThrows(DocumentException::class.java) { runBlocking { parser.parse(file) } }
        }
    }

    @Test fun mapsInlineAnchorsAndWarnsAboutMissingTargets() = runBlocking {
        val book = parser.parse(epub(body = "<p>Start <span id='start'>inside</span> paragraph.</p>",
            nav = "<nav epub:type='toc'><ol><li><a href='chapter.xhtml#start'>Inline</a></li><li><a href='chapter.xhtml#missing'>Missing</a></li></ol></nav>"))
        assertEquals(0, book.sections.single().startBlock)
        assertEquals(1, book.warnings.size)
    }
}
