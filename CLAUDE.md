# CLAUDE.md

**Last Modified:** 2026-07-14

This file provides guidance to Claude Code when working with the CMOS Remote project.

## Project Overview

**CMOS Remote** is an Android app that provides remote control functionality for the cmos desktop host. It includes a home screen widget for quick access to common actions.

The cmos host runs **Arch Linux**. The server runs as a systemd **user** service (not a system service), which gives it native access to the logged-in session's PipeWire and D-Bus, and lets it start other user services directly.

## Architecture

### Server Component (`server/`)
- **FastAPI** Python server running on port 8201
- Runs as a systemd **user** service for your logged-in desktop session (audio/Bluetooth/screen access)
- Endpoints:
  - `GET /health` - Health check for LAN detection
  - `GET /status` - Get current mute/volume/Bluetooth status
  - `POST /mute` - Toggle system audio mute (via `wpctl`)
  - `POST /volume` - Set volume level 0-100% (via `wpctl`)
  - `POST /bluetooth` - Toggle Bluetooth + connect Q30 (via `bt-toggle`)
  - `POST /screen-off` - Turn off screens + enable DND (via `systemctl --user start screen-off-toggle.service`)
- Binary paths are resolved at runtime (`shutil.which` with fallbacks), so `main.py` is not tied to any distro layout.

### Android App (`android/`)
- **Language:** Kotlin
- **UI Framework:** Jetpack Compose + Material 3
- **Widget Framework:** Jetpack Glance 1.1.1
- **HTTP Client:** OkHttp 4.12
- **Target SDK:** 35 (Android 15)
- **Min SDK:** 26 (Android 8.0)

## Ownership and Deployment Model

This repo owns the `cmos-remote` server end to end: the application code, the systemd user unit (`server/cmos-remote.service`), and the installer (`server/install.sh`). The Python venv lives at `server/.venv/` (gitignored).

`~/arch` is the machine's source of truth for packages, services, and firewall, but it deliberately owns only **one** thing for this project: the LAN firewall rule that opens port 8201. That rule must live in `~/arch/config/nftables/nftables.conf` because the nftables config does `destroy table inet filter` and rebuilds from that file on every reload, so a rule added any other way is wiped. The systemd user unit is intentionally NOT tracked in `~/arch` (it points at this repo's checkout, which is not part of the fresh-install flow); a `system-audit` will see the enabled user unit as living outside `~/arch`, and that is expected.

## Key Files

### Server
- `server/main.py` - FastAPI server with all endpoints
- `server/requirements.txt` - Python dependencies
- `server/cmos-remote.service` - systemd user unit (canonical; deployed by `install.sh` with paths rewritten to the actual checkout location)
- `server/install.sh` - idempotent installer: creates venv, installs deps, deploys and enables the user unit

### Android App
- `android/app/src/main/kotlin/com/clearcmos/cmosremote/`
  - `MainActivity.kt` - Main Compose UI
  - `RemoteViewModel.kt` - State management and network coordination
  - `data/Models.kt` - Data classes and enums
  - `data/SettingsManager.kt` - DataStore preferences
  - `network/ApiClient.kt` - OkHttp HTTP client
  - `network/NetworkMonitor.kt` - WiFi/connectivity monitoring
  - `widget/RemoteWidget.kt` - Glance home screen widget
  - `widget/WidgetActionReceiver.kt` - Broadcast receiver for widget actions

## Build Commands

### Server
```bash
# First install / redeploy after code changes
./server/install.sh

# Service management (user scope)
systemctl --user status cmos-remote
systemctl --user restart cmos-remote
systemctl --user stop cmos-remote

# Logs
journalctl --user -u cmos-remote -f

# Development mode with auto-reload (from server/)
.venv/bin/uvicorn main:app --host 0.0.0.0 --port 8201 --reload
```

### Android App
```bash
cd android

# Enter nix development shell
nix develop

# Build debug APK
./gradlew assembleDebug

# Build and install to connected device
./gradlew installDebug

# Or use helper scripts
./build.sh
./install.sh
```

## Wireless ADB Setup

Samsung S25 IP Address: `192.168.1.13` (typical)

```bash
# Enable Wireless Debugging on phone
# Settings > Developer Options > Wireless Debugging

# Pair (one-time)
adb pair <IP>:<PAIRING_PORT> <CODE>

# Connect
adb connect <IP>:<DEBUG_PORT>

# Verify
adb devices
```

## Configuration

### Default Server Settings
- IP: `192.168.1.2` (cmos host)
- Port: `8201`

These can be changed in the app settings (gear icon, stored via DataStore).

### Authentication (HMAC challenge-response)
The server and app share a secret (`CMOS_REMOTE_TOKEN`). It is never sent on the wire:

- Each request carries `X-Auth-Ts` / `X-Auth-Nonce` / `X-Auth-Sig`, where the signature is `HMAC-SHA256(token, "ts\nnonce\nMETHOD\npath\nsha256(body)")`. The server verifies it, enforces a 60s freshness window, and caches nonces to block replay.
- Each response carries `X-Resp-Ts` / `X-Resp-Sig` = `HMAC-SHA256(token, "nonce\nresp_ts\nstatus\nsha256(body)")`. The app verifies this before trusting the response, so an impostor at the same IP:port (e.g. on a foreign WiFi) cannot fake being the server, and the app never discloses the secret to it.
- If `CMOS_REMOTE_TOKEN` is unset, the server runs open (no auth) and the app skips signing when its token field is blank. Both must be set (to the same value) to enable auth.

