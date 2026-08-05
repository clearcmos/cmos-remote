# Architecture

## Overview

Desk Remote uses a client-server architecture with HTTP REST API communication over the local network.

## Components

### 1. FastAPI Server (`server/`)

Runs on the desktop host (developed on Arch Linux) as a systemd **user** service (required for audio/Bluetooth access).

Three modules:

| Module | Responsibility |
|--------|----------------|
| `main.py` | FastAPI app, request/response models, endpoints |
| `auth.py` | HMAC request verification and response signing |
| `controls.py` | system command wrappers, split into pure parsers and runners |

**Why user-level service?**
- PipeWire runs per-user, requires same user context for `wpctl`
- Bluetooth D-Bus access requires user session
- Running as root would require complex permission delegation

**Endpoints:**

```
GET  /health     → {"status": "ok", "service": "deskremote"}
GET  /status     → {"muted": bool, "volume": int, "bluetooth_on": bool, "bluetooth_connected": str|null}
POST /mute       → {"success": bool, "message": str, "new_state": bool}
POST /volume     → {"success": bool, "message": str, "level": int}  (body: {"level": 0-100})
POST /bluetooth  → {"success": bool, "message": str, "new_state": bool}
POST /screen-off → {"success": bool, "message": str}  (triggers Meta+F10 equivalent)
```

**Command Execution** (`controls.py`):
- Commands are resolved to absolute paths at import (`controls.resolve()`): PATH first, then known fallbacks, then the bare name. The systemd unit sets a minimal PATH, and resolving at runtime keeps the server portable across distros instead of hardcoding one layout.
- Every command goes through a single `run()` choke point, so tests can replace one function instead of patching `subprocess`.
- Output parsing is separated from execution (`parse_volume`, `parse_muted`, `parse_powered`, `parse_connected_device`). Parsers are what break when a tool changes its output, and they are tested against captured real output.
- Subprocess calls carry timeouts to prevent hangs; the Bluetooth toggle gets 30s because connecting is not instant.
- Failures degrade rather than raise: a broken command reports 0 / False / None and logs. That keeps one dead endpoint from taking down the rest, and is why the parsers are covered by tests.
- Screen-off triggers a systemd user service (`screen-off-toggle.service`).

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
    ├── NetworkMonitor (observes WiFi/Ethernet changes)
    ├── ApiClient (HTTP requests)
    └── SettingsManager (DataStore preferences)
```

**State Flow:**
1. App starts → ViewModel initializes
2. NetworkMonitor emits network state changes (WiFi or Ethernet)
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
│                      Desktop host (192.168.1.2)                    │
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

1. **HMAC challenge-response auth** - Server and app share a secret (`DESKREMOTE_TOKEN`). Requests are signed (HMAC-SHA256 over ts/nonce/method/path/body) and responses are signed over the request nonce, so the app also verifies the server's identity. The secret never travels on the wire; replay is bounded by a 60s window plus a server-side nonce cache. When the token is unset, the server runs open (no auth).
2. **LAN-Only** - Server binds to all interfaces but the nftables firewall restricts port 8201 to `192.168.1.0/24`
3. **Plain HTTP, deliberately** - There is no TLS: the server is reached by LAN IP, which no certificate authority will issue for. The app's network security config therefore permits cleartext for all destinations, because the destination is whatever address the user types into settings. Authenticity comes from the HMAC layer in both directions instead; confidentiality is not provided, and the payloads are mute and volume state. An earlier version of that config tried to allow cleartext per CIDR range, which Android does not support: `<domain>` matches exact hosts only, so the app could reach exactly one hardcoded address.

### Android Permissions

| Permission | Purpose |
|------------|---------|
| `INTERNET` | HTTP requests to server |
| `ACCESS_NETWORK_STATE` | Detect local-network connectivity |

The app requests no location or WiFi-state permissions. Connection is decided by connectivity plus the authenticated health check, not by network name.

### The wire format is a shared contract

The HMAC scheme and the response payloads are implemented twice, once in Python
and once in Kotlin, with nothing at build time connecting them. Two files under
`spec/` are the contract, and both test suites assert against them:

| File | Pins |
|------|------|
| `spec/hmac-vectors.json` | the exact strings that get signed, and their signatures for a fixed token |
| `spec/wire-payloads.json` | the JSON field names and null handling of every response |

A change to either format that is not mirrored on the other side fails CI,
rather than surfacing later as an unexplained "Disconnected" in the app.

## Data Flow

### App Startup

```
1. MainActivity.onCreate()
2. → RemoteViewModel initialized
3. → NetworkMonitor starts observing connectivity
4. → If on a local network (WiFi or Ethernet):
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
