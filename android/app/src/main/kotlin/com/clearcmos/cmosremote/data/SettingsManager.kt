package com.clearcmos.cmosremote.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Manages app settings using DataStore.
 */
class SettingsManager(private val context: Context) {

    companion object {
        // Default server settings
        const val DEFAULT_SERVER_IP = "192.168.1.2"
        const val DEFAULT_SERVER_PORT = "8201"

        // Preference keys
        private val SERVER_IP = stringPreferencesKey("server_ip")
        private val SERVER_PORT = stringPreferencesKey("server_port")
        private val AUTH_TOKEN = stringPreferencesKey("auth_token")

        @Volatile
        private var INSTANCE: SettingsManager? = null

        fun getInstance(context: Context): SettingsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
     * Flow of the server base URL.
     */
    val serverUrl: Flow<String> = context.dataStore.data.map { preferences ->
        val ip = preferences[SERVER_IP] ?: DEFAULT_SERVER_IP
        val port = preferences[SERVER_PORT] ?: DEFAULT_SERVER_PORT
        "http://$ip:$port"
    }

    /**
     * Flow of the server IP address.
     */
    val serverIp: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[SERVER_IP] ?: DEFAULT_SERVER_IP
    }

    /**
     * Flow of the server port.
     */
    val serverPort: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[SERVER_PORT] ?: DEFAULT_SERVER_PORT
    }

    /**
     * Flow of the shared auth token (empty = no auth).
     */
    val authToken: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[AUTH_TOKEN] ?: ""
    }

    /**
     * Get server IP synchronously (for non-flow contexts).
     */
    fun getServerUrlSync(): String {
        return "http://$DEFAULT_SERVER_IP:$DEFAULT_SERVER_PORT"
    }

    /**
     * Update server IP address.
     */
    suspend fun setServerIp(ip: String) {
        context.dataStore.edit { preferences ->
            preferences[SERVER_IP] = ip
        }
    }

    /**
     * Update server port.
     */
    suspend fun setServerPort(port: String) {
        context.dataStore.edit { preferences ->
            preferences[SERVER_PORT] = port
        }
    }

    /**
     * Update the shared auth token.
     */
    suspend fun setAuthToken(token: String) {
        context.dataStore.edit { preferences ->
            preferences[AUTH_TOKEN] = token
        }
    }

    /**
     * Read the current auth token synchronously (for widget coroutine contexts).
     */
    suspend fun getAuthToken(): String = authToken.first()
}
