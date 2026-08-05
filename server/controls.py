"""System control wrappers: audio (wpctl), Bluetooth (bt-toggle), screen off.

Every external command is split in two: a pure `parse_*` function over the
command's stdout, and a `run`-calling wrapper. The parsers are what break when
a tool changes its output format, and they are the part that can be tested
against captured real output without touching the host.
"""

from __future__ import annotations

import logging
import os
import shutil
import subprocess

logger = logging.getLogger(__name__)


def resolve(name: str, *fallbacks: str) -> str:
    """Resolve a command to an absolute path.

    Looks on PATH first (the systemd user unit sets PATH to include
    ~/.local/bin and /usr/bin), then tries known fallback locations, and
    finally returns the bare name so subprocess can still find it via PATH
    at exec time. Keeps the server portable across distros instead of
    hardcoding store or prefix paths.
    """
    found = shutil.which(name)
    if found:
        return found
    for candidate in fallbacks:
        expanded = os.path.expanduser(candidate)
        if os.path.exists(expanded):
            return expanded
    return name


# Command paths, resolved at import (Arch: /usr/bin, bt-toggle in ~/.local/bin)
WPCTL = resolve("wpctl", "/usr/bin/wpctl")
BLUETOOTHCTL = resolve("bluetoothctl", "/usr/bin/bluetoothctl")
BT_TOGGLE = resolve("bt-toggle", "~/.local/bin/bt-toggle")
SYSTEMCTL = resolve("systemctl", "/usr/bin/systemctl")

DEFAULT_SINK = "@DEFAULT_AUDIO_SINK@"


def run(args: list[str], timeout: int = 5, check: bool = False) -> subprocess.CompletedProcess[str]:
    """Single choke point for subprocess use, so tests can patch one function."""
    return subprocess.run(args, capture_output=True, text=True, timeout=timeout, check=check)


# --- parsers ------------------------------------------------------------------
# Sample `wpctl get-volume @DEFAULT_AUDIO_SINK@` output:
#   "Volume: 0.74"
#   "Volume: 0.74 [MUTED]"


def parse_muted(stdout: str) -> bool:
    return "[MUTED]" in stdout


def parse_volume(stdout: str) -> int:
    """Volume as 0-100. Returns 0 when the output does not parse."""
    parts = stdout.strip().split()
    if len(parts) >= 2:
        try:
            return int(round(float(parts[1]) * 100))
        except ValueError:
            return 0
    return 0


def parse_powered(stdout: str) -> bool:
    """True when `bluetoothctl show` reports the controller powered on."""
    return "Powered: yes" in stdout


def parse_connected_device(stdout: str) -> str | None:
    """Device name from `bluetoothctl devices Connected`, or None.

    Output is one "Device <MAC> <name>" line per connected device; the name is
    everything after the MAC and may contain spaces.
    """
    lines = stdout.strip().split("\n")
    if not lines or not lines[0].strip():
        return None
    parts = lines[0].split(" ", 2)
    if len(parts) >= 3:
        return parts[2]
    return None


# --- audio --------------------------------------------------------------------


def get_mute_status() -> bool:
    """Check if system audio is muted using wpctl."""
    try:
        return parse_muted(run([WPCTL, "get-volume", DEFAULT_SINK]).stdout)
    except Exception as e:
        logger.error(f"Failed to get mute status: {e}")
        return False


def get_volume() -> int:
    """Get current volume level (0-100) using wpctl."""
    try:
        return parse_volume(run([WPCTL, "get-volume", DEFAULT_SINK]).stdout)
    except Exception as e:
        logger.error(f"Failed to get volume: {e}")
        return 0


def set_volume(level: int) -> tuple[bool, int]:
    """Set volume level (0-100). Returns (success, new_level)."""
    try:
        level = max(0, min(100, level))
        # wpctl expects a decimal fraction, not a percentage.
        run([WPCTL, "set-volume", DEFAULT_SINK, str(level / 100.0)], check=True)
        new_level = get_volume()
        logger.info(f"Volume set to {new_level}%")
        return True, new_level
    except Exception as e:
        logger.error(f"Failed to set volume: {e}")
        return False, get_volume()


def toggle_mute() -> tuple[bool, bool]:
    """Toggle system mute. Returns (success, new_muted_state)."""
    try:
        run([WPCTL, "set-mute", DEFAULT_SINK, "toggle"], check=True)
        new_state = get_mute_status()
        logger.info(f"Mute toggled, new state: {'muted' if new_state else 'unmuted'}")
        return True, new_state
    except Exception as e:
        logger.error(f"Failed to toggle mute: {e}")
        return False, get_mute_status()


# --- bluetooth ----------------------------------------------------------------


def get_bluetooth_status() -> tuple[bool, str | None]:
    """Returns (is_powered_on, connected_device_name or None)."""
    try:
        powered_on = parse_powered(run([BLUETOOTHCTL, "show"]).stdout)
        if not powered_on:
            return False, None
        connected = parse_connected_device(run([BLUETOOTHCTL, "devices", "Connected"]).stdout)
        return True, connected
    except Exception as e:
        logger.error(f"Failed to get Bluetooth status: {e}")
        return False, None


def toggle_bluetooth() -> tuple[bool, bool, str | None]:
    """Toggle Bluetooth via the bt-toggle helper.

    bt-toggle owns the policy (off when on; on and connect the configured
    device when off). Returns (success, new_powered_state, connected_device).
    """
    try:
        # Longer timeout: turning Bluetooth on and connecting is not instant.
        result = run([BT_TOGGLE], timeout=30)
        if result.returncode != 0:
            logger.error(f"bt-toggle failed: {result.stderr}")
            powered_on, connected = get_bluetooth_status()
            return False, powered_on, connected

        powered_on, connected = get_bluetooth_status()
        logger.info(f"Bluetooth toggled, powered: {powered_on}, connected: {connected}")
        return True, powered_on, connected
    except Exception as e:
        logger.error(f"Failed to toggle Bluetooth: {e}")
        powered_on, connected = get_bluetooth_status()
        return False, powered_on, connected


# --- screen -------------------------------------------------------------------


def trigger_screen_off() -> bool:
    """Trigger the screen-off toggle via its systemd user service."""
    try:
        result = run([SYSTEMCTL, "--user", "start", "screen-off-toggle.service"], timeout=10)
        if result.returncode == 0:
            logger.info("Screen off toggle triggered successfully")
            return True
        logger.error(f"Screen off toggle failed: {result.stderr}")
        return False
    except Exception as e:
        logger.error(f"Failed to trigger screen off: {e}")
        return False
