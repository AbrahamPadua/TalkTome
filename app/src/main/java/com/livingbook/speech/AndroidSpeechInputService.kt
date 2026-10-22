package com.livingbook.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

/** Push-to-talk with android.speech.SpeechRecognizer. */
class AndroidSpeechInputService(private val context: Context) : SpeechInputService {
    override val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    override fun listen(): Flow<SpeechEvent> = callbackFlow {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            trySend(SpeechEvent.Error(speechErrorMessage(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)))
            close()
            return@callbackFlow
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(SpeechEvent.Ready)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                bestMatch(partialResults)?.let { trySend(SpeechEvent.Partial(it)) }
            }

            override fun onResults(results: Bundle?) {
                val text = bestMatch(results)
                trySend(if (text == null) SpeechEvent.Error(speechErrorMessage(SpeechRecognizer.ERROR_NO_MATCH)) else SpeechEvent.Final(text))
                close()
            }

            override fun onError(error: Int) {
                trySend(SpeechEvent.Error(speechErrorMessage(error)))
                close()
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true),
        )
        awaitClose { recognizer.destroy() }
    }.flowOn(Dispatchers.Main) // SpeechRecognizer must be created and used on the main thread

    private fun bestMatch(results: Bundle?): String? =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
}

/** Plain, actionable text for a [SpeechRecognizer] error code. */
internal fun speechErrorMessage(error: Int): String = when (error) {
    SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that. Try again."
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't hear anything. Try again and start speaking right away."
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER,
    SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
    -> "Speech recognition needs an internet connection on this device."
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed for voice questions."
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ->
        "Speech recognition is busy. Try again in a moment."
    SpeechRecognizer.ERROR_AUDIO -> "Couldn't use the microphone. Close other apps that are recording and try again."
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
        "Voice questions aren't available for this language on this device. Type your question instead."
    else -> "Voice input stopped unexpectedly. Try again, or type your question."
}
