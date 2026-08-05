# CLAUDE.md

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
- `server/main.py` - FastAPI app, models, endpoints
- `server/auth.py` - HMAC verification and response signing (`Authenticator`)
- `server/controls.py` - system command wrappers, parsers split from runners
- `server/tests/` - pytest suite, one file per module plus the contract tests
- `server/requirements.txt` - direct dependencies (human-edited)
- `server/requirements.lock` - hash-locked full set; what install.sh installs
- `server/requirements-dev.txt` - ruff, mypy, pytest, coverage, httpx
- `server/pyproject.toml` - ruff, mypy, pytest, coverage configuration (tool config only; the server is not a package)
- `server/cmos-remote.service` - systemd user unit (canonical; deployed by `install.sh` with paths rewritten to the actual checkout location)
- `server/install.sh` - idempotent installer: creates venv, installs deps, deploys and enables the user unit

### Contracts
- `spec/hmac-vectors.json` - the signed-message format, asserted by both test suites
- `spec/wire-payloads.json` - the response payload shapes, asserted by both test suites

### CI and release
- `Makefile` - `make check` runs what CI runs; `make help` lists targets
- `.github/workflows/ci.yml` - server lint/format/typecheck/tests on CPython 3.11 and 3.14, Android unit tests, Android Lint, debug APK build, `ci-ok` aggregate gate
- `.github/workflows/release.yml` - on `v*` tags: signed APK attached to a GitHub release
- `.github/dependabot.yml` - github-actions weekly, gradle and pip monthly

### Android App
- `android/app/src/main/kotlin/com/clearcmos/cmosremote/`
  - `MainActivity.kt` - Main Compose UI
  - `RemoteViewModel.kt` - State management and network coordination
  - `data/Models.kt` - Data classes and enums
  - `data/SettingsManager.kt` - DataStore preferences
  - `network/ApiClient.kt` - OkHttp HTTP client
  - `network/HmacInterceptor.kt` - signs requests, verifies response signatures
  - `network/NetworkMonitor.kt` - connectivity monitoring (WiFi or Ethernet)
  - `ui/theme/Theme.kt` - Material 3 theme
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

# Checks (from server/; configured in pyproject.toml, installed from
# requirements-dev.txt, and run by .github/workflows/ci.yml)
ruff check .
ruff format --check .
mypy .
coverage run -m pytest && coverage report   # gate: fail_under = 85 in pyproject
```

From the repo root: `make dev-install` once (adds the dev tooling to
`server/.venv`), then `make check` runs everything CI runs; `make help` lists the
targets. See "Tests" below for what is covered.

`pre-commit install` is optional and wires only the fast checks (ruff plus
whitespace and YAML/JSON hygiene). CI stays the authority.

### Android App
```bash
cd android

# Unit tests (JVM only, no device needed) and Android Lint
./gradlew testDebugUnitTest
./gradlew lintDebug

# Build debug APK (needs JDK 17 + Android SDK platform 35 / build-tools 35.0.0
# on PATH or via ANDROID_HOME; see docs/android-dev.md)
./gradlew assembleDebug

# Build and install to connected device
./gradlew installDebug

# On this Arch host, that toolchain comes from the optional nix dev shell,
# which is the only JDK and Android SDK installed here:
nix develop --command ./gradlew installDebug

# Or use helper scripts (they assume the toolchain is already on PATH)
./build.sh
./install.sh
```

The nix flake is a local convenience, not a requirement: CI and the build docs
use the plain SDK path (`setup-java` + `setup-android`) so the path outside
contributors follow is the one that gets exercised.

### Release

Tag `v*` to publish a signed APK via `.github/workflows/release.yml`. Signing
material comes from four repository secrets, and locally from a gitignored
`android/keystore.properties`. Full procedure in `docs/android-dev.md`
("Release Build"). Without a keystore, `assembleRelease` still builds but the
output is `app-release-unsigned.apk`, which the release workflow rejects.

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

The scheme is implemented in `server/auth.py` (`Authenticator.verify` + `sign_response`, wired into the app by `main.py`) and `android/.../network/HmacInterceptor.kt`. The two must stay byte-for-byte in agreement on the signed message format, which is what `spec/hmac-vectors.json` and the parity tests on both sides enforce.

**Token provisioning:** the secret lives in 1Password (`op://api/CMOS_REMOTE/password`, `api` vault). `install.sh` runs `op inject` (via the `SVC_API` service account, which has read access) to write it to `~/.config/cmos-remote/env` (0600), loaded by the unit's `EnvironmentFile`. The `SVC_API` service account is read-only, so the item must be created once with a personal 1Password login that can write to the `api` vault:
```bash
op item create --category=password --title=CMOS_REMOTE --vault=api --generate-password='letters,digits,32'
op read op://api/CMOS_REMOTE/password   # paste this into the app's Auth token field
```
Then re-run `./server/install.sh` and enter the same value in the app settings.

