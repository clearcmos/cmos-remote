package com.clearcmos.cmosremote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Access to the cross-language contract files under spec/.
 *
 * The server's pytest suite asserts against the same files, so these are the
 * one place where the two implementations meet. The signing helpers here are
 * written independently of HmacInterceptor on purpose: a test that reuses the
 * production helper to check the production helper proves nothing.
 */
object Spec {

    private val json = Json { ignoreUnknownKeys = true }

    private val specDir: File by lazy {
        val path = System.getProperty("spec.dir")
            ?: error("spec.dir system property not set (see app/build.gradle.kts testOptions)")
        File(path)
    }

    private fun load(name: String): JsonObject =
        json.parseToJsonElement(File(specDir, name).readText()) as JsonObject

    val hmacVectors: JsonObject by lazy { load("hmac-vectors.json") }
    val wirePayloads: JsonObject by lazy { load("wire-payloads.json") }

    val token: String by lazy { hmacVectors.str("token") }

    fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    fun requestVectors(): List<JsonObject> =
        hmacVectors["requests"]!!.jsonArray.map { it as JsonObject }

    fun responseVectors(): List<JsonObject> =
        hmacVectors["responses"]!!.jsonArray.map { it as JsonObject }

    fun requestVector(name: String): JsonObject = requestVectors().first { it.str("name") == name }

    /** The raw JSON text of a named payload, as the server would send it. */
    fun payload(name: String): String =
        (wirePayloads[name] ?: error("no payload named $name in spec/wire-payloads.json")).toString()

    fun payloadObject(name: String): JsonObject = wirePayloads[name] as JsonObject

    fun hmacHex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).hex()
    }

    fun sha256Hex(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).hex()

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
