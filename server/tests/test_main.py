"""Endpoint tests, driven through the real ASGI app with a TestClient.

These exercise the wiring the unit tests cannot: the app-level auth dependency,
the response-signing middleware, and the mapping from a failed control call to
a 500. System commands are stubbed at controls.run, so nothing here touches
PipeWire, BlueZ, or systemd.
"""

import pytest
from fastapi.testclient import TestClient

import auth
import controls
import main

TOKEN = b"cmos-remote-test-token-not-a-real-secret"
NOW = 1750000000


@pytest.fixture
def authed(monkeypatch, stub_commands):
    """Client against a server with auth enabled and a frozen clock."""
    authenticator = auth.Authenticator(TOKEN, clock=lambda: NOW)
    monkeypatch.setattr(main, "authenticator", authenticator)
    with TestClient(main.app) as client:
        yield client, authenticator


def signed_headers(nonce: str, method: str, path: str, body: bytes = b"") -> dict[str, str]:
    ts = str(NOW)
    sig = auth.sign(TOKEN, auth.request_message(ts, nonce, method, path, body))
    return {"X-Auth-Ts": ts, "X-Auth-Nonce": nonce, "X-Auth-Sig": sig}


# --- open mode ----------------------------------------------------------------


def test_health_is_open_when_no_token_is_set(open_client):
    response = open_client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "service": "cmos-remote"}


def test_open_mode_does_not_sign_responses(open_client):
    response = open_client.get("/health")
    assert "x-resp-sig" not in response.headers


def test_status_reports_parsed_state(open_client):
    body = open_client.get("/status").json()
    assert body == {
        "muted": False,
        "volume": 74,
        "bluetooth_on": True,
        "bluetooth_connected": "Soundcore Life Q30",
    }


def test_volume_endpoint_round_trip(open_client, monkeypatch):
    monkeypatch.setattr(controls, "set_volume", lambda level: (True, level))
    response = open_client.post("/volume", json={"level": 42})
    assert response.status_code == 200
    assert response.json() == {"success": True, "level": 42, "message": "Volume set to 42%"}


def test_volume_rejects_a_non_integer_level(open_client):
    assert open_client.post("/volume", json={"level": "loud"}).status_code == 422


def test_get_volume_endpoint(open_client):
    assert open_client.get("/volume").json() == {"level": 74}


def test_mute_endpoint(open_client, monkeypatch):
    monkeypatch.setattr(controls, "toggle_mute", lambda: (True, True))
    assert open_client.post("/mute").json() == {
        "success": True,
        "message": "Muted",
        "new_state": True,
    }


@pytest.mark.parametrize(
    ("result", "message"),
    [
        ((True, True, "Soundcore Life Q30"), "Bluetooth ON, connected to Soundcore Life Q30"),
        ((True, True, None), "Bluetooth ON"),
        ((True, False, None), "Bluetooth OFF"),
    ],
)
def test_bluetooth_messages(open_client, monkeypatch, result, message):
    monkeypatch.setattr(controls, "toggle_bluetooth", lambda: result)
    assert open_client.post("/bluetooth").json()["message"] == message


def test_screen_off_endpoint(open_client, monkeypatch):
    monkeypatch.setattr(controls, "trigger_screen_off", lambda: True)
    assert open_client.post("/screen-off").json()["success"] is True


# --- failure mapping ----------------------------------------------------------


@pytest.mark.parametrize(
    ("path", "target", "failure", "detail"),
    [
        ("/mute", "toggle_mute", (False, False), "Failed to toggle mute"),
        ("/bluetooth", "toggle_bluetooth", (False, False, None), "Failed to toggle Bluetooth"),
        ("/screen-off", "trigger_screen_off", False, "Failed to trigger screen off"),
    ],
)
def test_failed_actions_return_500(open_client, monkeypatch, path, target, failure, detail):
    monkeypatch.setattr(controls, target, lambda: failure)
    response = open_client.post(path)
    assert response.status_code == 500
    assert response.json()["detail"] == detail


def test_failed_volume_returns_500(open_client, monkeypatch):
    monkeypatch.setattr(controls, "set_volume", lambda level: (False, 0))
    response = open_client.post("/volume", json={"level": 10})
    assert response.status_code == 500
    assert response.json()["detail"] == "Failed to set volume"