### LAN Detection
The app uses a two-tier approach:
1. Check for a local-network-capable transport, WiFi or Ethernet (via `ConnectivityManager`; no location or WiFi-state permission required). Ethernet counts because docked tablets and emulators report it, and gating on WiFi alone left those permanently "Disconnected".
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
1. Add the command wrapper to `server/controls.py` (parser separate from runner)
2. Add the endpoint to `server/main.py`
3. Add tests: parser cases in `tests/test_controls.py`, endpoint in `tests/test_main.py`
4. If the response has a new shape, add it to `spec/wire-payloads.json` and assert
   it in both `tests/test_wire_payloads.py` and `ModelsTest.kt`
5. Add method to `network/ApiClient.kt` and a case to `ApiClientTest.kt`
6. Add action to `data/Models.kt` `RemoteAction` enum
7. Add button in `MainActivity.kt` and `widget/RemoteWidget.kt`

### Changing Server Port
1. Update the port in `server/cmos-remote.service` (ExecStart) and re-run `./server/install.sh`
2. Update the firewall rule in `~/arch/config/nftables/nftables.conf` and reload nftables
3. Update `DEFAULT_SERVER_PORT` in `data/SettingsManager.kt`

### Tests

142 tests: 104 on the server (pytest, 100% line coverage, gate at 85), 38 in the
app (JVM unit tests, no device or emulator needed).

| Suite | Covers |
|-------|--------|
| `server/tests/test_auth.py` | HMAC vectors, freshness window, replay, bad signature, open mode |
| `server/tests/test_controls.py` | output parsers against captured real output, every degraded path, the `run()` boundary |
| `server/tests/test_main.py` | endpoints through a TestClient, auth dependency, response signing, 500 mapping |
| `server/tests/test_wire_payloads.py` | response models against `spec/wire-payloads.json` |
| `server/tests/test_supply_chain.py` | every direct requirement is in the hash-locked lock file |
| `HmacInterceptorTest.kt` | the same HMAC vectors, plus response verification and impostor rejection |
| `ApiClientTest.kt` | requests, decoding, failure reporting, auth wiring, against MockWebServer |
| `ModelsTest.kt` | the same wire payloads decoded into the app's data classes |

**The two contract files under `spec/` are the point.** The HMAC scheme and the
response payloads exist twice, in Python and Kotlin, with nothing at build time
tying them together. Both suites assert against `spec/hmac-vectors.json` and
`spec/wire-payloads.json`, so a one-sided change fails CI instead of showing up
as an unexplained "Disconnected". Verified by mutation: flipping
`method.upper()` to `method.lower()` on either side fails 3 tests there.

**Documented exemptions** (Android-framework-bound, would need Robolectric or an
instrumented test run on a device, and none of them hold logic that fails
silently):

| Module | Why exempt |
|--------|-----------|
| `MainActivity.kt`, `ui/theme/Theme.kt` | Compose UI |
| `widget/RemoteWidget.kt`, `widget/WidgetActionReceiver.kt` | Glance widget and BroadcastReceiver |
| `RemoteViewModel.kt` | ViewModel plus `android.util.Log` |
| `data/SettingsManager.kt` | DataStore, needs a Context |
| `network/NetworkMonitor.kt` | ConnectivityManager callbacks |

## Decision Log

Dated, with the reason. Add to this rather than rewriting it.

- **2026-08-04 - server split into `main.py` / `auth.py` / `controls.py`.** The
  original single module bound `AUTH_TOKEN` at import time, so testing auth
  meant reloading the module with a patched environment. `Authenticator` takes
  its token and clock as arguments instead, which is what made the freshness and
  replay tests possible.
- **2026-08-04 - the app-level dependency is `Depends(authenticate)`, a wrapper,
  not `Depends(authenticator)`.** FastAPI captures the dependency object when the
  app is constructed, so passing the instance directly made the active
  Authenticator unswappable in tests.
