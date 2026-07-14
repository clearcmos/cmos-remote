package com.clearcmos.cmosremote.network

import com.clearcmos.cmosremote.data.ActionResponse
import com.clearcmos.cmosremote.data.StatusResponse
import com.clearcmos.cmosremote.data.VolumeRequest
import com.clearcmos.cmosremote.data.VolumeResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * HTTP client for communicating with the CMOS Remote server.
 */
class ApiClient(private val baseUrl: String, token: String = "") {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .apply {
            // Sign requests / verify responses only when a token is configured.
            if (token.isNotEmpty()) addInterceptor(HmacInterceptor(token))
        }
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Check if the server is reachable.
     */
    suspend fun healthCheck(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/health")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                Result.success(response.isSuccessful)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get the current system status.
     */
    suspend fun getStatus(): Result<StatusResponse> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/status")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: throw Exception("Empty response body")
                    val status = json.decodeFromString<StatusResponse>(body)
                    Result.success(status)
                } else {
                    Result.failure(Exception("Server returned ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Toggle system mute.
     */
    suspend fun toggleMute(): Result<ActionResponse> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/mute")
                .post("".toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: throw Exception("Empty response body")
                    val action = json.decodeFromString<ActionResponse>(body)
                    Result.success(action)
                } else {
                    Result.failure(Exception("Server returned ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Toggle Bluetooth (and connect Q30).
     */
    suspend fun toggleBluetooth(): Result<ActionResponse> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/bluetooth")
                .post("".toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: throw Exception("Empty response body")
                    val action = json.decodeFromString<ActionResponse>(body)
                    Result.success(action)
                } else {
                    Result.failure(Exception("Server returned ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Trigger screen off (Meta+F10 equivalent).
     */
    suspend fun screenOff(): Result<ActionResponse> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/screen-off")
                .post("".toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: throw Exception("Empty response body")
                    val action = json.decodeFromString<ActionResponse>(body)
                    Result.success(action)
                } else {
                    Result.failure(Exception("Server returned ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Set system volume level (0-100).
     */
    suspend fun setVolume(level: Int): Result<VolumeResponse> = withContext(Dispatchers.IO) {
        try {
            val requestBody = json.encodeToString(VolumeRequest(level))
            val request = Request.Builder()
                .url("$baseUrl/volume")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: throw Exception("Empty response body")
                    val volumeResponse = json.decodeFromString<VolumeResponse>(body)
                    Result.success(volumeResponse)
                } else {
                    Result.failure(Exception("Server returned ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: ApiClient? = null
        private var currentBaseUrl: String? = null
        private var currentToken: String? = null

        fun getInstance(baseUrl: String, token: String = ""): ApiClient {
            // Recreate if the URL or token changed
            val existing = INSTANCE
            if (existing != null && currentBaseUrl == baseUrl && currentToken == token) {
                return existing
            }
            return synchronized(this) {
                if (INSTANCE == null || currentBaseUrl != baseUrl || currentToken != token) {
                    currentBaseUrl = baseUrl
                    currentToken = token
                    INSTANCE = ApiClient(baseUrl, token)
                }
                INSTANCE!!
            }
        }
    }
}
