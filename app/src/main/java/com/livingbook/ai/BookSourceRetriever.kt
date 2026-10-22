package com.livingbook.ai

import com.livingbook.data.ReadingRepository
import com.livingbook.data.db.BlockEntity
import com.livingbook.data.db.TopicEntity
import com.livingbook.model.BlockKind

/** Current-topic passages first, then full-text matches from the rest of the book. */
class BookSourceRetriever(private val reading: ReadingRepository) : SourceRetriever {

    // shortcut: keyword overlap plus FTS, no semantic ranking. Swap in on-device embeddings when answers miss paraphrased passages.
    override suspend fun retrieve(bookId: String, question: String, topic: TopicEntity, limit: Int): List<Passage> {
        val terms = terms(question)
        val range = topic.startBlock..topic.endBlock
        // sortedByDescending is stable, so equally relevant blocks keep reading order.
        val inTopic = reading.blocks(bookId, topic.startBlock, topic.endBlock)
            .filter { it.kind != BlockKind.HEADING && it.text.isNotBlank() }
            .sortedByDescending { block -> terms.count { block.text.contains(it, ignoreCase = true) } }
        val elsewhere = if (terms.isEmpty()) emptyList() else reading.search(bookId, terms, limit).filter { it.idx !in range }
        // Keep at least half the slots for the current topic, the rest for matches elsewhere.
        val topicSlots = limit - minOf(elsewhere.size, limit / 2)
        val picked = (inTopic.take(topicSlots) + elsewhere).distinctBy { it.idx }.take(limit)
        if (picked.isEmpty()) return emptyList()
        val topics = if (picked.any { it.idx !in range }) reading.topics(bookId, topic.granularity) else emptyList()
        return picked.map { block ->
            val title = if (block.idx in range) topic.title else topics.firstOrNull { block.idx in it.startBlock..it.endBlock }?.title
            Passage(block.idx, clip(block), block.page, title)
        }
    }

    private fun clip(block: BlockEntity): String =
        if (block.text.length <= MAX_CHARS) block.text else block.text.take(MAX_CHARS).trimEnd() + "…"

    companion object {
        const val MAX_CHARS = 1200

        /** Content words of a question, lowercased, without common function words. */
        fun terms(question: String): List<String> =
            question.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length > 2 && it !in STOPWORDS }
                .distinct()

        private val STOPWORDS = setOf(
            "the", "and", "for", "are", "but", "not", "you", "your", "with", "this", "that", "these", "those", "what",
            "why", "how", "when", "where", "which", "who", "whom", "does", "did", "can", "could", "would", "should",
            "about", "from", "into", "than", "then", "there", "their", "they", "them", "have", "has", "had", "was",
            "were", "been", "being", "will", "its", "mean", "means", "explain", "tell", "give", "example",
            "please", "here", "also", "just", "some", "any", "more", "most", "very", "really", "author", "book",
        )
    }
}
