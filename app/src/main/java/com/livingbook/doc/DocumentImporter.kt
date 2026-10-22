package com.livingbook.doc

import android.content.Context
import android.net.Uri
import com.livingbook.data.db.AppDatabase

/**
 * Import pipeline: copy the picked file into app storage, validate, parse, segment at every granularity, store.
 * Returns the new book id.
 */
class DocumentImporter(
    private val context: Context,
    private val db: AppDatabase,
    private val parsers: List<DocumentParser>,
    private val segmenter: TopicSegmenter,
) {
    /** @throws DocumentException with a user-facing message. */
    suspend fun import(uri: Uri): String = TODO("doc worker")
}
