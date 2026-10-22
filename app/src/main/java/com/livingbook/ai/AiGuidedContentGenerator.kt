package com.livingbook.ai

import com.livingbook.data.ReadingRepository
import com.livingbook.data.SettingsRepository
import com.livingbook.model.GuidedContent

class AiGuidedContentGenerator(
    private val reading: ReadingRepository,
    private val provider: LlmProvider,
    private val settings: SettingsRepository,
) : GuidedContentGenerator {
    override suspend fun guidedFor(context: ReadingContext): GuidedContent = TODO("ai worker")
}
