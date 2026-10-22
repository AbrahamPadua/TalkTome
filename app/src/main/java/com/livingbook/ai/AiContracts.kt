package com.livingbook.ai

import com.livingbook.data.db.BlockEntity
import com.livingbook.data.db.TopicEntity
import com.livingbook.model.ChatMessage
import com.livingbook.model.GuidedContent
import com.livingbook.model.Rendition
import kotlinx.coroutines.flow.Flow

/** User-facing AI failure: offline, timeout, proxy not configured, no consent. */
class AiException(val userMessage: String, cause: Throwable? = null) : Exception(userMessage, cause)

// ---- Provider -------------------------------------------------------------------------------------------------------

data class LlmMessage(val role: String, val content: String)

data class LlmRequest(
    val system: String,
    val messages: List<LlmMessage>,
    /** Includes the model's thinking, which is always on for the default model; the proxy caps it at 4000. */
    val maxTokens: Int = 4000,
)

data class LlmResult(val text: String, val model: String)

/** Provider-agnostic model call. The app only talks to the user's backend proxy; no provider keys live on the device. */
interface LlmProvider {
    /** @throws AiException */
    suspend fun complete(request: LlmRequest): LlmResult
}

// ---- Retrieval ------------------------------------------------------------------------------------------------------

data class Passage(
    val blockIndex: Int,
    val text: String,
    val page: Int?,
    val topicTitle: String?,
)

/** Supplies source passages for grounding. Topic-scoped plus full-text search; embeddings can slot in later. */
interface SourceRetriever {
    suspend fun retrieve(bookId: String, question: String, topic: TopicEntity, limit: Int = 8): List<Passage>
}

// ---- Conversation ---------------------------------------------------------------------------------------------------

/** Everything the AI needs to know about where the reader is. */
data class ReadingContext(
    val bookId: String,
    val bookTitle: String,
    val author: String?,
    val topic: TopicEntity,
    val topicBlocks: List<BlockEntity>,
    /** The sentence narration was on when the user interrupted, if any. */
    val currentSentence: String?,
    val rendition: Rendition,
    /** Paragraph the user long-pressed to ask about, if any. */
    val selectedText: String? = null,
)

enum class TutorAction {
    ASK,
    EXPLAIN_DIFFERENTLY,
    EXAMPLE,
    GO_DEEPER,
    CONTINUE_EXPLAINING,
    SUMMARIZE,
    RECAP_DISCUSSION,
    /** Explain: alternative explanation, analogy, prerequisites. */
    EXPLAIN,
    /** Explore: thoughtful questions and connections. */
    EXPLORE,
    /** Challenge: a question testing application or interpretation. */
    CHALLENGE,
    QUIZ,
    /** Ask the user to explain the idea back. */
    TEACH_BACK,
}

interface ConversationManager {
    /** Opens or reuses the conversation attached to this topic and returns its id. */
    suspend fun open(context: ReadingContext): Long

    fun messages(conversationId: Long): Flow<List<ChatMessage>>

    /**
     * Stores the user's message, gets a grounded reply (real model or labeled demo), stores and returns it.
     * Failures come back as a [com.livingbook.model.ChatRole.NOTE] message with an actionable text; nothing is thrown.
     */
    suspend fun send(conversationId: Long, context: ReadingContext, userText: String, action: TutorAction = TutorAction.ASK): ChatMessage

    /** Saves the last assistant answer (or the current passage) as a note. Returns a confirmation for the user. */
    suspend fun remember(conversationId: Long, context: ReadingContext): String
}

/** Deterministic commands handled by app logic, not the model. */
sealed interface ReaderCommand {
    data object ContinueReading : ReaderCommand
    data object GoBack : ReaderCommand
    data object RepeatThat : ReaderCommand
    data object SkipTopic : ReaderCommand
    data object RememberThis : ReaderCommand
    /** Everything the model should answer, e.g. "give me an example" -> EXAMPLE. */
    data class Tutor(val action: TutorAction) : ReaderCommand
}

// ---- Tutoring -------------------------------------------------------------------------------------------------------

data class TutorSuggestion(val action: TutorAction, val label: String)

interface TutorEngine {
    /** Optional suggestions at a topic boundary. Empty when disabled in settings or dismissed for this topic. */
    suspend fun suggestionsFor(bookId: String, topic: TopicEntity): List<TutorSuggestion>

    suspend fun dismiss(bookId: String, topic: TopicEntity)
}

/** AI-guided rendition of a topic. Implementations read and write the per-block-range cache in ReadingRepository. */
interface GuidedContentGenerator {
    /** @throws AiException */
    suspend fun guidedFor(context: ReadingContext): GuidedContent
}
