package com.livingbook.ui.conversation

import com.livingbook.ai.ReaderCommand
import com.livingbook.ai.TutorAction
import com.livingbook.model.SegmentKind

/** What the conversation sheet does with one message the user sent. */
sealed interface ConversationStep {
    /** "Continue reading": leave the conversation and resume where the reader was. */
    data object Resume : ConversationStep

    /** "Go back" or "skip this topic": leave the conversation and move by [delta] topics. */
    data class Navigate(val delta: Int) : ConversationStep

    /** "Repeat that" when there is an answer: say the last answer again. */
    data class RepeatAnswer(val text: String) : ConversationStep

    /** "Repeat that" before any answer: leave the conversation and re-read the current sentence. */
    data object RepeatSentence : ConversationStep

    data object Remember : ConversationStep

    /** Ask the model. [resumeAfterReply]: the user asked to get back to the book once the answer has been read out. */
    data class Send(val action: TutorAction, val resumeAfterReply: Boolean) : ConversationStep
}

/** Maps the parsed [command] for [text] to a step. [lastAnswer] is the latest assistant reply in this conversation. */
fun conversationStep(text: String, command: ReaderCommand?, lastAnswer: String?): ConversationStep = when (command) {
    ReaderCommand.ContinueReading -> ConversationStep.Resume
    ReaderCommand.GoBack -> ConversationStep.Navigate(-1)
    ReaderCommand.SkipTopic -> ConversationStep.Navigate(1)
    ReaderCommand.RepeatThat -> lastAnswer?.let(ConversationStep::RepeatAnswer) ?: ConversationStep.RepeatSentence
    ReaderCommand.RememberThis -> ConversationStep.Remember
    is ReaderCommand.Tutor -> ConversationStep.Send(command.action, wantsResumeAfter(text))
    null -> ConversationStep.Send(TutorAction.ASK, wantsResumeAfter(text))
}

// "... then continue reading", "..., and then back to the book", "... after that resume."
// Bare "continue"/"resume" only count at the end, so "then continue explaining" stays a question.
private val RESUME_AFTER = Regex(
    """\b(?:then|after that|afterwards)\b[\s,]*(?:(?:please|let[’']?s|let us|we can|i[’']?ll)\s+)?""" +
        """(?:(?:continue|keep|carry on|go on)\s+(?:with\s+)?(?:reading|listening|the book|the reading|the text)\b""" +
        """|(?:continue|resume)(?:\s+(?:reading|listening|the book))?\s*(?:[.!?]+\s*)?$""" +
        """|(?:go\s+|get\s+|head\s+)?back\s+to\s+(?:the\s+)?(?:book|reading|text|narration|listening)\b)""",
    RegexOption.IGNORE_CASE,
)

/** True when a question ends by asking to return to the book once it has been answered. */
fun wantsResumeAfter(text: String): Boolean = RESUME_AFTER.containsMatchIn(text.trim())

fun citationLabel(page: Int?): String = page?.let { "p. $it" } ?: "Passage"

fun segmentLabel(kind: SegmentKind): String = when (kind) {
    SegmentKind.BOOK -> "From the book"
    SegmentKind.INTERPRETATION -> "Explanation"
    SegmentKind.GENERAL -> "Beyond the book"
}

/** One-tap follow-ups under the input. */
val QUICK_ACTIONS = listOf(
    "Explain differently" to TutorAction.EXPLAIN_DIFFERENTLY,
    "Give an example" to TutorAction.EXAMPLE,
    "Go deeper" to TutorAction.GO_DEEPER,
    "Summarize" to TutorAction.SUMMARIZE,
    "Quiz me" to TutorAction.QUIZ,
)
