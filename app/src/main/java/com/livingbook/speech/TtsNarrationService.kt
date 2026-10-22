package com.livingbook.speech

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

/**
 * Android TextToSpeech narration, one utterance per sentence, with audio focus and media session.
 * Call it from the main thread. Engine callbacks arrive on binder threads and are posted to the main thread.
 */
class TtsNarrationService(private val context: Context) : NarrationService {
    private val _state = MutableStateFlow(NarrationState())
    override val state: StateFlow<NarrationState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<NarrationEvent>(extraBufferCapacity = 16)
    override val events: Flow<NarrationEvent> = _events.asSharedFlow()
    private val _voices = MutableStateFlow<List<VoiceOption>>(emptyList())
    override val voices: StateFlow<List<VoiceOption>> = _voices.asStateFlow()
    private val _request = MutableStateFlow<NarrationRequest?>(null)

    /** The loaded topic, for the media session's metadata. */
    val request: StateFlow<NarrationRequest?> = _request.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val queue = SentenceQueue()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AUDIO_ATTRIBUTES)
        .setOnAudioFocusChangeListener(::onFocusChange, main)
        // Speech is never ducked by the system; pause for navigation prompts instead of talking over them.
        .setWillPauseWhenDucked(true)
        .build()
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = interrupt(HEADPHONES, resumeLater = false)
    }
    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) = Unit

        override fun onDone(utteranceId: String) {
            main.post { onUtteranceDone(utteranceId) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) {
            main.post { onUtteranceError(utteranceId) }
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            main.post { onUtteranceError(utteranceId) }
        }
    }

    private var rate = 1f
    private var voiceId: String? = null

    /** Null after a failure; the next [play] creates a fresh one. */
    private var engine: TextToSpeech? = createEngine()
    private var pendingPlay = false
    private var replyId: String? = null
    private var replyCount = 0
    private var holdingFocus = false
    private var resumeOnFocusGain = false
    private var controller: ListenableFuture<MediaController>? = null

    override fun load(request: NarrationRequest) {
        // A new topic never inherits a queued or after-a-call play from the old one.
        pendingPlay = false
        resumeOnFocusGain = false
        if (_state.value.status == NarrationStatus.PLAYING) engine?.stop()
        if (replyId == null) releaseFocus()
        _request.value = request
        queue.load(request.sentences.size, request.startSentence)
        publish(NarrationStatus.PAUSED)
    }

    override fun play() {
        if (_request.value == null) return
        cutReply()
        resumeOnFocusGain = false
        val status = _state.value.status
        if (status == NarrationStatus.PLAYING) return
        if (queue.size == 0) return finish()
        // Bind the media service now, while the user is in the app, even if the engine is still starting.
        ensurePlaybackService()
        if (engine == null || !_state.value.engineReady) {
            pendingPlay = true
            if (engine == null) engine = createEngine()
            return
        }
        if (!holdFocus()) return
        if (status == NarrationStatus.ENDED) queue.moveTo(0)
        publish(NarrationStatus.PLAYING)
        speakFromCurrent()
    }

    override fun pause() {
        pendingPlay = false
        resumeOnFocusGain = false
        if (_state.value.status == NarrationStatus.PLAYING) stopNarration()
        if (replyId == null) releaseFocus()
    }

    override fun stop() {
        pendingPlay = false
        resumeOnFocusGain = false
        engine?.stop()
        replyId = null
        _request.value = null
        queue.load(0, 0)
        _state.update { NarrationState(engineReady = it.engineReady) }
        releaseFocus()
        releaseController()
    }

    /** Unbinds from [PlaybackService] so it can stop once nothing plays. */
    internal fun releaseController() {
        controller?.let(MediaController::releaseFuture)
        controller = null
    }

    override fun seekToSentence(index: Int) {
        if (_request.value == null) return
        val status = _state.value.status
        queue.moveTo(index)
        publish(if (status == NarrationStatus.ENDED) NarrationStatus.PAUSED else status)
        if (status == NarrationStatus.PLAYING) speakFromCurrent()
    }

    override fun skipForward() = seekToSentence(queue.index + 1)

    override fun skipBack() = seekToSentence(queue.index - 1)

    override fun setRate(rate: Float) {
        this.rate = rate.coerceIn(0.5f, 2.5f)
        engine?.setSpeechRate(this.rate)
        // Utterances already queued keep the old rate, so restart the current sentence.
        if (_state.value.status == NarrationStatus.PLAYING) speakFromCurrent()
    }

    override fun setVoice(voiceId: String?) {
        this.voiceId = voiceId
        val tts = engine?.takeIf { _state.value.engineReady } ?: return // applied once the engine is ready
        if (!applyVoice(tts)) return fail(NO_VOICE)
        if (_state.value.status == NarrationStatus.PLAYING) speakFromCurrent()
    }

    override fun speakOnce(text: String) {
        // Without a ready engine the reply stays text-only; the ERROR state already explains a missing voice.
        if (engine == null || !_state.value.engineReady) return
        resumeOnFocusGain = false
        cutReply()
        if (_state.value.status == NarrationStatus.PLAYING) stopNarration()
        if (!holdFocus()) return releaseFocus()
        val id = "reply:${++replyCount}"
        replyId = id
        _state.update { it.copy(speakingReply = true) }
        speak(text, TextToSpeech.QUEUE_FLUSH, id)
    }

    override fun stopSpeakingOnce() {
        if (replyId == null) return
        cutReply()
        if (_state.value.status != NarrationStatus.PLAYING) releaseFocus()
    }

    /** Lets the media session report lock-screen and headset topic requests. */
    internal fun send(event: NarrationEvent) {
        _events.tryEmit(event)
    }

    // ---- Engine -------------------------------------------------------------------------------------------------

    private fun createEngine(): TextToSpeech {
        lateinit var tts: TextToSpeech
        // A failed init can be reported from inside the constructor, before tts is assigned, so always post it.
        tts = TextToSpeech(context) { status -> main.post { onEngineInit(tts, status) } }
        tts.setAudioAttributes(AUDIO_ATTRIBUTES)
        tts.setOnUtteranceProgressListener(progress)
        tts.setSpeechRate(rate)
        return tts
    }

    private fun onEngineInit(tts: TextToSpeech, status: Int) {
        if (tts !== engine) return
        if (status != TextToSpeech.SUCCESS || !applyVoice(tts)) return fail(NO_VOICE)
        _voices.value = voiceOptions(tts)
        _state.update { it.copy(engineReady = true) }
        if (_state.value.status == NarrationStatus.ERROR) {
            publish(if (_request.value == null) NarrationStatus.IDLE else NarrationStatus.PAUSED)
        }
        if (pendingPlay) {
            pendingPlay = false
            play()
        }
    }

    /** Uses the chosen voice, else the device language's default one. False if neither can speak. */
    private fun applyVoice(tts: TextToSpeech): Boolean {
        val chosen = voiceId?.let { id -> installedVoices(tts).firstOrNull { it.name == id } }
        if (chosen != null && tts.setVoice(chosen) == TextToSpeech.SUCCESS) return true
        return tts.setLanguage(Locale.getDefault()) >= TextToSpeech.LANG_AVAILABLE
    }

    private fun voiceOptions(tts: TextToSpeech): List<VoiceOption> {
        val here = Locale.getDefault()
        return installedVoices(tts)
            .sortedWith(compareBy({ it.locale != here }, { it.locale.language != here.language }, { it.locale.displayName }, { it.name }))
            .map { VoiceOption(it.name, "${it.locale.displayName}, ${it.name}", it.locale.toLanguageTag(), it.isNetworkConnectionRequired) }
    }

    // Some engines throw instead of returning null. Voices that still need a download cannot speak yet.
    private fun installedVoices(tts: TextToSpeech): List<Voice> = runCatching { tts.voices }.getOrNull().orEmpty()
        .filterNot { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features.orEmpty() }

    // ---- Speaking -----------------------------------------------------------------------------------------------

    /** Flushes the engine and queues the current sentence plus one ahead. */
    private fun speakFromCurrent() {
        val sentences = _request.value?.sentences ?: return
        queue.invalidate()
        for (i in queue.upcoming()) {
            val mode = if (i == queue.index) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (!speak(sentences[i], mode, queue.idFor(i))) return
        }
    }

    private fun speak(text: String, mode: Int, id: String): Boolean {
        val tts = engine ?: return false
        // shortcut: text past the engine limit (about 4000 chars) is cut off; split it if such sentences show up.
        val ok = tts.speak(text.take(TextToSpeech.getMaxSpeechInputLength()), mode, null, id) == TextToSpeech.SUCCESS
        if (!ok) onUtteranceError(id)
        return ok
    }

    private fun onUtteranceDone(id: String) {
        if (id == replyId) return endReply()
        when (val done = queue.onDone(id)) {
            SentenceQueue.Done.Stale -> Unit
            SentenceQueue.Done.Finished -> finish()
            is SentenceQueue.Done.Advanced -> {
                publish(NarrationStatus.PLAYING)
                val sentences = _request.value?.sentences ?: return
                done.enqueue?.let { speak(sentences[it], TextToSpeech.QUEUE_ADD, queue.idFor(it)) }
            }
        }
    }

    private fun onUtteranceError(id: String) {
        if (id == replyId) endReply() else if (queue.owns(id)) fail(SPEAK_FAILED)
    }

    private fun finish() {
        queue.invalidate()
        publish(NarrationStatus.ENDED)
        releaseFocus()
        _events.tryEmit(NarrationEvent.TopicFinished)
    }

    /** Stops narration audio and keeps the position. Audio focus is the caller's business. */
    private fun stopNarration() {
        queue.invalidate()
        engine?.stop()
        publish(NarrationStatus.PAUSED)
    }

    /** Shows [message] and drops the engine, so the next play starts a fresh one (the user may have installed a voice). */
    private fun fail(message: String) {
        pendingPlay = false
        queue.invalidate()
        engine?.shutdown()
        engine = null
        replyId = null
        _state.update { it.copy(engineReady = false, speakingReply = false) }
        publish(NarrationStatus.ERROR, message)
        releaseFocus()
    }

    /** Stops a reply without touching audio focus. */
    private fun cutReply() {
        if (replyId == null) return
        engine?.stop()
        endReply(release = false)
    }

    private fun endReply(release: Boolean = true) {
        replyId = null
        _state.update { it.copy(speakingReply = false) }
        if (release && _state.value.status != NarrationStatus.PLAYING) releaseFocus()
    }

    private fun publish(status: NarrationStatus, error: String? = null) = _state.update {
        it.copy(
            status = status,
            sentenceIndex = queue.index,
            sentenceCount = queue.size,
            errorMessage = error ?: it.errorMessage.takeIf { status == NarrationStatus.ERROR },
        )
    }

    // ---- Audio focus and the media service ----------------------------------------------------------------------

    /** Requests focus (again, after a transient loss) and watches for unplugged headphones. False during a call. */
    private fun holdFocus(): Boolean {
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            _events.tryEmit(NarrationEvent.Interrupted(CALL))
            return false
        }
        if (!holdingFocus) {
            holdingFocus = true
            val noisy = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            ContextCompat.registerReceiver(context, noisyReceiver, noisy, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        return true
    }

    private fun releaseFocus() {
        if (!holdingFocus) return
        holdingFocus = false
        audioManager.abandonAudioFocusRequest(focusRequest)
        context.unregisterReceiver(noisyReceiver)
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocusGain) play()
            AudioManager.AUDIOFOCUS_LOSS -> interrupt(OTHER_APP, resumeLater = false)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> interrupt(CALL, resumeLater = true)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> interrupt(OTHER_APP, resumeLater = true)
        }
    }

    /** Pauses for an outside reason. Focus is kept only when narration should resume once it comes back. */
    private fun interrupt(reason: String, resumeLater: Boolean) {
        val wasPlaying = _state.value.status == NarrationStatus.PLAYING
        cutReply()
        if (wasPlaying) {
            stopNarration()
            _events.tryEmit(NarrationEvent.Interrupted(reason))
        }
        // A second transient loss (ringing, then answered) must not cancel the resume the first one set up.
        resumeOnFocusGain = (wasPlaying || resumeOnFocusGain) && resumeLater
        if (!resumeOnFocusGain) releaseFocus()
    }

    /**
     * Keeps a controller connected to [PlaybackService], which starts it; Media3 then posts the media notification
     * and runs the service in the foreground while playing, so narration survives leaving the app.
     */
    private fun ensurePlaybackService() {
        val current = controller
        if (current != null && (!current.isDone || runCatching { current.get().isConnected }.getOrDefault(false))) return
        current?.let(MediaController::releaseFuture)
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controller = MediaController.Builder(context, token).buildAsync()
    }

    internal companion object {
        const val NO_VOICE = "No text-to-speech voice is available. Install one in Android Settings > Text-to-speech."
        const val SPEAK_FAILED = "Narration stopped because the text-to-speech engine failed. Press play to try again."
        const val OTHER_APP = "Paused: another app is playing audio"
        const val CALL = "Paused for a call"
        const val HEADPHONES = "Paused: headphones disconnected"

        val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
