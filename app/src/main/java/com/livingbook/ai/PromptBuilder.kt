package com.livingbook.ai

import com.livingbook.data.db.BlockEntity
import com.livingbook.data.db.LearningSignalEntity
import com.livingbook.data.db.SignalKind

/** A user turn plus the block indices it showed the model. Only those may be cited. */
data class BuiltPrompt(val text: String, val sentBlocks: Set<Int>)

/**
 * Prompts for the conversation and the guided rendition.
 * System prompts are constants so the provider can cache them; everything that changes per call goes in the user turn.
 */
object PromptBuilder {

    val SYSTEM = """
        You are a calm, precise reading companion inside an app that reads books aloud. The reader has paused a book to talk with you about it. Your replies are often spoken by text-to-speech, so keep them short and natural to listen to: a few plain sentences, no markdown, no lists. Go longer only when the reader asks to go deeper or the instruction says so.

        Grounding rules:
        - Base your answer on the passages from the book in the reader's message. Each passage starts with its block id, like [B12], and sometimes its page, like (p. 4).
        - Label every part of your answer with a kind:
          "book": directly supported by the passages. List the supporting block numbers in "citations".
          "interpretation": your explanation or reading of what the book says.
          "general": knowledge that is not in the book.
        - Cite only block ids that appear in the reader's message. Never invent block ids, page numbers or quotations.
        - Quote the book only word for word, and only from the passages.
        - If the passages don't answer the question, say so plainly in an "interpretation" segment, for example "The book doesn't cover this in the passages I have." You may then add a short "general" answer.
        - If a learning profile is given, adapt to it quietly without mentioning it.

        Reply with only a JSON object and no other text:
        {"segments":[{"kind":"book","text":"...","citations":[12,14]}],"assessment":null}
        "citations" holds block numbers as integers ([B12] is 12). Leave "assessment" null unless the reader's message asks you to assess their answer; then set it to {"understood":true,"concept":"the idea being checked, in a few words"} with understood true or false.
    """.trimIndent()

    val GUIDED_SYSTEM = """
        You rewrite one topic of a book as a guided rendition: a clearer, well-ordered educational version that the reader can read or listen to instead of the original.

        Rules:
        - Preserve the author's meaning, claims and order. Don't add opinions or new claims.
        - Keep roughly the original's length.
        - Label every segment with a kind:
          "restated": the author's content in clearer words.
          "added": anything that is not in the original, such as an analogy, an example or a transition. Keep these short.
          "quoted": text copied exactly, character for character, from the original. Use it for key definitions or memorable lines.
        - Never rewrite equations, formulas, code or tables. Instead write a short "restated" segment that points the reader to the original, for example "The original shows the equation for the posterior here."
        - "sourceBlocks" lists the block numbers each segment is based on ([B12] is 12). Use only blocks from the original.
        - Write for listening: plain sentences, no markdown.

        Reply with only a JSON object and no other text:
        {"segments":[{"kind":"restated","text":"...","sourceBlocks":[12]}]}
    """.trimIndent()

    /** Actions whose reply asks the reader something; the reader's next message is then assessed. */
    val ASSESSED_ACTIONS = setOf(TutorAction.CHALLENGE, TutorAction.QUIZ, TutorAction.TEACH_BACK)

