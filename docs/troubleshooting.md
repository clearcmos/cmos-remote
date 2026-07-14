# Troubleshooting

**Last Modified:** 2026-07-14

## Connection Issues

### App shows "Disconnected" or "Server Unreachable"

**Check 1: WiFi Connection**
```bash
# On phone: Settings > WiFi - verify connected to home network
```

**Check 2: Auth Token**
The app only connects when its auth token matches the server's `CMOS_REMOTE_TOKEN`.
- In the app, tap the gear icon and confirm the token is set correctly
- On the server, compare: `op read op://api/CMOS_REMOTE/password`

**Check 3: Server Running**
```bash
systemctl --user status cmos-remote
# Should show: active (running)

# If not running:
systemctl --user start cmos-remote
journalctl --user -u cmos-remote -n 50  # Check for errors
```

**Check 4: Network Connectivity**
```bash
# From another device on the same network (a 401 still proves reachability
# when auth is on; "connection refused"/timeout means it is not reachable):
curl -o /dev/null -w "%{http_code}\n" http://192.168.1.2:8201/health

# If unreachable, check the firewall:
sudo nft list chain inet filter input | grep 8201
```

**Check 5: Correct IP**
Verify cmos host IP is 192.168.1.2:
```bash
ip -4 addr show
```

### Widget Shows "Disconnected" but App Works

1. Widget updates are asynchronous - tap refresh icon
2. Kill and re-add widget to home screen
3. Reboot phone to reset widget state

### "Unreachable" even on the right WiFi

Usually an auth mismatch:
1. The app's token doesn't match the server's `CMOS_REMOTE_TOKEN` - re-check both
2. The server is running open (no token provisioned) while the app has a token set - the app refuses an unsigned server response; provision the server token (`./server/install.sh`) or clear the app's token
3. Phone and server clocks differ by more than 60s (the HMAC freshness window)

## Server Issues

### Service Won't Start

```bash
journalctl --user -u cmos-remote -n 100 --no-pager
```

Common causes:
- **Python syntax error** - Check `server/main.py`
- **Missing dependencies** - Server uses the venv at `server/.venv`; re-run `./server/install.sh`
- **Port in use** - `ss -tlnp | grep 8201`

### Commands Not Found

The server resolves each command at startup via `shutil.which` (with fallbacks), so nothing is hardcoded. It relies on the unit's PATH including `~/.local/bin` (for `bt-toggle`) and `/usr/bin`.

Verify the commands exist and are on the service's PATH:
```bash
command -v wpctl bluetoothctl systemctl   # expect /usr/bin/...
command -v bt-toggle                      # expect ~/.local/bin/bt-toggle
```

### Permission Denied

The service runs as a systemd **user** service in your own session, so it already has your PipeWire and D-Bus access. Verify it is the user unit:
```bash
systemctl --user status cmos-remote
```

If audio/bluetooth commands fail, test them directly in your session:
```bash
wpctl get-volume @DEFAULT_AUDIO_SINK@
bluetoothctl show
```

## Audio Issues

### Mute Toggle Doesn't Work

**Check 1: PipeWire Running**
```bash
systemctl --user status pipewire
systemctl --user status wireplumber
```

**Check 2: Test wpctl**
```bash
wpctl get-volume @DEFAULT_AUDIO_SINK@
# Should show: Volume: 0.50 or Volume: 0.50 [MUTED]

wpctl set-mute @DEFAULT_AUDIO_SINK@ toggle
# Should toggle mute state
```

**Check 3: Default Sink Exists**
```bash
wpctl status
# Look for default audio sink in output
```

### Wrong Audio Device Muted

The server mutes `@DEFAULT_AUDIO_SINK@`. To change:
1. Set correct default sink: `wpctl set-default <id>`
2. Or modify server to target specific sink

### Volume Slider Not Working

**Check 1: Test wpctl Volume**
```bash
wpctl get-volume @DEFAULT_AUDIO_SINK@
# Should show: Volume: 0.50 (or similar)

wpctl set-volume @DEFAULT_AUDIO_SINK@ 50%
# Should set volume to 50%
```

**Check 2: Test API Endpoint**
```bash
curl -X POST http://192.168.1.2:8201/volume \
  -H "Content-Type: application/json" \
  -d '{"level": 50}'
# Should return: {"success": true, "message": "Volume set to 50%", "level": 50}
```

