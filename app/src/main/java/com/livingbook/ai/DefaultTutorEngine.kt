package com.livingbook.ai

import com.livingbook.data.LearningProfileRepository
import com.livingbook.data.SettingsRepository
import com.livingbook.data.db.TopicEntity

class DefaultTutorEngine(
    private val learning: LearningProfileRepository,
    private val settings: SettingsRepository,
) : TutorEngine {
    override suspend fun suggestionsFor(bookId: String, topic: TopicEntity): List<TutorSuggestion> = TODO("ai worker")
    override suspend fun dismiss(bookId: String, topic: TopicEntity) = TODO("ai worker")
}
