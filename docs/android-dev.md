# Android Development Setup

**Last Modified:** 2025-12-18

## Overview

This project uses Nix flakes to provide a reproducible Android development environment on NixOS, eliminating the need for Android Studio.

## Prerequisites

- NixOS with flakes enabled
- Android device with Developer Options enabled
- USB cable or WiFi for ADB connection

## Development Environment

### Enter Dev Shell

```bash
cd /etc/nixos/apps/cmos-remote/android
nix develop
```

This provides:
- OpenJDK 17
- Android SDK (platform 35, build-tools 35.0.0)
- Platform tools (adb, etc.)
- Gradle wrapper

### Environment Variables

The flake sets these automatically:
```
ANDROID_HOME=/nix/store/.../android-sdk
JAVA_HOME=/nix/store/.../openjdk
```

## Wireless ADB Setup

### Enable Developer Options (One-time)

1. Settings → About Phone → Software Information
2. Tap "Build number" 7 times
3. Settings → Developer Options → Enable "Wireless debugging"

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

## Build Commands

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

## Project Structure

```
android/
├── flake.nix                 # Nix dev environment
├── flake.lock               # Locked dependencies
├── gradle/                  # Gradle wrapper
├── gradlew                  # Gradle wrapper script
├── gradlew.bat             # Windows wrapper (unused)
├── settings.gradle.kts     # Project settings
├── build.gradle.kts        # Root build config
└── app/
    ├── build.gradle.kts    # App build config
    └── src/
        └── main/
            ├── AndroidManifest.xml
            ├── kotlin/...   # Kotlin source
            └── res/...      # Resources
```

## Dependencies

Defined in `app/build.gradle.kts`:

| Dependency | Version | Purpose |
|------------|---------|---------|
| Kotlin | 2.0.21 | Language |
| Compose BOM | 2024.12.01 | UI framework |
| Material3 | (from BOM) | Design system |
| Glance | 1.1.1 | Widget framework |
| OkHttp | 4.12.0 | HTTP client |
| DataStore | 1.1.1 | Preferences storage |
| Lifecycle | 2.8.7 | ViewModel |

## Adding Dependencies

1. Edit `app/build.gradle.kts`
2. Add to `dependencies` block:
```kotlin
dependencies {
    implementation("group:artifact:version")
}
```
3. Sync: `./gradlew --refresh-dependencies`

## Common Issues

### "SDK location not found"

Ensure you're in the nix develop shell:
```bash
nix develop
echo $ANDROID_HOME  # Should show path
```

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

Check that Kotlin and Compose compiler versions match in `app/build.gradle.kts`:
```kotlin
kotlin("android") version "2.0.21"
kotlin("plugin.compose") version "2.0.21"
```

## Debugging

### View App Logs

```bash
adb logcat | grep -i cmosremote
# Or filter by tag:
adb logcat -s "RemoteViewModel"
```

### Install and View Logs Together

```bash
./gradlew installDebug && adb logcat | grep -i cmosremote
```

### Check App Installation

```bash
adb shell pm list packages | grep cmosremote
# Output: package:com.clearcmos.cmosremote
```

### Uninstall App

```bash
adb uninstall com.clearcmos.cmosremote
```

## Release Build

Not configured yet. For release:
1. Create keystore
2. Add signing config to `build.gradle.kts`
3. Run `./gradlew assembleRelease`

## IDE Integration (Optional)

While Android Studio isn't required, you can use it for better IDE support:

1. Install Android Studio
2. Open the `android/` directory as a project
3. Let it sync Gradle
4. Use nix develop shell for builds (Android Studio's terminal)

Alternatively, use VS Code with Kotlin extension for basic syntax highlighting.
