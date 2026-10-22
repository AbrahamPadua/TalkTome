package com.livingbook.ai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.livingbook.data.AiMode
import com.livingbook.data.ConversationRepository
import com.livingbook.data.LearningProfileRepository
import com.livingbook.data.ReadingRepository
import com.livingbook.data.SettingsRepository
import com.livingbook.data.db.AppDatabase
import com.livingbook.data.db.BlockEntity
import com.livingbook.data.db.BookEntity
import com.livingbook.data.db.TopicEntity
import com.livingbook.model.BlockKind
import com.livingbook.model.BookFormat
import com.livingbook.model.Granularity
import com.livingbook.model.Rendition

/** In-memory database with a small two-topic book, for Robolectric tests. */
class TestBook {
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val reading = ReadingRepository(db)
    val conversations = ConversationRepository(db)
    val learning = LearningProfileRepository(db)
    val settings = SettingsRepository(ApplicationProvider.getApplicationContext())

    val blocks = listOf(
        block(0, BlockKind.HEADING, "Priors", page = 1),
        block(1, BlockKind.PARAGRAPH, "A prior is what you believe before seeing data. It shapes every update.", page = 1),
        block(2, BlockKind.PARAGRAPH, "Evidence updates the prior into a posterior.", page = 2),
        block(3, BlockKind.HEADING, "Perception", page = 3),
        block(4, BlockKind.PARAGRAPH, "The visual system relies on priors about light coming from above.", page = 3),
    )
    val priors = TopicEntity(BOOK, Granularity.BALANCED, 0, "Priors", 0, 2, null)
    val perception = TopicEntity(BOOK, Granularity.BALANCED, 1, "Perception", 3, 4, null)

    suspend fun insert(): TestBook {
        db.books().insertBookWithContent(
            BookEntity(BOOK, "Thinking in Bets", "A. Author", BookFormat.EPUB, "/dev/null", 0, null, blocks.size, 3, ""),
            blocks,
            emptyList(),
            listOf(priors, perception),
        )
        return this
    }

    suspend fun configure(mode: AiMode, consent: Boolean = true, comprehensionChecks: Boolean = false, tutoring: Boolean = true) =
        settings.update {
            it.copy(aiMode = mode, aiConsent = consent, proxyUrl = "http://proxy.test", comprehensionChecks = comprehensionChecks, tutoringSuggestions = tutoring)
        }

    fun context(topic: TopicEntity = priors, sentence: String? = "Evidence updates the prior into a posterior.") = ReadingContext(
        bookId = BOOK,
        bookTitle = "Thinking in Bets",
        author = "A. Author",
        topic = topic,
        topicBlocks = blocks.filter { it.idx in topic.startBlock..topic.endBlock },
        currentSentence = sentence,
        rendition = Rendition.ORIGINAL,
    )

    fun close() = db.close()

    companion object {
        const val BOOK = "book-1"

        fun block(idx: Int, kind: BlockKind, text: String, page: Int? = null) =
            BlockEntity(bookId = BOOK, idx = idx, kind = kind, text = text, headingLevel = if (kind == BlockKind.HEADING) 1 else 0, page = page, location = null)
    }
}

/** Records requests and answers with [reply], or throws [error]. */
class FakeLlmProvider(var reply: String = "", var error: AiException? = null) : LlmProvider {
    val requests = mutableListOf<LlmRequest>()

    override suspend fun complete(request: LlmRequest): LlmResult {
        requests += request
        error?.let { throw it }
        return LlmResult(reply, "fake-model")
    }
}
