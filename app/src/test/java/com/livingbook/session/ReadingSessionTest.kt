package com.livingbook.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.livingbook.ai.AiException
import com.livingbook.ai.GuidedContentGenerator
import com.livingbook.ai.ReadingContext
import com.livingbook.ai.TutorAction
import com.livingbook.ai.TutorEngine
import com.livingbook.ai.TutorSuggestion
import com.livingbook.data.AiMode
import com.livingbook.data.ReadingRepository
import com.livingbook.data.SettingsRepository
import com.livingbook.data.db.AppDatabase
import com.livingbook.data.db.BlockEntity
import com.livingbook.data.db.BookEntity
import com.livingbook.data.db.TopicEntity
import com.livingbook.model.BlockKind
import com.livingbook.model.BookFormat
import com.livingbook.model.GuidedContent
import com.livingbook.model.GuidedKind
import com.livingbook.model.GuidedSegment
import com.livingbook.model.Granularity
import com.livingbook.model.ReadMode
import com.livingbook.model.Rendition
import com.livingbook.speech.NarrationEvent
import com.livingbook.speech.NarrationRequest
import com.livingbook.speech.NarrationService
import com.livingbook.speech.NarrationState
import com.livingbook.speech.NarrationStatus
import com.livingbook.speech.VoiceOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Exercises the reading-conversation state machine against a real in-memory database and a fake narrator. */
@RunWith(RobolectricTestRunner::class)
class ReadingSessionTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var reading: ReadingRepository
    private lateinit var settings: SettingsRepository
    private lateinit var narration: FakeNarration
    private lateinit var guided: FakeGuided
    private lateinit var tutor: FakeTutor
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .build()
        reading = ReadingRepository(db)
        settings = SettingsRepository(context)
        settings.update { com.livingbook.data.AppSettings() }
        narration = FakeNarration()
        guided = FakeGuided()
        tutor = FakeTutor()
        seedBook()
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        db.close()
    }

    private fun newSession(): ReadingSession {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scopes += scope
        return ReadingSession(reading, settings, narration, guided, tutor, scope)
    }

    /** True once the current topic's blocks and narration sentences are both loaded. */
    private fun SessionState.ready(): Boolean {
        val t = topic ?: return false
        return blocks.firstOrNull()?.idx == t.startBlock && blocks.last().idx == t.endBlock && sentences.firstOrNull()?.blockIndex == t.startBlock
    }

    private suspend fun ReadingSession.await(predicate: (SessionState) -> Boolean): SessionState =
        withTimeout(5_000) { state.first(predicate) }

    private suspend fun awaitProgress(predicate: (com.livingbook.data.db.ProgressEntity) -> Boolean) = withTimeout(5_000) {
        while (true) {
            val p = reading.progress(BOOK)
            if (p != null && predicate(p)) return@withTimeout p
            kotlinx.coroutines.delay(10)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    @Test
    fun `opening a new book starts at the first topic in read mode`() = runBlocking {
        val session = newSession()
        session.open(BOOK)
        val s = session.await { it.ready() }
        assertEquals(0, s.topicIndex)
        assertEquals(SessionPhase.READING, s.phase)
        assertEquals(listOf("Alpha one.", "Alpha two.", "Beta one.", "Beta two."), s.sentences.drop(1).map { it.text })
        assertEquals("Chapter 1", narration.lastRequest?.topicTitle)
    }

    @Test
    fun `listening progress survives leaving and reopening`() = runBlocking {
        val first = newSession()
        first.open(BOOK)
        first.await { it.ready() }
        first.play()
        assertEquals(SessionPhase.LISTENING, first.state.value.phase)
        narration.reachSentence(3) // "Beta one." in block 2
        first.pause()
        awaitProgress { it.blockIndex == 2 && it.mode == ReadMode.LISTEN }
        first.close()

        val second = newSession()
        second.open(BOOK)
        val s = second.await { it.ready() }
        assertEquals(3, s.sentenceIndex)
        assertEquals(ReadMode.LISTEN, s.mode)
        assertEquals(SessionPhase.PAUSED, s.phase)
        assertEquals(3, narration.lastRequest?.startSentence)
    }

    @Test
    fun `conversation pauses narration and resumes at the interrupted sentence`() = runBlocking {
        val session = newSession()
        session.open(BOOK)
        session.await { it.ready() }
        session.play()
        narration.reachSentence(2)

        val ctx = session.beginConversation()
        assertNotNull(ctx)
        assertEquals("Alpha two.", ctx!!.currentSentence)
        assertEquals(NarrationStatus.PAUSED, narration.state.value.status)
        assertEquals(SessionPhase.CONVERSATION, session.state.value.phase)

        // Talking must not move the reading position.
        narration.reachSentence(4)
        assertEquals(2, session.state.value.sentenceIndex)

        session.endConversation(resume = true, messagesExchanged = 2)
        assertEquals(2, narration.lastSeek)
        assertEquals(NarrationStatus.PLAYING, narration.state.value.status)
        assertEquals(SessionPhase.LISTENING, session.state.value.phase)
        assertNull(session.state.value.resumeHint)
    }

    @Test
    fun `conversation started while reading does not start narration`() = runBlocking {
        val session = newSession()
        session.open(BOOK)
        session.await { it.ready() }
        session.beginConversation("Beta one. Beta two.")
        session.endConversation(resume = true, messagesExchanged = 6)
        assertEquals(SessionPhase.READING, session.state.value.phase)
        assertEquals(0, narration.playCount)
        assertNotNull("long talks get a recap line", session.state.value.resumeHint)
    }

    @Test
    fun `switching read and listen keeps the topic and sentence`() = runBlocking {
        val session = newSession()
        session.open(BOOK)
        session.await { it.ready() }
        session.nextTopic()
        session.await { it.topicIndex == 1 && it.ready() }
        session.setMode(ReadMode.LISTEN)
        narration.reachSentence(1)
        session.setMode(ReadMode.READ)
        val s = session.state.value
        assertEquals(1, s.topicIndex)
        assertEquals(1, s.sentenceIndex)
        assertEquals(SessionPhase.READING, s.phase)
        assertEquals(NarrationStatus.PAUSED, narration.state.value.status)
    }

    @Test
    fun `changing granularity keeps the reading position`() = runBlocking {
        val session = newSession()
        session.open(BOOK)
        session.await { it.ready() }
        narration.reachSentence(4) // "Beta two." in block 2
        session.setGranularity(Granularity.BITE)
        val s = session.await { it.topic?.granularity == Granularity.BITE && it.ready() && it.currentSentence?.blockIndex == 2 }
        assertEquals(1, s.topicIndex) // BITE topic 1 is block 2 alone
        assertEquals("Beta two.", s.currentSentence?.text)
    }

    @Test
    fun `topic end autoplays the next topic and keeps suggestions available`() = runBlocking {
        tutor.suggestions = listOf(TutorSuggestion(TutorAction.EXPLAIN, "Explain"))
        val session = newSession()
        session.open(BOOK)
        session.await { it.ready() }
        session.play()
        narration.events.emit(NarrationEvent.TopicFinished)
        val s = session.await { it.topicIndex == 1 && it.ready() }
        assertEquals(SessionPhase.LISTENING, s.phase)
        assertEquals(NarrationStatus.PLAYING, narration.state.value.status)
        assertEquals(0, s.suggestionsTopicIndex)
        assertEquals(1, s.suggestions.size)
    }

    @Test
    fun `without autoplay a finished topic offers guidance instead of moving on`() = runBlocking {
        settings.update { it.copy(autoplay = false) }
        tutor.suggestions = listOf(TutorSuggestion(TutorAction.EXPLORE, "Explore"))
        val session = newSession()
        session.open(BOOK)
        session.await { it.ready() }
        session.play()
        narration.events.emit(NarrationEvent.TopicFinished)
        val s = session.await { it.phase == SessionPhase.OPTIONAL_GUIDANCE }
        assertEquals(0, s.topicIndex)
        session.dismissSuggestions()
        assertTrue(session.state.value.suggestions.isEmpty())
        assertEquals(1, tutor.dismissed)
    }

    @Test
    fun `guided rendition narrates the guided text and falls back on failure`() = runBlocking {
        val session = newSession()
        session.open(BOOK)
        session.await { it.ready() }
        session.setRendition(Rendition.GUIDED)
        val s = session.await { it.guided is GuidedState.Ready && it.sentences.first().text.startsWith("In short") }
        assertEquals("In short, alpha matters.", s.sentences.first().text)

        guided.fail = true
        session.nextTopic()
        val failed = session.await { it.topicIndex == 1 && it.guided is GuidedState.Failed }
        assertEquals("offline", (failed.guided as GuidedState.Failed).message)
        assertEquals("Section B", failed.sentences.first().text) // original text still narrates
    }

    @Test
    fun `guided rendition explains itself when AI is off`() = runBlocking {
        settings.update { it.copy(aiMode = AiMode.OFF) }
        val session = newSession()
        session.open(BOOK)
        session.await { it.ready() }
        session.setRendition(Rendition.GUIDED)
        val s = session.await { it.guided is GuidedState.Failed }
        assertTrue((s.guided as GuidedState.Failed).message.contains("Settings"))
        assertEquals(0, guided.calls)
    }

    @Test
    fun `citation jump remembers where the reader was`() = runBlocking {
        val session = newSession()
        session.open(BOOK)
        session.await { it.ready() }
        narration.reachSentence(2)
        session.beginConversation()
        session.goToBlock(5)
        val there = session.await { it.topicIndex == 1 && it.focusBlock == 5 }
        assertNotNull(there.returnTo)
        assertFalse(there.phase == SessionPhase.CONVERSATION)
        session.returnToSaved()
        val back = session.await { it.topicIndex == 0 && it.ready() && it.returnTo == null }
        assertEquals(2, back.sentenceIndex)
    }

    @Test
    fun `navigation commands from a conversation end it at the new topic`() = runBlocking {
        val session = newSession()
        session.open(BOOK)
        session.await { it.ready() }
        session.beginConversation()
        session.endConversationAndNavigate(+1)
        val s = session.await { it.topicIndex == 1 && it.ready() }
        assertEquals(SessionPhase.READING, s.phase)
        assertNull(s.saved)
    }

    @Test
    fun `media buttons and topic ends are ignored while talking`() = runBlocking {
        val session = newSession()
        session.open(BOOK)
        session.await { it.ready() }
        session.play()
        session.beginConversation()
        narration.events.emit(NarrationEvent.TopicFinished)
        narration.events.emit(NarrationEvent.NextTopicRequested)
        val s = session.state.value
        assertEquals(0, s.topicIndex)
        assertEquals(SessionPhase.CONVERSATION, s.phase)
        assertEquals(NarrationStatus.PAUSED, narration.state.value.status)
    }

    @Test
    fun `guided text that arrives during a conversation waits and then resumes cleanly`() = runBlocking {
        guided.gate = kotlinx.coroutines.CompletableDeferred()
        val session = newSession()
        session.open(BOOK)
        session.await { it.ready() }
        session.setRendition(Rendition.GUIDED)
        session.play() // waits for the guided text instead of reading the original aloud
        assertEquals(0, narration.playCount)
        session.beginConversation()
        guided.gate!!.complete(Unit)
        session.await { it.guided is GuidedState.Ready }
        assertEquals(0, narration.playCount)
        assertEquals(SessionPhase.CONVERSATION, session.state.value.phase)

        session.endConversation(resume = true)
        val s = session.await { it.phase == SessionPhase.LISTENING && it.sentences.first().text.startsWith("In short") }
        assertEquals(NarrationStatus.PLAYING, narration.state.value.status)
        assertEquals(0, s.sentenceIndex)
    }

    @Test
    fun `chained citation jumps return to the original place without saving the detour`() = runBlocking {
        val session = newSession()
        session.open(BOOK)
        session.await { it.ready() }
        narration.reachSentence(2)
        awaitProgress { it.blockIndex == 1 }
        session.goToBlock(4)
        session.await { it.focusBlock == 4 && it.ready() }
        session.goToBlock(5)
        session.await { it.focusBlock == 5 && it.ready() }
        assertEquals(1, reading.progress(BOOK)!!.blockIndex)
        session.returnToSaved()
        val back = session.await { it.topicIndex == 0 && it.returnTo == null && it.ready() }
        assertEquals(2, back.sentenceIndex)
    }

    @Test
    fun `phase rules`() {
        assertEquals(PhaseRules.TopicEnd.ADVANCE_AND_PLAY, PhaseRules.onTopicFinished(autoplay = true, hasNext = true, hasSuggestions = true))
        assertEquals(PhaseRules.TopicEnd.SHOW_GUIDANCE, PhaseRules.onTopicFinished(autoplay = true, hasNext = false, hasSuggestions = true))
        assertEquals(PhaseRules.TopicEnd.STOP, PhaseRules.onTopicFinished(autoplay = false, hasNext = true, hasSuggestions = false))
        val saved = SavedPosition(0, 0, com.livingbook.model.ReadingPosition(0), Rendition.ORIGINAL, wasPlaying = true, startedAt = 0)
        assertEquals(SessionPhase.LISTENING, PhaseRules.afterConversation(saved, resume = true, mode = ReadMode.LISTEN))
        assertEquals(SessionPhase.PAUSED, PhaseRules.afterConversation(saved, resume = false, mode = ReadMode.LISTEN))
        assertEquals(SessionPhase.READING, PhaseRules.afterConversation(saved.copy(wasPlaying = false), resume = true, mode = ReadMode.READ))
        assertTrue(PhaseRules.needsRecap(saved, now = 3 * 60_000, messagesExchanged = 1))
        assertFalse(PhaseRules.needsRecap(saved, now = 10_000, messagesExchanged = 2))
    }

    // ---- fixtures -------------------------------------------------------------------------------------------------

    private suspend fun seedBook() {
        val texts = listOf(
            BlockKind.HEADING to "Chapter 1",
            BlockKind.PARAGRAPH to "Alpha one. Alpha two.",
            BlockKind.PARAGRAPH to "Beta one. Beta two.",
            BlockKind.HEADING to "Section B",
            BlockKind.PARAGRAPH to "Gamma one. Gamma two.",
            BlockKind.PARAGRAPH to "Delta one.",
        )
        val blocks = texts.mapIndexed { i, (kind, text) ->
            BlockEntity(bookId = BOOK, idx = i, kind = kind, text = text, headingLevel = if (kind == BlockKind.HEADING) 1 else 0, page = null, location = null)
        }
        fun topic(g: Granularity, idx: Int, title: String, start: Int, end: Int) = TopicEntity(BOOK, g, idx, title, start, end, "Chapter 1")
        val topics = listOf(
            topic(Granularity.BALANCED, 0, "Chapter 1", 0, 2),
            topic(Granularity.BALANCED, 1, "Section B", 3, 5),
            topic(Granularity.BITE, 0, "Chapter 1", 0, 1),
            topic(Granularity.BITE, 1, "Beta", 2, 2),
            topic(Granularity.BITE, 2, "Section B", 3, 4),
            topic(Granularity.BITE, 3, "Delta", 5, 5),
            topic(Granularity.DEEP, 0, "Chapter 1", 0, 5),
        )
        db.books().insertBookWithContent(
            BookEntity(BOOK, "Test Book", "Author", BookFormat.EPUB, "/dev/null", 0, null, blocks.size, null, ""),
            blocks, emptyList(), topics,
        )
    }

    private companion object {
        const val BOOK = "book-1"
    }
}

private class FakeNarration : NarrationService {
    override val state = MutableStateFlow(NarrationState(engineReady = true))
    override val events = MutableSharedFlow<NarrationEvent>(extraBufferCapacity = 8)
    override val voices = MutableStateFlow(emptyList<VoiceOption>())
    var lastRequest: NarrationRequest? = null
    var lastSeek: Int? = null
    var playCount = 0

    fun reachSentence(i: Int) = state.update { it.copy(sentenceIndex = i) }

    override fun load(request: NarrationRequest) {
        lastRequest = request
        state.update { it.copy(sentenceIndex = request.startSentence, sentenceCount = request.sentences.size, status = if (it.status == NarrationStatus.PLAYING) NarrationStatus.PAUSED else it.status) }
    }
    override fun play() { playCount++; state.update { it.copy(status = NarrationStatus.PLAYING) } }
    override fun pause() = state.update { it.copy(status = if (it.status == NarrationStatus.PLAYING) NarrationStatus.PAUSED else it.status) }
    override fun stop() = state.update { it.copy(status = NarrationStatus.IDLE) }
    override fun seekToSentence(index: Int) { lastSeek = index; state.update { it.copy(sentenceIndex = index) } }
    override fun skipForward() = state.update { it.copy(sentenceIndex = it.sentenceIndex + 1) }
    override fun skipBack() = state.update { it.copy(sentenceIndex = (it.sentenceIndex - 1).coerceAtLeast(0)) }
    override fun setRate(rate: Float) = Unit
    override fun setVoice(voiceId: String?) = Unit
    override fun speakOnce(text: String) = Unit
    override fun stopSpeakingOnce() = Unit
}

private class FakeGuided : GuidedContentGenerator {
    var fail = false
    var calls = 0
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    override suspend fun guidedFor(context: ReadingContext): GuidedContent {
        calls++
        gate?.await()
        if (fail) throw AiException("offline")
        return GuidedContent(listOf(GuidedSegment(GuidedKind.RESTATED, "In short, alpha matters.", listOf(context.topic.startBlock + 1))), isMock = false, model = "fake")
    }
}

private class FakeTutor : TutorEngine {
    var suggestions = emptyList<TutorSuggestion>()
    var dismissed = 0
    override suspend fun suggestionsFor(bookId: String, topic: TopicEntity) = suggestions
    override suspend fun dismiss(bookId: String, topic: TopicEntity) { dismissed++; suggestions = emptyList() }
}
