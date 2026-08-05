# Android Development Setup

## Overview

The app builds with the committed Gradle wrapper. You need a JDK 17 and the
Android SDK (platform 35, build-tools 35.0.0); where those come from is up to
you. Android Studio is not required.

## Prerequisites

- JDK 17 (`jvmTarget` is 17; newer JDKs are not configured)
- Android SDK: `platforms;android-35`, `build-tools;35.0.0`, `platform-tools`
- An Android device with Developer Options enabled (API 26+)
- USB cable or WiFi for ADB

Pick one of the three setups below.

### Option A: command-line tools (no IDE)

Download the Android command-line tools, then install the components this
project pins:

```bash
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

Point Gradle at the SDK, either by exporting the path:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
```

or by writing `android/local.properties` (gitignored):

```
sdk.dir=/home/you/Android/Sdk
```

### Option B: Android Studio

Open the `android/` directory as a project and let it sync Gradle. Studio
installs the SDK components and manages `local.properties` for you. Its bundled
JDK satisfies the JDK 17 requirement.

### Option C: nix (optional)

`android/flake.nix` pins the whole toolchain (JDK 17, platform 35, build-tools
35.0.0, platform-tools). It is a convenience for machines that already run nix,
including non-NixOS ones; it is not a requirement for building this project and
nothing in CI uses it.

```bash
cd android
nix develop
```

The shell exports `ANDROID_HOME`, `ANDROID_SDK_ROOT`, and `JAVA_HOME`, and puts
`platform-tools` and `build-tools` on `PATH`.

## Build Commands

Run these from `android/`.

### Build Debug APK

```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Build and Install

```bash
./gradlew installDebug
```

### Clean Build

```bash
./gradlew clean assembleDebug
```

### View Build Logs

```bash
./gradlew assembleDebug --info
./gradlew assembleDebug --stacktrace  # For errors
```

## Wireless ADB Setup

### Enable Developer Options (One-time)

1. Settings > About Phone > Software Information
2. Tap "Build number" 7 times
3. Settings > Developer Options > Enable "Wireless debugging"

### Pair Device (One-time per device)

1. In Wireless debugging settings, tap "Pair device with pairing code"
2. Note the IP:PORT and 6-digit code
3. Run:
```bash
adb pair <IP>:<PAIRING_PORT> <CODE>
# Example: adb pair 192.168.1.13:36389 160799
```

### Connect for Development

1. In Wireless debugging, note the main IP:PORT (different from pairing port)
2. Run:
```bash
adb connect <IP>:<DEBUG_PORT>
# Example: adb connect 192.168.1.13:46833
```

3. Verify:
```bash
adb devices
# Should show: 192.168.1.13:46833    device
```

## Project Structure

```
android/
├── flake.nix                # Optional nix dev shell (see Option C)
├── flake.lock               # Locked nix inputs
├── gradle/                  # Gradle wrapper
├── gradlew                  # Gradle wrapper script
├── settings.gradle.kts      # Project settings
├── build.gradle.kts         # Root build config (plugin versions)
└── app/
    ├── build.gradle.kts     # App build config, signing, versioning
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── kotlin/...   # Kotlin source
        │   └── res/...      # Resources
        └── test/kotlin/...  # JVM unit tests
