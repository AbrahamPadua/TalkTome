package com.livingbook.ai

import com.livingbook.data.AiMode
import com.livingbook.data.ReadingRepository
import com.livingbook.data.SettingsRepository
import com.livingbook.data.db.BlockEntity
import com.livingbook.model.BlockKind
import com.livingbook.model.GuidedContent
import com.livingbook.model.GuidedKind
import com.livingbook.model.GuidedSegment
import com.livingbook.session.Sentences

class AiGuidedContentGenerator(
    private val reading: ReadingRepository,
    private val provider: LlmProvider,
    private val settings: SettingsRepository,
) : GuidedContentGenerator {

    override suspend fun guidedFor(context: ReadingContext): GuidedContent {
        val topic = context.topic
        reading.guided(context.bookId, topic.startBlock, topic.endBlock)?.let { return it }
        val s = settings.current()
        val blocks = context.topicBlocks.ifEmpty { reading.blocks(context.bookId, topic.startBlock, topic.endBlock) }
        return when (s.aiMode) {
            AiMode.OFF -> throw AiException("Guided content needs AI. Turn on AI in Settings, or keep reading the original.")
            // Not cached, so switching to a real model later produces a real rendition.
            AiMode.DEMO -> demo(blocks)
            AiMode.PROXY -> {
                if (!s.aiConsent) throw AiException(ProxyLlmProvider.NO_CONSENT)
                if (blocks.sumOf { it.text.length } > MAX_SOURCE_CHARS) {
                    throw AiException("This topic is too long for a guided version. Choose shorter topics in Settings, or keep reading the original.")
                }
                val result = provider.complete(
                    LlmRequest(PromptBuilder.GUIDED_SYSTEM, listOf(LlmMessage("user", PromptBuilder.guidedTurn(context, blocks))), maxTokens = MAX_TOKENS)
                )
                val segments = ResponseParser.parseGuided(result.text, blocks)
                if (segments.isEmpty()) throw AiException("The AI service couldn't produce a guided version of this topic. Try again, or keep reading the original.")
                GuidedContent(segments, isMock = false, model = result.model)
                    .also { reading.saveGuided(context.bookId, topic.startBlock, topic.endBlock, it) }
            }
        }
    }

    private fun demo(blocks: List<BlockEntity>): GuidedContent {
        val intro = GuidedSegment(
            GuidedKind.ADDED,
            "Demo mode, not AI: a guided rendition needs a real model. Below are the key sentences of the original, quoted verbatim.",
        )
        val quotes = blocks.filter { it.kind == BlockKind.PARAGRAPH }.mapNotNull { block ->
            Sentences.split(block.text).firstOrNull()?.let { (_, sentence) -> GuidedSegment(GuidedKind.QUOTED, sentence, listOf(block.idx)) }
        }
        return GuidedContent(listOf(intro) + quotes, isMock = true, model = "demo")
    }

    private companion object {
        /** A rendition of roughly the original's length has to fit in the proxy's output cap. */
        const val MAX_SOURCE_CHARS = 12_000
        const val MAX_TOKENS = 4000
    }
}
