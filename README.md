# CMOS Remote

Android remote control app for the cmos desktop (Arch Linux), with home screen widget support.

## Features

- **Mute Toggle** - Mute/unmute all system audio via PipeWire/WirePlumber
- **Volume Control** - Adjust system volume with slider (0-100%)
- **Bluetooth Toggle** - Turn Bluetooth on/off and auto-connect Soundcore Life Q30 headphones
- **Screen Off** - Turn off monitors and enable Do Not Disturb mode (auto-restores on wake)
- **Home Screen Widget** - Common actions from the home screen without opening the app
- **Authenticated** - HMAC challenge-response with a shared secret; the token never travels on the wire and the app verifies the server's identity before trusting it
- **LAN-Only** - Firewalled to the LAN; the authenticated health check gates access
- **Auto-Reconnect** - Automatically reconnects when WiFi state changes

## Architecture

```
+------------------+         HTTP/REST          +------------------+
|   Android App    | <------------------------> |  FastAPI Server  |
| (Kotlin/Compose) |        Port 8201           |     (Python)     |
+------------------+                            +--------+---------+
        |                                                |
        |                                                v
        |                                       +----------------------+
        |                                       |   System Commands    |
        |                                       |  - wpctl (audio)     |
        |                                       |  - bt-toggle (BT)    |
        |                                       |  - systemctl (screen)|
        +---------------------------------------+----------------------+
                        Home LAN (192.168.1.x)
```

The server runs as a systemd **user** service on the cmos host, so it shares the
logged-in session's PipeWire and D-Bus and can start other user services (screen
off) directly.

## Requirements

### Server (cmos host)
- Arch Linux with a logged-in desktop session (systemd user service)
- Python 3.11+ with `venv`
- PipeWire/WirePlumber for audio control
- BlueZ for Bluetooth control, plus the `bt-toggle` helper on PATH (deployed from `~/arch`)
- Port 8201 open on the LAN
- For auth (recommended): 1Password CLI (`op`) + the `SVC_API` service-account token, to provision the shared secret

### Android App
- Android 8.0+ (API 26)
- Connected to home WiFi
- To build from source: JDK 17 and the Android SDK (platform 35, build-tools
  35.0.0). See `docs/android-dev.md`.

## Quick Start

### 1. Install the Server

```bash
# Creates a venv, installs deps, deploys and enables the systemd user service
./server/install.sh

# Check status and logs
systemctl --user status cmos-remote
journalctl --user -u cmos-remote -f

# Test (only works before auth is enabled; once a token is set these return 401)
curl http://127.0.0.1:8201/health
```

Open port 8201 on the LAN (one-time). The rule lives in `~/arch/config/nftables/nftables.conf`; apply it with:

```bash
sudo install -m644 ~/arch/config/nftables/nftables.conf /etc/nftables.conf && sudo systemctl restart nftables
```

### 2. Set Up Authentication (recommended)

Create the shared secret in 1Password (`api` vault) with a personal login that can write to it (the `SVC_API` service account is read-only), then read it back to enter in the app:

```bash
op item create --category=password --title=CMOS_REMOTE --vault=api --generate-password='letters,digits,32'
op read op://api/CMOS_REMOTE/password
```

Then re-run `./server/install.sh` (it injects the token into `~/.config/cmos-remote/env` via the `SVC_API` service account and restarts the service). Enter the same value in the app's settings in step 4. If you skip this, the server runs without auth.

### 3. Install the App

