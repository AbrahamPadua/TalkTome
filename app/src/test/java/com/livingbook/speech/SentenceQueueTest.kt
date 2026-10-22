package com.livingbook.speech

import com.livingbook.speech.SentenceQueue.Done
import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceQueueTest {

    @Test
    fun startsAtRequestedSentenceAndLooksOneAhead() {
        val q = SentenceQueue().apply { load(size = 5, start = 2) }
        assertEquals(2, q.index)
        assertEquals(2..3, q.upcoming())
    }

    @Test
    fun doneAdvancesAndQueuesTheNextLookahead() {
        val q = SentenceQueue().apply { load(size = 3, start = 0) }
        assertEquals(Done.Advanced(enqueue = 2), q.onDone(q.idFor(0)))
        assertEquals(1, q.index)
        assertEquals(Done.Advanced(enqueue = null), q.onDone(q.idFor(1)))
        assertEquals(2, q.index)
        assertEquals(Done.Finished, q.onDone(q.idFor(2)))
        assertEquals(2, q.index)
    }

    @Test
    fun staleCallbacksAreIgnored() {
        val q = SentenceQueue().apply { load(size = 5, start = 0) }
        val old = q.idFor(0)
        q.moveTo(3)
        assertEquals(Done.Stale, q.onDone(old))
        assertEquals(3, q.index)
        // Same generation but not the sentence being spoken (an out-of-order callback).
        assertEquals(Done.Stale, q.onDone(q.idFor(4)))
        q.invalidate()
        assertEquals(Done.Stale, q.onDone("garbage"))
        assertEquals(3, q.index)
    }

    @Test
    fun movesAreClamped() {
        val q = SentenceQueue().apply { load(size = 4, start = 9) }
        assertEquals(3, q.index)
        q.moveTo(-2)
        assertEquals(0, q.index)
        q.moveTo(q.index + 1)
        assertEquals(1, q.index)
    }

    @Test
    fun emptyTopicHasNothingToSpeak() {
        val q = SentenceQueue().apply { load(size = 0, start = 3) }
        assertEquals(0, q.index)
        assertEquals(true, q.upcoming().isEmpty())
    }
}
