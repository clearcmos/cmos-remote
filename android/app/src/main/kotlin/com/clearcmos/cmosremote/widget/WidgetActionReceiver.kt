package com.clearcmos.cmosremote.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.clearcmos.cmosremote.data.RemoteAction
import com.clearcmos.cmosremote.data.SettingsManager
import com.clearcmos.cmosremote.network.ApiClient
import com.clearcmos.cmosremote.network.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Broadcast receiver for widget actions.
 * This provides an alternative way to handle widget button presses.
 */
class WidgetActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WidgetActionReceiver"
        const val ACTION_MUTE = "com.clearcmos.cmosremote.ACTION_MUTE"
        const val ACTION_BLUETOOTH = "com.clearcmos.cmosremote.ACTION_BLUETOOTH"
        const val ACTION_REFRESH = "com.clearcmos.cmosremote.ACTION_REFRESH"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received action: ${intent.action}")

        val action = when (intent.action) {
            ACTION_MUTE -> RemoteAction.MUTE
            ACTION_BLUETOOTH -> RemoteAction.BLUETOOTH
            ACTION_REFRESH -> RemoteAction.REFRESH
            else -> return
        }

        scope.launch {
            executeAction(context, action)
        }
    }

    private suspend fun executeAction(context: Context, action: RemoteAction) {
        try {
            val settingsManager = SettingsManager.getInstance(context)
            val networkMonitor = NetworkMonitor.getInstance(context)

            // Check network
            if (!networkMonitor.checkWifiConnected()) {
                Log.d(TAG, "Not connected to WiFi")
                updateAllWidgets(context)
                return
            }

            val serverUrl = settingsManager.getServerUrlSync()
            val client = ApiClient.getInstance(serverUrl, settingsManager.getAuthToken())

            when (action) {
                RemoteAction.MUTE -> {
                    val result = client.toggleMute()
                    result.fold(
                        onSuccess = { Log.d(TAG, "Mute toggled: ${it.message}") },
                        onFailure = { Log.e(TAG, "Failed to toggle mute", it) }
                    )
                }
                RemoteAction.BLUETOOTH -> {
                    val result = client.toggleBluetooth()
                    result.fold(
                        onSuccess = { Log.d(TAG, "Bluetooth toggled: ${it.message}") },
                        onFailure = { Log.e(TAG, "Failed to toggle Bluetooth", it) }
                    )
                }
                RemoteAction.REFRESH -> {
                    // Just update widgets
                }
                RemoteAction.SCREEN_OFF -> {
                    val result = client.screenOff()
                    result.fold(
                        onSuccess = { Log.d(TAG, "Screen off triggered: ${it.message}") },
                        onFailure = { Log.e(TAG, "Failed to trigger screen off", it) }
                    )
                }
            }

            // Update all widgets
            updateAllWidgets(context)
        } catch (e: Exception) {
            Log.e(TAG, "Action failed", e)
        }
    }

    private suspend fun updateAllWidgets(context: Context) {
        val manager = GlanceAppWidgetManager(context)
        val glanceIds = manager.getGlanceIds(RemoteWidget::class.java)

        for (glanceId in glanceIds) {
            refreshWidgetState(context, glanceId)
        }
    }
}
