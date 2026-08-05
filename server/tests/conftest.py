import json
import pathlib
import subprocess
import sys

import pytest

# The server is a flat set of modules run by uvicorn from server/, not an
# installed package, so tests import them the same way uvicorn does.
SERVER_DIR = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

SPEC_DIR = SERVER_DIR.parent / "spec"

import auth  # noqa: E402
import controls  # noqa: E402
import main  # noqa: E402
from tests.captured_output import (  # noqa: E402
    BLUETOOTHCTL_CONNECTED,
    BLUETOOTHCTL_SHOW_ON,
    WPCTL_UNMUTED,
)


@pytest.fixture(scope="session")
def vectors() -> dict:
    """The canonical wire-format vectors shared with the Android test suite."""
    loaded: dict = json.loads((SPEC_DIR / "hmac-vectors.json").read_text())
    return loaded


@pytest.fixture
def stub_commands(monkeypatch):
    """Every external command succeeds and reports a plausible state.

    Matches spec/wire-payloads.json "status_connected": unmuted, volume 74,
    Bluetooth on with the Q30 connected.
    """

    def fake(args, timeout=5, check=False):
        if args[0] == controls.WPCTL and "get-volume" in args:
            return subprocess.CompletedProcess(args, 0, WPCTL_UNMUTED, "")
        if args[0] == controls.BLUETOOTHCTL and args[1] == "show":
            return subprocess.CompletedProcess(args, 0, BLUETOOTHCTL_SHOW_ON, "")
        if args[0] == controls.BLUETOOTHCTL:
            return subprocess.CompletedProcess(args, 0, BLUETOOTHCTL_CONNECTED, "")
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(controls, "run", fake)


@pytest.fixture
def open_client(monkeypatch, stub_commands):
    """Client against a server with no token set (auth disabled)."""
    from fastapi.testclient import TestClient

    monkeypatch.setattr(main, "authenticator", auth.Authenticator(b""))
    with TestClient(main.app) as client:
        yield client
