package com.livingbook.ui.conversation

import com.livingbook.ai.ReaderCommand
import com.livingbook.ai.TutorAction
import com.livingbook.model.SegmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationLogicTest {

    @Test
    fun continueReadingResumes() {
        assertEquals(ConversationStep.Resume, conversationStep("continue reading", ReaderCommand.ContinueReading, lastAnswer = "x"))
    }

    @Test
    fun goBackAndSkipNavigateByOneTopic() {
        assertEquals(ConversationStep.Navigate(-1), conversationStep("go back", ReaderCommand.GoBack, null))
        assertEquals(ConversationStep.Navigate(1), conversationStep("skip this topic", ReaderCommand.SkipTopic, null))
    }

    @Test
    fun repeatThatRespeaksLastAnswerOrRereadsSentence() {
        assertEquals(ConversationStep.RepeatAnswer("Entropy measures spread."), conversationStep("repeat that", ReaderCommand.RepeatThat, "Entropy measures spread."))
        assertEquals(ConversationStep.RepeatSentence, conversationStep("repeat that", ReaderCommand.RepeatThat, null))
    }

    @Test
    fun rememberThisRemembers() {
        assertEquals(ConversationStep.Remember, conversationStep("remember this", ReaderCommand.RememberThis, "x"))
    }

    @Test
    fun tutorCommandSendsItsAction() {
        assertEquals(
            ConversationStep.Send(TutorAction.EXAMPLE, resumeAfterReply = false),
            conversationStep("give me an example", ReaderCommand.Tutor(TutorAction.EXAMPLE), null),
        )
        assertEquals(
            ConversationStep.Send(TutorAction.SUMMARIZE, resumeAfterReply = true),
            conversationStep("summarize it, then continue reading", ReaderCommand.Tutor(TutorAction.SUMMARIZE), null),
        )
    }

    @Test
    fun freeFormQuestionIsAsked() {
        assertEquals(ConversationStep.Send(TutorAction.ASK, resumeAfterReply = false), conversationStep("What is a prior?", null, null))
        assertEquals(
            ConversationStep.Send(TutorAction.ASK, resumeAfterReply = true),
            conversationStep("What is a prior? Then back to the book.", null, null),
        )
    }

    @Test
    fun detectsRequestsToResumeAfterTheAnswer() {
        listOf(
            "Explain entropy, then continue reading",
            "what's a prior? then back to the book",
            "Give an example and then go back to the book please",
            "Summarize this, after that resume.",
            "Explain it again then continue",
            "short version, then let's keep reading",
            "short version, then let’s keep reading",
            "Then get back to reading",
            "and afterwards continue with the book",
        ).forEach { assertTrue(it, wantsResumeAfter(it)) }
    }

    @Test
    fun ignoresPhrasesThatAreNotAboutReturningToTheBook() {
        listOf(
            "What is a prior?",
            "explain this then continue explaining the proof",
            "then go back",
            "continue reading",
            "How do I get back to the main idea?",
            "Is the book going back to the river image later?",
        ).forEach { assertFalse(it, wantsResumeAfter(it)) }
    }

    @Test
    fun labels() {
        assertEquals("p. 12", citationLabel(12))
        assertEquals("Passage", citationLabel(null))
        assertEquals("From the book", segmentLabel(SegmentKind.BOOK))
        assertEquals("Explanation", segmentLabel(SegmentKind.INTERPRETATION))
        assertEquals("Beyond the book", segmentLabel(SegmentKind.GENERAL))
    }
}
