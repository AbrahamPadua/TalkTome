package com.livingbook.doc

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.livingbook.data.db.AppDatabase
import com.livingbook.data.db.BlockEntity
import com.livingbook.data.db.BookEntity
import com.livingbook.data.db.SectionEntity
import com.livingbook.data.db.TopicEntity
import com.livingbook.model.Granularity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Import pipeline: copy the picked file into app storage, validate, parse, segment at every granularity, store.
 * Returns the new book id.
 */
class DocumentImporter(
    private val context: Context,
    private val db: AppDatabase,
    private val parsers: List<DocumentParser>,
    private val segmenter: TopicSegmenter,
) {
    /** @throws DocumentException with a user-facing message. */
    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        var copiedFile: File? = null
        try {
            val resolver = context.contentResolver
            val mimeType = resolver.getType(uri)
            val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }?.takeIf(String::isNotBlank) ?: uri.lastPathSegment.orEmpty()
            val id = UUID.randomUUID().toString()
            val directory = File(context.filesDir, "books")
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw DocumentException("There isn't enough storage to import this book. Free some space and try again.")
            }
            var parser: DocumentParser? = null
            resolver.openInputStream(uri)?.buffered()?.use { input ->
                input.mark(5)
                val header = ByteArray(5)
                var length = 0
                while (length < header.size) {
                    val read = input.read(header, length, header.size - length)
                    if (read < 0) break
                    if (read > 0) length += read
                }
                input.reset()
                val (extension, detectedMime) = when {
                    length == 5 && header.contentEquals("%PDF-".toByteArray(Charsets.US_ASCII)) -> "pdf" to "application/pdf"
                    length >= 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() -> "epub" to "application/epub+zip"
                    else -> throw DocumentException("Only PDF and EPUB files are supported.")
                }
                // Magic bytes choose the format even when a provider supplies an absent or misleading MIME type.
                parser = parsers.firstOrNull { it.canParse("$id.$extension", detectedMime) }
                    ?: throw DocumentException("Only PDF and EPUB files are supported.")
                // Query the original hints too, but never let them override the verified format.
                val hinted = parsers.firstOrNull { it.canParse(displayName, mimeType) }
                if (hinted != null && hinted.canParse("$id.$extension", detectedMime)) parser = hinted
                copiedFile = File(directory, "$id.$extension")
                copiedFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            } ?: throw DocumentException("This file can't be opened. Choose it again and allow access to import it.")
            val file = checkNotNull(copiedFile)
            val document = checkNotNull(parser).parse(file)
            if (document.blocks.isEmpty()) throw DocumentException("This book contains no readable text. Try another copy.")
            ensureActive()
            val title = if (document.title == file.nameWithoutExtension) {
                displayName.substringBeforeLast('.', displayName).ifBlank { "Untitled book" }
            } else document.title
            val book = BookEntity(id, title, document.author, document.format, file.absolutePath,
                System.currentTimeMillis(), null, document.blocks.size, document.pageCount, document.warnings.joinToString("\n"))
            val blocks = document.blocks.map { BlockEntity(bookId = id, idx = it.index, kind = it.kind,
                text = it.text, headingLevel = it.headingLevel, page = it.page, location = it.location) }
            val sections = document.sections.mapIndexed { index, section -> SectionEntity(id, index, section.title, section.level, section.startBlock) }
            val topics = Granularity.entries.flatMap { granularity ->
                segmenter.segment(document, granularity).map {
                    TopicEntity(id, granularity, it.index, it.title, it.startBlock, it.endBlock, it.sectionTitle)
                }
            }
            db.books().insertBookWithContent(book, blocks, sections, topics)
            id
        } catch (e: Exception) {
            copiedFile?.delete()
            when (e) {
                is CancellationException -> throw e
                is DocumentException -> throw e
                else -> throw DocumentException("This file couldn't be read. It may be damaged.", e)
            }
        }
    }
}
