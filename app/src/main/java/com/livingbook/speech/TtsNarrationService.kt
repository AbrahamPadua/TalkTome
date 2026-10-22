package com.livingbook.speech

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Android TextToSpeech narration, one utterance per sentence, with audio focus and media session. */
class TtsNarrationService(private val context: Context) : NarrationService {
    override val state: StateFlow<NarrationState> = MutableStateFlow(NarrationState())
    override val events: Flow<NarrationEvent> = MutableSharedFlow()
    override val voices: StateFlow<List<VoiceOption>> = MutableStateFlow(emptyList())
    override fun load(request: NarrationRequest) = TODO("speech worker")
    override fun play() = TODO("speech worker")
    override fun pause() = TODO("speech worker")
    override fun stop() = TODO("speech worker")
    override fun seekToSentence(index: Int) = TODO("speech worker")
    override fun skipForward() = TODO("speech worker")
    override fun skipBack() = TODO("speech worker")
    override fun setRate(rate: Float) = TODO("speech worker")
    override fun setVoice(voiceId: String?) = TODO("speech worker")
    override fun speakOnce(text: String) = TODO("speech worker")
    override fun stopSpeakingOnce() = TODO("speech worker")
}
