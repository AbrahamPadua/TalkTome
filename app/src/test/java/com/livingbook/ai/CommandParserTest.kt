package com.livingbook.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandParserTest {

    private val table: List<Pair<String, ReaderCommand?>> = listOf(
        "continue reading" to ReaderCommand.ContinueReading,
        "Back to the book." to ReaderCommand.ContinueReading,
        "OK, resume" to ReaderCommand.ContinueReading,
        "keep reading please" to ReaderCommand.ContinueReading,
        "Go back" to ReaderCommand.GoBack,
        "previous topic" to ReaderCommand.GoBack,
        "Repeat that." to ReaderCommand.RepeatThat,
        "Can you say that again" to ReaderCommand.RepeatThat,
        "skip this topic" to ReaderCommand.SkipTopic,
        "Next topic!" to ReaderCommand.SkipTopic,
        "skip" to ReaderCommand.SkipTopic,
        "Remember this" to ReaderCommand.RememberThis,
        "save this, please" to ReaderCommand.RememberThis,
        "Explain it differently" to ReaderCommand.Tutor(TutorAction.EXPLAIN_DIFFERENTLY),
        "Um, can you give me an example?" to ReaderCommand.Tutor(TutorAction.EXAMPLE),
        "Let's go deeper" to ReaderCommand.Tutor(TutorAction.GO_DEEPER),
        "Wait, what were we discussing?" to ReaderCommand.Tutor(TutorAction.RECAP_DISCUSSION),
        "Summarize this topic" to ReaderCommand.Tutor(TutorAction.SUMMARIZE),
        "Okay so quiz me" to ReaderCommand.Tutor(TutorAction.QUIZ),
        // A question mark keeps tutor requests but never triggers navigation, saving or repeating.
        "Quiz me?" to ReaderCommand.Tutor(TutorAction.QUIZ),
        "Go back?" to null,
        "Skip this?" to null,
        "Next topic?" to null,
        "Remember this?" to null,
        "Resume? " to null,
        "Can you say that again?" to null,
        "Go on?" to null,
        // Real questions with extra content go to the model.
        "can you give me an example of priors in vision?" to null,
        "Why does the author go back to Kant here?" to null,
        "What is a prior?" to null,
        "" to null,
    )

    @Test fun phraseTable() {
        table.forEach { (text, expected) -> assertEquals(text, expected, CommandParser.parse(text, hasActiveExplanation = false)) }
    }

    @Test fun bareContinueDependsOnActiveExplanation() {
        listOf("continue", "Go on.", "okay, keep going").forEach {
            assertEquals(it, ReaderCommand.Tutor(TutorAction.CONTINUE_EXPLAINING), CommandParser.parse(it, hasActiveExplanation = true))
            assertEquals(it, ReaderCommand.ContinueReading, CommandParser.parse(it, hasActiveExplanation = false))
        }
        assertEquals(ReaderCommand.Tutor(TutorAction.CONTINUE_EXPLAINING), CommandParser.parse("Go on?", hasActiveExplanation = true))
    }

    @Test fun continueReadingIsNeverContinueExplaining() {
        assertEquals(ReaderCommand.ContinueReading, CommandParser.parse("continue reading", hasActiveExplanation = true))
    }

    @Test fun normalizeStripsCasePunctuationAndFillers() {
        assertEquals("go deeper", CommandParser.normalize("Okay, can you go deeper?"))
        assertEquals("give me an example", CommandParser.normalize("  um   so give me an example please!! "))
    }
}