**Check 3: Server Logs**
```bash
journalctl --user -u cmos-remote -f
# Look for volume-related errors
```

## Bluetooth Issues

### Bluetooth Toggle Doesn't Work

**Check 1: Test bt-toggle Script**
```bash
bt-toggle
# Should toggle Bluetooth and attempt Q30 connection
```

**Check 2: BlueZ Running**
```bash
systemctl status bluetooth
bluetoothctl show
# Should show adapter info with "Powered: yes/no"
```

**Check 3: Q30 Paired**
```bash
bluetoothctl devices
# Should list: Device AA:BB:CC:DD:EE:FF Soundcore Life Q30
```

### Q30 Won't Connect

1. Put headphones in pairing mode
2. Manually pair: `bluetoothctl pair AA:BB:CC:DD:EE:FF`
3. Trust device: `bluetoothctl trust AA:BB:CC:DD:EE:FF`

## Screen Off Issues

### Screen Off Doesn't Work

**Check 1: Test Service Directly**
```bash
systemctl --user start screen-off-toggle.service
systemctl --user status screen-off-toggle
```

**Check 2: Test Keyboard Shortcut**
Press Meta+F10 on cmos desktop - screens should turn off and DND should enable.

**Check 3: Test API Endpoint**
```bash
curl -X POST http://192.168.1.2:8201/screen-off
# Should return: {"success": true, "message": "Screen off triggered", "new_state": null}
```

**Check 4: Check DPMS/KDE**
```bash
# Verify powerdevil shortcut exists
qdbus org.kde.kglobalaccel /component/org_kde_powerdevil shortcutNames
# Should include "Turn Off Screen"
```

### DND Not Restoring After Wake

The watcher service monitors idle state and restores DND when you wake up.

**Check 1: Watcher Service Running**
```bash
systemctl --user status screen-off-watcher
```

**Check 2: Check State File**
```bash
cat ~/.local/state/screen-off-dnd-enabled
# Should contain original DND state
```

**Check 3: Manual Restore**
```bash
# Edit plasmanotifyrc to remove DND section
sed -i '/^\[DoNotDisturb\]/,/^$/d' ~/.config/plasmanotifyrc
```

### Screens Wake Up Unexpectedly

This is usually caused by applications (Discord, etc.) generating activity that wakes the screen.
- The watcher correctly detects this and restores DND
- To prevent wake-ups, close apps that maintain persistent connections

## Build Issues

### Gradle Build Fails

```bash
# Clean and rebuild
./gradlew clean assembleDebug --stacktrace

# Check Java version
java -version
# Should be 17.x
```

### "SDK location not found"

Ensure you're in nix develop shell:
```bash
nix develop
echo $ANDROID_HOME
```

### ADB Device Not Found

```bash
# Reconnect
adb disconnect
adb connect 192.168.1.13:46833

# If pairing expired, re-pair
adb pair 192.168.1.13:<PAIRING_PORT> <CODE>
```

## Widget Issues

### Widget Not Updating

1. Force refresh by tapping status area
2. Android aggressively kills background processes - widget may need manual refresh
3. Check battery optimization settings for the app

### Widget Crashes

```bash
adb logcat | grep -E "(Glance|RemoteWidget|crash)"
```

Common causes:
- Network call on main thread (should use coroutine)
- Null pointer in state

### Widget Actions Don't Work

1. Verify `WidgetActionReceiver` is registered in `AndroidManifest.xml`
2. Check intent action strings match between widget and receiver
3. View logs: `adb logcat | grep WidgetAction`

## Logs

### Server Logs
```bash
journalctl --user -u cmos-remote -f  # Follow live
journalctl --user -u cmos-remote -n 100  # Last 100 lines
journalctl --user -u cmos-remote --since "10 minutes ago"
```

### Android Logs
```bash
adb logcat | grep -i cmosremote
adb logcat -s "RemoteViewModel"
adb logcat -s "ApiClient"
adb logcat -s "NetworkMonitor"
```

### Full Debug Session
```bash
# Terminal 1: Server logs
journalctl --user -u cmos-remote -f

# Terminal 2: Android logs
adb logcat | grep -iE "(cmosremote|glance)"

# Terminal 3: Test endpoints
watch -n 2 'curl -s http://192.168.1.2:8201/status | jq'
```
