package com.livingbook.ui.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livingbook.ai.CommandParser
import com.livingbook.ai.ConversationManager
import com.livingbook.ai.ReadingContext
import com.livingbook.ai.TutorAction
import com.livingbook.data.ReadingRepository
import com.livingbook.data.SettingsRepository
import com.livingbook.model.ChatMessage
import com.livingbook.model.ChatRole
import com.livingbook.session.ReadingSession
import com.livingbook.speech.NarrationService
import com.livingbook.speech.SpeechEvent
import com.livingbook.speech.SpeechInputService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The conversation sheet over the reader. The session owns pausing and resuming; this holds the open
 * conversation, sends messages, runs push-to-talk and decides when the sheet closes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModel(
    private val session: ReadingSession,
    private val conversations: ConversationManager,
    private val reading: ReadingRepository,
    private val settings: SettingsRepository,
    private val narration: NarrationService,
    private val speechInput: SpeechInputService,
) : ViewModel() {

    /** How the sheet was opened: plain Ask, a long-pressed passage, or a tutoring suggestion for some topic. */
    data class Opening(
        val selectedText: String? = null,
        val action: TutorAction? = null,
        val label: String? = null,
        val topicIndex: Int? = null,
    )

    /** Non-null while the sheet is open. */
    data class Opened(val topicTitle: String, val quote: String?)

    private val _opened = MutableStateFlow<Opened?>(null)
    val opened: StateFlow<Opened?> = _opened.asStateFlow()

    private val conversationId = MutableStateFlow<Long?>(null)
    val ready: StateFlow<Boolean> = conversationId.mapLatest { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val messages: StateFlow<List<ChatMessage>> = conversationId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else conversations.messages(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Page of each cited block, for "p. 12" chips; blocks without a page read "Passage". */
    val citationPages: StateFlow<Map<Int, Int?>> = messages
        .mapLatest { list ->
            val bookId = context?.bookId ?: return@mapLatest emptyMap()
            val cited = list.flatMap { m -> m.segments.flatMap { it.citations } }.distinct()
            reading.blocksAt(bookId, cited).associate { it.idx to it.page }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Transient line under the messages: a "remember this" confirmation or a microphone problem. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** What the microphone has heard so far; null when it is off. */
    private val _heard = MutableStateFlow<String?>(null)
    val heard: StateFlow<String?> = _heard.asStateFlow()

    private var context: ReadingContext? = null
    private var exchanged = 0
    private var listenJob: Job? = null
    private var replyJob: Job? = null

    // Bumped on every open, so a late reply from an earlier opening never speaks or closes the current sheet.
    private var openCount = 0

    fun open(opening: Opening = Opening()) {
        val begun = session.beginConversation(opening.selectedText) ?: return
        val ctx = opening.topicIndex?.let { session.readingContext(opening.selectedText, it) } ?: begun
        context = ctx
        exchanged = 0
        openCount++
        _notice.value = null
        _opened.value = Opened(ctx.topic.title, opening.selectedText)
        viewModelScope.launch {
            conversationId.value = null
            conversationId.value = conversations.open(ctx)
            opening.action?.let { send(opening.label ?: it.name, it, resumeAfterReply = false) }
        }
    }

    fun submit(text: String) {
        val message = text.trim()
        if (message.isEmpty() || _busy.value) return
        val history = messages.value
        val command = CommandParser.parse(message, hasActiveExplanation = history.lastOrNull()?.role == ChatRole.ASSISTANT)
        when (val step = conversationStep(message, command, history.lastOrNull { it.role == ChatRole.ASSISTANT }?.text)) {
            ConversationStep.Resume -> backToBook()
            is ConversationStep.Navigate -> {
                session.endConversationAndNavigate(step.delta)
                close()
            }
            is ConversationStep.RepeatAnswer -> narration.speakOnce(step.text)
            ConversationStep.RepeatSentence -> {
                session.endConversation(resume = false, messagesExchanged = exchanged)
                session.repeatSentence()
                close()
            }
            ConversationStep.Remember -> remember()
            is ConversationStep.Send -> send(message, step.action, step.resumeAfterReply)
        }
    }

    fun quickAction(label: String, action: TutorAction) = send(label, action, resumeAfterReply = false)

    fun backToBook() {
        session.endConversation(resume = true, messagesExchanged = exchanged)
        close()
    }

    /** Swipe or back: leave without resuming. Returns whether narration had been playing, so the reader can offer Resume. */
    fun dismiss(): Boolean {
        val wasPlaying = session.state.value.saved?.wasPlaying == true
        session.endConversation(resume = false, messagesExchanged = exchanged)
        close()
        return wasPlaying
    }

    /** Jumps to a cited passage; the session remembers where the reader was. */
    fun openCitation(blockIndex: Int) {
        session.goToBlock(blockIndex)
        close()
    }

    fun startListening() {
        if (listenJob?.isActive == true) return
        narration.stopSpeakingOnce()
        _notice.value = null
        _heard.value = ""
        listenJob = viewModelScope.launch {
            try {
                speechInput.listen().collect { event ->
                    when (event) {
                        SpeechEvent.Ready -> Unit
                        is SpeechEvent.Partial -> _heard.value = event.text
                        is SpeechEvent.Final -> {
                            _heard.value = null
                            submit(event.text)
                        }
                        is SpeechEvent.Error -> {
                            _heard.value = null
                            _notice.value = event.message
                        }
                    }
                }
            } finally {
                _heard.value = null
            }
        }
    }

    /** Stops the microphone and returns what it heard, so the text can be edited and sent. */
    fun stopListening(): String? {
        val heard = _heard.value
        listenJob?.cancel()
        _heard.value = null
        return heard?.takeIf { it.isNotBlank() }
    }

    fun microphoneDenied() {
        _notice.value = "Microphone access is off, so type your question instead. You can allow it in the app's system settings."
    }

    private fun remember() {
        val id = conversationId.value ?: return
        val ctx = context ?: return
        viewModelScope.launch { _notice.value = conversations.remember(id, ctx) }
    }

    private fun send(text: String, action: TutorAction, resumeAfterReply: Boolean) {
        val id = conversationId.value ?: return
        val ctx = context ?: return
        if (_busy.value) return
        val token = openCount
        _busy.value = true
        _notice.value = null
        replyJob?.cancel()
        replyJob = viewModelScope.launch {
            // Not cancelled when the sheet closes: the reply is still stored and shows up next time.
            val reply = try {
                conversations.send(id, ctx, text, action)
            } finally {
                _busy.value = false
            }
            exchanged += 2
            val current = token == openCount && _opened.value != null
            if (!current || reply.role != ChatRole.ASSISTANT || !settings.current().speakAiResponses) return@launch
            narration.speakOnce(reply.text)
            if (resumeAfterReply) {
                // Wait for the reply to start (briefly, in case speech fails), then for it to finish.
                withTimeoutOrNull(5_000) { narration.state.first { it.speakingReply } }
                narration.state.first { !it.speakingReply }
                if (token == openCount && _opened.value != null) backToBook()
            }
        }
    }

    private fun close() {
        _opened.value = null
        listenJob?.cancel()
        _heard.value = null
    }
}
