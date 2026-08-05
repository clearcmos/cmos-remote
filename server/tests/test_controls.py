"""Tests for the system control wrappers.

The parser cases run against output captured from a real Arch host (see
tests/captured_output.py). Parsers matter here because every failure path in
controls.py degrades to 0 / False / None rather than raising, so a format
change would otherwise look like "volume 0, not muted, nothing connected"
instead of an error.
"""

import subprocess
import sys

import pytest

import controls
from tests.captured_output import (
    BLUETOOTHCTL_CONNECTED,
    BLUETOOTHCTL_SHOW_OFF,
    BLUETOOTHCTL_SHOW_ON,
    WPCTL_MUTED,
    WPCTL_UNMUTED,
)


class FakeRun:
    """Stands in for controls.run, returning canned output per command."""

    def __init__(self, *, stdout: str = "", returncode: int = 0, stderr: str = ""):
        self.stdout = stdout
        self.returncode = returncode
        self.stderr = stderr
        self.calls: list[list[str]] = []

    def __call__(self, args, timeout=5, check=False):
        self.calls.append(args)
        if check and self.returncode != 0:
            raise subprocess.CalledProcessError(self.returncode, args)
        return subprocess.CompletedProcess(args, self.returncode, self.stdout, self.stderr)


# --- parsers ------------------------------------------------------------------


@pytest.mark.parametrize(
    ("stdout", "expected"),
    [(WPCTL_UNMUTED, False), (WPCTL_MUTED, True), ("", False), ("Volume: 0.00", False)],
)
def test_parse_muted(stdout, expected):
    assert controls.parse_muted(stdout) is expected


@pytest.mark.parametrize(
    ("stdout", "expected"),
    [
        (WPCTL_UNMUTED, 74),
        (WPCTL_MUTED, 74),
        ("Volume: 0.00\n", 0),
        ("Volume: 1.00\n", 100),
        # wpctl reports above 1.0 when a sink is boosted past 100%.
        ("Volume: 1.50\n", 150),
        # Rounds rather than truncates, so 0.746 reads as 75 and not 74.
        ("Volume: 0.745\n", 74),
        ("Volume: 0.746\n", 75),
    ],
)
def test_parse_volume(stdout, expected):
    assert controls.parse_volume(stdout) == expected


@pytest.mark.parametrize("stdout", ["", "\n", "Volume:", "unexpected output", "Volume: NaN%"])
def test_parse_volume_degrades_to_zero(stdout):
    assert controls.parse_volume(stdout) == 0


@pytest.mark.parametrize(
    ("stdout", "expected"),
    [(BLUETOOTHCTL_SHOW_ON, True), (BLUETOOTHCTL_SHOW_OFF, False), ("", False)],
)
def test_parse_powered(stdout, expected):
    assert controls.parse_powered(stdout) is expected


def test_parse_connected_device():
    assert controls.parse_connected_device(BLUETOOTHCTL_CONNECTED) == "Soundcore Life Q30"


def test_parse_connected_device_keeps_spaces_in_the_name():
    line = "Device 11:22:33:44:55:66 Some Very Long Device Name\n"
    assert controls.parse_connected_device(line) == "Some Very Long Device Name"


def test_parse_connected_device_takes_the_first_of_several():
    out = BLUETOOTHCTL_CONNECTED + "Device 66:55:44:33:22:11 Other Headset\n"
    assert controls.parse_connected_device(out) == "Soundcore Life Q30"


@pytest.mark.parametrize("stdout", ["", "\n", "Device 11:22:33:44:55:66", "garbage"])
def test_parse_connected_device_returns_none(stdout):
    assert controls.parse_connected_device(stdout) is None


# --- audio wrappers -----------------------------------------------------------


def test_get_volume_uses_wpctl(monkeypatch):
    fake = FakeRun(stdout=WPCTL_UNMUTED)
    monkeypatch.setattr(controls, "run", fake)
    assert controls.get_volume() == 74
    assert fake.calls == [[controls.WPCTL, "get-volume", controls.DEFAULT_SINK]]


def test_get_volume_returns_zero_when_wpctl_fails(monkeypatch):
    def boom(*a, **kw):
        raise subprocess.TimeoutExpired("wpctl", 5)

    monkeypatch.setattr(controls, "run", boom)
    assert controls.get_volume() == 0


def test_get_mute_status_returns_false_when_wpctl_fails(monkeypatch):
    def boom(*a, **kw):
        raise OSError("wpctl missing")

    monkeypatch.setattr(controls, "run", boom)
    assert controls.get_mute_status() is False


def test_set_volume_converts_percent_to_fraction(monkeypatch):
    fake = FakeRun(stdout="Volume: 0.42\n")
    monkeypatch.setattr(controls, "run", fake)
    assert controls.set_volume(42) == (True, 42)
    assert fake.calls[0] == [controls.WPCTL, "set-volume", controls.DEFAULT_SINK, "0.42"]


@pytest.mark.parametrize(("level", "sent"), [(-10, "0.0"), (0, "0.0"), (100, "1.0"), (250, "1.0")])
def test_set_volume_clamps_out_of_range_levels(monkeypatch, level, sent):
    fake = FakeRun(stdout="Volume: 0.00\n")
    monkeypatch.setattr(controls, "run", fake)
    controls.set_volume(level)
    assert fake.calls[0][3] == sent