# --- missing host dependencies ------------------------------------------------
# The point of these: anyone running this on a machine that is not the author's
# is missing bt-toggle and the screen-off unit, and a bare "failed" tells them
# nothing about which one.


@pytest.mark.parametrize(
    ("path", "missing", "json_body"),
    [
        ("/mute", "WPCTL", None),
        ("/volume", "WPCTL", {"level": 10}),
        ("/bluetooth", "BT_TOGGLE", None),
        ("/screen-off", "SYSTEMCTL", None),
    ],
)
def test_missing_command_returns_503_naming_it(open_client, monkeypatch, path, missing, json_body):
    command = getattr(controls, missing)
    monkeypatch.setattr(controls, "available", lambda c: c != command)

    response = open_client.post(path, json=json_body)

    assert response.status_code == 503
    assert command in response.json()["detail"]
    assert "not found on PATH" in response.json()["detail"]


def test_bluetooth_503_points_at_the_readme(open_client, monkeypatch):
    monkeypatch.setattr(controls, "available", lambda c: c != controls.BT_TOGGLE)
    assert "README" in open_client.post("/bluetooth").json()["detail"]


def test_status_still_answers_when_commands_are_missing(open_client, monkeypatch):
    # /status degrades instead of failing, so the app can still connect and show
    # something on a host with no PipeWire.
    monkeypatch.setattr(controls, "available", lambda c: False)
    assert open_client.get("/status").status_code == 200


# --- authenticated mode -------------------------------------------------------


def test_unsigned_request_is_rejected(authed):
    client, _ = authed
    response = client.get("/health")
    assert response.status_code == 401
    assert response.json()["detail"] == "missing auth headers"


def test_signed_request_is_accepted(authed):
    client, _ = authed
    response = client.get("/health", headers=signed_headers("n1", "GET", "/health"))
    assert response.status_code == 200


def test_response_signature_is_verifiable(authed):
    client, _ = authed
    response = client.get("/health", headers=signed_headers("n2", "GET", "/health"))
    expected = auth.sign(
        TOKEN,
        auth.response_message("n2", response.headers["x-resp-ts"], 200, response.content),
    )
    assert response.headers["x-resp-sig"] == expected


def test_signed_post_with_a_body_is_accepted(authed, monkeypatch):
    client, _ = authed
    monkeypatch.setattr(controls, "set_volume", lambda level: (True, level))
    body = b'{"level":42}'
    response = client.post(
        "/volume",
        content=body,
        headers={**signed_headers("n3", "POST", "/volume", body), "Content-Type": "application/json"},
    )
    assert response.status_code == 200


def test_signature_is_bound_to_the_body(authed, monkeypatch):
    client, _ = authed
    monkeypatch.setattr(controls, "set_volume", lambda level: (True, level))
    headers = signed_headers("n4", "POST", "/volume", b'{"level":42}')
    response = client.post(
        "/volume",
        content=b'{"level":100}',  # body swapped after signing
        headers={**headers, "Content-Type": "application/json"},
    )
    assert response.status_code == 401
    assert response.json()["detail"] == "bad signature"


def test_signature_is_bound_to_the_path(authed):
    client, _ = authed
    headers = signed_headers("n5", "GET", "/health")
    assert client.get("/status", headers=headers).status_code == 401


def test_replay_is_rejected(authed):
    client, _ = authed
    headers = signed_headers("n6", "GET", "/health")
    assert client.get("/health", headers=headers).status_code == 200
    assert client.get("/health", headers=headers).status_code == 401


def test_error_responses_are_signed_too(authed):
    # The app rejects the request, but the client still has to be able to tell
    # a real 401 from an impostor's.
    client, _ = authed
    response = client.get("/health", headers={"X-Auth-Ts": "1", "X-Auth-Nonce": "n7"})
    assert response.status_code == 401
    expected = auth.sign(
        TOKEN,
        auth.response_message("n7", response.headers["x-resp-ts"], 401, response.content),
    )
    assert response.headers["x-resp-sig"] == expected
