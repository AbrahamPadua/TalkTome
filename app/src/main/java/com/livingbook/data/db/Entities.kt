package com.livingbook.data.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.livingbook.model.BlockKind
import com.livingbook.model.BookFormat
import com.livingbook.model.Granularity
import com.livingbook.model.ReadMode
import com.livingbook.model.Rendition

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String?,
    val format: BookFormat,
    /** Private copy of the imported file inside app storage. */
    val filePath: String,
    val importedAt: Long,
    val lastOpenedAt: Long?,
    val blockCount: Int,
    val pageCount: Int?,
    /** Newline-separated extraction warnings shown on the book. */
    val warnings: String,
)

@Entity(
    tableName = "blocks",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["bookId", "idx"], unique = true)],
)
data class BlockEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val bookId: String,
    val idx: Int,
    val kind: BlockKind,
    val text: String,
    val headingLevel: Int,
    val page: Int?,
    val location: String?,
)

/** Full-text index over block text for source retrieval. */
@Fts4(contentEntity = BlockEntity::class)
@Entity(tableName = "blocks_fts")
data class BlockFts(val text: String)

@Entity(
    tableName = "sections",
    primaryKeys = ["bookId", "idx"],
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class SectionEntity(
    val bookId: String,
    val idx: Int,
    val title: String,
    val level: Int,
    val startBlock: Int,
)

@Entity(
    tableName = "topics",
    primaryKeys = ["bookId", "granularity", "idx"],
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class TopicEntity(
    val bookId: String,
    val granularity: Granularity,
    val idx: Int,
    val title: String,
    val startBlock: Int,
    val endBlock: Int,
    val sectionTitle: String?,
)

@Entity(
    tableName = "progress",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class ProgressEntity(
    @PrimaryKey val bookId: String,
    val blockIndex: Int,
    val charOffset: Int,
    val rendition: Rendition,
    val mode: ReadMode,
    val updatedAt: Long,
)

enum class AnnotationKind { BOOKMARK, HIGHLIGHT, NOTE }

@Entity(
    tableName = "annotations",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")],
)
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val kind: AnnotationKind,
    val blockIndex: Int,
    /** Highlighted or remembered text. */
    val text: String,
    val note: String? = null,
    val createdAt: Long,
)

@Entity(
    tableName = "guided",
    primaryKeys = ["bookId", "startBlock", "endBlock"],
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class GuidedEntity(
    val bookId: String,
    val startBlock: Int,
    val endBlock: Int,
    /** JSON of [com.livingbook.model.GuidedContent]. */
    val contentJson: String,
    val createdAt: Long,
)

@Entity(
    tableName = "conversations",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")],
)
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    /** First block of the topic the conversation started in. */
    val anchorBlock: Int,
    val topicTitle: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(ConversationEntity::class, ["id"], ["conversationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: com.livingbook.model.ChatRole,
    val text: String,
    /** JSON list of [com.livingbook.model.AnswerSegment]; null for user messages. */
    val segmentsJson: String?,
    val isMock: Boolean,
    val createdAt: Long,
)

enum class SignalKind {
    /** e.g. "Prefers intuitive analogies". */
    PREFERENCE,
    /** A concept the user explicitly struggled with. */
    STRUGGLE,
    /** A concept the user demonstrated understanding of (teach-back, quiz). */
    UNDERSTOOD,
}

/** Lightweight, user-editable evidence about how the user learns. Never inferred from timing alone. */
@Entity(tableName = "learning_signals", indices = [Index(value = ["kind", "label"], unique = true)])
data class LearningSignalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: SignalKind,
    val label: String,
    /** Why it was recorded, in plain words. */
    val evidence: String,
    val count: Int = 1,
    val bookId: String? = null,
    val updatedAt: Long,
)

/** A tutoring suggestion the user dismissed for a topic; it is not offered again there. */
@Entity(tableName = "dismissals", primaryKeys = ["bookId", "startBlock"])
data class DismissalEntity(val bookId: String, val startBlock: Int)
