"""HMAC challenge-response authentication for the Desk Remote server.

The shared secret never travels on the wire. Each request carries
X-Auth-Ts / X-Auth-Nonce / X-Auth-Sig, where the signature is

    HMAC-SHA256(token, "ts\\nnonce\\nMETHOD\\npath\\nsha256(body)")

Binding method, path and body means a captured signature cannot be replayed on
a different request, and the nonce cache blocks replay of the same one. Every
response is signed with

    HMAC-SHA256(token, "nonce\\nresp_ts\\nstatus\\nsha256(body)")

so the client can confirm it reached the real server rather than an impostor
sitting at the same address.

Both message formats are pinned by spec/hmac-vectors.json, which the Python and
Kotlin test suites assert against independently. Change a format here and that
file (plus the app) has to change with it.
"""

from __future__ import annotations

import hashlib
import hmac
import time
from collections.abc import Callable

from fastapi import HTTPException, Request

# Seconds of tolerated clock skew, and the lifetime of a signature.
DEFAULT_WINDOW = 60


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def request_message(ts: str, nonce: str, method: str, path: str, body: bytes) -> str:
    """Build the string a request signature is computed over."""
    return "\n".join([ts, nonce, method.upper(), path, sha256_hex(body)])


def response_message(nonce: str, resp_ts: str, status: int, body: bytes) -> str:
    """Build the string a response signature is computed over."""
    return "\n".join([nonce, resp_ts, str(status), sha256_hex(body)])


def sign(token: bytes, message: str) -> str:
    return hmac.new(token, message.encode(), hashlib.sha256).hexdigest()


class Authenticator:
    """Verifies request signatures and signs responses.

    With an empty token the server runs open: verification is skipped and
    responses are left unsigned. Both halves check the same flag, so auth can
    never end up half-enabled.

    Usable as a FastAPI dependency: `dependencies=[Depends(authenticator)]`.
    """

    def __init__(
        self,
        token: bytes,
        window: int = DEFAULT_WINDOW,
        clock: Callable[[], int] = lambda: int(time.time()),
    ) -> None:
        self.token = token
        self.window = window
        self.clock = clock
        # nonce -> expiry (epoch seconds)
        self._seen_nonces: dict[str, int] = {}

    @property
    def enabled(self) -> bool:
        return bool(self.token)

    def _prune_nonces(self, now: int) -> None:
        for nonce, expiry in list(self._seen_nonces.items()):
            if expiry <= now:
                del self._seen_nonces[nonce]

    def sign(self, message: str) -> str:
        return sign(self.token, message)

    def verify(self, ts: str, nonce: str, sig: str, method: str, path: str, body: bytes) -> None:
        """Raise HTTPException(401) unless the request signature checks out."""
        try:
            ts_int = int(ts)
        except ValueError:
            # "from None": the client never needs the parse error, only the 401.
            raise HTTPException(status_code=401, detail="bad timestamp") from None

        now = self.clock()
        if abs(now - ts_int) > self.window:
            raise HTTPException(status_code=401, detail="stale request")

        self._prune_nonces(now)
        if nonce in self._seen_nonces:
            raise HTTPException(status_code=401, detail="replayed nonce")

        expected = self.sign(request_message(ts, nonce, method, path, body))
        if not hmac.compare_digest(expected, sig):
            raise HTTPException(status_code=401, detail="bad signature")

        # Recorded only after the signature checks out, so an attacker cannot
        # burn a legitimate client's nonce by replaying it with a bad signature.
        self._seen_nonces[nonce] = now + self.window

    def sign_response(self, nonce: str, status: int, body: bytes) -> tuple[str, str]:
        """Return (resp_ts, signature) for a response to `nonce`."""
        resp_ts = str(self.clock())
        return resp_ts, self.sign(response_message(nonce, resp_ts, status, body))

    async def __call__(self, request: Request) -> None:
        """FastAPI dependency: authenticate the incoming request."""
        if not self.enabled or request.method == "OPTIONS":
            return

        ts = request.headers.get("x-auth-ts")
        nonce = request.headers.get("x-auth-nonce")
        sig = request.headers.get("x-auth-sig")
        if not (ts and nonce and sig):
            raise HTTPException(status_code=401, detail="missing auth headers")

        self.verify(ts, nonce, sig, request.method, request.url.path, await request.body())
