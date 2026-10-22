package com.livingbook.data

import com.livingbook.data.db.AnnotationEntity
import com.livingbook.data.db.AppDatabase
import com.livingbook.data.db.BlockEntity
import com.livingbook.data.db.BookEntity
import com.livingbook.data.db.GuidedEntity
import com.livingbook.data.db.LibraryRow
import com.livingbook.data.db.ProgressEntity
import com.livingbook.data.db.SectionEntity
import com.livingbook.data.db.TopicEntity
import com.livingbook.model.GuidedContent
import com.livingbook.model.Granularity
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import java.io.File

/** Books, their structure, reading progress, annotations and cached guided renditions. All local. */
class ReadingRepository(private val db: AppDatabase) {

    fun observeLibrary(): Flow<List<LibraryRow>> = db.books().observeLibrary()

    suspend fun book(bookId: String): BookEntity? = db.books().get(bookId)

    suspend fun topics(bookId: String, granularity: Granularity): List<TopicEntity> =
        db.content().topics(bookId, granularity)

    suspend fun sections(bookId: String): List<SectionEntity> = db.content().sections(bookId)

    suspend fun blocks(bookId: String, from: Int, to: Int): List<BlockEntity> = db.content().blocks(bookId, from, to)

    suspend fun blocksAt(bookId: String, indices: List<Int>): List<BlockEntity> =
        if (indices.isEmpty()) emptyList() else db.content().blocksAt(bookId, indices)

    /** Full-text search over the book. [terms] are plain words; they are sanitized into an FTS expression here. */
    suspend fun search(bookId: String, terms: List<String>, limit: Int): List<BlockEntity> {
        val query = terms.map { it.lowercase().filter(Char::isLetterOrDigit) }
            .filter { it.length > 2 }
            .distinct()
            .joinToString(" OR ") { "$it*" }
        if (query.isEmpty()) return emptyList()
        return db.content().search(bookId, query, limit)
    }

    suspend fun progress(bookId: String): ProgressEntity? = db.progress().get(bookId)

    suspend fun saveProgress(progress: ProgressEntity) = db.progress().save(progress)

    suspend fun markOpened(bookId: String) = db.books().markOpened(bookId, System.currentTimeMillis())

    fun observeAnnotations(bookId: String): Flow<List<AnnotationEntity>> = db.annotations().observe(bookId)

    suspend fun addAnnotation(annotation: AnnotationEntity): Long = db.annotations().insert(annotation)

    suspend fun deleteAnnotation(id: Long) = db.annotations().delete(id)

    suspend fun guided(bookId: String, startBlock: Int, endBlock: Int): GuidedContent? =
        db.content().guided(bookId, startBlock, endBlock)?.let { json.decodeFromString(GuidedContent.serializer(), it.contentJson) }

    suspend fun saveGuided(bookId: String, startBlock: Int, endBlock: Int, content: GuidedContent) =
        db.content().saveGuided(
            GuidedEntity(bookId, startBlock, endBlock, json.encodeToString(GuidedContent.serializer(), content), System.currentTimeMillis())
        )

    /** Deletes the book, its private file copy and everything attached to it (cascade). */
    suspend fun deleteBook(bookId: String) {
        val book = db.books().get(bookId) ?: return
        db.books().delete(bookId)
        File(book.filePath).delete()
    }

    private val json = Json { ignoreUnknownKeys = true }
}
