# Desk Remote

Android remote control app for the desktop host (Arch Linux), with home screen widget support.

## Features

Built for one desktop (Arch, KDE Plasma, PipeWire). Two of the actions depend on
helper scripts that are not in this repo; see
[Adapting This for Your Own Desktop](#adapting-this-for-your-own-desktop).

- **Mute Toggle** - Mute/unmute all system audio via PipeWire/WirePlumber
- **Volume Control** - Adjust system volume with slider (0-100%)
- **Bluetooth Toggle** - Turn Bluetooth on/off and auto-connect a specific pair of headphones (needs your own `bt-toggle` script)
- **Screen Off** - Turn off monitors and enable Do Not Disturb (needs your own `screen-off-toggle.service`; the author's is KDE Plasma-specific)
- **Home Screen Widget** - Common actions from the home screen without opening the app
- **Authenticated** - HMAC challenge-response with a shared secret; the token never travels on the wire and the app verifies the server's identity before trusting it
- **LAN-Only** - Firewalled to the LAN; the authenticated health check gates access
- **Auto-Reconnect** - Automatically reconnects when network state changes

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

The server runs as a systemd **user** service on the desktop host, so it shares the
logged-in session's PipeWire and D-Bus and can start other user services (screen
off) directly.

## Requirements

### Server
- Linux with systemd and a logged-in graphical session (the unit runs in user scope so it inherits that session's PipeWire and D-Bus). Developed on Arch + KDE Plasma; the server code is not Arch-specific, but two endpoints depend on helpers that are (see below).
- Python 3.11+ with `venv`
- PipeWire/WirePlumber for audio control
- BlueZ for Bluetooth control, plus the `bt-toggle` helper on PATH (deployed from `~/arch`)
- Port 8201 open on the LAN
- For auth (recommended): any way to set `DESKREMOTE_TOKEN` (pass it to `install.sh`, see below). The author's setup provisions it from 1Password, which is optional.

### Android App
- Android 8.0+ (API 26)
- On the same network as the server (WiFi or Ethernet; cellular is treated as off-LAN)
- To build from source: JDK 17 and the Android SDK (platform 35, build-tools
  35.0.0). See `docs/android-dev.md`.

## Quick Start

### 1. Install the Server

```bash
# Creates a venv, installs deps, deploys and enables the systemd user service
./server/install.sh

# Check status and logs
systemctl --user status deskremote
journalctl --user -u deskremote -f

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
op item create --category=password --title=DESKREMOTE --vault=api --generate-password='letters,digits,32'
op read op://api/DESKREMOTE/password
```

Then re-run `./server/install.sh` (it injects the token into `~/.config/deskremote/env` via the `SVC_API` service account and restarts the service). Enter the same value in the app's settings in step 4. If you skip this, the server runs without auth.

### 3. Install the App

Either install a published APK from [Releases](https://github.com/clearcmos/deskremote/releases)
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
3. Make sure the phone is on the same network as the server
4. The app should show "Connected" status

### 5. Add the Widget

1. Long-press on home screen
2. Select "Widgets"
3. Find "Desk Remote" and drag to home screen

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
deskremote/
├── server/
│   ├── main.py              # FastAPI app, models, endpoints
│   ├── auth.py              # HMAC verification + response signing
│   ├── controls.py          # system commands (parsers split from runners)
│   ├── tests/               # pytest suite
│   ├── requirements.txt     # direct dependencies
│   ├── requirements.lock    # hash-locked full set (what install.sh installs)
│   ├── requirements-dev.txt # ruff, mypy, pytest, coverage
│   ├── pyproject.toml       # ruff, mypy, pytest, coverage config
│   ├── deskremote.service  # systemd user unit (canonical)
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

## Adapting This for Your Own Desktop

This is a personal tool built for one machine: Arch, KDE Plasma, PipeWire. It is
not a general-purpose desktop remote and it will not fully work on an arbitrary
setup without changes. Concretely, four of the six endpoints are generic and two
need equivalents you write yourself.

| Endpoint | Works on |
|----------|----------|
| `/health`, `/status` | any Linux with the server running |
| `/mute`, `/volume` | any PipeWire/WirePlumber system |
| `/bluetooth` | needs a `bt-toggle` script you supply (not in this repo) |
| `/screen-off` | needs a `screen-off-toggle.service` you supply; the author's is KDE Plasma-specific |

**Hard requirements**
- Linux with systemd, because the server installs as a systemd user unit. No
  macOS, no Windows, no init system without user services.
- A logged-in graphical session, since the unit inherits that session's PipeWire
  and D-Bus. This is not a headless server tool.
- Python 3.11+.
- PipeWire/WirePlumber for audio. PulseAudio and bare ALSA are not supported;
  `wpctl` is the only backend.

Within those bounds nothing is hardcoded: commands are resolved from PATH at
runtime, there is no config file to edit, no hostname compiled into the app, and
no dependency on the author's config repo.

**Set your own values**
```bash
# Server: any random token works; the app just has to match it
DESKREMOTE_TOKEN="$(openssl rand -hex 32)" ./server/install.sh
```
In the app, tap the gear icon and set the server IP, port, and that token. No
rebuild needed; the APK is not tied to an address. Skip the token entirely and
both sides run open.

The installer also accepts the token from 1Password via `op inject`, which is the
author's setup and entirely optional. It never overwrites an existing token
unless you pass a new one.

**Supply your own equivalents for two endpoints**
- `/bluetooth` runs a `bt-toggle` command on the server's PATH. That script is
  not in this repo (it encodes "turn Bluetooth on and connect one specific pair
  of headphones"). Write your own with that name, or drop the endpoint.
- `/screen-off` starts a `screen-off-toggle.service` systemd user unit, which is
  KDE Plasma-specific (DND plus display off). Supply your own unit of that name,
  or drop the endpoint.

Missing helpers do not crash the server or break the other endpoints. The
affected endpoint answers `503` naming the command it could not find, and the
app shows that message rather than a generic failure. `/status` keeps working so
the app still connects.

**Firewall**
The server listens on `0.0.0.0:8201`; restrict it to your LAN with whatever
firewall you use. The `~/arch/.../nftables` commands in these docs are the
author's mechanism, not a requirement.

**What is author-specific**

Two categories, and the difference matters:

- *You must replace these or lose the feature*: `bt-toggle` and
  `screen-off-toggle.service`. Neither is in this repo. The screen-off one is
  KDE Plasma-specific in the author's setup (DND plus DPMS through powerdevil);
  on GNOME, Sway, or anything else you are writing your own.
- *Optional conveniences you can ignore*: 1Password token provisioning, the
  nftables rule location, and the nix dev shell in `android/`. The default
  server IP in the app's settings (`192.168.1.2`) is just a starting value you
  overwrite in the UI.

## Troubleshooting

### App shows "Disconnected"

1. Check the phone is on the same network as the server (cellular counts as off-LAN)
2. Verify the auth token in the app matches the server's
3. Verify server is running: `systemctl --user status deskremote`
4. Confirm the firewall is open (port 8201) and the service is reachable from the LAN

### Mute doesn't work

1. Check PipeWire is running: `systemctl --user status pipewire`
2. Test manually: `wpctl set-mute @DEFAULT_AUDIO_SINK@ toggle`

### Volume slider doesn't work

1. Test manually: `wpctl set-volume @DEFAULT_AUDIO_SINK@ 50%`
2. Check logs: `journalctl --user -u deskremote -f`

### Bluetooth toggle fails

1. Test bt-toggle script: `bt-toggle`
2. Check logs: `journalctl --user -u deskremote -f`

### Screen Off doesn't work

1. Test manually: `systemctl --user start screen-off-toggle.service`
2. Check service: `systemctl --user status screen-off-toggle`
3. Test keyboard shortcut: Press Meta+F10

## Development

```bash
make dev-install   # once: server venv + dev tooling
make check         # everything CI runs
make help          # list individual targets
```

Under the hood:

```bash
# Server (from server/, using the venv's tools)
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
