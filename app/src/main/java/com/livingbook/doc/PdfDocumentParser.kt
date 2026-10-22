package com.livingbook.doc

import android.content.Context
import com.livingbook.model.ParsedDocument
import java.io.File

/** Text-based PDF parsing with PdfBox-Android. Detects scanned/image-only PDFs. */
class PdfDocumentParser(private val context: Context) : DocumentParser {
    override fun canParse(fileName: String, mimeType: String?): Boolean =
        mimeType == "application/pdf" || fileName.endsWith(".pdf", ignoreCase = true)

    override suspend fun parse(file: File): ParsedDocument = TODO("doc worker")
}
