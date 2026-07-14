package com.clearcmos.cmosremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.*
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clearcmos.cmosremote.data.ConnectionState
import com.clearcmos.cmosremote.data.RemoteAction
import com.clearcmos.cmosremote.data.RemoteState
import com.clearcmos.cmosremote.ui.theme.CMOSRemoteTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            CMOSRemoteTheme {
                val viewModel: RemoteViewModel = viewModel(
                    factory = RemoteViewModel.Factory(applicationContext)
                )
                val state by viewModel.state.collectAsState()
                val settings by viewModel.settings.collectAsState()

                RemoteScreen(
                    state = state,
                    settings = settings,
                    onAction = { viewModel.executeAction(it) },
                    onVolumeChange = { viewModel.setVolume(it) },
                    onSaveSettings = { ip, port, token -> viewModel.saveSettings(ip, port, token) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(
    state: RemoteState,
    settings: AppSettings,
    onAction: (RemoteAction) -> Unit,
    onVolumeChange: (Int) -> Unit,
    onSaveSettings: (String, String, String) -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CMOS Remote") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = { onAction(RemoteAction.REFRESH) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Connection Status Card
            ConnectionStatusCard(state)

            Spacer(modifier = Modifier.height(24.dp))

            // Control Buttons
            if (state.connectionState == ConnectionState.CONNECTED) {
                ControlGrid(state = state, onAction = onAction, onVolumeChange = onVolumeChange)
            } else {
                DisconnectedMessage(state)
            }

            // Error message if any
            state.error?.let { error ->
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            settings = settings,
            onDismiss = { showSettings = false },
            onSave = { ip, port, token ->
                onSaveSettings(ip, port, token)
                showSettings = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    settings: AppSettings,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var ip by remember(settings) { mutableStateOf(settings.serverIp) }
    var port by remember(settings) { mutableStateOf(settings.serverPort) }
    var token by remember(settings) { mutableStateOf(settings.authToken) }
    var showToken by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text("Server IP") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Auth token") },
                    singleLine = true,
                    visualTransformation = if (showToken)
                        VisualTransformation.None
                    else
                        PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                imageVector = if (showToken)
                                    Icons.Default.VisibilityOff
                                else
                                    Icons.Default.Visibility,
                                contentDescription = if (showToken) "Hide token" else "Show token"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Leave the token blank to run without authentication.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(ip, port, token) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun ConnectionStatusCard(state: RemoteState) {
    val (color, text, icon) = when (state.connectionState) {
        ConnectionState.CONNECTED -> Triple(
            Color(0xFF4CAF50),
            "Connected",
            Icons.Default.CheckCircle
        )
        ConnectionState.DISCONNECTED -> Triple(
            Color(0xFF9E9E9E),
            "Disconnected",
            Icons.Default.WifiOff
        )
        ConnectionState.UNREACHABLE -> Triple(
            Color(0xFFF44336),
            "Server Unreachable",
            Icons.Default.ErrorOutline
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                if (state.connectionState == ConnectionState.CONNECTED && state.bluetoothDevice != null) {
                    Text(
                        text = "BT: ${state.bluetoothDevice}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
fun VolumeSlider(
    volume: Int,
    onVolumeChange: (Int) -> Unit,
    enabled: Boolean = true
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                alpha = if (enabled) 1f else 0.5f
            )
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when {
                            volume == 0 -> Icons.Default.VolumeOff
                            volume < 50 -> Icons.Default.VolumeDown
                            else -> Icons.Default.VolumeUp
                        },
                        contentDescription = "Volume",
                        tint = if (enabled)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Volume",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (enabled)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
                Text(
                    text = "$volume%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Slider(
                value = volume.toFloat(),
                onValueChange = { onVolumeChange(it.toInt()) },
                valueRange = 0f..100f,
                steps = 99,  // 1% increments
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    }
}

@Composable
fun ControlGrid(
    state: RemoteState,
    onAction: (RemoteAction) -> Unit,
    onVolumeChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Mute Button
            ControlButton(
                modifier = Modifier.weight(1f),
                icon = if (state.muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                label = if (state.muted) "Unmute" else "Mute",
                isActive = state.muted,
                activeColor = Color(0xFFF44336),
                onClick = { onAction(RemoteAction.MUTE) }
            )

            // Bluetooth Button
            ControlButton(
                modifier = Modifier.weight(1f),
                icon = if (state.bluetoothOn) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled,
                label = if (state.bluetoothOn) "BT On" else "BT Off",
                isActive = state.bluetoothOn,
                activeColor = Color(0xFF2196F3),
                onClick = { onAction(RemoteAction.BLUETOOTH) }
            )
        }

        // Screen Off Button (full width)
        ScreenOffButton(
            onClick = { onAction(RemoteAction.SCREEN_OFF) }
        )

        // Volume Slider
        VolumeSlider(
            volume = state.volume,
            onVolumeChange = onVolumeChange,
            enabled = !state.muted
        )

        // Status info
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "System Status",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                StatusRow("Audio", if (state.muted) "Muted" else "Active")
                StatusRow("Volume", "${state.volume}%")
                StatusRow("Bluetooth", if (state.bluetoothOn) "On" else "Off")
                if (state.bluetoothDevice != null) {
                    StatusRow("Device", state.bluetoothDevice)
                }
            }
        }
    }
}

@Composable
fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun ControlButton(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    val backgroundColor = if (isActive) activeColor else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (isActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = modifier
            .aspectRatio(1f),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun ScreenOffButton(
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF5C6BC0)),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Bedtime,
                contentDescription = "Screen Off",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Screen Off",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun DisconnectedMessage(state: RemoteState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.WifiOff,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = when (state.connectionState) {
                ConnectionState.DISCONNECTED -> "Not connected to WiFi"
                ConnectionState.UNREACHABLE -> "Cannot reach CMOS server"
                else -> "Connecting..."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Connect to your home WiFi to control CMOS",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}
