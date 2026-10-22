package com.livingbook.ai

import com.livingbook.data.LearningProfileRepository
import com.livingbook.data.SettingsRepository
import com.livingbook.data.db.TopicEntity

class DefaultTutorEngine(
    private val learning: LearningProfileRepository,
    private val settings: SettingsRepository,
) : TutorEngine {

    override suspend fun suggestionsFor(bookId: String, topic: TopicEntity): List<TutorSuggestion> {
        val s = settings.current()
        if (!s.tutoringSuggestions || learning.isDismissed(bookId, topic.startBlock)) return emptyList()
        return listOfNotNull(
            TutorSuggestion(TutorAction.EXPLAIN, "Explain"),
            TutorSuggestion(TutorAction.EXPLORE, "Explore"),
            TutorSuggestion(TutorAction.CHALLENGE, "Challenge"),
            TutorSuggestion(TutorAction.TEACH_BACK, "Check my understanding").takeIf { s.comprehensionChecks },
        )
    }

    override suspend fun dismiss(bookId: String, topic: TopicEntity) = learning.dismiss(bookId, topic.startBlock)
}