```

## Dependencies

Plugin versions live in `android/build.gradle.kts`, library versions in
`app/build.gradle.kts`:

| Dependency | Version | Purpose |
|------------|---------|---------|
| Kotlin | 2.1.0 | Language |
| Android Gradle Plugin | 8.7.3 | Build |
| Gradle (wrapper) | 8.11 | Build |
| Compose BOM | 2024.12.01 | UI framework |
| Material3 | (from BOM) | Design system |
| Glance | 1.1.1 | Widget framework |
| OkHttp | 4.12.0 | HTTP client |
| DataStore | 1.1.1 | Preferences storage |
| Lifecycle | 2.8.7 | ViewModel |
| JUnit | 4.13.2 | Unit tests (test only) |
| MockWebServer | 4.12.0 | HTTP tests (test only) |

## Adding Dependencies

1. Edit `app/build.gradle.kts`
2. Add to `dependencies` block:
```kotlin
dependencies {
    implementation("group:artifact:version")
}
```
3. Sync: `./gradlew --refresh-dependencies`

## Release Build

Releases are published by tagging; `.github/workflows/release.yml` builds a
signed APK and attaches it to the GitHub release.

### One-time: create a signing keystore

The keystore is the only thing that can publish an upgrade for this app id.
Losing it means users must uninstall before installing a future version.

```bash
keytool -genkeypair -v -keystore release.jks -alias deskremote -keyalg RSA -keysize 4096 -validity 10000
```

For local release builds, keep it at `android/release.jks` and write
`android/keystore.properties` (both gitignored):

```
storeFile=release.jks
storePassword=...
keyAlias=deskremote
keyPassword=...
```

Then:

```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

Without `keystore.properties` the release build still succeeds but is unsigned,
which no phone will install.

### One-time: repository secrets for CI

Add these four secrets to the GitHub repository:

| Secret | Value |
|--------|-------|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | store password |
| `ANDROID_KEY_ALIAS` | key alias (e.g. `deskremote`) |
| `ANDROID_KEY_PASSWORD` | key password |

### Publishing

```bash
git tag v1.0.1
git push origin v1.0.1
```

`versionName` comes from the tag with its leading `v` stripped; `versionCode`
comes from the workflow run number, so it always increases.

## Common Issues

### "SDK location not found"

Gradle cannot see an SDK. Set `ANDROID_HOME` or write `android/local.properties`
(Option A above). Inside the nix shell, check `echo $ANDROID_HOME`.

### "No connected devices"

```bash
adb devices
# If empty, reconnect:
adb connect 192.168.1.13:46833
```

### Build cache issues

```bash
./gradlew clean
rm -rf ~/.gradle/caches/
./gradlew assembleDebug
```

### Kotlin version mismatch

The Kotlin and Compose compiler plugin versions must match in
`android/build.gradle.kts`:

```kotlin
id("org.jetbrains.kotlin.android") version "2.1.0" apply false
id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
```

## Debugging

### View App Logs

```bash
adb logcat | grep -i deskremote
# Or filter by tag:
adb logcat -s "RemoteViewModel"
```

### Install and View Logs Together

```bash
./gradlew installDebug && adb logcat | grep -i deskremote
```

### Check App Installation

```bash
adb shell pm list packages | grep deskremote
# Output: package:com.clearcmos.deskremote
```

### Uninstall App

```bash
adb uninstall com.clearcmos.deskremote
```

## Tests

The app's tests are plain JVM unit tests: no device, no emulator, no Robolectric.

```bash
./gradlew testDebugUnitTest
# HTML report: app/build/reports/tests/testDebugUnitTest/index.html
```

| Test class | Covers |
|------------|--------|
| `HmacInterceptorTest` | request signing and response verification against `spec/hmac-vectors.json`, including impostor and tampered-body rejection |
| `ApiClientTest` | each endpoint's request, payload decoding, failure reporting, and auth wiring, against MockWebServer |
| `ModelsTest` | decoding the payloads in `spec/wire-payloads.json` into the app's data classes |

The two files under `spec/` are shared with the server's pytest suite, so a
change to the wire format on one side and not the other fails CI. `build.gradle.kts`
passes their directory to the tests via the `spec.dir` system property.

UI, widget, ViewModel, DataStore, and ConnectivityManager code is deliberately
not unit tested; the exemptions and their reasons are listed in CLAUDE.md.

## CI

`.github/workflows/ci.yml` runs on push to `main` and on pull requests:

- `server`: ruff lint, ruff format check, mypy, and pytest with a coverage gate,
  on CPython 3.11 and 3.14
- `android`: `testDebugUnitTest`, `lintDebug`, then `assembleDebug`, using
  `setup-java` plus `setup-android`, deliberately the same plain-SDK path as
  Option A rather than the nix flake
- `ci-ok`: aggregate gate

On a failed android job the test and lint reports are uploaded as an artifact.
