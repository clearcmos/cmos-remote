package com.clearcmos.cmosremote.network

import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs each request and verifies each response using an HMAC-SHA256 shared
 * secret, mirroring the server's scheme in server/main.py.
 *
 * Request:  X-Auth-Sig  = HMAC(token, "ts\nnonce\nMETHOD\npath\nsha256(body)")
 * Response: X-Resp-Sig  = HMAC(token, "nonce\nresp_ts\nstatus\nsha256(body)")
 *
 * The secret never travels on the wire. A response without a valid signature is
 * treated as a hard failure (possible impostor at the same address), so the app
 * never trusts or acts on it.
 */
class HmacInterceptor(token: String) : Interceptor {

    private val key = token.toByteArray(Charsets.UTF_8)
    private val secureRandom = SecureRandom()

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        val ts = (System.currentTimeMillis() / 1000L).toString()
        val nonce = ByteArray(16).also { secureRandom.nextBytes(it) }.toHex()

        val bodyBytes = original.body?.let { body ->
            Buffer().use { buffer ->
                body.writeTo(buffer)
                buffer.readByteArray()
            }
        } ?: ByteArray(0)

        val reqMsg = listOf(
            ts,
            nonce,
            original.method.uppercase(),
            original.url.encodedPath,
            sha256Hex(bodyBytes),
        ).joinToString("\n")

        val signed = original.newBuilder()
            .header("X-Auth-Ts", ts)
            .header("X-Auth-Nonce", nonce)
            .header("X-Auth-Sig", hmacHex(reqMsg))
            .build()

        val response = chain.proceed(signed)

        val respTs = response.header("X-Resp-Ts")
        val respSig = response.header("X-Resp-Sig")
        if (respTs == null || respSig == null) {
            response.close()
            throw IOException("unsigned response (server did not authenticate)")
        }

        val respBody = response.peekBody(MAX_BODY_BYTES).bytes()
        val expected = hmacHex(
            listOf(nonce, respTs, response.code.toString(), sha256Hex(respBody)).joinToString("\n")
        )
        if (!MessageDigest.isEqual(expected.toByteArray(), respSig.toByteArray())) {
            response.close()
            throw IOException("invalid response signature (possible impostor)")
        }

        return response
    }

    private fun hmacHex(msg: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(msg.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).toHex()

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append(HEX[(b.toInt() ushr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        return sb.toString()
    }

    companion object {
        private const val MAX_BODY_BYTES = 1L * 1024 * 1024
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
