"""Tests for the HMAC authentication core.

The vector tests are the ones that matter most: they pin the exact bytes that
get signed, and android/app/src/test/.../HmacInterceptorTest.kt asserts the same
file. A divergence between the two implementations otherwise surfaces only as
"Disconnected" in the app, with nothing in either log saying why.
"""

import pytest
from fastapi import HTTPException

import auth

TOKEN = b"deskremote-test-token-not-a-real-secret"


def make_auth(now: int = 1750000000, token: bytes = TOKEN, window: int = 60) -> auth.Authenticator:
    """Authenticator with a frozen clock, so freshness tests are deterministic."""
    return auth.Authenticator(token, window=window, clock=lambda: now)


# --- canonical vectors --------------------------------------------------------


def test_request_vectors_match_spec(vectors):
    assert vectors["token"] == TOKEN.decode()
    for case in vectors["requests"]:
        message = auth.request_message(
            case["ts"], case["nonce"], case["method"], case["path"], case["body"].encode()
        )
        assert message == case["message"], case["name"]
        assert auth.sign(TOKEN, message) == case["signature"], case["name"]


def test_response_vectors_match_spec(vectors):
    for case in vectors["responses"]:
        message = auth.response_message(case["nonce"], case["resp_ts"], case["status"], case["body"].encode())
        assert message == case["message"], case["name"]
        assert auth.sign(TOKEN, message) == case["signature"], case["name"]


def test_verify_accepts_a_vector_signature(vectors):
    case = vectors["requests"][0]
    a = make_auth(now=int(case["ts"]))
    a.verify(
        case["ts"], case["nonce"], case["signature"], case["method"], case["path"], case["body"].encode()
    )


def test_sign_response_uses_the_clock(vectors):
    case = vectors["responses"][0]
    a = make_auth(now=int(case["resp_ts"]))
    resp_ts, sig = a.sign_response(case["nonce"], case["status"], case["body"].encode())
    assert resp_ts == case["resp_ts"]
    assert sig == case["signature"]


# --- message construction -----------------------------------------------------


def test_request_message_uppercases_the_method():
    assert auth.request_message("1", "n", "post", "/x", b"").split("\n")[2] == "POST"


def test_request_message_binds_body():
    with_body = auth.request_message("1", "n", "POST", "/x", b"a")
    without = auth.request_message("1", "n", "POST", "/x", b"")
    assert with_body != without


def test_request_message_binds_path_and_method():
    base = auth.request_message("1", "n", "POST", "/x", b"")
    assert auth.request_message("1", "n", "POST", "/y", b"") != base
    assert auth.request_message("1", "n", "GET", "/x", b"") != base


# --- verification failures ----------------------------------------------------


def valid_args(a: auth.Authenticator, now: int = 1750000000, nonce: str = "n1", body: bytes = b""):
    ts = str(now)
    sig = a.sign(auth.request_message(ts, nonce, "GET", "/status", body))
    return (ts, nonce, sig, "GET", "/status", body)


def test_bad_timestamp_is_rejected():
    a = make_auth()
    with pytest.raises(HTTPException) as exc:
        a.verify("not-a-number", "n1", "sig", "GET", "/status", b"")
    assert exc.value.status_code == 401
    assert exc.value.detail == "bad timestamp"


@pytest.mark.parametrize("skew", [61, -61, 3600])
def test_stale_request_is_rejected(skew):
    a = make_auth()
    args = valid_args(a, now=1750000000 + skew)
    with pytest.raises(HTTPException) as exc:
        a.verify(*args)
    assert exc.value.detail == "stale request"


@pytest.mark.parametrize("skew", [0, 59, -59])
def test_requests_inside_the_window_are_accepted(skew):
    a = make_auth()
    a.verify(*valid_args(a, now=1750000000 + skew, nonce=f"n{skew}"))


def test_bad_signature_is_rejected():
    a = make_auth()
    ts, nonce, _sig, method, path, body = valid_args(a)
    with pytest.raises(HTTPException) as exc:
        a.verify(ts, nonce, "0" * 64, method, path, body)
    assert exc.value.detail == "bad signature"


def test_signature_from_a_different_token_is_rejected():
    a = make_auth()
    other = make_auth(token=b"a-different-secret")
    ts, nonce, sig, method, path, body = valid_args(other)
    with pytest.raises(HTTPException) as exc:
        a.verify(ts, nonce, sig, method, path, body)
    assert exc.value.detail == "bad signature"


def test_replayed_nonce_is_rejected():
    a = make_auth()
    args = valid_args(a)
    a.verify(*args)
    with pytest.raises(HTTPException) as exc:
        a.verify(*args)
    assert exc.value.detail == "replayed nonce"


def test_a_failed_signature_does_not_burn_the_nonce():
    # Otherwise an attacker who can see a nonce could lock out the real client
    # by replaying it with a garbage signature before the real request lands.
    a = make_auth()
    ts, nonce, sig, method, path, body = valid_args(a)
    with pytest.raises(HTTPException):
        a.verify(ts, nonce, "0" * 64, method, path, body)
    a.verify(ts, nonce, sig, method, path, body)


def test_expired_nonces_are_pruned():
    a = auth.Authenticator(TOKEN, window=60, clock=lambda: clock[0])
    clock = [1750000000]
    a.verify(*valid_args(a, now=clock[0]))
    assert len(a._seen_nonces) == 1
    # Past the window, the cache entry is dropped on the next verification.
    clock[0] = 1750000200
    a.verify(*valid_args(a, now=clock[0], nonce="n2"))
    assert list(a._seen_nonces) == ["n2"]


# --- open mode ----------------------------------------------------------------


def test_open_mode_reports_disabled():
    assert not auth.Authenticator(b"").enabled
    assert make_auth().enabled
