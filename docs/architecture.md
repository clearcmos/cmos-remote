# Architecture

**Last Modified:** 2026-07-14

## Overview

CMOS Remote uses a client-server architecture with HTTP REST API communication over the local network.

## Components

### 1. FastAPI Server (`server/main.py`)

Runs on the cmos desktop host (Arch Linux) as a systemd **user** service (required for audio/Bluetooth access).

**Why user-level service?**
- PipeWire runs per-user, requires same user context for `wpctl`
- Bluetooth D-Bus access requires user session
- Running as root would require complex permission delegation

**Endpoints:**

```
GET  /health     → {"status": "ok", "service": "cmos-remote"}
GET  /status     → {"muted": bool, "volume": int, "bluetooth_on": bool, "bluetooth_connected": str|null}
POST /mute       → {"success": bool, "message": str, "new_state": bool}
POST /volume     → {"success": bool, "message": str, "level": int}  (body: {"level": 0-100})
POST /bluetooth  → {"success": bool, "message": str, "new_state": bool}
POST /screen-off → {"success": bool, "message": str}  (triggers Meta+F10 equivalent)
```

**Command Execution:**
- Uses full paths for commands (`/run/current-system/sw/bin/wpctl`) because systemd services have minimal PATH
- Subprocess calls with timeouts to prevent hangs
- Bluetooth toggle has 30s timeout (connection takes time)
- Screen-off triggers systemd user service (`screen-off-toggle.service`)

### 2. Android App

#### UI Layer (Jetpack Compose)

```
MainActivity.kt
    └── RemoteScreen (Composable)
        ├── ConnectionStatusCard
        ├── ControlGrid (when connected)
        │   ├── MuteButton
        │   ├── BluetoothButton
        │   ├── ScreenOffButton
        │   └── VolumeSlider
        └── DisconnectedMessage (when not connected)
```

#### State Management (ViewModel)

```
RemoteViewModel
    ├── state: StateFlow<RemoteState>
    ├── NetworkMonitor (observes WiFi changes)
    ├── ApiClient (HTTP requests)
    └── SettingsManager (DataStore preferences)
```

**State Flow:**
1. App starts → ViewModel initializes
2. NetworkMonitor emits WiFi state changes
3. ViewModel builds the signed API client (server URL + token)
4. Polls `/status` endpoint (signed request; response signature verified)
5. UI updates based on RemoteState

#### Widget Layer (Jetpack Glance)

```
RemoteWidget (GlanceAppWidget)
    ├── provideGlance() → Composable content
    └── WidgetActionReceiver (BroadcastReceiver)
        └── Handles ACTION_MUTE, ACTION_BLUETOOTH, ACTION_REFRESH
```

**Widget Communication:**
- Glance widgets can't make network calls directly
- Widget buttons send broadcasts to WidgetActionReceiver
- Receiver uses CoroutineScope to call API
- After action, calls `RemoteWidget().update(context, id)` to refresh

### 3. Network Flow

```
┌──────────────────────────────────────────────────────────────────┐
│                        Android Device                            │
│  ┌─────────────┐    ┌──────────────┐    ┌───────────────────┐  │
│  │   Widget    │───►│ ActionReceiver│───►│    ApiClient      │  │
│  └─────────────┘    └──────────────┘    └─────────┬─────────┘  │
│                                                    │             │
│  ┌─────────────┐    ┌──────────────┐              │             │
│  │    App UI   │◄───│  ViewModel   │◄─────────────┘             │
│  └─────────────┘    └──────┬───────┘                            │
│                            │                                     │
│                     ┌──────▼───────┐                            │
│                     │NetworkMonitor│                            │
│                     └──────────────┘                            │
└────────────────────────────┼────────────────────────────────────┘
                             │ HTTP (port 8201)
                             ▼
┌──────────────────────────────────────────────────────────────────┐
│                      cmos Desktop (192.168.1.2)                  │
│  ┌─────────────────┐                                            │
│  │  FastAPI Server │                                            │
│  │  (port 8201)    │                                            │
│  └────────┬────────┘                                            │
│           │                                                      │
│     ┌─────┴─────┐                                               │
│     ▼           ▼                                               │
│  ┌──────┐  ┌──────────┐  ┌────────────────────┐                 │
│  │wpctl │  │bt-toggle │  │screen-off-toggle   │                 │
│  └──────┘  └──────────┘  └────────────────────┘                 │
│     │           │                 │                             │
│     ▼           ▼                 ▼                             │
│  PipeWire    BlueZ        KDE Plasma (DND+DPMS)                 │
└──────────────────────────────────────────────────────────────────┘
```

