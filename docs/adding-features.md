# Adding New Features

This guide explains how to add new remote control actions to CMOS Remote.

## Overview

Adding a new action requires changes to:
1. **Server** - New endpoint
2. **Android Models** - New action enum
3. **Android ApiClient** - New API method
4. **Android UI** - New button in app
5. **Android Widget** - New button in widget (optional)

## Example: Adding a "Play/Pause" Action

### Step 1: Server Endpoint

Edit `server/main.py`:

```python
# Add command path
PLAYERCTL = "/run/current-system/sw/bin/playerctl"

# Add helper function
def toggle_play_pause() -> tuple[bool, bool]:
    """Toggle media play/pause. Returns (success, is_playing)."""
    try:
        # Get current status
        result = subprocess.run(
            [PLAYERCTL, "status"],
            capture_output=True,
            text=True,
            timeout=5
        )
        is_playing = result.stdout.strip() == "Playing"

        # Toggle
        subprocess.run(
            [PLAYERCTL, "play-pause"],
            check=True,
            timeout=5
        )

        return True, not is_playing
    except Exception as e:
        logger.error(f"Failed to toggle play/pause: {e}")
        return False, False

# Add endpoint
@app.post("/playpause", response_model=ActionResponse)
async def toggle_play_pause_endpoint():
    """Toggle media play/pause."""
    success, is_playing = toggle_play_pause()

    if success:
        return ActionResponse(
            success=True,
            message="Playing" if is_playing else "Paused",
            new_state=is_playing
        )
    else:
        raise HTTPException(
            status_code=500,
            detail="Failed to toggle play/pause"
        )
```

### Step 2: Update Status Response (Optional)

If you want to track the new state:

```python
class StatusResponse(BaseModel):
    muted: bool
    bluetooth_on: bool
    bluetooth_connected: Optional[str] = None
    is_playing: bool = False  # Add new field
```

### Step 3: Android Models

Edit `data/Models.kt`:

```kotlin
enum class RemoteAction {
    MUTE,
    BLUETOOTH,
    REFRESH,
    PLAY_PAUSE  // Add new action
}

data class RemoteState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val muted: Boolean = false,
    val bluetoothOn: Boolean = false,
    val bluetoothDevice: String? = null,
    val isPlaying: Boolean = false,  // Add new state
    val error: String? = null
)
```

### Step 4: Android ApiClient

Edit `network/ApiClient.kt`:

```kotlin
// Add response class if needed
data class PlayPauseResponse(
    val success: Boolean,
    val message: String,
    val new_state: Boolean?
)

// Add API method
suspend fun togglePlayPause(): Result<PlayPauseResponse> = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder()
            .url("$baseUrl/playpause")
            .post("".toRequestBody())
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""

        if (response.isSuccessful) {
            val result = json.decodeFromString<PlayPauseResponse>(body)
            Result.success(result)
        } else {
            Result.failure(Exception("Server error: ${response.code}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

### Step 5: Android ViewModel

Edit `RemoteViewModel.kt`:

```kotlin
fun executeAction(action: RemoteAction) {
    viewModelScope.launch {
        when (action) {
            RemoteAction.MUTE -> {
                // existing code
            }
            RemoteAction.BLUETOOTH -> {
                // existing code
            }
            RemoteAction.PLAY_PAUSE -> {
                apiClient.togglePlayPause().onSuccess { response ->
                    _state.update { it.copy(isPlaying = response.new_state ?: false) }
                }.onFailure { e ->
                    _state.update { it.copy(error = e.message) }
                }
            }
            RemoteAction.REFRESH -> refreshStatus()
        }
    }
}
```

### Step 6: Android UI

Edit `MainActivity.kt` in the `ControlGrid` composable:

```kotlin
@Composable
fun ControlGrid(
    state: RemoteState,
    onAction: (RemoteAction) -> Unit
) {
    Column(...) {
        Row(...) {
            // Existing Mute button
            ControlButton(...)

            // Existing Bluetooth button
            ControlButton(...)
        }

        // Add new row for Play/Pause
        Row(...) {
            ControlButton(
                modifier = Modifier.weight(1f),
                icon = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                label = if (state.isPlaying) "Pause" else "Play",
                isActive = state.isPlaying,
                activeColor = Color(0xFF4CAF50),
                onClick = { onAction(RemoteAction.PLAY_PAUSE) }
            )
        }
    }
}
```

### Step 7: Widget (Optional)

Edit `widget/RemoteWidget.kt`:

```kotlin
// Add action constant
private const val ACTION_PLAY_PAUSE = "com.clearcmos.cmosremote.ACTION_PLAY_PAUSE"

