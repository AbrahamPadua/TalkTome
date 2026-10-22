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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        configure(url = localUrl(), consent = true)
    }

    private fun localUrl() = "http://127.0.0.1:${server.port}"

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
        runBlocking { configure(url = localUrl(), consent = false) }
        assertEquals(ProxyLlmProvider.NO_CONSENT, failureMessage())
        assertEquals(0, server.requestCount)
    }

    @Test fun plainHttpToAPublicHostIsRefused() {
        runBlocking { configure(url = "http://proxy.example.com:8787", consent = true) }
        assertEquals(ProxyLlmProvider.NOT_HTTPS, failureMessage())
    }

    @Test fun localHosts() {
        listOf("localhost", "127.0.0.1", "::1", "10.0.2.2", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.20", "mypc.local")
            .forEach { assertTrue(it, ProxyLlmProvider.isLocalHost(it)) }
        listOf("example.com", "8.8.8.8", "172.32.0.1", "172.15.0.1", "192.169.0.1", "11.0.0.1", "local", "10.0.0")
            .forEach { assertFalse(it, ProxyLlmProvider.isLocalHost(it)) }
    }

    @Test fun unsendableTokenIsActionable() {
        runBlocking { settings.update { it.copy(proxyToken = "tökén") } }
        assertEquals("The proxy URL or token in Settings contains characters that can't be sent. Check both and try again.", failureMessage())
        assertEquals(0, server.requestCount)
    }

    @Test fun oversizedReplyIsMalformed() {
        server.enqueue(MockResponse().setBody("{\"text\":\"" + "x".repeat(ProxyLlmProvider.MAX_RESPONSE_BYTES.toInt()) + "\",\"model\":\"m\"}"))
        assertEquals("The AI service sent a reply the app couldn't read. Check that your proxy is up to date.", failureMessage())
    }
}
