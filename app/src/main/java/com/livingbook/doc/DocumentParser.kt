package com.livingbook.doc

import com.livingbook.model.Granularity
import com.livingbook.model.ParsedDocument
import com.livingbook.model.Topic
import java.io.File

/** Thrown for anything the user must be told about: unsupported, corrupted, DRM-protected or scanned files. */
class DocumentException(val userMessage: String, cause: Throwable? = null) : Exception(userMessage, cause)

/** Extracts readable text and structure from one file format, fully on device. */
interface DocumentParser {
    fun canParse(fileName: String, mimeType: String?): Boolean

    /** @throws DocumentException with an actionable message. Never returns silently empty content. */
    suspend fun parse(file: File): ParsedDocument
}

/**
 * Groups blocks into readable topics.
 * Invariant: topics are contiguous, in reading order, and together cover every block exactly once.
 */
interface TopicSegmenter {
    fun segment(document: ParsedDocument, granularity: Granularity): List<Topic>
}
