package com.livingbook.ai

import com.livingbook.data.AiMode
import com.livingbook.model.GuidedKind
import com.livingbook.model.GuidedSegment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AiGuidedContentGeneratorTest {

    private lateinit var book: TestBook
    private val provider = FakeLlmProvider()
    private lateinit var generator: AiGuidedContentGenerator

    @Before fun setUp() = runBlocking {
        book = TestBook().insert()
        generator = AiGuidedContentGenerator(book.reading, provider, book.settings)
    }

    @After fun tearDown() = book.close()

    @Test fun demoQuotesFirstSentencesAndIsNotCached() = runBlocking {
        book.configure(AiMode.DEMO)

        val content = generator.guidedFor(book.context())

        assertTrue(content.isMock)
        assertEquals("demo", content.model)
        assertEquals(GuidedKind.ADDED, content.segments.first().kind)
        assertTrue(content.segments.first().text.startsWith("Demo mode, not AI"))
        assertEquals(
            listOf(
                GuidedSegment(GuidedKind.QUOTED, "A prior is what you believe before seeing data.", listOf(1)),
                GuidedSegment(GuidedKind.QUOTED, "Evidence updates the prior into a posterior.", listOf(2)),
            ),
            content.segments.drop(1),
        )
        assertNull(book.reading.guided(TestBook.BOOK, 0, 2))
        assertTrue(provider.requests.isEmpty())
    }

    @Test fun proxyValidatesQuotesAndCaches() = runBlocking {
        book.configure(AiMode.PROXY)
        provider.reply = """{"segments":[
            {"kind":"quoted","text":"A prior is what you believe before seeing data.","sourceBlocks":[1]},
            {"kind":"quoted","text":"Priors are guesses.","sourceBlocks":[1, 4]},
            {"kind":"added","text":"Think of a weather forecast.","sourceBlocks":[]}]}"""

        val content = generator.guidedFor(book.context())

        assertFalse(content.isMock)
        assertEquals("fake-model", content.model)
        assertEquals(
            listOf(
                GuidedSegment(GuidedKind.QUOTED, "A prior is what you believe before seeing data.", listOf(1)),
                GuidedSegment(GuidedKind.RESTATED, "Priors are guesses.", listOf(1)),
                GuidedSegment(GuidedKind.ADDED, "Think of a weather forecast."),
            ),
            content.segments,
        )
        assertEquals(PromptBuilder.GUIDED_SYSTEM, provider.requests.single().system)
        assertEquals(content, book.reading.guided(TestBook.BOOK, 0, 2))
    }

    @Test fun cacheHitSkipsTheModelEvenWhenAiIsOff() = runBlocking {
        book.configure(AiMode.PROXY)
        provider.reply = """{"segments":[{"kind":"restated","text":"Beliefs come first.","sourceBlocks":[1]}]}"""
        val first = generator.guidedFor(book.context())
        book.configure(AiMode.OFF)

        assertEquals(first, generator.guidedFor(book.context()))
        assertEquals(1, provider.requests.size)
    }

    @Test fun offThrowsActionableError() = runBlocking {
        book.configure(AiMode.OFF)
        try {
            generator.guidedFor(book.context())
            fail("expected AiException")
        } catch (e: AiException) {
            assertTrue("Settings" in e.userMessage)
        }
    }
}
