package com.livingbook.ai

import com.livingbook.data.ReadingRepository
import com.livingbook.data.db.TopicEntity

/** Current-topic passages first, then full-text matches from the rest of the book. */
class BookSourceRetriever(private val reading: ReadingRepository) : SourceRetriever {
    override suspend fun retrieve(bookId: String, question: String, topic: TopicEntity, limit: Int): List<Passage> = TODO("ai worker")
}
