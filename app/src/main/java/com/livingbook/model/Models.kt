package com.livingbook.model

import kotlinx.serialization.Serializable

enum class BookFormat { PDF, EPUB }

/** What a block of source text is. FIGURE and EQUATION blocks usually point back to the original page. */
enum class BlockKind { HEADING, PARAGRAPH, LIST_ITEM, QUOTE, CODE, TABLE, EQUATION, FIGURE, CAPTION, FOOTNOTE }

enum class Granularity(val label: String) {
    BITE("Bite-sized"),
    BALANCED("Balanced"),
    DEEP("Deep"),
}

enum class Rendition { ORIGINAL, GUIDED }

enum class ReadMode { READ, LISTEN }

/**
 * One unit of source text in reading order.
 * [index] is the stable anchor inside a book: topics, progress, citations and conversations all point at block indices,
 * so they survive a change of topic granularity.
 */
data class SourceBlock(
    val index: Int,
    val kind: BlockKind,
    val text: String,
    /** 1..6 for HEADING, 0 otherwise. */
    val headingLevel: Int = 0,
    /** 1-based PDF page, when known. Never guessed. */
    val page: Int? = null,
    /** Format-specific locator, e.g. an EPUB "chapter3.xhtml#sec2". */
    val location: String? = null,
)

/** A chapter or section from the document outline (PDF bookmarks, EPUB nav) or from detected headings. */
data class ParsedSection(val title: String, val level: Int, val startBlock: Int)

data class ParsedDocument(
    val title: String,
    val author: String?,
    val format: BookFormat,
    val blocks: List<SourceBlock>,
    val sections: List<ParsedSection>,
    val pageCount: Int?,
    /** User-facing notes about extraction quality, e.g. "Pages 4-6 look like scanned images and were skipped". */
    val warnings: List<String> = emptyList(),
)

/** A contiguous run of blocks [startBlock]..[endBlock] (inclusive) read as one unit. */
data class Topic(
    val index: Int,
    val title: String,
    val startBlock: Int,
    val endBlock: Int,
    val sectionTitle: String?,
)

/** Where the reader is. Block-based so it maps onto any granularity. */
@Serializable
data class ReadingPosition(
    val blockIndex: Int,
    /** Character offset inside the block's text where the current sentence starts. */
    val charOffset: Int = 0,
)

// ---- AI answers -----------------------------------------------------------------------------------------------------

/** How an answer segment relates to the book. Shown to the user as a label on every segment. */
@Serializable
enum class SegmentKind {
    /** Directly supported by the imported book; carries citations. */
    BOOK,
    /** An explanation or interpretation of the book's content. */
    INTERPRETATION,
    /** General knowledge not contained in the book. */
    GENERAL,
}

@Serializable
data class AnswerSegment(
    val kind: SegmentKind,
    val text: String,
    /** Block indices that support this segment. Validated against the passages actually sent to the model. */
    val citations: List<Int> = emptyList(),
)

enum class ChatRole { USER, ASSISTANT, NOTE }

data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val segments: List<AnswerSegment>,
    /** True when produced by the offline demo provider, never by a real model. */
    val isMock: Boolean,
    val createdAt: Long,
)

// ---- Guided rendition ------------------------------------------------------------------------------------------------

@Serializable
enum class GuidedKind {
    /** Restates the author's content in clearer words. Never a quotation. */
    RESTATED,
    /** Added by the AI: analogy, example, transition. Visibly marked as added. */
    ADDED,
    /** Verbatim text from the source. Only kept if it matches the source exactly; otherwise downgraded to RESTATED. */
    QUOTED,
}

@Serializable
data class GuidedSegment(
    val kind: GuidedKind,
    val text: String,
    /** Source blocks this segment is based on, so the user can inspect the original. */
    val sourceBlocks: List<Int> = emptyList(),
)

@Serializable
data class GuidedContent(
    val segments: List<GuidedSegment>,
    val isMock: Boolean,
    val model: String,
)
