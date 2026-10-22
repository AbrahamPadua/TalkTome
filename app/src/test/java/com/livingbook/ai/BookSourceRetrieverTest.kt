package com.livingbook.ai

import com.livingbook.model.BlockKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BookSourceRetrieverTest {

    private lateinit var book: TestBook
    private lateinit var retriever: BookSourceRetriever

    @Before fun setUp() = runBlocking {
        book = TestBook().insert()
        retriever = BookSourceRetriever(book.reading)
    }

    @After fun tearDown() = book.close()

    @Test fun topicFirstRankedByOverlapThenMatchesElsewhere() = runBlocking {
        val passages = retriever.retrieve(TestBook.BOOK, "How does the posterior relate to priors?", book.priors, limit = 8)

        assertEquals(listOf(2, 1, 4), passages.map { it.blockIndex })
        assertEquals(listOf("Priors", "Priors", "Perception"), passages.map { it.topicTitle })
        assertEquals(listOf(2, 1, 3), passages.map { it.page })
    }

    @Test fun matchesElsewhereKeepSlotsWhenTheTopicIsFull() = runBlocking {
        val passages = retriever.retrieve(TestBook.BOOK, "priors", book.priors, limit = 2)
        assertEquals(2, passages.size)
        assertEquals(4, passages.last().blockIndex)
    }

    @Test fun longPassagesAreClipped() = runBlocking {
        val db = book.db
        db.books().insertBlocks(listOf(TestBook.block(5, BlockKind.PARAGRAPH, "posterior ".repeat(300))))
        val topic = book.perception.copy(endBlock = 5)

        val passage = retriever.retrieve(TestBook.BOOK, "posterior", topic, limit = 8).first { it.blockIndex == 5 }

        assertTrue(passage.text.length in 1100..BookSourceRetriever.MAX_CHARS + 1)
        assertTrue(passage.text.endsWith("…"))
    }

    @Test fun termsDropFunctionWords() {
        assertEquals(listOf("posterior", "priors"), BookSourceRetriever.terms("What is the posterior, and why priors?"))
    }
}
