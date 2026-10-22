package com.livingbook.ai

import androidx.test.core.app.ApplicationProvider
import com.livingbook.data.SettingsRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ProxyLlmProviderTest {

    private val server = MockWebServer()
    private val settings = SettingsRepository(ApplicationProvider.getApplicationContext())
    private val client = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build()
    private val provider = ProxyLlmProvider(settings, client)
    private val request = LlmRequest("system text", listOf(LlmMessage("user", "What is a prior?")), maxTokens = 1500)

    @Before fun setUp() = runBlocking {
        server.start()
        configure(url = server.url("/").toString(), consent = true)
    }

    @After fun tearDown() = server.shutdown()

    private suspend fun configure(url: String, consent: Boolean) =
        settings.update { it.copy(proxyUrl = url, proxyToken = "secret-token", aiConsent = consent) }

    private fun failureMessage(): String = runBlocking {
        try {
            provider.complete(request)
            fail("expected AiException")
            ""
        } catch (e: AiException) {
            e.userMessage
        }
    }

    @Test fun successSendsContractAndParsesReply() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"text":"A prior is a belief.","model":"claude-opus-5-5","extra":1}"""))

        val result = provider.complete(request)

        assertEquals(LlmResult("A prior is a belief.", "claude-opus-5-5"), result)
        val recorded = server.takeRequest()
        assertEquals("/v1/complete", recorded.path)
        assertEquals("Bearer secret-token", recorded.getHeader("Authorization"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("system text", body["system"]!!.jsonPrimitive.content)
        assertEquals(1500, body["max_tokens"]!!.jsonPrimitive.int)
        val message = body["messages"]!!.jsonArray.single().jsonObject
        assertEquals("user", message["role"]!!.jsonPrimitive.content)
        assertEquals("What is a prior?", message["content"]!!.jsonPrimitive.content)
    }

    @Test fun unauthorizedMeansTokenRejected() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Invalid token."}"""))
        assertEquals(ProxyLlmProvider.messageForStatus(401), failureMessage())
    }

    @Test fun unprocessableMeansModelDeclined() {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"error":"The model declined this request."}"""))
        assertEquals("The model declined this request. Try rephrasing your question.", failureMessage())
    }

    @Test fun serverErrorIsActionable() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(ProxyLlmProvider.messageForStatus(500), failureMessage())
    }

    @Test fun malformedReply() {
        server.enqueue(MockResponse().setBody("<html>not json</html>"))
        assertEquals("The AI service sent a reply the app couldn't read. Check that your proxy is up to date.", failureMessage())
    }

    @Test fun timeout() {
        server.enqueue(MockResponse().setBody("""{"text":"late","model":"m"}""").setHeadersDelay(2, TimeUnit.SECONDS))
        assertEquals("The AI service took too long to answer. Try again, or ask a shorter question.", failureMessage())
    }

    @Test fun unreachable() {
        runBlocking { configure(url = "http://127.0.0.1:1", consent = true) }
        assertEquals(ProxyLlmProvider.UNREACHABLE, failureMessage())
    }

    @Test fun noUrlSendsNothing() {
        runBlocking { configure(url = "  ", consent = true) }
        assertEquals(ProxyLlmProvider.NO_URL, failureMessage())
        assertEquals(0, server.requestCount)
    }

    @Test fun noConsentSendsNothing() {
        runBlocking { configure(url = server.url("/").toString(), consent = false) }
        assertEquals(ProxyLlmProvider.NO_CONSENT, failureMessage())
        assertEquals(0, server.requestCount)
    }
}
