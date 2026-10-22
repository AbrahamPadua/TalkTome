package com.livingbook.session

import com.livingbook.ai.AiException
import com.livingbook.ai.GuidedContentGenerator
import com.livingbook.ai.ReadingContext
import com.livingbook.ai.TutorEngine
import com.livingbook.ai.TutorSuggestion
import com.livingbook.data.AiMode
import com.livingbook.data.ReadingRepository
import com.livingbook.data.SettingsRepository
import com.livingbook.data.db.BlockEntity
import com.livingbook.data.db.BookEntity
import com.livingbook.data.db.ProgressEntity
import com.livingbook.data.db.SectionEntity
import com.livingbook.data.db.TopicEntity
import com.livingbook.model.GuidedContent
import com.livingbook.model.Granularity
import com.livingbook.model.ReadMode
import com.livingbook.model.ReadingPosition
import com.livingbook.model.Rendition
import com.livingbook.speech.NarrationEvent
import com.livingbook.speech.NarrationRequest
import com.livingbook.speech.NarrationService
import com.livingbook.speech.NarrationStatus
import com.livingbook.speech.Sentence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SessionPhase { READING, LISTENING, CONVERSATION, OPTIONAL_GUIDANCE, PAUSED }

sealed interface GuidedState {
    data object Idle : GuidedState
    data object Loading : GuidedState
    data class Ready(val content: GuidedContent) : GuidedState
    data class Failed(val message: String) : GuidedState
}

/** Where the reader was when a conversation started. Restored when it ends. */
data class SavedPosition(
    val topicIndex: Int,
    val sentenceIndex: Int,
    val position: ReadingPosition,
    val rendition: Rendition,
    val wasPlaying: Boolean,
    val startedAt: Long,
    /** Text of the interrupted sentence: checks the sentence list is unchanged, and feeds the recap line. */
    val sentenceText: String? = null,
)

data class SessionState(
    val book: BookEntity? = null,
    val topics: List<TopicEntity> = emptyList(),
    val sections: List<SectionEntity> = emptyList(),
    val topicIndex: Int = 0,
    val blocks: List<BlockEntity> = emptyList(),
    val granularity: Granularity = Granularity.BALANCED,
    val rendition: Rendition = Rendition.ORIGINAL,
    val mode: ReadMode = ReadMode.READ,
    val phase: SessionPhase = SessionPhase.READING,
    val sentences: List<Sentence> = emptyList(),
    val sentenceIndex: Int = 0,
    val guided: GuidedState = GuidedState.Idle,
    /** Optional tutoring offers for [suggestionsTopicIndex]. Shown, never forced. */
    val suggestions: List<TutorSuggestion> = emptyList(),
    val suggestionsTopicIndex: Int = 0,
    /** Block to scroll to and briefly highlight (citation jump, resume). */
    val focusBlock: Int? = null,
    /** Shown after a long conversation: where reading picks up again. */
    val resumeHint: String? = null,
    /** Set after jumping to a cited passage, so the reader can go back. */
    val returnTo: SavedPosition? = null,
    val saved: SavedPosition? = null,
    /** Transient, user-facing message (errors, interruptions). */
    val message: String? = null,
) {
    val topic: TopicEntity? get() = topics.getOrNull(topicIndex)
    val hasNext: Boolean get() = topicIndex < topics.lastIndex
    val hasPrevious: Boolean get() = topicIndex > 0
    val currentSentence: Sentence? get() = sentences.getOrNull(sentenceIndex)
    val position: ReadingPosition
        get() = currentSentence?.let { ReadingPosition(it.blockIndex, it.charOffset) }
            ?: ReadingPosition(topic?.startBlock ?: 0)
}

/** Pure decisions of the reading-conversation state machine, kept separate so they are easy to test. */
object PhaseRules {
    enum class TopicEnd { ADVANCE_AND_PLAY, SHOW_GUIDANCE, STOP }

