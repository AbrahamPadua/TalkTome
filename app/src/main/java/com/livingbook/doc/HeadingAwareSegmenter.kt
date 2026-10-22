package com.livingbook.doc

import com.livingbook.model.Granularity
import com.livingbook.model.ParsedDocument
import com.livingbook.model.Topic

/** Splits at headings first, then at paragraph boundaries near a per-granularity length target. */
class HeadingAwareSegmenter : TopicSegmenter {
    override fun segment(document: ParsedDocument, granularity: Granularity): List<Topic> = TODO("doc worker")
}
