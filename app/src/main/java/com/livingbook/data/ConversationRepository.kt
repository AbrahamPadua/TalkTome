package com.livingbook.data

import com.livingbook.data.db.AppDatabase
import com.livingbook.data.db.ConversationEntity
import com.livingbook.data.db.DismissalEntity
import com.livingbook.data.db.LearningSignalEntity
import com.livingbook.data.db.MessageEntity
import com.livingbook.data.db.SignalKind
import com.livingbook.model.AnswerSegment
import com.livingbook.model.ChatMessage
import com.livingbook.model.ChatRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Conversations stay attached to the topic (anchor block) where they started. */
class ConversationRepository(private val db: AppDatabase) {

    /** Reuses the latest conversation at this anchor so reopening a topic shows its history. */
    suspend fun conversationFor(bookId: String, anchorBlock: Int, topicTitle: String): Long {
        db.conversations().latestAt(bookId, anchorBlock)?.let { return it.id }
        val now = System.currentTimeMillis()
        return db.conversations().insert(ConversationEntity(bookId = bookId, anchorBlock = anchorBlock, topicTitle = topicTitle, createdAt = now, updatedAt = now))
    }

    fun observeConversations(bookId: String): Flow<List<ConversationEntity>> = db.conversations().observeForBook(bookId)

    fun observeMessages(conversationId: Long): Flow<List<ChatMessage>> =
        db.conversations().observeMessages(conversationId).map { rows -> rows.map { it.toChat() } }

    suspend fun messages(conversationId: Long): List<ChatMessage> = db.conversations().messages(conversationId).map { it.toChat() }

    suspend fun append(conversationId: Long, role: ChatRole, text: String, segments: List<AnswerSegment> = emptyList(), isMock: Boolean = false): Long {
        val now = System.currentTimeMillis()
        db.conversations().touch(conversationId, now)
        return db.conversations().insertMessage(
            MessageEntity(
                conversationId = conversationId,
                role = role,
                text = text,
                segmentsJson = if (segments.isEmpty()) null else json.encodeToString(segmentList, segments),
                isMock = isMock,
                createdAt = now,
            )
        )
    }

    suspend fun deleteForBook(bookId: String) = db.conversations().deleteForBook(bookId)

    suspend fun deleteAll() = db.conversations().deleteAll()

    private fun MessageEntity.toChat() = ChatMessage(
        id = id,
        role = role,
        text = text,
        segments = segmentsJson?.let { json.decodeFromString(segmentList, it) } ?: emptyList(),
        isMock = isMock,
        createdAt = createdAt,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val segmentList = ListSerializer(AnswerSegment.serializer())
}

/** The user's inspectable, editable learning profile plus dismissed tutoring suggestions. */
class LearningProfileRepository(private val db: AppDatabase) {

    fun observe(): Flow<List<LearningSignalEntity>> = db.learning().observe()

    suspend fun top(limit: Int = 8): List<LearningSignalEntity> = db.learning().top(limit)

    /** Records explicit evidence. Repeated evidence for the same label raises its count instead of duplicating it. */
    suspend fun record(kind: SignalKind, label: String, evidence: String, bookId: String? = null) {
        val existing = db.learning().find(kind, label)
        db.learning().upsert(
            existing?.copy(evidence = evidence, count = existing.count + 1, updatedAt = System.currentTimeMillis())
                ?: LearningSignalEntity(kind = kind, label = label, evidence = evidence, bookId = bookId, updatedAt = System.currentTimeMillis())
        )
    }

    suspend fun update(signal: LearningSignalEntity) = db.learning().upsert(signal.copy(updatedAt = System.currentTimeMillis()))

    suspend fun delete(id: Long) = db.learning().delete(id)

    suspend fun deleteAll() = db.learning().deleteAll()

    suspend fun isDismissed(bookId: String, startBlock: Int) = db.learning().isDismissed(bookId, startBlock)

    suspend fun dismiss(bookId: String, startBlock: Int) = db.learning().dismiss(DismissalEntity(bookId, startBlock))
}
