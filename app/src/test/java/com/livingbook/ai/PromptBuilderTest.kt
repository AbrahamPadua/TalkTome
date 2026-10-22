package com.livingbook.ai

import com.livingbook.data.db.BlockEntity
import com.livingbook.data.db.LearningSignalEntity
import com.livingbook.data.db.SignalKind
import com.livingbook.data.db.TopicEntity
import com.livingbook.model.BlockKind
import com.livingbook.model.Granularity
import com.livingbook.model.Rendition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    private val topic = TopicEntity("b", Granularity.BALANCED, 0, "Priors", 10, 11, null)
    private val blocks = listOf(
        BlockEntity(bookId = "b", idx = 10, kind = BlockKind.PARAGRAPH, text = "A prior is a belief.", headingLevel = 0, page = 3, location = null),
        BlockEntity(bookId = "b", idx = 11, kind = BlockKind.PARAGRAPH, text = "Evidence updates it.", headingLevel = 0, page = null, location = null),
    )
    private val context = ReadingContext("b", "Thinking in Bets", "A. Author", topic, blocks, "Evidence updates it.", Rendition.ORIGINAL)
    private val passages = listOf(
        Passage(10, "A prior is a belief.", 3, "Priors"),
        Passage(42, "Vision uses priors too.", 17, "Perception"),
    )
    private val profile = listOf(LearningSignalEntity(kind = SignalKind.PREFERENCE, label = "Prefers intuitive analogies", evidence = "asked", updatedAt = 0))

    @Test fun systemPromptCarriesGroundingRulesAndFormat() {
        listOf("\"book\"", "\"interpretation\"", "\"general\"", "Never invent block ids", "[B12]", "\"segments\"", "\"assessment\"", "doesn't cover")
            .forEach { assertTrue(it, it in PromptBuilder.SYSTEM) }
    }

    @Test fun userTurnHasContextPassagesProfileAndAction() {
        val prompt = PromptBuilder.conversationTurn(context, blocks, passages, profile, "What is a prior?", TutorAction.EXAMPLE, assessAnswer = false)

        listOf(
            "Book: \"Thinking in Bets\" by A. Author",
            "Topic: Priors",
            "The reader was at this sentence: \"Evidence updates it.\"",
            "[B10] (p. 3) A prior is a belief.",
            "[B11] Evidence updates it.",
            "From \"Perception\":",
            "[B42] (p. 17) Vision uses priors too.",
            "- Prefers intuitive analogies",
            "Instruction: ${PromptBuilder.instruction(TutorAction.EXAMPLE)}",
            "Reader's message: What is a prior?",
        ).forEach { assertTrue(it, it in prompt.text) }
        assertEquals("topic block is not repeated", prompt.text.indexOf("A prior is a belief."), prompt.text.lastIndexOf("A prior is a belief."))
        assertFalse("Assessment:" in prompt.text)
        assertEquals(setOf(10, 11, 42), prompt.sentBlocks)
    }

    @Test fun assessmentIsRequestedAfterAQuestionTurn() {
        val prompt = PromptBuilder.conversationTurn(context, blocks, emptyList(), emptyList(), "It updates the belief.", TutorAction.ASK, assessAnswer = true)
        assertTrue("Assessment:" in prompt.text)
        assertFalse("Learning profile" in prompt.text)
    }

    @Test fun everyActionHasAnInstruction() {
        TutorAction.entries.forEach { assertTrue(it.name, PromptBuilder.instruction(it).isNotBlank()) }
    }

    @Test fun longTopicIsClippedAndOnlyShownBlocksCount() {
        val long = (0 until 20).map { BlockEntity(bookId = "b", idx = it, kind = BlockKind.PARAGRAPH, text = "x".repeat(1000), headingLevel = 0, page = null, location = null) }
        val prompt = PromptBuilder.conversationTurn(context, long, emptyList(), emptyList(), "q", TutorAction.ASK, assessAnswer = false)
        assertTrue("(The rest of this topic is omitted.)" in prompt.text)
        assertTrue(prompt.sentBlocks.size in 2 until 20)
        assertTrue(prompt.sentBlocks.none { "[B$it]" !in prompt.text })
    }

    @Test fun guidedTurnListsTheOriginalWithBlockIds() {
        val text = PromptBuilder.guidedTurn(context, blocks)
        assertTrue("[B10] (p. 3) A prior is a belief." in text)
        assertTrue("\"quoted\"" in PromptBuilder.GUIDED_SYSTEM && "equations" in PromptBuilder.GUIDED_SYSTEM)
    }
}
