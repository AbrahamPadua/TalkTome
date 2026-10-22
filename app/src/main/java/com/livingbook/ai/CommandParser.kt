package com.livingbook.ai

/** Maps typed or spoken phrases to deterministic commands. Returns null for free-form questions. */
object CommandParser {
    /** [hasActiveExplanation]: the last message was an AI explanation, so a bare "continue" means continue explaining. */
    fun parse(text: String, hasActiveExplanation: Boolean): ReaderCommand? {
        val phrase = normalize(text)
        val command = if (phrase in BARE_CONTINUE) {
            if (hasActiveExplanation) ReaderCommand.Tutor(TutorAction.CONTINUE_EXPLAINING) else ReaderCommand.ContinueReading
        } else {
            COMMANDS[phrase]
        }
        // "Go back?" or "Skip this?" asks about something; acting on it would move or save without being told to.
        return if (text.trimEnd().endsWith("?") && command !is ReaderCommand.Tutor) null else command
    }

    /** Lowercase, no punctuation, no fillers: "Okay, can you go deeper?" -> "go deeper". */
    internal fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("['’]"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .let { " $it ".replace(" can you ", " ").replace(" could you ", " ") }
            .split(' ')
            .filter { it.isNotEmpty() && it !in FILLERS }
            .joinToString(" ")

    private val FILLERS = setOf("okay", "ok", "please", "wait", "um", "uh", "so", "lets", "just", "now")

    private val BARE_CONTINUE = setOf("continue", "go on", "keep going")

    private val COMMANDS: Map<String, ReaderCommand> = buildMap {
        fun add(command: ReaderCommand, vararg phrases: String) = phrases.forEach { put(it, command) }
        add(ReaderCommand.ContinueReading, "continue reading", "back to the book", "go back to the book", "resume", "resume reading", "keep reading")
        add(ReaderCommand.GoBack, "go back", "previous topic")
        add(ReaderCommand.RepeatThat, "repeat that", "say that again", "repeat")
        add(ReaderCommand.SkipTopic, "skip this topic", "next topic", "skip", "skip this")
        add(ReaderCommand.RememberThis, "remember this", "save this", "remember that")
        add(ReaderCommand.Tutor(TutorAction.EXPLAIN_DIFFERENTLY), "explain it differently", "explain that differently", "explain this differently", "explain differently")
        add(ReaderCommand.Tutor(TutorAction.EXAMPLE), "give me an example", "show me an example", "an example", "example")
        add(ReaderCommand.Tutor(TutorAction.GO_DEEPER), "go deeper", "deeper")
        add(ReaderCommand.Tutor(TutorAction.RECAP_DISCUSSION), "what were we discussing", "what were we talking about")
        add(ReaderCommand.Tutor(TutorAction.SUMMARIZE), "summarize this topic", "summarise this topic", "summarize this", "summarize")
        add(ReaderCommand.Tutor(TutorAction.QUIZ), "quiz me")
    }
}
