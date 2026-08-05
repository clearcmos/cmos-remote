package com.clearcmos.cmosremote.network

import com.clearcmos.cmosremote.Spec
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ApiClient against a real HTTP server (MockWebServer), covering the request it
 * sends, the payloads it decodes, and how it reports failures.
 *
 * Response bodies come from spec/wire-payloads.json, the same file the server's
 * tests assert its models against, so a field renamed on either side fails here.
 */
class ApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // --- requests -------------------------------------------------------------

    @Test
    fun `health check hits the health endpoint`() {
        server.enqueue(MockResponse().setBody(Spec.payload("health")))
        val result = runBlocking { ApiClient(baseUrl).healthCheck() }

        assertEquals(true, result.getOrNull())
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/health", recorded.path)
    }

    @Test
    fun `status decodes the shared payload`() {
        server.enqueue(MockResponse().setBody(Spec.payload("status_connected")))
        val status = runBlocking { ApiClient(baseUrl).getStatus() }.getOrThrow()

        assertFalse(status.muted)
        assertEquals(74, status.volume)
        assertTrue(status.bluetooth_on)
        assertEquals("Soundcore Life Q30", status.bluetooth_connected)
    }

    @Test
    fun `status decodes a payload with no connected device`() {
        server.enqueue(MockResponse().setBody(Spec.payload("status_bluetooth_off")))
        val status = runBlocking { ApiClient(baseUrl).getStatus() }.getOrThrow()

        assertTrue(status.muted)
        assertFalse(status.bluetooth_on)
        assertNull(status.bluetooth_connected)
    }

    @Test
    fun `mute posts to the mute endpoint and decodes the action payload`() {
        server.enqueue(MockResponse().setBody(Spec.payload("action_muted")))
        val action = runBlocking { ApiClient(baseUrl).toggleMute() }.getOrThrow()

        assertEquals("POST", server.takeRequest().method)
        assertTrue(action.success)
        assertEquals("Muted", action.message)
        assertEquals(true, action.new_state)
    }

    @Test
    fun `screen off decodes an action payload without a state`() {
        server.enqueue(MockResponse().setBody(Spec.payload("action_without_state")))
        val action = runBlocking { ApiClient(baseUrl).screenOff() }.getOrThrow()

        assertEquals("/screen-off", server.takeRequest().path)
        assertNull(action.new_state)
    }

    @Test
    fun `bluetooth posts to the bluetooth endpoint`() {
        server.enqueue(MockResponse().setBody(Spec.payload("action_muted")))
        runBlocking { ApiClient(baseUrl).toggleBluetooth() }.getOrThrow()
        assertEquals("/bluetooth", server.takeRequest().path)
    }

    @Test
    fun `set volume sends the level and decodes the response`() {
        server.enqueue(MockResponse().setBody(Spec.payload("volume_set")))
        val response = runBlocking { ApiClient(baseUrl).setVolume(42) }.getOrThrow()

        val recorded = server.takeRequest()
        assertEquals("/volume", recorded.path)
        assertEquals("""{"level":42}""", recorded.body.readUtf8())
        assertEquals(42, response.level)
        assertTrue(response.success)
    }

    // --- failure reporting ----------------------------------------------------

    @Test
    fun `health check reports false for a server error`() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(false, runBlocking { ApiClient(baseUrl).healthCheck() }.getOrNull())
    }

    @Test
    fun `health check fails when nothing is listening`() {
        server.shutdown()
        assertTrue(runBlocking { ApiClient(baseUrl).healthCheck() }.isFailure)
    }

    @Test
    fun `status fails on a non-2xx response and reports the reason`() {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"detail":"bad signature"}"""),
        )
        val result = runBlocking { ApiClient(baseUrl).getStatus() }

        assertTrue(result.isFailure)
        assertEquals("bad signature", result.exceptionOrNull()!!.message)
    }

    @Test
    fun `status falls back to the status code with no detail`() {
        server.enqueue(MockResponse().setResponseCode(500))
        val result = runBlocking { ApiClient(baseUrl).getStatus() }

        assertEquals("Server returned 500", result.exceptionOrNull()!!.message)
    }

    @Test
    fun `a server error surfaces the server's own explanation`() {
        // The server names the missing helper in FastAPI's detail field; that is
        // the message someone setting this up on their own machine needs to see,
        // not "Server returned 503".
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setBody("""{"detail":"bt-toggle not found on PATH. See README."}"""),
        )
        val result = runBlocking { ApiClient(baseUrl).toggleBluetooth() }

        assertTrue(result.isFailure)
        assertEquals(
            "bt-toggle not found on PATH. See README.",
            result.exceptionOrNull()!!.message,
        )
    }

    @Test
    fun `an error without a detail field falls back to the status code`() {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"))
        val result = runBlocking { ApiClient(baseUrl).toggleMute() }

        assertEquals("Server returned 502", result.exceptionOrNull()!!.message)
    }

    @Test
    fun `status fails on a body it cannot decode`() {
        // A field renamed server-side lands here rather than as a crash.
        server.enqueue(MockResponse().setBody("""{"muted":false}"""))
        assertTrue(runBlocking { ApiClient(baseUrl).getStatus() }.isFailure)
    }

    // --- authentication wiring ------------------------------------------------

    @Test
    fun `no token means unsigned requests`() {
        server.enqueue(MockResponse().setBody(Spec.payload("health")))
        runBlocking { ApiClient(baseUrl, token = "").healthCheck() }

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("X-Auth-Sig"))
        assertNull(recorded.getHeader("X-Auth-Nonce"))
    }

    @Test
    fun `a token signs requests and the response must be signed back`() {
        server.dispatcher = signingDispatcher(Spec.token)
        val result = runBlocking { ApiClient(baseUrl, token = Spec.token).healthCheck() }

        assertEquals(true, result.getOrNull())
        assertEquals(64, server.takeRequest().getHeader("X-Auth-Sig")!!.length)
    }

    @Test
    fun `a server that cannot sign is treated as unreachable`() {
        // The impostor case: right address, no shared secret. The client must
        // not report a healthy connection.
        server.dispatcher = signingDispatcher("a-different-secret")
        val result = runBlocking { ApiClient(baseUrl, token = Spec.token).healthCheck() }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("invalid response signature"))
    }

    // --- instance reuse -------------------------------------------------------

    @Test
    fun `getInstance reuses the client for the same url and token`() {
        val first = ApiClient.getInstance("http://example.invalid:8201", "t")
        assertSame(first, ApiClient.getInstance("http://example.invalid:8201", "t"))
    }

    @Test
    fun `getInstance rebuilds the client when the url or token changes`() {
        val first = ApiClient.getInstance("http://example.invalid:8201", "t")
        assertNotSame(first, ApiClient.getInstance("http://other.invalid:8201", "t"))
        assertNotSame(first, ApiClient.getInstance("http://example.invalid:8201", "t2"))
    }

    /** Serves a health payload signed with [signingToken], echoing the request nonce. */
    private fun signingDispatcher(signingToken: String) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val body = Spec.payload("health")
            val nonce = request.getHeader("X-Auth-Nonce") ?: return MockResponse().setResponseCode(401)
            val respTs = "1750000001"
            val message = listOf(nonce, respTs, "200", Spec.sha256Hex(body.toByteArray()))
                .joinToString("\n")
            return MockResponse()
                .setBody(body)
                .setHeader("X-Resp-Ts", respTs)
                .setHeader("X-Resp-Sig", Spec.hmacHex(signingToken, message))
        }
    }
}
