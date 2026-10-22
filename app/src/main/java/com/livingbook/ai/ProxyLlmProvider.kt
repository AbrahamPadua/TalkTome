package com.livingbook.ai

import com.livingbook.data.SettingsRepository

/** Calls the user's backend proxy (see /backend). Requires AI consent and a configured proxy URL. */
class ProxyLlmProvider(private val settings: SettingsRepository) : LlmProvider {
    override suspend fun complete(request: LlmRequest): LlmResult = TODO("ai worker")
}