Either install a published APK from [Releases](https://github.com/clearcmos/cmos-remote/releases)
(Android will ask you to allow installs from an unknown source), or build it
yourself:

```bash
cd android

# Requires JDK 17 + Android SDK (platform 35, build-tools 35.0.0) and a device
# connected via ADB. See docs/android-dev.md for the three ways to get the SDK.
./gradlew installDebug
```

### 4. Configure the App

1. Open the app
2. Tap the gear icon and paste the auth token (from step 2); optionally set the server IP/port
3. Ensure you're connected to your home WiFi
4. The app should show "Connected" status

### 5. Add the Widget

1. Long-press on home screen
2. Select "Widgets"
3. Find "CMOS Remote" and drag to home screen

## Configuration

### Default Settings

| Setting | Default Value |
|---------|---------------|
| Server IP | 192.168.1.2 |
| Server Port | 8201 |
| Auth token | (none - set to enable auth) |

Settings are editable in-app (tap the gear icon). Defaults live in `SettingsManager.kt`.

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/health` | GET | Health check (returns `{"status": "ok"}`) |
| `/status` | GET | Current mute/volume/bluetooth state |
| `/mute` | POST | Toggle audio mute |
| `/volume` | POST | Set volume level (body: `{"level": 0-100}`) |
| `/bluetooth` | POST | Toggle Bluetooth + connect Q30 |
| `/screen-off` | POST | Turn off screen + enable DND (Meta+F10 equivalent) |

## Project Structure

```
cmos-remote/
├── server/
│   ├── main.py              # FastAPI app, models, endpoints
│   ├── auth.py              # HMAC verification + response signing
│   ├── controls.py          # system commands (parsers split from runners)
│   ├── tests/               # pytest suite
│   ├── requirements.txt     # direct dependencies
│   ├── requirements.lock    # hash-locked full set (what install.sh installs)
│   ├── requirements-dev.txt # ruff, mypy, pytest, coverage
│   ├── pyproject.toml       # ruff, mypy, pytest, coverage config
│   ├── cmos-remote.service  # systemd user unit (canonical)
│   └── install.sh           # venv + service installer (idempotent)
├── android/
│   ├── app/src/main/
│   │   ├── kotlin/.../
│   │   │   ├── MainActivity.kt      # Main UI
│   │   │   ├── RemoteViewModel.kt   # State management
│   │   │   ├── data/
│   │   │   │   ├── Models.kt        # Data classes
│   │   │   │   └── SettingsManager.kt
│   │   │   ├── network/
│   │   │   │   ├── ApiClient.kt        # HTTP client
│   │   │   │   ├── HmacInterceptor.kt  # Signs requests, verifies responses
│   │   │   │   └── NetworkMonitor.kt
│   │   │   ├── ui/theme/Theme.kt
│   │   │   └── widget/
│   │   │       ├── RemoteWidget.kt  # Glance widget
│   │   │       └── WidgetActionReceiver.kt
│   │   ├── src/test/kotlin/...      # JVM unit tests
│   │   └── res/
│   ├── build.gradle.kts
│   └── flake.nix            # Optional nix dev shell (not required to build)
├── spec/                    # Cross-language wire-format contracts
├── .github/workflows/       # ci.yml (checks, tests, APK build), release.yml
├── Makefile                 # make check runs what CI runs
├── docs/                    # Development documentation
├── CLAUDE.md               # Claude Code instructions
└── README.md               # This file
```

## Deployment Model

This repo owns the server end to end (code, systemd user unit, installer). The
only machine-level dependency tracked in `~/arch` is the LAN firewall rule for
port 8201, because the nftables config is rebuilt from that file on every reload.
See `CLAUDE.md` for details.

## Adapting This for Your Own Machine

This is a personal tool built for one desktop (Arch + KDE Plasma + PipeWire), so some server-side pieces assume that environment. If you want to run it yourself:

**Works anywhere with PipeWire**
- `/health`, `/status`, `/mute`, `/volume` only need `wpctl` (PipeWire/WirePlumber).
- The Android app is host-agnostic: set the server IP, port, and auth token in the app's settings (gear icon). No rebuild is needed to point it at your own server.

**Needs your own equivalents**
- `/bluetooth` runs a `bt-toggle` command on the server's PATH. That script is not included here (it lives in the author's separate config repo) and encodes "turn Bluetooth on and connect a specific device." Supply your own `bt-toggle` on PATH, or drop the endpoint.
- `/screen-off` starts a `screen-off-toggle.service` systemd user unit, which is KDE Plasma-specific (DND + display off). Supply your own unit of that name, or drop the endpoint.
- A missing dependency does not crash the server; only that one endpoint returns an error.

**Authentication**
- The server enables auth when `CMOS_REMOTE_TOKEN` is set in its environment, and runs open when it is not. Any delivery method works (a plain `EnvironmentFile`, a shell export, etc.).
- `install.sh` provisions the token from 1Password via `op inject`, which is specific to the author's setup. If you do not use 1Password, set `CMOS_REMOTE_TOKEN` yourself and skip that step. Enter the same value in the app.

**Firewall**
- The server listens on `0.0.0.0:8201`; restrict it to your LAN with whatever firewall you use. The `~/arch/.../nftables` references in these docs are the author's mechanism, not a requirement.

## Troubleshooting

### App shows "Disconnected"

1. Check the phone is on your home WiFi
2. Verify the auth token in the app matches the server's
3. Verify server is running: `systemctl --user status cmos-remote`
4. Confirm the firewall is open (port 8201) and the service is reachable from the LAN

### Mute doesn't work

1. Check PipeWire is running: `systemctl --user status pipewire`
2. Test manually: `wpctl set-mute @DEFAULT_AUDIO_SINK@ toggle`

### Volume slider doesn't work

1. Test manually: `wpctl set-volume @DEFAULT_AUDIO_SINK@ 50%`
2. Check logs: `journalctl --user -u cmos-remote -f`

### Bluetooth toggle fails

1. Test bt-toggle script: `bt-toggle`
2. Check logs: `journalctl --user -u cmos-remote -f`

### Screen Off doesn't work

1. Test manually: `systemctl --user start screen-off-toggle.service`
2. Check service: `systemctl --user status screen-off-toggle`
3. Test keyboard shortcut: Press Meta+F10

## Development

`make check` from the repo root runs everything CI runs; `make help` lists the
individual targets. Under the hood:

```bash
# Server (from server/): pip install -r requirements-dev.txt first
ruff check . && ruff format --check . && mypy .
coverage run -m pytest && coverage report   # gate: 85%

# App (from android/): needs JDK 17 + Android SDK, see docs/android-dev.md
./gradlew testDebugUnitTest lintDebug assembleDebug
```

142 tests: 104 on the server (100% line coverage) and 38 in the app, all
runnable without a device. The HMAC scheme and the JSON payloads are each
implemented twice, once in Python and once in Kotlin, so `spec/hmac-vectors.json`
and `spec/wire-payloads.json` hold the canonical formats and both test suites
assert against them. A one-sided change fails CI rather than turning into an
unexplained "Disconnected" in the app.

`.github/workflows/ci.yml` runs the server checks on CPython 3.11 and 3.14 plus
the app's unit tests, Android Lint, and a debug APK build on every push and pull
request. Pushing a `v*` tag publishes a signed APK to a GitHub release.
`pre-commit install` is optional and covers only the fast checks.

See `docs/` for detailed development documentation:
- `docs/architecture.md` - System architecture details
- `docs/android-dev.md` - Android development setup, SDK options, releases
- `docs/adding-features.md` - How to add new remote actions
- `docs/troubleshooting.md` - Common issues and debug commands

## License

MIT - see [LICENSE](LICENSE). Published as-is with no warranty or support.