    fun conversationTurn(
        context: ReadingContext,
        topicBlocks: List<BlockEntity>,
        passages: List<Passage>,
        profile: List<LearningSignalEntity>,
        question: String,
        action: TutorAction,
        assessAnswer: Boolean,
    ): BuiltPrompt {
        val sent = mutableSetOf<Int>()
        val text = buildString {
            appendLine(bookLine(context))
            appendLine("Topic: ${context.topic.title}")
            context.currentSentence?.let { appendLine("The reader was at this sentence: \"${clip(it, 400)}\"") }
            context.selectedText?.let { appendLine("The reader selected this paragraph: \"${clip(it, 1500)}\"") }

            appendLine()
            appendLine("Topic text:")
            var budget = TOPIC_BUDGET
            for (block in topicBlocks.filter { it.text.isNotBlank() }) {
                if (budget <= 0) {
                    appendLine("(The rest of this topic is omitted.)")
                    break
                }
                val line = passageLine(block.idx, block.page, clip(block.text, BookSourceRetriever.MAX_CHARS))
                appendLine(line)
                budget -= line.length
                sent += block.idx
            }

            val others = passages.filter { it.blockIndex !in sent }
            if (others.isNotEmpty()) {
                appendLine()
                appendLine("Other passages from the book:")
                var lastTitle: String? = null
                for (p in others) {
                    if (p.topicTitle != null && p.topicTitle != lastTitle) appendLine("From \"${p.topicTitle}\":")
                    lastTitle = p.topicTitle
                    appendLine(passageLine(p.blockIndex, p.page, p.text))
                    sent += p.blockIndex
                }
            }

            if (profile.isNotEmpty()) {
                appendLine()
                appendLine("Learning profile:")
                profile.forEach { appendLine("- ${profileLine(it)}") }
            }

            appendLine()
            appendLine("Instruction: ${instruction(action)}")
            if (assessAnswer) appendLine("Assessment: $ASSESS")
            appendLine()
            append("Reader's message: $question")
        }
        return BuiltPrompt(text, sent)
    }

    fun guidedTurn(context: ReadingContext, blocks: List<BlockEntity>): String = buildString {
        appendLine(bookLine(context))
        appendLine("Topic: ${context.topic.title}")
        appendLine()
        appendLine("Original:")
        blocks.filter { it.text.isNotBlank() }.forEach { appendLine(passageLine(it.idx, it.page, it.text)) }
    }.trimEnd()

    fun instruction(action: TutorAction): String = when (action) {
        TutorAction.ASK -> "Answer the reader's message."
        TutorAction.EXPLAIN_DIFFERENTLY -> "Explain the idea the reader is asking about differently from before: a new angle, simpler words or an analogy."
        TutorAction.EXAMPLE -> "Give one concrete example of the idea. Use an example from the passages if there is one (kind book, with citations); otherwise make one up and label it interpretation or general."
        TutorAction.GO_DEEPER -> "Go deeper than before: the reasoning behind the idea, its assumptions and how it connects to the rest of the topic. A longer answer is fine here."
        TutorAction.CONTINUE_EXPLAINING -> "Continue your previous explanation from where it stopped, without repeating what you already said."
        TutorAction.SUMMARIZE -> "Summarize the current topic in a few sentences, in the book's order, with citations."
        TutorAction.RECAP_DISCUSSION -> "Briefly recap what you and the reader have discussed in this conversation so far."
        TutorAction.EXPLAIN -> "Explain the main idea of the current topic another way: an alternative explanation, an analogy, or the background it assumes."
        TutorAction.EXPLORE -> "Help the reader explore the topic: ask one or two thoughtful open questions and point out a connection to other ideas in the book or beyond. Don't lecture."
        TutorAction.CHALLENGE -> "Ask the reader one targeted question that tests whether they can apply or interpret the main idea of the topic. Ask only the question and don't answer it."
        TutorAction.QUIZ -> "Quiz the reader on this topic with 2 or 3 short questions. Asking one at a time is fine. Don't give the answers yet."
        TutorAction.TEACH_BACK -> "Ask the reader to explain the main idea of the topic in their own words, as if teaching a friend. Only ask; don't explain it yourself."
    }

    private const val TOPIC_BUDGET = 6000

    private const val ASSESS = "The reader is answering the question you asked in your previous turn. Respond to their answer: confirm what is right and gently correct what is not, citing the book. Set \"assessment\"."

    private fun bookLine(context: ReadingContext) =
        "Book: \"${context.bookTitle}\"" + (context.author?.let { " by $it" } ?: "")

    private fun passageLine(block: Int, page: Int?, text: String) = "[B$block] " + (page?.let { "(p. $it) " } ?: "") + text

    private fun profileLine(signal: LearningSignalEntity) = when (signal.kind) {
        SignalKind.PREFERENCE -> signal.label
        SignalKind.STRUGGLE -> "Has struggled with: ${signal.label}"
        SignalKind.UNDERSTOOD -> "Has shown understanding of: ${signal.label}"
    }

    private fun clip(text: String, max: Int) = if (text.length <= max) text else text.take(max).trimEnd() + "…"
}
