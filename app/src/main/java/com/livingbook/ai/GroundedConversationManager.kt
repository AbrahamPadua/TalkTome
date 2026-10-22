package com.livingbook.ai

import com.livingbook.data.AiMode
import com.livingbook.data.ConversationRepository
import com.livingbook.data.LearningProfileRepository
import com.livingbook.data.ReadingRepository
import com.livingbook.data.SettingsRepository
import com.livingbook.data.db.AnnotationEntity
import com.livingbook.data.db.AnnotationKind
import com.livingbook.data.db.SignalKind
import com.livingbook.model.AnswerSegment
import com.livingbook.model.ChatMessage
import com.livingbook.model.ChatRole
import com.livingbook.model.SegmentKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentHashMap

/** Grounded conversation: retrieval -> prompt -> provider (or labeled demo) -> validated citations -> stored messages. */
class GroundedConversationManager(
    private val conversations: ConversationRepository,
    private val reading: ReadingRepository,
    private val learning: LearningProfileRepository,
    private val retriever: SourceRetriever,
    private val provider: LlmProvider,
    private val settings: SettingsRepository,
) : ConversationManager {

    // shortcut: kept in memory, so an answer given after an app restart is not assessed. Persist on MessageEntity if that matters.
    private val lastAction = ConcurrentHashMap<Long, TutorAction>()

    private data class Reply(val role: ChatRole, val text: String, val segments: List<AnswerSegment> = emptyList(), val isMock: Boolean = false)

    override suspend fun open(context: ReadingContext): Long =
        conversations.conversationFor(context.bookId, context.topic.startBlock, context.topic.title)

    override fun messages(conversationId: Long): Flow<List<ChatMessage>> = conversations.observeMessages(conversationId)

    override suspend fun send(conversationId: Long, context: ReadingContext, userText: String, action: TutorAction): ChatMessage {
        val history = storedMessages(conversationId)
        conversations.append(conversationId, ChatRole.USER, userText)
        val reply = try {
            recordRequestSignals(context, userText, action)
            answer(conversationId, context, userText, action, history)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            if (action == TutorAction.RECAP_DISCUSSION) recap(history) else note(e.userMessage)
        } catch (e: Exception) {
            note("Something went wrong while answering. Your place in the book is safe; please try again.")
        }
        // Only a real answer can have asked the question the reader replies to next.
        if (reply.role == ChatRole.ASSISTANT && !reply.isMock) lastAction[conversationId] = action
        val id = conversations.append(conversationId, reply.role, reply.text, reply.segments, reply.isMock)
        return storedMessages(conversationId).firstOrNull { it.id == id }
            ?: ChatMessage(id, reply.role, reply.text, reply.segments, reply.isMock, System.currentTimeMillis())
    }

    override suspend fun remember(conversationId: Long, context: ReadingContext): String {
        val text = storedMessages(conversationId).lastOrNull { it.role == ChatRole.ASSISTANT && !it.isMock }?.text
            ?: context.currentSentence
            ?: context.topic.title
        reading.addAnnotation(
            AnnotationEntity(
                bookId = context.bookId,
                kind = AnnotationKind.NOTE,
                blockIndex = context.topic.startBlock,
                text = text,
                createdAt = System.currentTimeMillis(),
            )
        )
        return "Saved to your notes."
    }

    private suspend fun answer(id: Long, context: ReadingContext, userText: String, action: TutorAction, history: List<ChatMessage>): Reply {
        val s = settings.current()
        val aiAvailable = s.aiMode == AiMode.PROXY && s.aiConsent
        if (action == TutorAction.RECAP_DISCUSSION && !aiAvailable) return recap(history)
        val query = listOfNotNull(userText, context.selectedText).joinToString(" ")
        return when {
            s.aiMode == AiMode.OFF -> note("AI is turned off. You can turn it on in Settings.")
            s.aiMode == AiMode.DEMO -> demo(retriever.retrieve(context.bookId, query, context.topic, limit = 3))
            !s.aiConsent -> note(ProxyLlmProvider.NO_CONSENT)
            else -> proxy(id, context, query, userText, action, history)
        }
    }

    private suspend fun proxy(id: Long, context: ReadingContext, query: String, userText: String, action: TutorAction, history: List<ChatMessage>): Reply {
        val topicBlocks = context.topicBlocks.ifEmpty { reading.blocks(context.bookId, context.topic.startBlock, context.topic.endBlock) }
        val prompt = PromptBuilder.conversationTurn(
            context = context,
            topicBlocks = topicBlocks,
            passages = retriever.retrieve(context.bookId, query, context.topic),
            profile = learning.top(),
            question = userText,
            action = action,
            assessAnswer = lastAction[id] in PromptBuilder.ASSESSED_ACTIONS,
        )
        // Demo replies are not the model's words, so they never go back to it as assistant turns.
        val turns = history.filter { it.role != ChatRole.NOTE && !it.isMock }
            .takeLast(HISTORY)
            .dropWhile { it.role != ChatRole.USER }
            .map { LlmMessage(if (it.role == ChatRole.USER) "user" else "assistant", it.text) }
        val result = provider.complete(LlmRequest(PromptBuilder.SYSTEM, turns + LlmMessage("user", prompt.text)))
        val parsed = ResponseParser.parseAnswer(result.text, prompt.sentBlocks)
        if (parsed.segments.isEmpty()) return note("The AI service didn't send a usable answer. Please try asking again.")
        parsed.assessment?.let { a ->
            val kind = if (a.understood) SignalKind.UNDERSTOOD else SignalKind.STRUGGLE
            val evidence = if (a.understood) "Answered a check-in question correctly in “${context.topic.title}”."
            else "Had trouble with a check-in question in “${context.topic.title}”."
            learning.record(kind, a.concept, evidence, context.bookId)
        }
        return assistant(parsed.segments)
    }

    private fun demo(passages: List<Passage>): Reply {
        if (passages.isEmpty()) {
            return assistant(listOf(AnswerSegment(SegmentKind.INTERPRETATION, "Demo mode, not AI: no passage in this book matches your question.")), isMock = true)
        }
        val intro = AnswerSegment(SegmentKind.INTERPRETATION, "Demo mode, not AI: here are the passages in this book that best match your question.")
        return assistant(listOf(intro) + passages.map { AnswerSegment(SegmentKind.BOOK, it.text, listOf(it.blockIndex)) }, isMock = true)
    }

    /** Answers "what were we discussing" from stored history, no model needed. */
    private fun recap(history: List<ChatMessage>): Reply {
        val asked = history.filter { it.role == ChatRole.USER }.takeLast(3).map { "“${it.text.take(120)}”" }
        val text = if (asked.isEmpty()) "We haven't discussed anything in this topic yet."
        else "So far you asked: ${asked.joinToString(", ")}."
        return note(text)
    }

    /** Stored messages, or none if a row can't be read, so [send] never fails on a lookup. */
    private suspend fun storedMessages(conversationId: Long): List<ChatMessage> = try {
        conversations.messages(conversationId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    private fun assistant(segments: List<AnswerSegment>, isMock: Boolean = false) =
        Reply(ChatRole.ASSISTANT, segments.joinToString(" ") { it.text }, segments, isMock)

    /** App-generated message (errors, settings hints, recaps): plain text, no AI labels. */
    private fun note(text: String) = Reply(ChatRole.NOTE, text)

    /** Only explicit evidence: what the reader asked for, in their own words. */
    private suspend fun recordRequestSignals(context: ReadingContext, userText: String, action: TutorAction) {
        val where = "in “${context.topic.title}”"
        when (action) {
            TutorAction.EXAMPLE -> learning.record(SignalKind.PREFERENCE, "Asks for concrete examples", "Asked for an example $where.", context.bookId)
            TutorAction.EXPLAIN_DIFFERENTLY -> learning.record(SignalKind.PREFERENCE, "Asks for alternative explanations", "Asked for another explanation $where.", context.bookId)
            TutorAction.GO_DEEPER -> learning.record(SignalKind.PREFERENCE, "Likes going deeper", "Asked to go deeper $where.", context.bookId)
            else -> Unit
        }
        if (INTUITIVE.containsMatchIn(userText)) {
            learning.record(SignalKind.PREFERENCE, "Prefers intuitive analogies", "Asked for an intuitive explanation $where.", context.bookId)
        }
        if (MATH.containsMatchIn(userText)) {
            learning.record(SignalKind.PREFERENCE, "Prefers mathematical explanations", "Asked for a mathematical explanation $where.", context.bookId)
        }
    }

    private companion object {
        const val HISTORY = 10
        val INTUITIVE = Regex("\\b(intuitive|intuitively|intuition|analogy|analogies)\\b", RegexOption.IGNORE_CASE)
        val MATH = Regex("\\b(math|maths|mathematical|mathematically|equation|equations|formal|formally|formula|derive|derived|derivation)\\b", RegexOption.IGNORE_CASE)
    }
}
