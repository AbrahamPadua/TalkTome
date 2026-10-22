package com.livingbook.doc

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.livingbook.data.db.AppDatabase
import com.livingbook.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DocumentImporterTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var provider: BookProvider
    private val uri = Uri.parse("content://import-test/book")
    private val segmenter = HeadingAwareSegmenter()

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        provider = BookProvider().apply {
            source = File(this@DocumentImporterTest.context.cacheDir, "source.epub")
            attachInfo(this@DocumentImporterTest.context, ProviderInfo().apply { authority = "import-test" })
        }
        ShadowContentResolver.registerProviderInternal("import-test", provider)
    }

    @After fun teardown() {
        db.close()
        File(context.filesDir, "books").listFiles()?.forEach { it.delete() }
        provider.source.delete()
    }

    private fun epub() {
        ZipOutputStream(provider.source.outputStream()).use { zip ->
            mapOf(
                "META-INF/container.xml" to "<container><rootfile full-path='book.opf'/></container>",
                "book.opf" to "<package><manifest><item id='a' href='a.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='a'/></spine></package>",
                "a.xhtml" to "<html><body><h1>Chapter</h1><p>Book text for reading.</p></body></html>",
            ).forEach { (path, text) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
            }
        }
    }

    private fun importer(parser: DocumentParser, splitter: TopicSegmenter = segmenter) = DocumentImporter(context, db, listOf(parser), splitter)
    private fun content(file: File) = ParsedDocument(file.nameWithoutExtension, null, BookFormat.PDF,
        listOf(SourceBlock(0, BlockKind.PARAGRAPH, "Readable content.")), emptyList(), 1, listOf("First warning.", "Second warning."))

    @Test fun importsRealEpubWithDisplayNameFallbackAndAllGranularities() = runBlocking {
        epub()
        provider.displayName = "Original book.epub"
        provider.mime = "application/octet-stream"
        val id = importer(EpubDocumentParser()).import(uri)
        val book = db.books().get(id)!!
        assertEquals("Original book", book.title)
        assertEquals(BookFormat.EPUB, book.format)
        assertEquals(2, book.blockCount)
        assertTrue(File(book.filePath).isFile)
        assertEquals("$id.epub", File(book.filePath).name)
        assertEquals(listOf(0, 1), db.content().blocks(id, 0, 10).map { it.idx })
        assertEquals("Chapter", db.content().sections(id).single().title)
        Granularity.entries.forEach { granularity ->
            val topic = db.content().topics(id, granularity).single()
            assertEquals(0, topic.startBlock)
            assertEquals(1, topic.endBlock)
        }
    }

    @Test fun parsesOnIoAndStoresWarningsWithPdfMagicDespiteWrongHints() = runBlocking {
        provider.source.writeText("%PDF-synthetic")
        provider.displayName = "Wrong extension.epub"
        provider.mime = "application/epub+zip"
        val caller = Thread.currentThread()
        val parser = object : DocumentParser {
            override fun canParse(fileName: String, mimeType: String?) = fileName.endsWith(".pdf")
            override suspend fun parse(file: File): ParsedDocument {
                assertNotSame(caller, Thread.currentThread())
                return content(file)
            }
        }
        val id = importer(parser).import(uri)
        val book = db.books().get(id)!!
        assertTrue(book.filePath.endsWith(".pdf"))
        assertEquals("First warning.\nSecond warning.", book.warnings)
    }

    @Test fun rejectsInvalidMagicAndRemovesCopiesAfterParserFailures() = runBlocking {
        provider.source.writeText("Not a supported file")
        val unsupported = assertThrows(DocumentException::class.java) { runBlocking { importer(EpubDocumentParser()).import(uri) } }
        assertEquals("Only PDF and EPUB files are supported.", unsupported.userMessage)
        val failures = listOf(DocumentException("Useful parser error."), IllegalStateException("Internal detail"), CancellationException("Cancelled"))
        for (failure in failures) {
            provider.source.writeText("PKbroken archive")
            val parser = object : DocumentParser {
                override fun canParse(fileName: String, mimeType: String?) = true
                override suspend fun parse(file: File): ParsedDocument = throw failure
            }
            if (failure is CancellationException) {
                assertThrows(CancellationException::class.java) { runBlocking { importer(parser).import(uri) } }
            } else {
                val error = assertThrows(DocumentException::class.java) { runBlocking { importer(parser).import(uri) } }
                assertEquals(if (failure is DocumentException) failure.userMessage else "This file couldn't be read. It may be damaged.", error.userMessage)
            }
            assertTrue(File(context.filesDir, "books").listFiles().orEmpty().isEmpty())
        }
        assertTrue(db.books().observeLibrary().first().isEmpty())
    }

    @Test fun databaseFailureRollsBackContentAndDeletesPrivateCopy() = runBlocking {
        provider.source.writeText("%PDF-synthetic")
        val parser = object : DocumentParser {
            override fun canParse(fileName: String, mimeType: String?) = true
            override suspend fun parse(file: File) = content(file)
        }
        val duplicateTopics = object : TopicSegmenter {
            override fun segment(document: ParsedDocument, granularity: Granularity) = List(2) { Topic(0, "Topic", 0, 0, null) }
        }
        assertThrows(DocumentException::class.java) { runBlocking { importer(parser, duplicateTopics).import(uri) } }
        assertTrue(db.books().observeLibrary().first().isEmpty())
        assertTrue(File(context.filesDir, "books").listFiles().orEmpty().isEmpty())
    }

    class BookProvider : ContentProvider() {
        lateinit var source: File
        var displayName = "Source.epub"
        var mime: String? = "application/epub+zip"
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = mime
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(displayName)) }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
