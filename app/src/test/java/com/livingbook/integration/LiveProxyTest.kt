package com.livingbook.integration

import android.app.Application
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.livingbook.ai.AiGuidedContentGenerator
import com.livingbook.ai.BookSourceRetriever
import com.livingbook.ai.GroundedConversationManager
import com.livingbook.ai.ProxyLlmProvider
import com.livingbook.ai.ReadingContext
import com.livingbook.data.AiMode
import com.livingbook.data.AppSettings
import com.livingbook.data.ConversationRepository
import com.livingbook.data.LearningProfileRepository
import com.livingbook.data.ReadingRepository
import com.livingbook.data.SettingsRepository
import com.livingbook.data.db.AppDatabase
import com.livingbook.doc.DocumentImporter
import com.livingbook.doc.EpubDocumentParser
import com.livingbook.doc.HeadingAwareSegmenter
import com.livingbook.model.ChatRole
import com.livingbook.model.Granularity
import com.livingbook.model.Rendition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Imports the sample EPUB and asks a real model through a running proxy. Skipped unless LIVE_PROXY_URL and
 * LIVE_PROXY_TOKEN are set, e.g. the backend pointed at a local Ollama model.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LiveProxyTest {

    @Test
    fun `grounded answer and guided rendition from a real model`() = runBlocking {
        val url = System.getenv("LIVE_PROXY_URL")
        val token = System.getenv("LIVE_PROXY_TOKEN")
        assumeTrue("set LIVE_PROXY_URL and LIVE_PROXY_TOKEN to run", !url.isNullOrBlank() && !token.isNullOrBlank())

        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val settings = SettingsRepository(context)
        settings.update { AppSettings(aiMode = AiMode.PROXY, aiConsent = true, proxyUrl = url!!, proxyToken = token!!) }
        val reading = ReadingRepository(db)
        val samples = File("../samples").takeIf { it.isDirectory } ?: File("samples")
        val bookId = DocumentImporter(context, db, listOf(EpubDocumentParser()), HeadingAwareSegmenter())
            .import(Uri.fromFile(File(samples, "senses-primer.epub")))

        val topic = reading.topics(bookId, Granularity.BALANCED).first { "Bayesian" in it.title }
        val blocks = reading.blocks(bookId, topic.startBlock, topic.endBlock)
        val book = reading.book(bookId)!!
        val ctx = ReadingContext(bookId, book.title, book.author, topic, blocks, blocks.getOrNull(1)?.text?.take(200), Rendition.ORIGINAL)

        val llm = ProxyLlmProvider(settings)
        val manager = GroundedConversationManager(ConversationRepository(db), reading, LearningProfileRepository(db), BookSourceRetriever(reading), llm, settings)
        val conversation = manager.open(ctx)
        val reply = manager.send(conversation, ctx, "Wait, how is that different from predictive processing?")
        println("REPLY role=${reply.role} mock=${reply.isMock}")
        reply.segments.forEach { println("  ${it.kind} ${it.citations}: ${it.text}") }
        if (reply.role == ChatRole.NOTE) println("  NOTE: ${reply.text}")
        assertEquals(ChatRole.ASSISTANT, reply.role)
        val cited = reply.segments.flatMap { it.citations }
        val known = reading.blocksAt(bookId, cited).map { it.idx }.toSet()
        assertTrue("every citation exists in the book", cited.all { it in known })

        val guided = AiGuidedContentGenerator(reading, llm, settings).guidedFor(ctx)
        println("GUIDED model=${guided.model} mock=${guided.isMock}")
        guided.segments.forEach { println("  ${it.kind} ${it.sourceBlocks}: ${it.text.take(160)}") }
        assertTrue(guided.segments.isNotEmpty())
        assertTrue(guided.segments.flatMap { it.sourceBlocks }.all { it in topic.startBlock..topic.endBlock })
        db.close()
    }
}
