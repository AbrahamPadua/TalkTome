package com.livingbook.ai

import com.livingbook.data.AiMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DefaultTutorEngineTest {

    private lateinit var book: TestBook
    private lateinit var tutor: DefaultTutorEngine

    @Before fun setUp() = runBlocking {
        book = TestBook().insert()
        tutor = DefaultTutorEngine(book.learning, book.settings)
    }

    @After fun tearDown() = book.close()

    @Test fun offersExplainExploreChallenge() = runBlocking {
        book.configure(AiMode.DEMO)
        assertEquals(
            listOf(TutorAction.EXPLAIN, TutorAction.EXPLORE, TutorAction.CHALLENGE),
            tutor.suggestionsFor(TestBook.BOOK, book.priors).map { it.action },
        )
    }

    @Test fun teachBackOnlyWithComprehensionChecks() = runBlocking {
        book.configure(AiMode.DEMO, comprehensionChecks = true)
        val last = tutor.suggestionsFor(TestBook.BOOK, book.priors).last()
        assertEquals(TutorSuggestion(TutorAction.TEACH_BACK, "Check my understanding"), last)
    }

    @Test fun emptyWhenTurnedOffOrDismissed() = runBlocking {
        book.configure(AiMode.DEMO, tutoring = false)
        assertTrue(tutor.suggestionsFor(TestBook.BOOK, book.priors).isEmpty())

        book.configure(AiMode.DEMO)
        tutor.dismiss(TestBook.BOOK, book.priors)
        assertTrue(tutor.suggestionsFor(TestBook.BOOK, book.priors).isEmpty())
        assertEquals(3, tutor.suggestionsFor(TestBook.BOOK, book.perception).size)
    }
}
