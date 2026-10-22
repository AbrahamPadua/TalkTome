package com.livingbook.speech

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class NarrationStatus { IDLE, PLAYING, PAUSED, ENDED, ERROR }

data class NarrationState(
    val status: NarrationStatus = NarrationStatus.IDLE,
    /** Sentence currently being spoken (or where playback will resume). */
    val sentenceIndex: Int = 0,
    val sentenceCount: Int = 0,
    /** False until the TTS engine has initialized. */
    val engineReady: Boolean = false,
    val errorMessage: String? = null,
    /** True while [NarrationService.speakOnce] is talking (an AI reply). Narration never runs at the same time. */
    val speakingReply: Boolean = false,
)

data class VoiceOption(val id: String, val label: String, val localeTag: String, val needsNetwork: Boolean)

/** What to narrate: one topic, already split into sentences. */
data class NarrationRequest(
    val bookTitle: String,
    val topicTitle: String,
    val sentences: List<String>,
    val startSentence: Int = 0,
)

sealed interface NarrationEvent {
    /** The last sentence of the loaded topic finished. The session decides whether to continue. */
    data object TopicFinished : NarrationEvent
    /** Lock screen / headset "next" and "previous": move by topic. */
    data object NextTopicRequested : NarrationEvent
    data object PreviousTopicRequested : NarrationEvent
    /** Audio focus lost, headphones unplugged, call started. Playback is already paused when this arrives. */
    data class Interrupted(val reason: String) : NarrationEvent
}

/**
 * Sentence-level narration with background playback and media controls.
 * Implementations must never speak narration and [speakOnce] audio at the same time.
 * Positions are sentence indices; word-level sync is not promised.
 */
interface NarrationService {
    val state: StateFlow<NarrationState>
    val events: Flow<NarrationEvent>
    val voices: StateFlow<List<VoiceOption>>

    /** Replaces the current topic. Does not start playback. */
    fun load(request: NarrationRequest)
    fun play()
    fun pause()
    fun stop()
    fun seekToSentence(index: Int)
    fun skipForward()
    fun skipBack()
    fun setRate(rate: Float)
    fun setVoice(voiceId: String?)

    /** Speaks one standalone text (an AI reply). Pauses narration first if it is playing. */
    fun speakOnce(text: String)
    fun stopSpeakingOnce()
}

sealed interface SpeechEvent {
    data object Ready : SpeechEvent
    data class Partial(val text: String) : SpeechEvent
    data class Final(val text: String) : SpeechEvent
    data class Error(val message: String) : SpeechEvent
}

/** Push-to-talk speech recognition. The caller pauses narration before calling [listen]. */
interface SpeechInputService {
    val isAvailable: Boolean

    /** Starts one recognition session; the flow completes after [SpeechEvent.Final] or [SpeechEvent.Error]. Cancel to stop. */
    fun listen(): Flow<SpeechEvent>
}

/** Splits text into speakable sentences, keeping character offsets so positions map back to blocks. */
data class Sentence(val text: String, val blockIndex: Int, val charOffset: Int)