def test_set_volume_reports_failure(monkeypatch):
    fake = FakeRun(stdout="Volume: 0.10\n", returncode=1)
    monkeypatch.setattr(controls, "run", fake)
    success, level = controls.set_volume(50)
    assert success is False
    assert level == 10  # the level actually in effect, not the requested one


def test_toggle_mute_returns_the_new_state(monkeypatch):
    fake = FakeRun(stdout=WPCTL_MUTED)
    monkeypatch.setattr(controls, "run", fake)
    assert controls.toggle_mute() == (True, True)
    assert fake.calls[0] == [controls.WPCTL, "set-mute", controls.DEFAULT_SINK, "toggle"]


def test_toggle_mute_reports_failure(monkeypatch):
    fake = FakeRun(stdout=WPCTL_UNMUTED, returncode=1)
    monkeypatch.setattr(controls, "run", fake)
    assert controls.toggle_mute() == (False, False)


# --- bluetooth wrappers -------------------------------------------------------


def test_get_bluetooth_status_when_on_and_connected(monkeypatch):
    outputs = [BLUETOOTHCTL_SHOW_ON, BLUETOOTHCTL_CONNECTED]

    def fake(args, timeout=5, check=False):
        return subprocess.CompletedProcess(args, 0, outputs.pop(0), "")

    monkeypatch.setattr(controls, "run", fake)
    assert controls.get_bluetooth_status() == (True, "Soundcore Life Q30")


def test_get_bluetooth_status_skips_device_lookup_when_powered_off(monkeypatch):
    fake = FakeRun(stdout=BLUETOOTHCTL_SHOW_OFF)
    monkeypatch.setattr(controls, "run", fake)
    assert controls.get_bluetooth_status() == (False, None)
    assert len(fake.calls) == 1


def test_get_bluetooth_status_survives_a_missing_bluetoothctl(monkeypatch):
    def boom(*a, **kw):
        raise FileNotFoundError("bluetoothctl")

    monkeypatch.setattr(controls, "run", boom)
    assert controls.get_bluetooth_status() == (False, None)


def test_toggle_bluetooth_reports_failure_when_bt_toggle_exits_nonzero(monkeypatch):
    calls: list[list[str]] = []

    def fake(args, timeout=5, check=False):
        calls.append(args)
        if args == [controls.BT_TOGGLE]:
            return subprocess.CompletedProcess(args, 1, "", "no such device")
        if args[1] == "show":
            return subprocess.CompletedProcess(args, 0, BLUETOOTHCTL_SHOW_OFF, "")
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(controls, "run", fake)
    assert controls.toggle_bluetooth() == (False, False, None)


def test_toggle_bluetooth_success(monkeypatch):
    outputs = ["", BLUETOOTHCTL_SHOW_ON, BLUETOOTHCTL_CONNECTED]

    def fake(args, timeout=5, check=False):
        return subprocess.CompletedProcess(args, 0, outputs.pop(0), "")

    monkeypatch.setattr(controls, "run", fake)
    assert controls.toggle_bluetooth() == (True, True, "Soundcore Life Q30")


# --- screen -------------------------------------------------------------------


def test_trigger_screen_off_starts_the_user_unit(monkeypatch):
    fake = FakeRun()
    monkeypatch.setattr(controls, "run", fake)
    assert controls.trigger_screen_off() is True
    assert fake.calls == [[controls.SYSTEMCTL, "--user", "start", "screen-off-toggle.service"]]


def test_trigger_screen_off_reports_failure(monkeypatch):
    monkeypatch.setattr(controls, "run", FakeRun(returncode=1, stderr="Unit not found."))
    assert controls.trigger_screen_off() is False


def test_trigger_screen_off_survives_a_missing_systemctl(monkeypatch):
    def boom(*a, **kw):
        raise FileNotFoundError("systemctl")

    monkeypatch.setattr(controls, "run", boom)
    assert controls.trigger_screen_off() is False


def test_toggle_bluetooth_survives_a_missing_helper(monkeypatch):
    def boom(*a, **kw):
        raise FileNotFoundError("bt-toggle")

    monkeypatch.setattr(controls, "run", boom)
    assert controls.toggle_bluetooth() == (False, False, None)


# --- the subprocess boundary itself -------------------------------------------


def test_run_captures_stdout():
    # Hermetic: the interpreter running the tests, not a host tool.
    result = controls.run([sys.executable, "-c", "print('hello')"])
    assert result.returncode == 0
    assert result.stdout == "hello\n"


def test_run_with_check_raises_on_failure():
    with pytest.raises(subprocess.CalledProcessError):
        controls.run([sys.executable, "-c", "raise SystemExit(3)"], check=True)


# --- command resolution -------------------------------------------------------


def test_resolve_prefers_path(monkeypatch):
    monkeypatch.setattr(controls.shutil, "which", lambda name: "/usr/bin/" + name)
    assert controls.resolve("wpctl", "/nowhere/wpctl") == "/usr/bin/wpctl"


def test_resolve_falls_back_to_an_existing_candidate(monkeypatch, tmp_path):
    monkeypatch.setattr(controls.shutil, "which", lambda name: None)
    candidate = tmp_path / "bt-toggle"
    candidate.write_text("#!/bin/sh\n")
    assert controls.resolve("bt-toggle", str(candidate)) == str(candidate)


def test_resolve_returns_the_bare_name_when_nothing_is_found(monkeypatch):
    monkeypatch.setattr(controls.shutil, "which", lambda name: None)
    # subprocess can still find it via PATH at exec time, which is the point.
    assert controls.resolve("bt-toggle", "/nowhere/bt-toggle") == "bt-toggle"
