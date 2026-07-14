package com.clearcmos.cmosremote.data

import kotlinx.serialization.Serializable

/**
 * Response from GET /status endpoint.
 */
@Serializable
data class StatusResponse(
    val muted: Boolean,
    val volume: Int,
    val bluetooth_on: Boolean,
    val bluetooth_connected: String? = null
)

/**
 * Response from POST /volume endpoint.
 */
@Serializable
data class VolumeResponse(
    val success: Boolean,
    val level: Int,
    val message: String
)

/**
 * Request body for POST /volume endpoint.
 */
@Serializable
data class VolumeRequest(
    val level: Int
)

/**
 * Response from POST /mute or /bluetooth endpoints.
 */
@Serializable
data class ActionResponse(
    val success: Boolean,
    val message: String,
    val new_state: Boolean? = null
)

/**
 * Connection state for the app.
 */
enum class ConnectionState {
    CONNECTED,      // On LAN and server reachable
    DISCONNECTED,   // Not on LAN WiFi
    UNREACHABLE     // On LAN but server not responding
}

/**
 * Complete remote state including connection and system status.
 */
data class RemoteState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val muted: Boolean = false,
    val volume: Int = 50,
    val bluetoothOn: Boolean = false,
    val bluetoothDevice: String? = null,
    val lastUpdated: Long = 0L,
    val error: String? = null
)

/**
 * Available remote actions.
 */
enum class RemoteAction {
    MUTE,
    BLUETOOTH,
    SCREEN_OFF,
    REFRESH
}
