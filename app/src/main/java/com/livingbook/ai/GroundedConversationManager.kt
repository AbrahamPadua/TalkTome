package com.livingbook.ai

import com.livingbook.data.ConversationRepository
import com.livingbook.data.LearningProfileRepository
import com.livingbook.data.ReadingRepository
import com.livingbook.data.SettingsRepository
import com.livingbook.model.ChatMessage
import kotlinx.coroutines.flow.Flow

/** Grounded conversation: retrieval -> prompt -> provider (or labeled demo) -> validated citations -> stored messages. */
class GroundedConversationManager(
    private val conversations: ConversationRepository,
    private val reading: ReadingRepository,
    private val learning: LearningProfileRepository,
    private val retriever: SourceRetriever,
    private val provider: LlmProvider,
    private val settings: SettingsRepository,
) : ConversationManager {
    override suspend fun open(context: ReadingContext): Long = TODO("ai worker")
    override fun messages(conversationId: Long): Flow<List<ChatMessage>> = TODO("ai worker")
    override suspend fun send(conversationId: Long, context: ReadingContext, userText: String, action: TutorAction): ChatMessage = TODO("ai worker")
    override suspend fun remember(conversationId: Long, context: ReadingContext): String = TODO("ai worker")
}