The scheme is implemented in `server/main.py` (`require_auth` + `sign_response`) and `android/.../network/HmacInterceptor.kt`; the two must stay byte-for-byte in agreement on the signed message format.

**Token provisioning:** the secret lives in 1Password (`op://api/CMOS_REMOTE/password`, `api` vault). `install.sh` runs `op inject` (via the `SVC_API` service account, which has read access) to write it to `~/.config/cmos-remote/env` (0600), loaded by the unit's `EnvironmentFile`. The `SVC_API` service account is read-only, so the item must be created once with a personal 1Password login that can write to the `api` vault:
```bash
op item create --category=password --title=CMOS_REMOTE --vault=api --generate-password='letters,digits,32'
op read op://api/CMOS_REMOTE/password   # paste this into the app's Auth token field
```
Then re-run `./server/install.sh` and enter the same value in the app settings.

### LAN Detection
The app uses a two-tier approach:
1. Check if connected to WiFi (via `ConnectivityManager`; no location or WiFi-state permission required)
2. Authenticated health check against the server endpoint at the configured IP:port

If either fails, the app shows "Disconnected"/"Unreachable" and disables controls. With auth enabled, the health check only succeeds against a server that holds the shared secret, so the app connects based on cryptographic identity, not network name. There is no SSID allowlist.

### Firewall
Port 8201 is opened for the LAN only (`192.168.1.0/24`) via a single rule in `~/arch/config/nftables/nftables.conf`. Apply changes to it with:
```bash
sudo install -m644 ~/arch/config/nftables/nftables.conf /etc/nftables.conf && sudo systemctl restart nftables
```

## Widget

The Glance widget provides:
- Connection status indicator (tap to refresh)
- Mute toggle button
- Bluetooth toggle button
- Connected device info

Note: Volume slider and Screen Off are only available in the main app (not widget).

Widget updates automatically when:
- Network state changes
- Action is performed
- User taps the status indicator

## Troubleshooting

### Server not reachable
1. Check service status: `systemctl --user status cmos-remote`
2. Check firewall: port 8201 should be open for the LAN (see Firewall above)
3. Test manually: `curl http://192.168.1.2:8201/health`

### Widget shows "Disconnected"
1. Ensure phone is on home WiFi
2. Tap status indicator to force refresh
3. Check server is running

### Bluetooth toggle fails
1. Ensure `bt-toggle` works: `bt-toggle`
2. Confirm it is on PATH for the user session: `command -v bt-toggle` (expected `~/.local/bin/bt-toggle`)
3. View logs: `journalctl --user -u cmos-remote -f`

### Audio mute fails
1. Test wpctl: `wpctl get-volume @DEFAULT_AUDIO_SINK@`
2. Check PipeWire is running: `systemctl --user status pipewire`

### Volume slider doesn't work
1. Test wpctl: `wpctl set-volume @DEFAULT_AUDIO_SINK@ 50%`
2. View logs: `journalctl --user -u cmos-remote -f`

### Screen off doesn't work
1. Test the user service: `systemctl --user start screen-off-toggle.service`
2. Test keyboard shortcut: Press Meta+F10
3. The `screen-off-toggle` script and service are deployed from `~/arch` (`config/shell/screen-off-toggle.sh`, `config/systemd/user/screen-off-toggle.service`)

## Development Notes

### Adding New Actions
1. Add endpoint to `server/main.py`
2. Add method to `network/ApiClient.kt`
3. Add action to `data/Models.kt` `RemoteAction` enum
4. Add button in `MainActivity.kt` and `widget/RemoteWidget.kt`

### Changing Server Port
1. Update the port in `server/cmos-remote.service` (ExecStart) and re-run `./server/install.sh`
2. Update the firewall rule in `~/arch/config/nftables/nftables.conf` and reload nftables
3. Update `DEFAULT_SERVER_PORT` in `data/SettingsManager.kt`

## Additional Documentation

The `docs/` folder contains detailed development documentation:

- `docs/architecture.md` - System architecture, data flow diagrams, security model
- `docs/android-dev.md` - Android development setup, ADB, Gradle commands
- `docs/adding-features.md` - Step-by-step guide for adding new remote actions
- `docs/troubleshooting.md` - Common issues, debug commands, log locations

Consult these docs for in-depth information beyond this quick reference.

## Related Files (on the Arch cmos host, deployed from `~/arch`)

- `~/arch/config/shell/bt-toggle.sh` - Bluetooth toggle script (symlinked to `~/.local/bin/bt-toggle`)
- `~/arch/config/shell/screen-off-toggle.sh` + `config/systemd/user/screen-off-toggle.service` - screen off + DND
- `~/arch/config/nftables/nftables.conf` - firewall (the port 8201 LAN rule)
