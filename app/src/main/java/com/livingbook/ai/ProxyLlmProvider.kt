package com.livingbook.ai

import com.livingbook.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** Calls the user's backend proxy (see /backend). Requires AI consent and a configured proxy URL. */
class ProxyLlmProvider(
    private val settings: SettingsRepository,
    private val client: OkHttpClient = defaultClient,
) : LlmProvider {

    override suspend fun complete(request: LlmRequest): LlmResult {
        val s = settings.current()
        val base = s.proxyUrl.trim().trimEnd('/')
        if (base.isEmpty()) throw AiException(NO_URL)
        if (!s.aiConsent) throw AiException(NO_CONSENT)
        val url = "$base/v1/complete".toHttpUrlOrNull()
            ?: throw AiException("The AI proxy address in Settings isn't a valid web address. It should look like http://192.168.1.20:8787.")
        val body = json.encodeToString(
            WireRequest.serializer(),
            WireRequest(request.system, request.messages.map { WireMessage(it.role, it.content) }, request.maxTokens),
        )
        val call = client.newCall(
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${s.proxyToken.trim()}")
                .post(body.toRequestBody(JSON))
                .build()
        )
        val (code, text) = try {
            runInterruptible(Dispatchers.IO) { call.execute().use { it.code to it.body.string() } }
        } catch (e: SocketTimeoutException) {
            throw AiException("The AI service took too long to answer. Try again, or ask a shorter question.", e)
        } catch (e: IOException) {
            throw AiException(UNREACHABLE, e)
        }
        if (code !in 200..299) throw AiException(messageForStatus(code))
        val reply = runCatching { json.decodeFromString(WireResponse.serializer(), text) }.getOrNull()
        if (reply == null || reply.text.isBlank()) {
            throw AiException("The AI service sent a reply the app couldn't read. Check that your proxy is up to date.")
        }
        return LlmResult(reply.text, reply.model)
    }

    @Serializable
    private data class WireMessage(val role: String, val content: String)

    @Serializable
    private data class WireRequest(
        val system: String,
        val messages: List<WireMessage>,
        @SerialName("max_tokens") val maxTokens: Int,
    )

    @Serializable
    private data class WireResponse(val text: String = "", val model: String = "unknown")

    companion object {
        const val NO_URL = "Set up your AI proxy in Settings."
        const val NO_CONSENT = "AI answers send short excerpts of this book to your AI service. Review what is sent and turn on AI consent in Settings first."
        const val UNREACHABLE = "Can't reach the AI service. Reading and narration still work offline."

        internal fun messageForStatus(code: Int): String = when (code) {
            401, 403 -> "Your AI proxy rejected the access token. Check the proxy token in Settings."
            413 -> "This request is too large for the AI proxy. Try a shorter question or a smaller selection."
            422 -> "The model declined this request. Try rephrasing your question."
            429 -> "The AI service is busy right now. Wait a moment and try again."
            in 500..599 -> "The AI service had a problem answering (error $code). Try again in a moment; reading and narration still work."
            else -> "The AI proxy returned an unexpected error (HTTP $code). Check the proxy address in Settings."
        }

        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }
}