    fun onTopicFinished(autoplay: Boolean, hasNext: Boolean, hasSuggestions: Boolean): TopicEnd = when {
        autoplay && hasNext -> TopicEnd.ADVANCE_AND_PLAY
        hasSuggestions -> TopicEnd.SHOW_GUIDANCE
        else -> TopicEnd.STOP
    }

    fun afterConversation(saved: SavedPosition?, resume: Boolean, mode: ReadMode): SessionPhase = when {
        saved?.wasPlaying == true && resume -> SessionPhase.LISTENING
        mode == ReadMode.LISTEN -> SessionPhase.PAUSED
        else -> SessionPhase.READING
    }

    /** A recap line is worth showing only after a real detour. */
    fun needsRecap(saved: SavedPosition, now: Long, messagesExchanged: Int): Boolean =
        messagesExchanged >= 4 || now - saved.startedAt > 2 * 60_000

    fun idlePhase(mode: ReadMode): SessionPhase = if (mode == ReadMode.LISTEN) SessionPhase.PAUSED else SessionPhase.READING
}

/**
 * App-scoped coordinator for the open book: topic navigation, read/listen, original/guided, conversation
 * interruptions and progress persistence. Narration keeps going in the background, so this lives outside any ViewModel.
 */
class ReadingSession(
    private val reading: ReadingRepository,
    private val settings: SettingsRepository,
    private val narration: NarrationService,
    private val guidedGenerator: GuidedContentGenerator,
    private val tutor: TutorEngine,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var guidedJob: Job? = null
    /** Narration was asked to play while the guided text was still being generated. */
    private var playWhenGuidedReady = false

    init {
        scope.launch {
            narration.state.collect { n ->
                val s = _state.value
                if (s.book == null || s.phase == SessionPhase.CONVERSATION) return@collect
                if (n.sentenceIndex != s.sentenceIndex && n.sentenceIndex in s.sentences.indices) {
                    _state.update { it.copy(sentenceIndex = n.sentenceIndex) }
                    saveProgress()
                }
                if (n.status == NarrationStatus.ERROR && n.errorMessage != null) {
                    _state.update { it.copy(phase = PhaseRules.idlePhase(it.mode), message = n.errorMessage) }
                }
            }
        }
        scope.launch { narration.events.collect(::onNarrationEvent) }
    }

    // ---- Opening and navigation ---------------------------------------------------------------------------------

    suspend fun open(bookId: String) {
        if (_state.value.book?.id == bookId) return
        loadJob?.cancel()
        guidedJob?.cancel()
        narration.stop()
        val book = reading.book(bookId) ?: run {
            _state.update { it.copy(message = "This book is no longer in your library.") }
            return
        }
        val prefs = settings.current()
        val progress = reading.progress(bookId)
        val topics = reading.topics(bookId, prefs.granularity)
        val position = progress?.let { ReadingPosition(it.blockIndex, it.charOffset) } ?: ReadingPosition(0)
        val index = topicIndexFor(topics, position.blockIndex)
        _state.value = SessionState(
            book = book,
            topics = topics,
            sections = reading.sections(bookId),
            topicIndex = index,
            granularity = prefs.granularity,
            rendition = progress?.rendition ?: Rendition.ORIGINAL,
            mode = progress?.mode ?: ReadMode.READ,
            phase = PhaseRules.idlePhase(progress?.mode ?: ReadMode.READ),
        )
        narration.setRate(prefs.ttsRate)
        narration.setVoice(prefs.ttsVoice)
        reading.markOpened(bookId)
        loadTopic(index, position, play = false).join()
    }

    fun close() {
        narration.stop()
        loadJob?.cancel()
        guidedJob?.cancel()
        playWhenGuidedReady = false
        _state.value = SessionState()
    }

    fun nextTopic() = goToTopic(_state.value.topicIndex + 1)

    fun previousTopic() = goToTopic(_state.value.topicIndex - 1)

    fun goToTopic(index: Int) {
        val s = _state.value
        if (index !in s.topics.indices) return
        loadTopic(index, ReadingPosition(s.topics[index].startBlock), play = isPlaying() || playWhenGuidedReady)
    }

    /**
     * Jump to a cited passage. Remembers where the reader was (the first place, across chained jumps) so they can go
     * back, and does not overwrite saved progress with the detour.
     */
    fun goToBlock(blockIndex: Int) {
        val s = _state.value
        val here = s.returnTo ?: s.saved ?: snapshot(wasPlaying = isPlaying())
        narration.stopSpeakingOnce()
        narration.pause()
        _state.update { it.copy(returnTo = here, saved = null, phase = PhaseRules.idlePhase(it.mode)) }
        loadTopic(topicIndexFor(s.topics, blockIndex), ReadingPosition(blockIndex), play = false, focus = blockIndex, persist = false)
    }

    fun returnToSaved() {
        val back = _state.value.returnTo ?: return
        _state.update { it.copy(returnTo = null, rendition = back.rendition) }
        loadTopic(back.topicIndex, back.position, play = back.wasPlaying, focus = back.position.blockIndex)
    }

    fun consumeFocus() = _state.update { it.copy(focusBlock = null) }

    fun consumeMessage() = _state.update { it.copy(message = null, resumeHint = null) }

    // ---- Modes ----------------------------------------------------------------------------------------------------

    fun setMode(mode: ReadMode) {
        val s = _state.value
        if (s.mode == mode) return
        _state.update { it.copy(mode = mode, focusBlock = it.currentSentence?.blockIndex) }
        if (mode == ReadMode.LISTEN) play() else {
            narration.pause()
            playWhenGuidedReady = false
            _state.update { it.copy(phase = SessionPhase.READING) }
        }
        saveProgress()
    }

    fun setRendition(rendition: Rendition) {
        val s = _state.value
        if (s.rendition == rendition || s.topic == null) return
        val position = s.position
        val wasPlaying = isPlaying() || playWhenGuidedReady
        playWhenGuidedReady = false
        _state.update { it.copy(rendition = rendition) }
        rebuildSentences(position, wasPlaying)
        if (rendition == Rendition.GUIDED) ensureGuided()
        saveProgress()
    }

    fun setGranularity(granularity: Granularity) {
        val s = _state.value
        val book = s.book ?: return
        if (s.granularity == granularity) return
        loadJob?.cancel()
        loadJob = scope.launch {
            settings.update { it.copy(granularity = granularity) }
            val topics = reading.topics(book.id, granularity)
            val position = _state.value.position
            val index = topicIndexFor(topics, position.blockIndex)
            fun SavedPosition.remap() = copy(topicIndex = topicIndexFor(topics, this.position.blockIndex))
            _state.update {
                it.copy(topics = topics, granularity = granularity, topicIndex = index, returnTo = it.returnTo?.remap(), saved = it.saved?.remap())
            }
            loadTopic(index, position, play = isPlaying() || playWhenGuidedReady)
        }
    }

    // ---- Playback -------------------------------------------------------------------------------------------------

    fun play() {
        val s = _state.value
        if (s.phase == SessionPhase.CONVERSATION || s.topic == null) return
        _state.update { it.copy(mode = ReadMode.LISTEN, phase = SessionPhase.LISTENING) }
        if (s.rendition == Rendition.GUIDED && (s.guided is GuidedState.Idle || s.guided is GuidedState.Loading)) {
            playWhenGuidedReady = true
            return
        }
        narration.play()
    }

    fun pause() {
        playWhenGuidedReady = false
        narration.pause()
        _state.update { if (it.phase == SessionPhase.CONVERSATION) it else it.copy(phase = PhaseRules.idlePhase(it.mode)) }
        saveProgress()
    }

    fun togglePlay() = if (isPlaying() || playWhenGuidedReady) pause() else play()

    fun skipForward() = narration.skipForward()

    fun skipBack() = narration.skipBack()

    /** Re-reads the current sentence (the "repeat that" command). */
    fun repeatSentence() {
        narration.seekToSentence(_state.value.sentenceIndex)
        play()
    }

    fun setRate(rate: Float) {
        narration.setRate(rate)
        scope.launch { settings.update { it.copy(ttsRate = rate) } }
    }

    fun setVoice(voiceId: String?) {
        narration.setVoice(voiceId)
        scope.launch { settings.update { it.copy(ttsVoice = voiceId) } }
    }

    private fun isPlaying() = narration.state.value.status == NarrationStatus.PLAYING

    // ---- Conversation ---------------------------------------------------------------------------------------------

    /** Pauses narration, remembers the exact sentence, and enters CONVERSATION. */
    fun beginConversation(selectedText: String? = null): ReadingContext? {
        val s = _state.value
        if (s.book == null || s.topic == null) return null
        if (s.phase != SessionPhase.CONVERSATION) {
            val saved = snapshot(wasPlaying = isPlaying() || playWhenGuidedReady)
            playWhenGuidedReady = false
            narration.pause()
            _state.update { it.copy(phase = SessionPhase.CONVERSATION, saved = saved, resumeHint = null) }
        }
        return readingContext(selectedText)
    }

    /**
     * Leaves the conversation and restores the reading position at the start of the interrupted sentence.
     * Narration resumes only if it was playing and [resume] is true.
     */
    fun endConversation(resume: Boolean, messagesExchanged: Int = 0) {
        val s = _state.value
        narration.stopSpeakingOnce()
        if (s.phase != SessionPhase.CONVERSATION) return
        val saved = s.saved
        val phase = PhaseRules.afterConversation(saved, resume, s.mode)
        val hint = saved?.takeIf { PhaseRules.needsRecap(it, clock(), messagesExchanged) }?.sentenceText
            ?.let { "Picking up at: “${it.take(90)}${if (it.length > 90) "…" else ""}”" }
        _state.update { it.copy(phase = phase, saved = null, resumeHint = hint) }
        if (saved == null) return
        val play = phase == SessionPhase.LISTENING
        val sameTopic = saved.topicIndex == s.topicIndex && saved.rendition == s.rendition
        // Guided text may have arrived during the talk; then the sentence list changes and the position is remapped.
        val unchanged = sameTopic && s.sentences == sentencesFor(s) && s.sentences.getOrNull(saved.sentenceIndex)?.text == saved.sentenceText
        when {
            unchanged -> {
                _state.update { it.copy(sentenceIndex = saved.sentenceIndex) }
                narration.seekToSentence(saved.sentenceIndex)
                if (play) play()
            }
            sameTopic -> rebuildSentences(saved.position, play)
            else -> {
                _state.update { it.copy(rendition = saved.rendition) }
                loadTopic(saved.topicIndex, saved.position, play = play)
            }
        }
    }

    /** A navigation command issued from the conversation ("skip this topic", "go back") ends it at the new place. */
    fun endConversationAndNavigate(delta: Int) {
        val s = _state.value
        val wasPlaying = s.saved?.wasPlaying == true
        _state.update { it.copy(phase = PhaseRules.idlePhase(it.mode), saved = null) }
        narration.stopSpeakingOnce()
        val target = (s.topicIndex + delta).coerceIn(0, (s.topics.size - 1).coerceAtLeast(0))
        loadTopic(target, ReadingPosition(s.topics.getOrNull(target)?.startBlock ?: 0), play = wasPlaying)
    }

    fun readingContext(selectedText: String? = null, topicIndex: Int = _state.value.topicIndex): ReadingContext? {
        val s = _state.value
        val book = s.book ?: return null
        val topic = s.topics.getOrNull(topicIndex) ?: return null
        return ReadingContext(
            bookId = book.id,
            bookTitle = book.title,
            author = book.author,
            topic = topic,
            topicBlocks = if (topicIndex == s.topicIndex) s.blocks else emptyList(),
            currentSentence = s.saved?.sentenceText ?: s.currentSentence?.text,
            rendition = s.rendition,
            selectedText = selectedText,
        )
    }

    // ---- Tutoring -------------------------------------------------------------------------------------------------

    fun dismissSuggestions() {
        val s = _state.value
        val book = s.book ?: return
        val topic = s.topics.getOrNull(s.suggestionsTopicIndex) ?: return
        _state.update { it.copy(suggestions = emptyList(), phase = if (it.phase == SessionPhase.OPTIONAL_GUIDANCE) PhaseRules.idlePhase(it.mode) else it.phase) }
        scope.launch { tutor.dismiss(book.id, topic) }
    }

    private suspend fun refreshSuggestions(topicIndex: Int) {
        val s = _state.value
        val book = s.book ?: return
        val topic = s.topics.getOrNull(topicIndex) ?: return
        val suggestions = tutor.suggestionsFor(book.id, topic)
        _state.update { it.copy(suggestions = suggestions, suggestionsTopicIndex = topicIndex) }
    }

    private fun onNarrationEvent(event: NarrationEvent) {
        val s = _state.value
        val book = s.book ?: return
        // Media buttons and late callbacks must not move the book while the user is talking.
        if (s.phase == SessionPhase.CONVERSATION && event !is NarrationEvent.Interrupted) return
        when (event) {
            NarrationEvent.TopicFinished -> scope.launch {
                val finished = s.topicIndex
                refreshSuggestions(finished)
                val autoplay = settings.current().autoplay
                val now = _state.value
                // The user may have navigated, opened another book or started talking meanwhile.
                if (now.book?.id != book.id || now.topicIndex != finished || now.phase == SessionPhase.CONVERSATION) return@launch
                when (PhaseRules.onTopicFinished(autoplay, now.hasNext, now.suggestions.isNotEmpty())) {
                    PhaseRules.TopicEnd.ADVANCE_AND_PLAY ->
                        loadTopic(finished + 1, ReadingPosition(now.topics[finished + 1].startBlock), play = true, keepSuggestions = true)
                    PhaseRules.TopicEnd.SHOW_GUIDANCE -> _state.update { it.copy(phase = SessionPhase.OPTIONAL_GUIDANCE) }
                    PhaseRules.TopicEnd.STOP -> _state.update { it.copy(phase = SessionPhase.PAUSED) }
                }
            }
            NarrationEvent.NextTopicRequested -> nextTopic()
            NarrationEvent.PreviousTopicRequested -> previousTopic()
            is NarrationEvent.Interrupted -> {
                playWhenGuidedReady = false
                if (s.phase != SessionPhase.CONVERSATION) _state.update { it.copy(phase = SessionPhase.PAUSED) }
                saveProgress()
            }
        }
    }

    // ---- Internals ------------------------------------------------------------------------------------------------

    private fun snapshot(wasPlaying: Boolean): SavedPosition {
        val s = _state.value
        return SavedPosition(s.topicIndex, s.sentenceIndex, s.position, s.rendition, wasPlaying, clock(), s.currentSentence?.text)
    }

    private fun loadTopic(
        index: Int,
        position: ReadingPosition,
        play: Boolean,
        focus: Int? = null,
        keepSuggestions: Boolean = false,
        persist: Boolean = true,
    ): Job {
        loadJob?.cancel()
        guidedJob?.cancel()
        playWhenGuidedReady = false
        // Silence the old topic right away so it cannot finish (and auto-advance) while the new one loads.
        if (index != _state.value.topicIndex) narration.pause()
        return scope.launch {
            val book = _state.value.book ?: return@launch
            val topic = _state.value.topics.getOrNull(index) ?: return@launch
            val blocks = reading.blocks(book.id, topic.startBlock, topic.endBlock)
            val rendition = _state.value.rendition
            val cached = if (rendition == Rendition.GUIDED) reading.guided(book.id, topic.startBlock, topic.endBlock) else null
            val talking = _state.value.phase == SessionPhase.CONVERSATION
            val playNow = play && !talking
            _state.update {
                it.copy(
                    topicIndex = index,
                    blocks = blocks,
                    guided = cached?.let(GuidedState::Ready) ?: GuidedState.Idle,
                    focusBlock = focus,
                    suggestions = if (keepSuggestions) it.suggestions else emptyList(),
                    phase = when {
                        talking -> it.phase
                        playNow -> SessionPhase.LISTENING
                        else -> PhaseRules.idlePhase(it.mode)
                    },
                )
            }
            rebuildSentences(position, playNow)
            if (rendition == Rendition.GUIDED && cached == null) ensureGuided()
            if (persist) saveProgress()
            if (!keepSuggestions && _state.value.mode == ReadMode.READ) refreshSuggestions(index)
        }.also { loadJob = it }
    }

    /**
     * Recomputes narration sentences for the current rendition and reloads narration at [position].
     * While a guided rendition is still being generated, playback waits for it instead of reading the original aloud.
     */
    private fun rebuildSentences(position: ReadingPosition, play: Boolean) {
        val s = _state.value
        val book = s.book ?: return
        val topic = s.topic ?: return
        val guided = (s.guided as? GuidedState.Ready)?.content
        val sentences = sentencesFor(s)
        val index = Sentences.indexAt(sentences, position)
        // State first: when narration.load reports the new index it already matches, so nothing is applied to the wrong list.
        _state.update { it.copy(sentences = sentences, sentenceIndex = index) }
        narration.load(NarrationRequest(book.title, topic.title, sentences.map { it.text }, index))
        val waitingForGuided = s.rendition == Rendition.GUIDED && guided == null && s.guided !is GuidedState.Failed
        when {
            !play -> Unit
            waitingForGuided -> playWhenGuidedReady = true
            else -> narration.play()
        }
    }

    /** Narration sentences for the current topic and rendition (original text while guided is not ready). */
    private fun sentencesFor(s: SessionState): List<Sentence> {
        val guided = (s.guided as? GuidedState.Ready)?.content
        return if (s.rendition == Rendition.GUIDED && guided != null) Sentences.fromGuided(guided, s.topic?.startBlock ?: 0)
        else Sentences.fromBlocks(s.blocks)
    }

    private fun ensureGuided() {
        val s = _state.value
        val topic = s.topic ?: return
        if (s.guided is GuidedState.Ready || s.guided is GuidedState.Loading) return
        guidedJob?.cancel()
        guidedJob = scope.launch {
            val book = s.book ?: return@launch
            val cached = reading.guided(book.id, topic.startBlock, topic.endBlock)
            val result = when {
                cached != null -> GuidedState.Ready(cached)
                settings.current().aiMode == AiMode.OFF ->
                    GuidedState.Failed("Guided content needs AI. Turn on AI in Settings, or keep reading the original.")
                else -> {
                    _state.update { it.copy(guided = GuidedState.Loading) }
                    val context = readingContext() ?: return@launch
                    try {
                        GuidedState.Ready(guidedGenerator.guidedFor(context))
                    } catch (e: AiException) {
                        GuidedState.Failed(e.userMessage)
                    }
                }
            }
            val now = _state.value
            if (now.topic != topic) return@launch
            _state.update { it.copy(guided = result) }
            // During a conversation the new text waits; endConversation rebuilds when the saved sentence is gone.
            if (now.rendition != Rendition.GUIDED || now.phase == SessionPhase.CONVERSATION) return@launch
            val play = playWhenGuidedReady || isPlaying()
            playWhenGuidedReady = false
            // On failure the original sentences are already loaded; start them if playback was requested.
            if (result is GuidedState.Ready) rebuildSentences(now.position, play) else if (play) narration.play()
        }
    }

    private fun saveProgress() {
        val s = _state.value
        val book = s.book ?: return
        if (s.sentences.isEmpty()) return // not loaded yet: never overwrite real progress with a default position
        val p = s.position
        scope.launch {
            reading.saveProgress(ProgressEntity(book.id, p.blockIndex, p.charOffset, s.rendition, s.mode, clock()))
        }
    }

    private fun topicIndexFor(topics: List<TopicEntity>, blockIndex: Int): Int =
        topics.indexOfFirst { blockIndex in it.startBlock..it.endBlock }.takeIf { it >= 0 } ?: 0
}