// In RemoteWidgetContent, add button:
Button(
    text = if (isPlaying) "⏸" else "▶",
    modifier = GlanceModifier
        .defaultWeight()
        .fillMaxHeight()
        .clickable(actionSendBroadcast(
            Intent(context, WidgetActionReceiver::class.java).apply {
                action = ACTION_PLAY_PAUSE
            }
        )),
    colors = ButtonDefaults.buttonColors(...)
)
```

Edit `widget/WidgetActionReceiver.kt`:

```kotlin
override fun onReceive(context: Context, intent: Intent) {
    when (intent.action) {
        ACTION_MUTE -> executeAction { apiClient.toggleMute() }
        ACTION_BLUETOOTH -> executeAction { apiClient.toggleBluetooth() }
        ACTION_PLAY_PAUSE -> executeAction { apiClient.togglePlayPause() }
        ACTION_REFRESH -> refreshWidget(context)
    }
}
```

Edit `AndroidManifest.xml` to add the new action:

```xml
<receiver android:name=".widget.WidgetActionReceiver" android:exported="false">
    <intent-filter>
        <action android:name="com.clearcmos.cmosremote.ACTION_MUTE" />
        <action android:name="com.clearcmos.cmosremote.ACTION_BLUETOOTH" />
        <action android:name="com.clearcmos.cmosremote.ACTION_PLAY_PAUSE" />
        <action android:name="com.clearcmos.cmosremote.ACTION_REFRESH" />
    </intent-filter>
</receiver>
```

### Step 8: Test

1. Restart server: `systemctl restart cmos-remote`
2. Test endpoint: `curl -X POST http://192.168.1.2:8201/playpause`
3. Rebuild app: `./gradlew installDebug`
4. Test in app and widget

## NixOS Dependencies

If your new feature needs additional system commands, ensure they're available:

1. Add package to system: Edit `/etc/nixos/modules/desktop/packages.nix`
2. Find full path: `which playerctl` → `/run/current-system/sw/bin/playerctl`
3. Use full path in server code

## Testing Endpoints

```bash
# Health check
curl http://192.168.1.2:8201/health

# Get status
curl http://192.168.1.2:8201/status

# Toggle mute
curl -X POST http://192.168.1.2:8201/mute

# Toggle bluetooth
curl -X POST http://192.168.1.2:8201/bluetooth

# Your new endpoint
curl -X POST http://192.168.1.2:8201/playpause
```

## Checklist

- [ ] Server endpoint added and tested with curl
- [ ] Server logs show no errors
- [ ] RemoteAction enum updated
- [ ] RemoteState updated (if tracking state)
- [ ] ApiClient method added
- [ ] ViewModel handles new action
- [ ] UI button added
- [ ] Widget button added (optional)
- [ ] AndroidManifest updated for widget action
- [ ] App builds without errors
- [ ] Feature works end-to-end

## Real Examples

### Volume Control (Slider)

Volume control differs from simple toggle buttons - it uses a slider with debouncing.

**Key differences from toggle pattern:**
- **Debouncing**: Slider generates many events; ViewModel debounces API calls (100ms)
- **Immediate feedback**: Local state updates instantly, server confirms later
- **Value vs Toggle**: POST body contains `{"level": 0-100}`, response includes actual level

**Files modified:**
- `server/main.py`: Added `/volume` endpoint with `VolumeRequest` model
- `data/Models.kt`: Added `volume: Int` to `RemoteState`, `VolumeRequest`/`VolumeResponse` data classes
- `network/ApiClient.kt`: Added `setVolume(level: Int)` method
- `RemoteViewModel.kt`: Added `setVolume()` with debounce via `delay()` in coroutine
- `MainActivity.kt`: Added `VolumeSlider` composable with Material3 Slider

### Screen Off (System Service)

Screen Off demonstrates integrating with NixOS systemd user services.

**Key differences from direct command pattern:**
- **No new_state**: Screen off is a one-shot action, doesn't toggle state
- **Systemd integration**: Triggers `screen-off-toggle.service` instead of running command directly
- **Complex behavior**: Service enables DND, turns off screens, spawns watcher to restore DND on wake

**Files modified:**
- `server/main.py`: Added `/screen-off` endpoint calling `systemctl --user start screen-off-toggle.service`
- `data/Models.kt`: Added `SCREEN_OFF` to `RemoteAction` enum
- `network/ApiClient.kt`: Added `screenOff()` method
- `RemoteViewModel.kt`: Added `SCREEN_OFF` case in `executeAction()`
- `MainActivity.kt`: Added `ScreenOffButton` composable (full-width indigo button)
- `widget/RemoteWidget.kt`: Added `SCREEN_OFF` case (exhaustive when)
- `widget/WidgetActionReceiver.kt`: Added `SCREEN_OFF` case (exhaustive when)

**NixOS side:**
- `/etc/nixos/modules/desktop/screen-off.nix`: Defines the systemd user services
