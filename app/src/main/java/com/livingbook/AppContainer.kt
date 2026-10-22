package com.livingbook

import android.content.Context
import com.livingbook.ai.AiGuidedContentGenerator
import com.livingbook.ai.BookSourceRetriever
import com.livingbook.ai.ConversationManager
import com.livingbook.ai.DefaultTutorEngine
import com.livingbook.ai.GroundedConversationManager
import com.livingbook.ai.LlmProvider
import com.livingbook.ai.ProxyLlmProvider
import com.livingbook.data.ConversationRepository
import com.livingbook.data.LearningProfileRepository
import com.livingbook.data.ReadingRepository
import com.livingbook.data.SettingsRepository
import com.livingbook.data.db.AppDatabase
import com.livingbook.doc.DocumentImporter
import com.livingbook.doc.EpubDocumentParser
import com.livingbook.doc.HeadingAwareSegmenter
import com.livingbook.doc.PdfDocumentParser
import com.livingbook.session.ReadingSession
import com.livingbook.speech.AndroidSpeechInputService
import com.livingbook.speech.NarrationService
import com.livingbook.speech.SpeechInputService
import com.livingbook.speech.TtsNarrationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency wiring. One instance per process, owned by [LivingBookApp]. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val db = AppDatabase.create(context)
    val settings = SettingsRepository(context)
    val reading = ReadingRepository(db)
    val conversationsRepo = ConversationRepository(db)
    val learning = LearningProfileRepository(db)

    val importer = DocumentImporter(context, db, listOf(PdfDocumentParser(context), EpubDocumentParser()), HeadingAwareSegmenter())

    val narration: NarrationService by lazy { TtsNarrationService(context) }
    val speechInput: SpeechInputService by lazy { AndroidSpeechInputService(context) }

    val llm: LlmProvider = ProxyLlmProvider(settings)
    val retriever = BookSourceRetriever(reading)
    val conversations: ConversationManager = GroundedConversationManager(conversationsRepo, reading, learning, retriever, llm, settings)
    val tutor = DefaultTutorEngine(learning, settings)
    val guided = AiGuidedContentGenerator(reading, llm, settings)

    val session by lazy { ReadingSession(reading, settings, narration, guided, tutor, appScope) }
}
