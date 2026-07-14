package com.clearcmos.cmosremote

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clearcmos.cmosremote.data.ConnectionState
import com.clearcmos.cmosremote.data.RemoteAction
import com.clearcmos.cmosremote.data.RemoteState
import com.clearcmos.cmosremote.data.SettingsManager
import com.clearcmos.cmosremote.network.ApiClient
import com.clearcmos.cmosremote.network.NetworkMonitor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay  // Used for volume debouncing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * User-editable settings surfaced to the settings dialog.
 */
data class AppSettings(
    val serverIp: String = SettingsManager.DEFAULT_SERVER_IP,
    val serverPort: String = SettingsManager.DEFAULT_SERVER_PORT,
    val authToken: String = ""
)

private data class ConnInputs(
    val wifiConnected: Boolean,
    val serverUrl: String,
    val authToken: String
)

/**
 * ViewModel managing the remote control state.
 */
class RemoteViewModel(
    private val settingsManager: SettingsManager,
    private val networkMonitor: NetworkMonitor
) : ViewModel() {

    companion object {
        private const val TAG = "RemoteViewModel"
        private const val VOLUME_DEBOUNCE_MS = 100L   // Debounce volume changes for smooth slider
    }

    private val _state = MutableStateFlow(RemoteState())
    val state: StateFlow<RemoteState> = _state.asStateFlow()

    /** Current persisted settings, for the settings dialog. */
    val settings: StateFlow<AppSettings> = combine(
        settingsManager.serverIp,
        settingsManager.serverPort,
        settingsManager.authToken
    ) { ip, port, token ->
        AppSettings(ip, port, token)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private var volumeJob: Job? = null
    private var currentApiClient: ApiClient? = null

    init {
        // Monitor WiFi and settings changes
        viewModelScope.launch {
            combine(
                networkMonitor.isWifiConnected,
                settingsManager.serverUrl,
                settingsManager.authToken
            ) { wifiConnected, serverUrl, authToken ->
                ConnInputs(wifiConnected, serverUrl, authToken)
            }.collectLatest { (wifiConnected, serverUrl, authToken) ->
                Log.d(TAG, "Network state changed: wifi=$wifiConnected, url=$serverUrl")

                if (!wifiConnected) {
                    _state.value = _state.value.copy(
                        connectionState = ConnectionState.DISCONNECTED,
                        error = null
                    )
                    return@collectLatest
                }

                // Update API client and refresh status once
                currentApiClient = ApiClient.getInstance(serverUrl, authToken)
                refreshStatus()
            }
        }
    }

    /**
     * Persist settings from the settings dialog. The connection flow reacts
     * automatically to the DataStore changes.
     */
    fun saveSettings(serverIp: String, serverPort: String, authToken: String) {
        viewModelScope.launch {
            settingsManager.setServerIp(serverIp.trim())
            settingsManager.setServerPort(serverPort.trim())
            settingsManager.setAuthToken(authToken.trim())
        }
    }

    /**
     * Refresh the current status from the server.
     */
    suspend fun refreshStatus() {
        val client = currentApiClient ?: return

        // First check if server is reachable
        val healthResult = client.healthCheck()
        if (healthResult.isFailure) {
            _state.value = _state.value.copy(
                connectionState = ConnectionState.UNREACHABLE,
                error = "Server not reachable"
            )
            return
        }

        // Get full status
        val statusResult = client.getStatus()
        statusResult.fold(
            onSuccess = { status ->
                _state.value = _state.value.copy(
                    connectionState = ConnectionState.CONNECTED,
                    muted = status.muted,
                    volume = status.volume,
                    bluetoothOn = status.bluetooth_on,
                    bluetoothDevice = status.bluetooth_connected,
                    lastUpdated = System.currentTimeMillis(),
                    error = null
                )
            },
            onFailure = { e ->
                Log.e(TAG, "Failed to get status", e)
                _state.value = _state.value.copy(
                    connectionState = ConnectionState.UNREACHABLE,
                    error = e.message
                )
            }
        )
    }

    /**
     * Execute a remote action.
     */
    fun executeAction(action: RemoteAction) {
        val client = currentApiClient ?: return

        viewModelScope.launch {
            when (action) {
                RemoteAction.MUTE -> {
                    val result = client.toggleMute()
                    result.fold(
                        onSuccess = { response ->
                            Log.d(TAG, "Mute toggled: ${response.message}")
                            _state.value = _state.value.copy(
                                muted = response.new_state ?: !_state.value.muted,
                                error = null
                            )
                        },
                        onFailure = { e ->
                            Log.e(TAG, "Failed to toggle mute", e)
                            _state.value = _state.value.copy(error = e.message)
                        }
                    )
                }

                RemoteAction.BLUETOOTH -> {
                    val result = client.toggleBluetooth()
                    result.fold(
                        onSuccess = { response ->
                            Log.d(TAG, "Bluetooth toggled: ${response.message}")
                            // Refresh full status after BT toggle (may have device info)
                            refreshStatus()
                        },
                        onFailure = { e ->
                            Log.e(TAG, "Failed to toggle Bluetooth", e)
                            _state.value = _state.value.copy(error = e.message)
                        }
                    )
                }

                RemoteAction.SCREEN_OFF -> {
                    val result = client.screenOff()
                    result.fold(
                        onSuccess = { response ->
                            Log.d(TAG, "Screen off triggered: ${response.message}")
                            _state.value = _state.value.copy(error = null)
                        },
                        onFailure = { e ->
                            Log.e(TAG, "Failed to trigger screen off", e)
                            _state.value = _state.value.copy(error = e.message)
                        }
                    )
                }

                RemoteAction.REFRESH -> {
                    refreshStatus()
                }
            }
        }
    }

    /**
     * Set volume level with debouncing for smooth slider experience.
     * Updates local state immediately, debounces server calls.
     */
    fun setVolume(level: Int) {
        // Clamp to valid range
        val clampedLevel = level.coerceIn(0, 100)

        // Update local state immediately for responsive UI
        _state.value = _state.value.copy(volume = clampedLevel)

        // Cancel any pending volume update
        volumeJob?.cancel()

        // Debounce the server call
        volumeJob = viewModelScope.launch {
            delay(VOLUME_DEBOUNCE_MS)

            val client = currentApiClient ?: return@launch
            val result = client.setVolume(clampedLevel)
            result.fold(
                onSuccess = { response ->
                    Log.d(TAG, "Volume set: ${response.message}")
                    // Update with actual server value
                    _state.value = _state.value.copy(
                        volume = response.level,
                        error = null
                    )
                },
                onFailure = { e ->
                    Log.e(TAG, "Failed to set volume", e)
                    _state.value = _state.value.copy(error = e.message)
                }
            )
        }
    }

    /**
     * Force an immediate refresh.
     */
    fun forceRefresh() {
        viewModelScope.launch {
            refreshStatus()
        }
    }

    override fun onCleared() {
        super.onCleared()
        volumeJob?.cancel()
    }

    /**
     * Factory for creating RemoteViewModel with dependencies.
     */
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return RemoteViewModel(
                SettingsManager.getInstance(context),
                NetworkMonitor.getInstance(context)
            ) as T
        }
    }
}
