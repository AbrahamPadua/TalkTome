package com.livingbook.speech

import android.content.Context
import kotlinx.coroutines.flow.Flow

/** Push-to-talk with android.speech.SpeechRecognizer. */
class AndroidSpeechInputService(private val context: Context) : SpeechInputService {
    override val isAvailable: Boolean get() = TODO("speech worker")
    override fun listen(): Flow<SpeechEvent> = TODO("speech worker")
}
