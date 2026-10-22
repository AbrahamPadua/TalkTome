package com.livingbook.speech

import android.Manifest
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSpeechRecognizer

@RunWith(RobolectricTestRunner::class)
class AndroidSpeechInputServiceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val service = AndroidSpeechInputService(app)
    private val events = mutableListOf<SpeechEvent>()

    private fun listen(): Job = CoroutineScope(Dispatchers.Unconfined).launch { service.listen().collect { events += it } }
        .also { idle() }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun recognizer() = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())

    private fun results(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    @Test
    fun mapsCallbacksAndCompletesAfterFinal() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val job = listen()
        recognizer().triggerOnReadyForSpeech(Bundle())
        recognizer().triggerOnPartialResults(results("what is"))
        recognizer().triggerOnResults(results("what is a whale"))
        idle()
        assertEquals(listOf(SpeechEvent.Ready, SpeechEvent.Partial("what is"), SpeechEvent.Final("what is a whale")), events)
        assertTrue(job.isCompleted)
        assertTrue(recognizer().isDestroyed)
    }

    @Test
    fun recognizerErrorEndsWithFriendlyText() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val job = listen()
        recognizer().triggerOnError(SpeechRecognizer.ERROR_NETWORK)
        idle()
        assertEquals(listOf(SpeechEvent.Error("Speech recognition needs an internet connection on this device.")), events)
        assertTrue(job.isCompleted)
    }

    @Test
    fun missingPermissionIsAnErrorNotACrash() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val job = listen()
        assertEquals(listOf(SpeechEvent.Error("Microphone permission is needed for voice questions.")), events)
        assertTrue(job.isCompleted)
    }

    @Test
    fun errorCodesMapToActionableText() {
        assertEquals("Didn't catch that. Try again.", speechErrorMessage(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(speechErrorMessage(SpeechRecognizer.ERROR_NETWORK), speechErrorMessage(SpeechRecognizer.ERROR_NETWORK_TIMEOUT))
        assertEquals("Microphone permission is needed for voice questions.", speechErrorMessage(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals("Speech recognition is busy. Try again in a moment.", speechErrorMessage(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
        assertTrue(speechErrorMessage(SpeechRecognizer.ERROR_SPEECH_TIMEOUT).startsWith("Didn't hear anything"))
        assertTrue(speechErrorMessage(-42).contains("Try again"))
    }
}