## Security Model

### Network Security

1. **HMAC challenge-response auth** - Server and app share a secret (`CMOS_REMOTE_TOKEN`). Requests are signed (HMAC-SHA256 over ts/nonce/method/path/body) and responses are signed over the request nonce, so the app also verifies the server's identity. The secret never travels on the wire; replay is bounded by a 60s window plus a server-side nonce cache. When the token is unset, the server runs open (no auth).
2. **LAN-Only** - Server binds to all interfaces but the nftables firewall restricts port 8201 to `192.168.1.0/24`

### Android Permissions

| Permission | Purpose |
|------------|---------|
| `INTERNET` | HTTP requests to server |
| `ACCESS_NETWORK_STATE` | Detect WiFi connectivity |

The app requests no location or WiFi-state permissions. Connection is decided by connectivity plus the authenticated health check, not by network name.

## Data Flow

### App Startup

```
1. MainActivity.onCreate()
2. → RemoteViewModel initialized
3. → NetworkMonitor starts observing connectivity
4. → If WiFi connected:
   4a. → Call /status endpoint (signed request)
   4b. → Verify response signature, then update state with mute/bluetooth status
5. → UI renders based on state
```

### Button Press (Mute)

```
1. User taps Mute button
2. → onAction(RemoteAction.MUTE) called
3. → ViewModel.executeAction(MUTE)
4. → ApiClient.toggleMute() - POST /mute
5. → Server runs: wpctl set-mute @DEFAULT_AUDIO_SINK@ toggle
6. → Server returns new state
7. → ViewModel updates state
8. → UI re-renders with new mute state
```

### Volume Slider Change

```
1. User drags volume slider
2. → onVolumeChange(level) called repeatedly
3. → ViewModel.setVolume(level)
4. → Local state updates immediately (responsive UI)
5. → Debounce timer (100ms) prevents API spam
6. → After debounce: ApiClient.setVolume() - POST /volume
7. → Server runs: wpctl set-volume @DEFAULT_AUDIO_SINK@ {level}%
8. → Server returns actual level
9. → ViewModel updates with server-confirmed value
```

### Screen Off Button

```
1. User taps Screen Off button
2. → onAction(RemoteAction.SCREEN_OFF) called
3. → ViewModel.executeAction(SCREEN_OFF)
4. → ApiClient.screenOff() - POST /screen-off
5. → Server runs: systemctl --user start screen-off-toggle.service
6. → Service enables DND (via plasmanotifyrc)
7. → Service turns off screens (via DPMS/powerdevil)
8. → Watcher service monitors for user wake
9. → On wake: DND automatically restored
```

### Widget Button Press

```
1. User taps widget button
2. → PendingIntent fires
3. → WidgetActionReceiver.onReceive()
4. → Launch coroutine with ApiClient call
5. → After response, update widget via RemoteWidget().update()
```

## Error Handling

### Network Errors

- OkHttp has 10s connect/read timeouts
- Errors caught and logged, state set to UNREACHABLE
- Widget shows disconnected state

### Server Errors

- Commands wrapped in try/except
- Errors return HTTP 500 with detail message
- App shows error message to user

### State Recovery

- NetworkMonitor observes connectivity changes
- When WiFi reconnects, automatic status refresh
- Widget has refresh button for manual recovery
