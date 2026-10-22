package com.livingbook.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import com.livingbook.model.Granularity
import kotlinx.coroutines.flow.Flow

data class LibraryRow(
    val id: String,
    val title: String,
    val author: String?,
    val format: com.livingbook.model.BookFormat,
    val blockCount: Int,
    val warnings: String,
    val importedAt: Long,
    val lastOpenedAt: Long?,
    val blockIndex: Int?,
)

@Dao
interface BookDao {
    @Query(
        """SELECT b.id, b.title, b.author, b.format, b.blockCount, b.warnings, b.importedAt, b.lastOpenedAt,
                  p.blockIndex
           FROM books b LEFT JOIN progress p ON p.bookId = b.id
           ORDER BY COALESCE(b.lastOpenedAt, b.importedAt) DESC"""
    )
    fun observeLibrary(): Flow<List<LibraryRow>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: String): BookEntity?

    @Insert
    suspend fun insertBook(book: BookEntity)

    @Insert
    suspend fun insertBlocks(blocks: List<BlockEntity>)

    @Insert
    suspend fun insertSections(sections: List<SectionEntity>)

    @Insert
    suspend fun insertTopics(topics: List<TopicEntity>)

    @Transaction
    suspend fun insertBookWithContent(
        book: BookEntity,
        blocks: List<BlockEntity>,
        sections: List<SectionEntity>,
        topics: List<TopicEntity>,
    ) {
        insertBook(book)
        insertBlocks(blocks)
        insertSections(sections)
        insertTopics(topics)
    }

    @Query("UPDATE books SET lastOpenedAt = :at WHERE id = :id")
    suspend fun markOpened(id: String, at: Long)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ContentDao {
    @Query("SELECT * FROM blocks WHERE bookId = :bookId AND idx BETWEEN :from AND :to ORDER BY idx")
    suspend fun blocks(bookId: String, from: Int, to: Int): List<BlockEntity>

    @Query("SELECT * FROM blocks WHERE bookId = :bookId AND idx IN (:indices) ORDER BY idx")
    suspend fun blocksAt(bookId: String, indices: List<Int>): List<BlockEntity>

    @Query("SELECT * FROM topics WHERE bookId = :bookId AND granularity = :granularity ORDER BY idx")
    suspend fun topics(bookId: String, granularity: Granularity): List<TopicEntity>

    @Query("SELECT * FROM sections WHERE bookId = :bookId ORDER BY idx")
    suspend fun sections(bookId: String): List<SectionEntity>

    /** [query] is an FTS4 MATCH expression, e.g. "bayesian OR inference". */
    @Query(
        """SELECT b.* FROM blocks b JOIN blocks_fts f ON b.rowId = f.rowid
           WHERE blocks_fts MATCH :query AND b.bookId = :bookId LIMIT :limit"""
    )
    suspend fun search(bookId: String, query: String, limit: Int): List<BlockEntity>

    @Query("SELECT * FROM guided WHERE bookId = :bookId AND startBlock = :start AND endBlock = :end")
    suspend fun guided(bookId: String, start: Int, end: Int): GuidedEntity?

    @Upsert
    suspend fun saveGuided(guided: GuidedEntity)
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    suspend fun get(bookId: String): ProgressEntity?

    @Upsert
    suspend fun save(progress: ProgressEntity)
}

@Dao
interface AnnotationDao {
    @Query("SELECT * FROM annotations WHERE bookId = :bookId ORDER BY blockIndex, createdAt")
    fun observe(bookId: String): Flow<List<AnnotationEntity>>

    @Insert
    suspend fun insert(annotation: AnnotationEntity): Long

    @Query("DELETE FROM annotations WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE bookId = :bookId ORDER BY updatedAt DESC")
    fun observeForBook(bookId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE bookId = :bookId AND anchorBlock = :anchorBlock ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestAt(bookId: String, anchorBlock: Int): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: Long): ConversationEntity?

    @Insert
    suspend fun insert(conversation: ConversationEntity): Long

    @Query("UPDATE conversations SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt, id")
    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt, id")
    suspend fun messages(conversationId: Long): List<MessageEntity>

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("DELETE FROM conversations WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}

@Dao
interface LearningDao {
    @Query("SELECT * FROM learning_signals ORDER BY kind, count DESC, updatedAt DESC")
    fun observe(): Flow<List<LearningSignalEntity>>

    @Query("SELECT * FROM learning_signals ORDER BY count DESC, updatedAt DESC LIMIT :limit")
    suspend fun top(limit: Int): List<LearningSignalEntity>

    @Query("SELECT * FROM learning_signals WHERE kind = :kind AND label = :label")
    suspend fun find(kind: SignalKind, label: String): LearningSignalEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(signal: LearningSignalEntity): Long

    @Query("DELETE FROM learning_signals WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM learning_signals")
    suspend fun deleteAll()

    @Query("SELECT EXISTS(SELECT 1 FROM dismissals WHERE bookId = :bookId AND startBlock = :startBlock)")
    suspend fun isDismissed(bookId: String, startBlock: Int): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun dismiss(dismissal: DismissalEntity)
}

@Database(
    entities = [
        BookEntity::class, BlockEntity::class, BlockFts::class, SectionEntity::class, TopicEntity::class,
        ProgressEntity::class, AnnotationEntity::class, GuidedEntity::class, ConversationEntity::class,
        MessageEntity::class, LearningSignalEntity::class, DismissalEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun content(): ContentDao
    abstract fun progress(): ProgressDao
    abstract fun annotations(): AnnotationDao
    abstract fun conversations(): ConversationDao
    abstract fun learning(): LearningDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "livingbook.db").build()
    }
}
