package com.clearcmos.cmosremote.widget

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.clearcmos.cmosremote.data.ConnectionState
import com.clearcmos.cmosremote.data.RemoteAction
import com.clearcmos.cmosremote.data.SettingsManager
import com.clearcmos.cmosremote.network.ApiClient
import com.clearcmos.cmosremote.network.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Color constants for widget
private val WidgetBackground = androidx.compose.ui.graphics.Color(0xFF1E1E1E)
private val ButtonBackground = androidx.compose.ui.graphics.Color(0xFF2D2D2D)
private val ButtonDisabled = androidx.compose.ui.graphics.Color(0xFF3D3D3D)
private val TextDisabled = androidx.compose.ui.graphics.Color(0xFF888888)
private val TextMuted = androidx.compose.ui.graphics.Color(0xFFBBBBBB)
private val ColorConnected = androidx.compose.ui.graphics.Color(0xFF4CAF50)
private val ColorDisconnected = androidx.compose.ui.graphics.Color(0xFF9E9E9E)
private val ColorUnreachable = androidx.compose.ui.graphics.Color(0xFFF44336)
private val ColorMuteActive = androidx.compose.ui.graphics.Color(0xFFF44336)
private val ColorBluetoothActive = androidx.compose.ui.graphics.Color(0xFF2196F3)

/**
 * Glance widget for CMOS Remote control.
 */
class RemoteWidget : GlanceAppWidget() {

    companion object {
        private const val TAG = "RemoteWidget"

        // State keys
        val KEY_MUTED = booleanPreferencesKey("muted")
        val KEY_BLUETOOTH_ON = booleanPreferencesKey("bluetooth_on")
        val KEY_BLUETOOTH_DEVICE = stringPreferencesKey("bluetooth_device")
        val KEY_CONNECTION_STATE = stringPreferencesKey("connection_state")
        val KEY_ERROR = stringPreferencesKey("error")
    }

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Refresh state when widget is loaded
        refreshWidgetState(context, id)

        provideContent {
            WidgetContent()
        }
    }

    @Composable
    private fun WidgetContent() {
        val prefs = currentState<Preferences>()

        val connectionState = try {
            ConnectionState.valueOf(prefs[KEY_CONNECTION_STATE] ?: "DISCONNECTED")
        } catch (e: Exception) {
            ConnectionState.DISCONNECTED
        }
        val muted = prefs[KEY_MUTED] ?: false
        val bluetoothOn = prefs[KEY_BLUETOOTH_ON] ?: false
        val bluetoothDevice = prefs[KEY_BLUETOOTH_DEVICE]

        GlanceTheme {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(WidgetBackground)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Connection status indicator
                ConnectionIndicator(connectionState)

                Spacer(modifier = GlanceModifier.height(8.dp))

                // Control buttons
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Mute button
                    WidgetButton(
                        label = if (muted) "Unmute" else "Mute",
                        isActive = muted,
                        activeColor = ColorMuteActive,
                        enabled = connectionState == ConnectionState.CONNECTED,
                        action = RemoteAction.MUTE
                    )

                    Spacer(modifier = GlanceModifier.width(8.dp))

                    // Bluetooth button
                    WidgetButton(
                        label = if (bluetoothOn) "BT On" else "BT Off",
                        isActive = bluetoothOn,
                        activeColor = ColorBluetoothActive,
                        enabled = connectionState == ConnectionState.CONNECTED,
                        action = RemoteAction.BLUETOOTH
                    )
                }

                // Bluetooth device info
                if (bluetoothDevice != null && connectionState == ConnectionState.CONNECTED) {
                    Spacer(modifier = GlanceModifier.height(4.dp))
                    Text(
                        text = bluetoothDevice,
                        style = TextStyle(
                            color = ColorProvider(TextMuted),
                            fontSize = 10.sp
                        )
                    )
                }
            }
        }
    }

    @Composable
    private fun ConnectionIndicator(state: ConnectionState) {
        val (color, text) = when (state) {
            ConnectionState.CONNECTED -> ColorConnected to "Connected"
            ConnectionState.DISCONNECTED -> ColorDisconnected to "Disconnected"
            ConnectionState.UNREACHABLE -> ColorUnreachable to "Unreachable"
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = GlanceModifier
                .fillMaxWidth()
                .clickable(actionRunCallback<RefreshAction>())
        ) {
            Box(
                modifier = GlanceModifier
                    .size(8.dp)
                    .background(color)
                    .cornerRadius(4.dp)
            ) {}
            Spacer(modifier = GlanceModifier.width(6.dp))
            Text(
                text = text,
                style = TextStyle(
                    color = ColorProvider(color),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            )
        }
    }

    @Composable
    private fun WidgetButton(
        label: String,
        isActive: Boolean,
        activeColor: androidx.compose.ui.graphics.Color,
        enabled: Boolean,
        action: RemoteAction
    ) {
        val backgroundColor = when {
            !enabled -> ButtonDisabled
            isActive -> activeColor
            else -> ButtonBackground
        }
        val textColor = if (enabled) androidx.compose.ui.graphics.Color.White else TextDisabled

        val modifier = GlanceModifier
            .size(width = 70.dp, height = 50.dp)
            .background(backgroundColor)
            .cornerRadius(8.dp)

        val clickableModifier = if (enabled) {
            modifier.clickable(
                actionRunCallback<WidgetActionCallback>(
                    actionParametersOf(WidgetActionCallback.ACTION_KEY to action.name)
                )
            )
        } else {
            modifier
        }

        Box(
            modifier = clickableModifier,
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = TextStyle(
                    color = ColorProvider(textColor),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            )
        }
    }
}

/**
 * Receiver for the widget.
 */
class RemoteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RemoteWidget()
}

/**
 * Action callback for widget button presses.
 */
class WidgetActionCallback : ActionCallback {
    companion object {
        val ACTION_KEY = ActionParameters.Key<String>("action")
        private const val TAG = "WidgetActionCallback"
    }

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val actionName = parameters[ACTION_KEY] ?: return
        val action = try {
            RemoteAction.valueOf(actionName)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid action: $actionName")
            return
        }

        Log.d(TAG, "Widget action: $action")

        withContext(Dispatchers.IO) {
            try {
                val settingsManager = SettingsManager.getInstance(context)
                val networkMonitor = NetworkMonitor.getInstance(context)

                // Check network
                if (!networkMonitor.checkWifiConnected()) {
                    updateWidgetError(context, glanceId, "Not connected to WiFi")
                    return@withContext
                }

                // Get server URL and execute action
                val serverUrl = settingsManager.getServerUrlSync()
                val client = ApiClient.getInstance(serverUrl, settingsManager.getAuthToken())

                when (action) {
                    RemoteAction.MUTE -> {
                        val result = client.toggleMute()
                        result.fold(
                            onSuccess = { response ->
                                Log.d(TAG, "Mute toggled: ${response.message}")
                                refreshWidgetState(context, glanceId)
                            },
                            onFailure = { e ->
                                Log.e(TAG, "Failed to toggle mute", e)
                                updateWidgetError(context, glanceId, e.message)
                            }
                        )
                    }
                    RemoteAction.BLUETOOTH -> {
                        val result = client.toggleBluetooth()
                        result.fold(
                            onSuccess = { response ->
                                Log.d(TAG, "Bluetooth toggled: ${response.message}")
                                refreshWidgetState(context, glanceId)
                            },
                            onFailure = { e ->
                                Log.e(TAG, "Failed to toggle Bluetooth", e)
                                updateWidgetError(context, glanceId, e.message)
                            }
                        )
                    }
                    RemoteAction.REFRESH -> {
                        refreshWidgetState(context, glanceId)
                    }
                    RemoteAction.SCREEN_OFF -> {
                        val result = client.screenOff()
                        result.fold(
                            onSuccess = { response ->
                                Log.d(TAG, "Screen off triggered: ${response.message}")
                                // No state update needed for screen off
                            },
                            onFailure = { e ->
                                Log.e(TAG, "Failed to trigger screen off", e)
                                updateWidgetError(context, glanceId, e.message)
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Action failed", e)
                updateWidgetError(context, glanceId, e.message)
            }
        }
    }
}

/**
 * Action callback for refresh.
 */
class RefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        refreshWidgetState(context, glanceId)
    }
}

/**
 * Refresh widget state from server.
 */
suspend fun refreshWidgetState(context: Context, glanceId: GlanceId) {
    withContext(Dispatchers.IO) {
        try {
            val settingsManager = SettingsManager.getInstance(context)
            val networkMonitor = NetworkMonitor.getInstance(context)

            // Check network
            if (!networkMonitor.checkWifiConnected()) {
                updateAppWidgetState(context, glanceId) { prefs ->
                    prefs[RemoteWidget.KEY_CONNECTION_STATE] = ConnectionState.DISCONNECTED.name
                    prefs[RemoteWidget.KEY_ERROR] = ""
                }
                RemoteWidget().update(context, glanceId)
                return@withContext
            }

            val serverUrl = settingsManager.getServerUrlSync()
            val client = ApiClient.getInstance(serverUrl, settingsManager.getAuthToken())

            // Check health
            val healthResult = client.healthCheck()
            if (healthResult.isFailure) {
                updateAppWidgetState(context, glanceId) { prefs ->
                    prefs[RemoteWidget.KEY_CONNECTION_STATE] = ConnectionState.UNREACHABLE.name
                    prefs[RemoteWidget.KEY_ERROR] = "Server not reachable"
                }
                RemoteWidget().update(context, glanceId)
                return@withContext
            }

            // Get status
            val statusResult = client.getStatus()
            statusResult.fold(
                onSuccess = { status ->
                    updateAppWidgetState(context, glanceId) { prefs ->
                        prefs[RemoteWidget.KEY_CONNECTION_STATE] = ConnectionState.CONNECTED.name
                        prefs[RemoteWidget.KEY_MUTED] = status.muted
                        prefs[RemoteWidget.KEY_BLUETOOTH_ON] = status.bluetooth_on
                        if (status.bluetooth_connected != null) {
                            prefs[RemoteWidget.KEY_BLUETOOTH_DEVICE] = status.bluetooth_connected
                        } else {
                            prefs.remove(RemoteWidget.KEY_BLUETOOTH_DEVICE)
                        }
                        prefs[RemoteWidget.KEY_ERROR] = ""
                    }
                },
                onFailure = { e ->
                    updateAppWidgetState(context, glanceId) { prefs ->
                        prefs[RemoteWidget.KEY_CONNECTION_STATE] = ConnectionState.UNREACHABLE.name
                        prefs[RemoteWidget.KEY_ERROR] = e.message ?: "Unknown error"
                    }
                }
            )

            RemoteWidget().update(context, glanceId)
        } catch (e: Exception) {
            Log.e("RemoteWidget", "Failed to refresh", e)
            updateWidgetError(context, glanceId, e.message)
        }
    }
}

/**
 * Update widget with error state.
 */
suspend fun updateWidgetError(context: Context, glanceId: GlanceId, error: String?) {
    updateAppWidgetState(context, glanceId) { prefs ->
        prefs[RemoteWidget.KEY_ERROR] = error ?: "Unknown error"
    }
    RemoteWidget().update(context, glanceId)
}
