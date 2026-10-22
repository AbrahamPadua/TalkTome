package com.livingbook.ai

import com.livingbook.data.AiMode
import com.livingbook.data.db.AnnotationKind
import com.livingbook.data.db.MessageEntity
import com.livingbook.data.db.SignalKind
import com.livingbook.model.AnswerSegment
import com.livingbook.model.ChatRole
import com.livingbook.model.SegmentKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GroundedConversationManagerTest {

    private lateinit var book: TestBook
    private val provider = FakeLlmProvider()
    private lateinit var manager: GroundedConversationManager
    private var conversation = 0L

    @Before fun setUp() = runBlocking {
        book = TestBook().insert()
        manager = GroundedConversationManager(book.conversations, book.reading, book.learning, BookSourceRetriever(book.reading), provider, book.settings)
        conversation = manager.open(book.context())
    }

    @After fun tearDown() = book.close()

    @Test fun openReusesTheTopicConversation() = runBlocking {
        assertEquals(conversation, manager.open(book.context()))
        assertTrue(manager.open(book.context(book.perception)) != conversation)
    }

    @Test fun proxyStoresBothMessagesWithValidatedCitations() = runBlocking {
        book.configure(AiMode.PROXY)
        provider.reply = """```json
            {"segments":[{"kind":"book","text":"A prior is a belief.","citations":[1,99]},
            {"kind":"book","text":"Invented.","citations":[99]}],"assessment":null}
            ```"""

        val reply = manager.send(conversation, book.context(), "What is a prior?")

        assertEquals(ChatRole.ASSISTANT, reply.role)
        assertFalse(reply.isMock)
        assertEquals(
            listOf(AnswerSegment(SegmentKind.BOOK, "A prior is a belief.", listOf(1)), AnswerSegment(SegmentKind.INTERPRETATION, "Invented.")),
            reply.segments,
        )
        val stored = manager.messages(conversation).first()
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), stored.map { it.role })
        assertEquals("What is a prior?", stored[0].text)
        val request = provider.requests.single()
        assertEquals(PromptBuilder.SYSTEM, request.system)
        assertTrue("[B1] (p. 1) A prior is what you believe" in request.messages.last().content)
    }

    @Test fun proxySendsRecentHistory() = runBlocking {
        book.configure(AiMode.PROXY)
        provider.reply = """{"segments":[{"kind":"interpretation","text":"First answer."}]}"""
        manager.send(conversation, book.context(), "First question")
        manager.send(conversation, book.context(), "Second question")

        val messages = provider.requests.last().messages
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.role })
        assertEquals(listOf("First question", "First answer."), messages.take(2).map { it.content })
    }

    @Test fun demoIsLabeledMockAndQuotesPassages() = runBlocking {
        book.configure(AiMode.DEMO)

        val reply = manager.send(conversation, book.context(), "What does evidence do to the posterior?")

        assertTrue(reply.isMock)
        assertTrue(reply.segments.first().text.startsWith("Demo mode, not AI"))
        val quoted = reply.segments.drop(1)
        assertTrue(quoted.isNotEmpty())
        quoted.forEach { seg ->
            assertEquals(SegmentKind.BOOK, seg.kind)
            assertEquals(seg.text, book.blocks[seg.citations.single()].text)
        }
        assertEquals(2, quoted.first().citations.single())
        assertTrue(provider.requests.isEmpty())
    }

    @Test fun offGivesANote() = runBlocking {
        book.configure(AiMode.OFF)
        val reply = manager.send(conversation, book.context(), "What is a prior?")
        assertEquals(ChatRole.NOTE, reply.role)
        assertEquals("AI is turned off. You can turn it on in Settings.", reply.text)
        assertTrue(reply.segments.isEmpty())
        assertTrue(provider.requests.isEmpty())
    }

    @Test fun proxyWithoutConsentGivesANoteAndSendsNothing() = runBlocking {
        book.configure(AiMode.PROXY, consent = false)
        val reply = manager.send(conversation, book.context(), "What is a prior?")
        assertEquals(ChatRole.NOTE, reply.role)
        assertEquals(ProxyLlmProvider.NO_CONSENT, reply.text)
        assertTrue(provider.requests.isEmpty())
    }

    @Test fun providerErrorBecomesANote() = runBlocking {
        book.configure(AiMode.PROXY)
        provider.error = AiException(ProxyLlmProvider.UNREACHABLE)
        val reply = manager.send(conversation, book.context(), "What is a prior?")
        assertEquals(ChatRole.NOTE, reply.role)
        assertEquals(ProxyLlmProvider.UNREACHABLE, reply.text)
        assertEquals(listOf(ChatRole.USER, ChatRole.NOTE), manager.messages(conversation).first().map { it.role })
    }

    @Test fun recapComesFromHistoryWhenAiIsOff() = runBlocking {
        book.configure(AiMode.OFF)
        manager.send(conversation, book.context(), "What is a prior?")
        val reply = manager.send(conversation, book.context(), "what were we discussing", TutorAction.RECAP_DISCUSSION)
        assertEquals(ChatRole.NOTE, reply.role)
        assertEquals("So far you asked: “What is a prior?”.", reply.text)
    }

    @Test fun requestSignalsAreRecorded() = runBlocking {
        book.configure(AiMode.DEMO)
        manager.send(conversation, book.context(), "Give me an example", TutorAction.EXAMPLE)
        manager.send(conversation, book.context(), "Is there an intuitive analogy, or the equation?")

        val labels = book.learning.top().map { it.kind to it.label }.toSet()
        assertEquals(
            setOf(
                SignalKind.PREFERENCE to "Asks for concrete examples",
                SignalKind.PREFERENCE to "Prefers intuitive analogies",
                SignalKind.PREFERENCE to "Prefers mathematical explanations",
            ),
            labels,
        )
    }

    @Test fun answerAfterChallengeIsAssessed() = runBlocking {
        book.configure(AiMode.PROXY)
        provider.reply = """{"segments":[{"kind":"interpretation","text":"How would new evidence change your prior?"}]}"""
        manager.send(conversation, book.context(), "Challenge", TutorAction.CHALLENGE)
        provider.reply = """{"segments":[{"kind":"interpretation","text":"Not quite."}],"assessment":{"understood":false,"concept":"Bayesian updating"}}"""

        manager.send(conversation, book.context(), "It stays the same.")

        assertTrue("Assessment:" in provider.requests.last().messages.last().content)
        assertFalse("Assessment:" in provider.requests.first().messages.last().content)
        val signal = book.learning.top().single()
        assertEquals(SignalKind.STRUGGLE, signal.kind)
        assertEquals("Bayesian updating", signal.label)
    }

    @Test fun rememberSavesTheLastRealAnswerAsANote() = runBlocking {
        book.configure(AiMode.DEMO)
        manager.send(conversation, book.context(), "posterior")
        assertEquals("Saved to your notes.", manager.remember(conversation, book.context()))
        book.configure(AiMode.PROXY)
        provider.reply = """{"segments":[{"kind":"interpretation","text":"The posterior is the updated belief."}]}"""
        manager.send(conversation, book.context(), "And the posterior?")

        manager.remember(conversation, book.context())

        val notes = book.reading.observeAnnotations(TestBook.BOOK).first()
        assertEquals(2, notes.size)
        assertTrue(notes.all { it.kind == AnnotationKind.NOTE && it.blockIndex == book.priors.startBlock })
        assertTrue("a demo reply is skipped for the current sentence", notes.any { it.text == "Evidence updates the prior into a posterior." })
        assertTrue("saves the last real answer", notes.any { it.text == "The posterior is the updated belief." })
        assertFalse(notes.any { it.text.startsWith("Demo mode") })
    }

    @Test fun demoRepliesNeverGoBackToTheModel() = runBlocking {
        book.configure(AiMode.DEMO)
        manager.send(conversation, book.context(), "First question")
        book.configure(AiMode.PROXY)
        provider.reply = """{"segments":[{"kind":"interpretation","text":"Answer."}]}"""

        manager.send(conversation, book.context(), "Second question")

        val messages = provider.requests.single().messages
        assertEquals(listOf("user", "user"), messages.map { it.role })
        assertEquals("First question", messages.first().content)
        assertFalse(messages.any { "Demo mode" in it.content })
    }

    @Test fun onlyARealQuestionTurnLeadsToAssessment() = runBlocking {
        book.configure(AiMode.DEMO)
        manager.send(conversation, book.context(), "Challenge", TutorAction.CHALLENGE)
        book.configure(AiMode.PROXY)
        provider.error = AiException(ProxyLlmProvider.UNREACHABLE)
        manager.send(conversation, book.context(), "Challenge", TutorAction.CHALLENGE)
        provider.error = null
        provider.reply = """{"segments":[{"kind":"interpretation","text":"Sure."}]}"""

        manager.send(conversation, book.context(), "It stays the same.")

        assertFalse("Assessment:" in provider.requests.last().messages.last().content)
    }

    @Test fun decodedButEmptyReplyGivesANote() = runBlocking {
        book.configure(AiMode.PROXY)
        provider.reply = """{"segments":[]}"""
        val reply = manager.send(conversation, book.context(), "What is a prior?")
        assertEquals(ChatRole.NOTE, reply.role)
        assertEquals("The AI service didn't send a usable answer. Please try asking again.", reply.text)
    }

    @Test fun unreadableStoredRowsDoNotBreakSend() = runBlocking {
        book.db.conversations().insertMessage(MessageEntity(conversationId = conversation, role = ChatRole.ASSISTANT, text = "old", segmentsJson = "{not json", isMock = false, createdAt = 0))
        book.configure(AiMode.PROXY)
        provider.reply = """{"segments":[{"kind":"interpretation","text":"Still answers."}]}"""

        val reply = manager.send(conversation, book.context(), "What is a prior?")

        assertEquals(ChatRole.ASSISTANT, reply.role)
        assertEquals("Still answers.", reply.text)
        assertEquals("Saved to your notes.", manager.remember(conversation, book.context()))
    }
}
