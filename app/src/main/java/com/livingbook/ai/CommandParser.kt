package com.livingbook.ai

/** Maps typed or spoken phrases to deterministic commands. Returns null for free-form questions. */
object CommandParser {
    /** [hasActiveExplanation]: the last message was an AI explanation, so a bare "continue" means continue explaining. */
    fun parse(text: String, hasActiveExplanation: Boolean): ReaderCommand? = TODO("ai worker")
}