- **2026-08-04 - `spec/*.json` as cross-language contracts.** Chosen over
  generating the Kotlin models from the OpenAPI schema: two hand-written
  implementations plus a shared assertion is far less machinery, and the failure
  it prevents (silent field or format drift) is the only one that mattered.
- **2026-08-04 - CI builds Android with `setup-java` plus `setup-android`, not
  the nix flake.** The flake is the local toolchain on the Arch host, but CI
  should exercise the path the build docs give outside contributors.
- **2026-08-04 - R8 stays off for release builds.** Compose and Glance need
  keep rules this project has never tested on a device; shipping a shrunk APK
  untested is a worse trade than a larger one.
- **2026-08-04 - deps installed from a hash-locked `requirements.lock` with
  `--require-hashes`.** The server holds a shared secret and runs inside the
  desktop session; a swapped wheel should fail the install, not land on the host.
- **2026-08-04 - no CHANGELOG.md.** Git history plus tagged GitHub releases
  serve that purpose; the release workflow generates notes from commits.
- **2026-08-05 - migrated off `android { kotlinOptions { jvmTarget } }` to
  `kotlin { compilerOptions { jvmTarget } }`.** The old DSL is deprecated in
  Kotlin 2.1 and a hard error from 2.4; a dependabot PR bumping the Kotlin
  plugin surfaced it before it could bite. Bytecode target verified unchanged
  (class file major version 61).
- **2026-08-05 - dependabot updates are grouped.** Kotlin plugin versions have
  to move together (language, compose, serialization) or the build fails on a
  mismatch, and AndroidX bumps often carry a minimum AGP, so a per-dependency PR
  can never go green on its own.

- **2026-08-05 - the app's network security config permits cleartext globally
  (`base-config`), replacing per-CIDR `<domain>` entries.** Android's `<domain>`
  matches exact hosts or IP literals only, with no range support, and
  `usesCleartextTraffic` is ignored whenever a config is present. The old file
  listed `192.168.1.0/24` and friends, so cleartext was permitted to exactly one
  hardcoded address: the app worked only against 192.168.1.2 and silently failed
  for anyone else. Verified in the built APK with `aapt2 dump xmltree`.
- **2026-08-05 - LAN detection accepts WiFi or Ethernet, not WiFi alone.**
  Docked tablets and emulators report `TRANSPORT_ETHERNET` and were permanently
  "Disconnected". Cellular is still excluded. The authenticated health check,
  not the transport, remains the real gate.
- **2026-08-05 - endpoints answer 503 naming a missing helper, and ApiClient
  surfaces FastAPI's `detail`.** "bt-toggle not found on PATH" is a five-second
  fix; "Server returned 503" is a puzzle. `/status` still degrades rather than
  failing, so the app can connect to a host with no PipeWire.
- **2026-08-05 - the shipped systemd unit uses an `@SERVER_DIR@` placeholder.**
  It previously contained the author's absolute checkout path, which install.sh
  rewrote by matching that exact literal. The installer now substitutes the
  placeholder and fails loudly if any remains.

### Known upgrade chains (blocked, not forgotten)

Dependabot's first run found two bumps that need a toolchain move, which CI
correctly refused:

- `androidx.activity:activity-compose` 1.13.0 requires Android Gradle Plugin
  8.9.1 or newer (currently 8.7.3), and pulls `androidx.navigationevent`. Doing
  it means moving AGP, and likely `compileSdk` past 35, together.
- Kotlin 2.4.x requires bumping all four Kotlin plugin versions in
  `android/build.gradle.kts` at once, and a Compose compiler that matches.

Neither is urgent: the app targets SDK 35 and builds clean. Take them as one
deliberate toolchain upgrade rather than as individual dependency PRs.

## Additional Documentation

The `docs/` folder contains detailed development documentation:

- `docs/architecture.md` - System architecture, data flow diagrams, security model
- `docs/android-dev.md` - Android SDK setup options, ADB, Gradle commands, release signing, CI
- `docs/adding-features.md` - Step-by-step guide for adding new remote actions
- `docs/troubleshooting.md` - Common issues, debug commands, log locations

Consult these docs for in-depth information beyond this quick reference.

## Related Files (on the Arch cmos host, deployed from `~/arch`)

- `~/arch/config/shell/bt-toggle.sh` - Bluetooth toggle script (symlinked to `~/.local/bin/bt-toggle`)
- `~/arch/config/shell/screen-off-toggle.sh` + `config/systemd/user/screen-off-toggle.service` - screen off + DND
- `~/arch/config/nftables/nftables.conf` - firewall (the port 8201 LAN rule)
