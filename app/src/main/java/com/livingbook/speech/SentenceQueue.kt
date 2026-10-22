package com.livingbook.speech

/**
 * Sentence bookkeeping for narration, free of Android so it runs on the JVM.
 * Utterance ids carry a generation counter. Every load, move or [invalidate] bumps it, so callbacks for
 * utterances handed to the engine before that are recognized as stale and ignored.
 */
internal class SentenceQueue {
    var size = 0
        private set
    var index = 0
        private set
    private var generation = 0

    sealed interface Done {
        /** From an earlier generation, or not the sentence being spoken: ignore. */
        data object Stale : Done
        /** Moved to the next sentence. [enqueue] is the sentence to hand the engine now, if any. */
        data class Advanced(val enqueue: Int?) : Done
        /** The last sentence finished. */
        data object Finished : Done
    }

    fun load(size: Int, start: Int) {
        this.size = size
        moveTo(start)
    }

    /** Moves to [target], clamped to the topic. */
    fun moveTo(target: Int) {
        index = target.coerceIn(0, (size - 1).coerceAtLeast(0))
        invalidate()
    }

    fun invalidate() {
        generation++
    }

    fun idFor(sentence: Int) = "$generation:$sentence"

    /** True if [utteranceId] was queued in the current generation. */
    fun owns(utteranceId: String) = utteranceId.substringBefore(':') == generation.toString()

    /** Sentences to queue when playback starts: the current one plus [LOOKAHEAD] - 1 ahead, so there is no gap. */
    fun upcoming(): IntRange = index until minOf(index + LOOKAHEAD, size)

    fun onDone(utteranceId: String): Done {
        if (!owns(utteranceId)) return Done.Stale
        val done = utteranceId.substringAfter(':').toIntOrNull()
        if (done != index) return Done.Stale
        if (done >= size - 1) return Done.Finished
        index = done + 1
        return Done.Advanced((index + LOOKAHEAD - 1).takeIf { it < size })
    }

    private companion object {
        const val LOOKAHEAD = 2
    }
}
