package com.livingbook.ai

import com.livingbook.data.db.BlockEntity
import com.livingbook.model.AnswerSegment
import com.livingbook.model.GuidedKind
import com.livingbook.model.GuidedSegment
import com.livingbook.model.SegmentKind
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** The model's verdict on the reader's answer to a CHALLENGE, QUIZ or TEACH_BACK question. */
data class Assessment(val understood: Boolean, val concept: String)

data class ParsedAnswer(val segments: List<AnswerSegment>, val assessment: Assessment?)

/** Lenient parsing of model output plus the grounding checks: only real citations, only real quotes. */
object ResponseParser {

    /**
     * Keeps only citations in [sentBlocks]; a BOOK segment left without one becomes INTERPRETATION.
     * Unusable JSON falls back to one INTERPRETATION segment with the reply's text.
     */
    fun parseAnswer(raw: String, sentBlocks: Set<Int>): ParsedAnswer {
        val wire = decode(raw, WireReply.serializer())
        val segments = wire?.segments.orEmpty().mapNotNull { seg ->
            val text = seg.text.trim().ifEmpty { return@mapNotNull null }
            val kind = when (seg.kind.trim().lowercase()) {
                "book" -> SegmentKind.BOOK
                "general" -> SegmentKind.GENERAL
                else -> SegmentKind.INTERPRETATION
            }
            val citations = if (kind == SegmentKind.GENERAL) emptyList()
            else seg.citations.mapNotNull(::blockNumber).filter { it in sentBlocks }.distinct()
            AnswerSegment(if (kind == SegmentKind.BOOK && citations.isEmpty()) SegmentKind.INTERPRETATION else kind, text, citations)
        }
        if (segments.isEmpty()) {
            return ParsedAnswer(fallbackText(raw)?.let { listOf(AnswerSegment(SegmentKind.INTERPRETATION, it)) }.orEmpty(), null)
        }
        val assessment = wire?.assessment?.takeIf { it.concept.isNotBlank() }?.let { Assessment(it.understood, it.concept.trim()) }
        return ParsedAnswer(segments, assessment)
    }

    /**
     * Keeps [GuidedSegment.sourceBlocks] inside [range]; a QUOTED segment not found verbatim in [source]
     * (ignoring whitespace differences) becomes RESTATED. Returns empty when the JSON is unusable.
     */
    fun parseGuided(raw: String, source: List<BlockEntity>, range: IntRange): List<GuidedSegment> {
        val wire = decode(raw, WireReply.serializer()) ?: return emptyList()
        val sourceTexts = source.map { squash(it.text) }
        return wire.segments.mapNotNull { seg ->
            val text = seg.text.trim().ifEmpty { return@mapNotNull null }
            val blocks = seg.sourceBlocks.mapNotNull(::blockNumber).filter { it in range }.distinct()
            when (seg.kind.trim().lowercase()) {
                "added" -> GuidedSegment(GuidedKind.ADDED, text, blocks)
                "quoted" -> {
                    val quote = text.trim('"', '“', '”').trim()
                    if (quote.isNotEmpty() && sourceTexts.any { squash(quote) in it }) GuidedSegment(GuidedKind.QUOTED, quote, blocks)
                    else GuidedSegment(GuidedKind.RESTATED, text, blocks)
                }
                else -> GuidedSegment(GuidedKind.RESTATED, text, blocks)
            }
        }
    }

    /** Strips code fences and surrounding prose by taking the outermost braces. */
    private fun <T> decode(raw: String, serializer: KSerializer<T>): T? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.decodeFromString(serializer, raw.substring(start, end + 1)) }.getOrNull()
    }

    /** Salvages "text" fields from broken JSON (e.g. a reply cut off mid-object), else the raw reply without fences. */
    private fun fallbackText(raw: String): String? {
        val salvaged = TEXT_FIELD.findAll(raw)
            .mapNotNull { runCatching { json.decodeFromString(String.serializer(), "\"${it.groupValues[1]}\"") }.getOrNull() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
        return salvaged.ifBlank { raw.replace(FENCE, "").trim() }.ifBlank { null }
    }

    /** Accepts 12, "12" and "B12". */
    private fun blockNumber(value: JsonPrimitive): Int? = DIGITS.find(value.content)?.value?.toIntOrNull()

    private fun squash(text: String) = text.replace(WHITESPACE, " ").trim()

    @Serializable
    private data class WireSegment(
        val kind: String = "",
        val text: String = "",
        val citations: List<JsonPrimitive> = emptyList(),
        val sourceBlocks: List<JsonPrimitive> = emptyList(),
    )

    @Serializable
    private data class WireAssessment(val understood: Boolean = false, val concept: String = "")

    @Serializable
    private data class WireReply(val segments: List<WireSegment> = emptyList(), val assessment: WireAssessment? = null)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
    private val TEXT_FIELD = Regex("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val FENCE = Regex("```[a-zA-Z]*")
    private val DIGITS = Regex("\\d+")
    private val WHITESPACE = Regex("\\s+")
}
