package com.clearcmos.cmosremote.network

import com.clearcmos.cmosremote.Spec
import com.clearcmos.cmosremote.Spec.str
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Pins this client to the same HMAC wire format the server implements.
 *
 * The vector tests read spec/hmac-vectors.json, the same file the server's
 * tests/test_auth.py asserts against. Either implementation drifting from that
 * file fails CI here, instead of showing up later as an unexplained
 * "Disconnected" in the app with nothing in either log to explain it.
 */
class HmacInterceptorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // --- canonical vectors ----------------------------------------------------

    @Test
    fun `request vectors match the shared spec`() {
        for (case in Spec.requestVectors()) {
            val message = HmacInterceptor.requestMessage(
                case.str("ts"),
                case.str("nonce"),
                case.str("method"),
                case.str("path"),
                case.str("body").toByteArray(),
            )
            assertEquals("message for ${case.str("name")}", case.str("message"), message)
            assertEquals(
                "signature for ${case.str("name")}",
                case.str("signature"),
                Spec.hmacHex(Spec.token, message),
            )
        }
    }

    @Test
    fun `response vectors match the shared spec`() {
        for (case in Spec.responseVectors()) {
            val message = HmacInterceptor.responseMessage(
                case.str("nonce"),
                case.str("resp_ts"),
                case.str("status").toInt(),
                case.str("body").toByteArray(),
            )
            assertEquals("message for ${case.str("name")}", case.str("message"), message)
            assertEquals(
                "signature for ${case.str("name")}",
                case.str("signature"),
                Spec.hmacHex(Spec.token, message),
            )
        }
    }

    @Test
    fun `sha256 of an empty body matches the spec vectors`() {
        val fromVector = Spec.requestVector("get_empty_body").str("message").substringAfterLast("\n")
        assertEquals(fromVector, HmacInterceptor.sha256Hex(ByteArray(0)))
    }

    // --- request signing ------------------------------------------------------

    @Test
    fun `signs a GET exactly as the spec vector does`() {
        val vector = Spec.requestVector("get_empty_body")
        server.enqueue(signedResponse(nonce = vector.str("nonce"), body = "{}"))

        clientWith(ts = vector.str("ts").toLong(), nonce = vector.str("nonce"))
            .newCall(Request.Builder().url(server.url("/health")).build())
            .execute()
            .close()

        val recorded = server.takeRequest()
        assertEquals(vector.str("ts"), recorded.getHeader("X-Auth-Ts"))
        assertEquals(vector.str("nonce"), recorded.getHeader("X-Auth-Nonce"))
        assertEquals(vector.str("signature"), recorded.getHeader("X-Auth-Sig"))
    }

    @Test
    fun `signs a POST body exactly as the spec vector does`() {
        val vector = Spec.requestVector("post_with_body")
        server.enqueue(signedResponse(nonce = vector.str("nonce"), body = "{}"))

        val body = vector.str("body").toRequestBody("application/json".toMediaType())
        clientWith(ts = vector.str("ts").toLong(), nonce = vector.str("nonce"))
            .newCall(Request.Builder().url(server.url("/volume")).post(body).build())
            .execute()
            .close()

        assertEquals(vector.str("signature"), server.takeRequest().getHeader("X-Auth-Sig"))
    }

    @Test
    fun `signs a hyphenated path exactly as the spec vector does`() {
        val vector = Spec.requestVector("hyphenated_path")
        server.enqueue(signedResponse(nonce = vector.str("nonce"), body = "{}"))

        clientWith(ts = vector.str("ts").toLong(), nonce = vector.str("nonce"))
            .newCall(
                Request.Builder()
                    .url(server.url("/screen-off"))
                    .post("".toRequestBody("application/json".toMediaType()))
                    .build(),
            )
            .execute()
            .close()

        assertEquals(vector.str("signature"), server.takeRequest().getHeader("X-Auth-Sig"))
    }

    @Test
    fun `changing the body changes the signature`() {
        assertNotEquals(
            signatureFor("/volume", """{"level":42}"""),
            signatureFor("/volume", """{"level":100}"""),
        )
    }

    @Test
    fun `changing the path changes the signature`() {
        assertNotEquals(
            signatureFor("/volume", """{"level":42}"""),
            signatureFor("/mute", """{"level":42}"""),
        )
    }

    @Test
    fun `each request gets a fresh nonce`() {
        val client = OkHttpClient.Builder().addInterceptor(HmacInterceptor(Spec.token)).build()
        val nonces = mutableSetOf<String>()
        repeat(5) {
            server.enqueue(MockResponse().setBody("{}"))
            try {
                client.newCall(Request.Builder().url(server.url("/health")).build()).execute().close()
            } catch (_: IOException) {
                // Expected: the canned response is unsigned. The request still
                // reached the server, which is what this test inspects.
            }
            nonces.add(server.takeRequest().getHeader("X-Auth-Nonce")!!)
        }
        assertEquals(5, nonces.size)
        assertTrue(nonces.all { it.length == 32 && it.all { c -> c in "0123456789abcdef" } })
    }

    // --- response verification ------------------------------------------------

    @Test
    fun `accepts a correctly signed response`() {
        val nonce = "aaaabbbbccccddddeeeeffff00001111"
        server.enqueue(signedResponse(nonce = nonce, body = """{"status":"ok"}"""))

        val response = clientWith(ts = 1750000000, nonce = nonce)
            .newCall(Request.Builder().url(server.url("/health")).build())
            .execute()

        assertEquals(200, response.code)
        assertEquals("""{"status":"ok"}""", response.body!!.string())
    }

    @Test
    fun `rejects an unsigned response`() {
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        assertTrue(expectFailure().message!!.contains("unsigned response"))
    }

    @Test
    fun `rejects a response signed with the wrong key`() {
        val nonce = "aaaabbbbccccddddeeeeffff00001111"
        server.enqueue(signedResponse(nonce = nonce, body = "{}", signingToken = "someone-elses-secret"))
        assertTrue(expectFailure(nonce).message!!.contains("invalid response signature"))
    }

    @Test
    fun `rejects a response whose body was altered after signing`() {
        val nonce = "aaaabbbbccccddddeeeeffff00001111"
        server.enqueue(signedResponse(nonce = nonce, body = "{}").setBody("""{"muted":true}"""))
        assertTrue(expectFailure(nonce).message!!.contains("invalid response signature"))
    }

    @Test
    fun `rejects a response signed for a different nonce`() {
        server.enqueue(signedResponse(nonce = "0".repeat(32), body = "{}"))
        assertTrue(expectFailure("1".repeat(32)).message!!.contains("invalid response signature"))
    }

    @Test
    fun `rejects an error response that is not signed`() {
        // A 401 has to be authenticated too, or an impostor could fake one.
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"nope"}"""))
        assertTrue(expectFailure().message!!.contains("unsigned response"))
    }

    // --- helpers --------------------------------------------------------------

    private fun clientWith(ts: Long, nonce: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(HmacInterceptor(Spec.token, clock = { ts }, nonceSource = { nonce }))
            .build()

    /** A response carrying the signature the client should accept. */
    private fun signedResponse(
        nonce: String,
        body: String,
        status: Int = 200,
        signingToken: String = Spec.token,
    ): MockResponse {
        val respTs = "1750000001"
        val message = listOf(nonce, respTs, status.toString(), Spec.sha256Hex(body.toByteArray()))
            .joinToString("\n")
        return MockResponse()
            .setResponseCode(status)
            .setBody(body)
            .setHeader("X-Resp-Ts", respTs)
            .setHeader("X-Resp-Sig", Spec.hmacHex(signingToken, message))
    }

    private fun signatureFor(path: String, body: String): String {
        server.enqueue(MockResponse().setBody("{}"))
        try {
            clientWith(ts = 1750000000, nonce = "0".repeat(32)).newCall(
                Request.Builder()
                    .url(server.url(path))
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build(),
            ).execute().close()
        } catch (_: IOException) {
            // The canned response is unsigned; only the request matters here.
        }
        return server.takeRequest().getHeader("X-Auth-Sig")!!
    }

    private fun expectFailure(nonce: String = "aaaabbbbccccddddeeeeffff00001111"): IOException {
        try {
            clientWith(ts = 1750000000, nonce = nonce)
                .newCall(Request.Builder().url(server.url("/health")).build())
                .execute()
                .close()
        } catch (e: IOException) {
            return e
        }
        fail("expected the interceptor to reject the response")
        error("unreachable")
    }
}
