package com.livingbook.doc

import com.livingbook.model.ParsedDocument
import java.io.File

/** DRM-free EPUB 2/3 parsing: container.xml -> OPF spine -> XHTML blocks. Rejects DRM-protected books. */
class EpubDocumentParser : DocumentParser {
    override fun canParse(fileName: String, mimeType: String?): Boolean =
        mimeType == "application/epub+zip" || fileName.endsWith(".epub", ignoreCase = true)

    override suspend fun parse(file: File): ParsedDocument = TODO("doc worker")
}
