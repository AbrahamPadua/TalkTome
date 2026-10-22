package com.livingbook.speech

import android.app.Application
import android.content.ComponentName
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.media3.common.Player
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
class NarrationPlayerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val events = mutableListOf<NarrationEvent>()
    private lateinit var narration: TtsNarrationService
    private lateinit var player: NarrationPlayer

    @Before
    fun setUp() {
        shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
        ShadowTextToSpeech.addLanguageAvailability(Locale.getDefault())
        narration = TtsNarrationService(app)
        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
        // Keep utterances running; otherwise the shadow finishes the whole topic on the next idle.
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = Unit

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = Unit
        })
        shadowOf(tts).onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
        player = NarrationPlayer(narration)
        scope.launch { narration.events.collect { events += it } }
        narration.load(NarrationRequest("Moby Dick", "The Whiteness", listOf("One.", "Two.", "Three.")))
        idle()
    }

    @After
    fun tearDown() {
        player.release()
        scope.cancel()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun mirrorsLoadedTopic() {
        assertEquals("The Whiteness", player.mediaMetadata.title.toString())
        assertEquals("Moby Dick", player.mediaMetadata.artist.toString())
        assertEquals(Player.STATE_READY, player.playbackState)
        assertFalse(player.playWhenReady)
    }

    @Test
    fun playAndPauseDriveNarration() {
        player.play()
        idle()
        assertEquals(NarrationStatus.PLAYING, narration.state.value.status)
        assertTrue(player.isPlaying)
        player.pause()
        idle()
        assertEquals(NarrationStatus.PAUSED, narration.state.value.status)
    }

    @Test
    fun nextAndPreviousRequestTopics() {
        player.seekToNext()
        player.seekToPrevious()
        assertEquals(listOf(NarrationEvent.NextTopicRequested, NarrationEvent.PreviousTopicRequested), events)
    }

    @Test
    fun seekForwardAndBackMoveBySentence() {
        player.seekForward()
        player.seekForward()
        assertEquals(2, narration.state.value.sentenceIndex)
        player.seekBack()
        assertEquals(1, narration.state.value.sentenceIndex)
    }

    @Test
    fun followsNarrationState() {
        narration.stop()
        idle()
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertEquals(0, player.mediaItemCount)
    }
}
