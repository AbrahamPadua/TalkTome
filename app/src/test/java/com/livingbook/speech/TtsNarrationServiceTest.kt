package com.livingbook.speech

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class TtsNarrationServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val events = mutableListOf<NarrationEvent>()

    /** Utterance ids in the order the engine started them. */
    private val started = mutableListOf<String>()
    private lateinit var narration: TtsNarrationService
    private lateinit var tts: TextToSpeech
    private lateinit var callbacks: UtteranceProgressListener

    @Before
    fun setUp() {
        // The media service is not under test here; narration must work when it cannot be reached.
        shadowOf(context as Application).declareComponentUnbindable(ComponentName(context, PlaybackService::class.java))
        narration = TtsNarrationService(context)
        tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
        callbacks = shadowOf(tts).utteranceProgressListener
        // The shadow would finish every utterance by itself; record them and finish them by hand instead.
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                started += utteranceId
            }

            override fun onDone(utteranceId: String) = Unit

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = Unit
        })
        scope.launch { narration.events.collect { events += it } }
    }

    @After
    fun tearDown() = scope.cancel()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun initEngine(status: Int = TextToSpeech.SUCCESS) {
        ShadowTextToSpeech.addLanguageAvailability(Locale.getDefault())
        shadowOf(tts).onInitListener.onInit(status)
        idle()
    }

    private fun spoken() = shadowOf(tts).spokenTextList

    private fun finishUtterance(index: Int) {
        callbacks.onDone(started[index])
        idle()
    }

    private fun loadAndPlay() {
        narration.load(NarrationRequest("Book", "Topic", listOf("One.", "Two.", "Three.")))
        narration.play()
        idle()
    }

    @Test
    fun playSpeaksFirstSentenceAndOneAhead() {
        initEngine()
        loadAndPlay()
        assertEquals(listOf("One.", "Two."), spoken())
        assertEquals(NarrationStatus.PLAYING, narration.state.value.status)
        assertEquals(0, narration.state.value.sentenceIndex)
    }

    @Test
    fun finishingUtterancesAdvancesThenEndsTopic() {
        initEngine()
        loadAndPlay()
        finishUtterance(0)
        assertEquals(1, narration.state.value.sentenceIndex)
        assertEquals("Three.", spoken().last())
        finishUtterance(1)
        finishUtterance(2)
        assertEquals(NarrationStatus.ENDED, narration.state.value.status)
        assertEquals(2, narration.state.value.sentenceIndex)
        assertEquals(listOf<NarrationEvent>(NarrationEvent.TopicFinished), events)
    }

    @Test
    fun pauseThenPlayRestartsTheSameSentence() {
        initEngine()
        loadAndPlay()
        finishUtterance(0)
        narration.pause()
        assertEquals(NarrationStatus.PAUSED, narration.state.value.status)
        assertTrue(shadowOf(tts).isStopped)
        finishUtterance(1) // a late callback from before the pause
        assertEquals(1, narration.state.value.sentenceIndex)
        narration.play()
        idle()
        assertEquals(listOf("Two.", "Three."), spoken().takeLast(2))
        assertEquals(NarrationStatus.PLAYING, narration.state.value.status)
    }

    @Test
    fun seekAndSkipClampAndKeepPlaying() {
        initEngine()
        loadAndPlay()
        narration.skipBack()
        assertEquals(0, narration.state.value.sentenceIndex)
        narration.seekToSentence(9)
        assertEquals(2, narration.state.value.sentenceIndex)
        assertEquals("Three.", spoken().last())
        finishUtterance(0) // stale: queued before the seek
        assertEquals(2, narration.state.value.sentenceIndex)
        assertEquals(NarrationStatus.PLAYING, narration.state.value.status)
    }

    @Test
    fun playBeforeEngineIsReadyIsQueued() {
        loadAndPlay()
        assertTrue(spoken().isEmpty())
        initEngine()
        assertEquals("One.", spoken().first())
        assertEquals(NarrationStatus.PLAYING, narration.state.value.status)
    }

    @Test
    fun engineFailureShowsInstallHint() {
        initEngine(TextToSpeech.ERROR)
        val state = narration.state.value
        assertEquals(NarrationStatus.ERROR, state.status)
        assertEquals(TtsNarrationService.NO_VOICE, state.errorMessage)
        assertFalse(state.engineReady)
    }

    @Test
    fun replyPausesNarrationAndNeverOverlaps() {
        initEngine()
        loadAndPlay()
        finishUtterance(0)
        narration.speakOnce("Here is my answer.")
        idle()
        assertEquals(NarrationStatus.PAUSED, narration.state.value.status)
        assertTrue(narration.state.value.speakingReply)
        assertEquals(TextToSpeech.QUEUE_FLUSH, shadowOf(tts).queueMode)
        finishUtterance(1) // a narration callback that was in flight must not restart narration
        assertEquals("Here is my answer.", spoken().last())
        callbacks.onDone(started.last())
        idle()
        assertFalse(narration.state.value.speakingReply)
        narration.play()
        idle()
        assertEquals("Two.", spoken()[spoken().size - 2])
    }

    @Test
    fun playCutsAReplyShort() {
        initEngine()
        narration.load(NarrationRequest("Book", "Topic", listOf("One.")))
        narration.speakOnce("Answer.")
        narration.play()
        idle()
        assertFalse(narration.state.value.speakingReply)
        assertEquals(listOf("Answer.", "One."), spoken())
    }

    @Test
    fun transientFocusLossPausesAndResumes() {
        initEngine()
        loadAndPlay()
        val focus = shadowOf(context.getSystemService(AudioManager::class.java)).lastAudioFocusRequest.listener
        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(NarrationStatus.PAUSED, narration.state.value.status)
        assertEquals(listOf<NarrationEvent>(NarrationEvent.Interrupted(TtsNarrationService.CALL)), events)
        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(NarrationStatus.PLAYING, narration.state.value.status)
    }

    @Test
    fun permanentFocusLossDoesNotResume() {
        initEngine()
        loadAndPlay()
        val focus = shadowOf(context.getSystemService(AudioManager::class.java)).lastAudioFocusRequest.listener
        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(NarrationStatus.PAUSED, narration.state.value.status)
        assertEquals(listOf<NarrationEvent>(NarrationEvent.Interrupted(TtsNarrationService.OTHER_APP)), events)
    }

    @Test
    fun aSecondTransientLossStillResumesAfterTheCall() {
        initEngine()
        loadAndPlay()
        val focus = shadowOf(context.getSystemService(AudioManager::class.java)).lastAudioFocusRequest.listener
        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) // ringing
        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) // answered
        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(NarrationStatus.PLAYING, narration.state.value.status)
    }

    @Test
    fun navigationPromptsPauseAndResumeInsteadOfTalkingOver() {
        initEngine()
        loadAndPlay()
        val focus = shadowOf(context.getSystemService(AudioManager::class.java)).lastAudioFocusRequest.listener
        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(NarrationStatus.PAUSED, narration.state.value.status)
        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(NarrationStatus.PLAYING, narration.state.value.status)
    }

    @Test
    fun loadingAnotherTopicCancelsAResumeAfterACall() {
        initEngine()
        loadAndPlay()
        val focus = shadowOf(context.getSystemService(AudioManager::class.java)).lastAudioFocusRequest.listener
        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        narration.load(NarrationRequest("Book", "Other", listOf("Elsewhere.")))
        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(NarrationStatus.PAUSED, narration.state.value.status)
    }

    @Test
    fun unpluggedHeadphonesPause() {
        initEngine()
        loadAndPlay()
        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        idle()
        assertEquals(NarrationStatus.PAUSED, narration.state.value.status)
        assertEquals(listOf<NarrationEvent>(NarrationEvent.Interrupted(TtsNarrationService.HEADPHONES)), events)
    }

    @Test
    fun voicesListTheDeviceLocaleFirstAndSkipUninstalled() {
        val here = Locale.getDefault()
        ShadowTextToSpeech.addVoice(Voice("de-voice", Locale.GERMANY, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, true, emptySet()))
        ShadowTextToSpeech.addVoice(Voice("local", here, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, emptySet()))
        val missing = setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        ShadowTextToSpeech.addVoice(Voice("missing", here, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, missing))
        initEngine()
        val voices = narration.voices.value
        assertEquals(listOf("local", "de-voice"), voices.map { it.id })
        assertEquals(listOf(false, true), voices.map { it.needsNetwork })
        assertTrue(voices[0].label.contains(here.displayName))
    }
}
