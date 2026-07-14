#!/usr/bin/env python3
"""
CMOS Remote Server - Desktop control API for Android remote app.

Endpoints:
- POST /mute - Toggle system audio mute
- POST /bluetooth - Toggle Bluetooth + connect Q30 headphones
- GET /status - Get current mute/bluetooth status
- GET /health - Health check for LAN detection

Runs on port 8201 as the logged-in desktop user for audio/BT access.
"""

import asyncio
import hashlib
import hmac
import os
import shutil
import subprocess
import time
import logging
from contextlib import asynccontextmanager
from typing import Optional

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from starlette.responses import Response


def _resolve(name: str, *fallbacks: str) -> str:
    """Resolve a command to an absolute path.

    Looks on PATH first (the systemd user unit sets PATH to include
    ~/.local/bin and /usr/bin), then tries known fallback locations, and
    finally returns the bare name so subprocess can still find it via PATH
    at exec time. Keeps the server portable across distros instead of
    hardcoding NixOS store paths.
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
WPCTL = _resolve("wpctl", "/usr/bin/wpctl")
BLUETOOTHCTL = _resolve("bluetoothctl", "/usr/bin/bluetoothctl")
BT_TOGGLE = _resolve("bt-toggle", "~/.local/bin/bt-toggle")
SYSTEMCTL = _resolve("systemctl", "/usr/bin/systemctl")

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(levelname)s - %(message)s"
)
logger = logging.getLogger(__name__)


# --- HMAC challenge-response auth ---------------------------------------------
# Shared secret supplied via the environment (injected from 1Password at install
# time). When unset, the server runs open (no auth) for backward compatibility.
#
# Each request carries X-Auth-Ts / X-Auth-Nonce / X-Auth-Sig, where the sig is
# HMAC-SHA256(token, "ts\nnonce\nMETHOD\npath\nsha256(body)"). Binding method,
# path and body means a captured signature can't be replayed on another request,
# and the nonce cache blocks replay of the same one. Every response is signed
# with HMAC-SHA256(token, "nonce\nresp_ts\nstatus\nsha256(body)") so the client
# can confirm it reached the real server (not an impostor at the same address).
# The secret itself never travels on the wire.
AUTH_TOKEN = os.environ.get("CMOS_REMOTE_TOKEN", "").strip().encode()
AUTH_WINDOW = 60  # seconds of tolerated clock skew / request lifetime

if not AUTH_TOKEN:
    logger.warning("CMOS_REMOTE_TOKEN not set - running WITHOUT authentication")

# Replay cache: nonce -> expiry (epoch seconds)
_seen_nonces: dict[str, int] = {}


def _hmac_hex(msg: str) -> str:
    return hmac.new(AUTH_TOKEN, msg.encode(), hashlib.sha256).hexdigest()


def _sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _prune_nonces(now: int) -> None:
    for nonce, expiry in list(_seen_nonces.items()):
        if expiry <= now:
            del _seen_nonces[nonce]


async def require_auth(request: Request) -> None:
    """Verify a request's HMAC signature; raise 401 on any failure."""
    if not AUTH_TOKEN or request.method == "OPTIONS":
        return

    ts = request.headers.get("x-auth-ts")
    nonce = request.headers.get("x-auth-nonce")
    sig = request.headers.get("x-auth-sig")
    if not (ts and nonce and sig):
        raise HTTPException(status_code=401, detail="missing auth headers")

    try:
        ts_int = int(ts)
    except ValueError:
        raise HTTPException(status_code=401, detail="bad timestamp")

    now = int(time.time())
    if abs(now - ts_int) > AUTH_WINDOW:
        raise HTTPException(status_code=401, detail="stale request")

    _prune_nonces(now)
    if nonce in _seen_nonces:
        raise HTTPException(status_code=401, detail="replayed nonce")

    body = await request.body()
    expected = _hmac_hex(
        "\n".join([ts, nonce, request.method.upper(), request.url.path, _sha256_hex(body)])
    )
    if not hmac.compare_digest(expected, sig):
        raise HTTPException(status_code=401, detail="bad signature")

    _seen_nonces[nonce] = now + AUTH_WINDOW


class StatusResponse(BaseModel):
    """Current system status."""
    muted: bool
    volume: int  # Volume level 0-100
    bluetooth_on: bool
    bluetooth_connected: Optional[str] = None


class VolumeRequest(BaseModel):
    """Request to set volume level."""
    level: int  # 0-100  # Device name if connected


class ActionResponse(BaseModel):
    """Response from an action endpoint."""
    success: bool
    message: str
    new_state: Optional[bool] = None


def get_mute_status() -> bool:
    """Check if system audio is muted using wpctl."""
    try:
        result = subprocess.run(
            [WPCTL, "get-volume", "@DEFAULT_AUDIO_SINK@"],
            capture_output=True,
            text=True,
            timeout=5
        )
        # Output: "Volume: 0.50" or "Volume: 0.50 [MUTED]"
        return "[MUTED]" in result.stdout
    except Exception as e:
        logger.error(f"Failed to get mute status: {e}")
        return False


def get_volume() -> int:
    """Get current volume level (0-100) using wpctl."""
    try:
        result = subprocess.run(
            [WPCTL, "get-volume", "@DEFAULT_AUDIO_SINK@"],
            capture_output=True,
            text=True,
            timeout=5
        )
        # Output: "Volume: 0.50" or "Volume: 0.50 [MUTED]"
        # Parse the decimal value and convert to percentage
        parts = result.stdout.strip().split()
        if len(parts) >= 2:
            volume_decimal = float(parts[1])
            return int(round(volume_decimal * 100))
        return 0
    except Exception as e:
        logger.error(f"Failed to get volume: {e}")
        return 0


def set_volume(level: int) -> tuple[bool, int]:
    """Set volume level (0-100). Returns (success, new_level)."""
    try:
        # Clamp to 0-100 range
        level = max(0, min(100, level))
        # wpctl expects decimal (0.0 to 1.0)
        volume_decimal = level / 100.0

        subprocess.run(
            [WPCTL, "set-volume", "@DEFAULT_AUDIO_SINK@", str(volume_decimal)],
            check=True,
            timeout=5
        )
        new_level = get_volume()
        logger.info(f"Volume set to {new_level}%")
        return True, new_level
    except Exception as e:
        logger.error(f"Failed to set volume: {e}")
        return False, get_volume()


def toggle_mute() -> tuple[bool, bool]:
    """Toggle system mute. Returns (success, new_muted_state)."""
    try:
        subprocess.run(
            [WPCTL, "set-mute", "@DEFAULT_AUDIO_SINK@", "toggle"],
            check=True,
            timeout=5
        )
        new_state = get_mute_status()
        logger.info(f"Mute toggled, new state: {'muted' if new_state else 'unmuted'}")
        return True, new_state
    except Exception as e:
        logger.error(f"Failed to toggle mute: {e}")
        return False, get_mute_status()


def get_bluetooth_status() -> tuple[bool, Optional[str]]:
    """
    Check Bluetooth status using bluetoothctl.
    Returns (is_powered_on, connected_device_name or None).
    """
    try:
        # Check if Bluetooth is powered on
        result = subprocess.run(
            [BLUETOOTHCTL, "show"],
            capture_output=True,
            text=True,
            timeout=5
        )
        powered_on = "Powered: yes" in result.stdout

        # Check for connected devices
        connected_device = None
        if powered_on:
            result = subprocess.run(
                [BLUETOOTHCTL, "devices", "Connected"],
                capture_output=True,
                text=True,
                timeout=5
            )
            # Output: "Device AA:BB:CC:DD:EE:FF My Headphones"
            if result.stdout.strip():
                lines = result.stdout.strip().split("\n")
                if lines:
                    # Extract device name (everything after MAC address)
                    parts = lines[0].split(" ", 2)
                    if len(parts) >= 3:
                        connected_device = parts[2]

        return powered_on, connected_device
    except Exception as e:
        logger.error(f"Failed to get Bluetooth status: {e}")
        return False, None


def toggle_bluetooth() -> tuple[bool, bool, Optional[str]]:
    """
    Toggle Bluetooth using bt-toggle script.
    Returns (success, new_powered_state, connected_device).
    """
    try:
        # bt-toggle script handles the logic:
        # - If BT on -> turn off
        # - If BT off -> turn on and connect Q30
        result = subprocess.run(
            [BT_TOGGLE],
            capture_output=True,
            text=True,
            timeout=30  # Longer timeout for BT connection
        )

        if result.returncode != 0:
            logger.error(f"bt-toggle failed: {result.stderr}")
            powered_on, connected = get_bluetooth_status()
            return False, powered_on, connected

        # Get new state after toggle
        powered_on, connected = get_bluetooth_status()
        logger.info(f"Bluetooth toggled, powered: {powered_on}, connected: {connected}")
        return True, powered_on, connected
    except Exception as e:
        logger.error(f"Failed to toggle Bluetooth: {e}")
        powered_on, connected = get_bluetooth_status()
        return False, powered_on, connected


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Startup and shutdown events."""
    logger.info("CMOS Remote Server starting...")
    yield
    logger.info("CMOS Remote Server shutting down...")


app = FastAPI(
    title="CMOS Remote Server",
    description="Desktop control API for Android remote app",
    version="1.0.0",
    lifespan=lifespan,
    dependencies=[Depends(require_auth)],
)

# Allow CORS for Android app
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def sign_response(request: Request, call_next):
    """Sign every response so the client can confirm it reached the real server."""
    response = await call_next(request)
    if not AUTH_TOKEN:
        return response
    nonce = request.headers.get("x-auth-nonce")
    if not nonce:
        return response

    body = b""
    async for chunk in response.body_iterator:
        body += chunk

    resp_ts = str(int(time.time()))
    sig = _hmac_hex("\n".join([nonce, resp_ts, str(response.status_code), _sha256_hex(body)]))

    headers = dict(response.headers)
    headers.pop("content-length", None)
    headers["x-resp-ts"] = resp_ts
    headers["x-resp-sig"] = sig
    return Response(
        content=body,
        status_code=response.status_code,
        headers=headers,
        media_type=response.media_type,
    )


@app.get("/health")
async def health_check():
    """Health check endpoint for LAN detection."""
    return {"status": "ok", "service": "cmos-remote"}


@app.get("/status", response_model=StatusResponse)
async def get_status():
    """Get current system status (mute, volume, bluetooth)."""
    muted = get_mute_status()
    volume = get_volume()
    bt_on, bt_connected = get_bluetooth_status()

    return StatusResponse(
        muted=muted,
        volume=volume,
        bluetooth_on=bt_on,
        bluetooth_connected=bt_connected
    )


@app.post("/mute", response_model=ActionResponse)
async def toggle_mute_endpoint():
    """Toggle system audio mute."""
    success, new_state = toggle_mute()

    if success:
        return ActionResponse(
            success=True,
            message="Muted" if new_state else "Unmuted",
            new_state=new_state
        )
    else:
        raise HTTPException(
            status_code=500,
            detail="Failed to toggle mute"
        )


class VolumeResponse(BaseModel):
    """Response from volume set endpoint."""
    success: bool
    level: int
    message: str


@app.post("/volume", response_model=VolumeResponse)
async def set_volume_endpoint(request: VolumeRequest):
    """Set system volume level (0-100)."""
    success, new_level = set_volume(request.level)

    if success:
        return VolumeResponse(
            success=True,
            level=new_level,
            message=f"Volume set to {new_level}%"
        )
    else:
        raise HTTPException(
            status_code=500,
            detail="Failed to set volume"
        )


@app.get("/volume")
async def get_volume_endpoint():
    """Get current volume level."""
    return {"level": get_volume()}


@app.post("/bluetooth", response_model=ActionResponse)
async def toggle_bluetooth_endpoint():
    """Toggle Bluetooth and connect Q30 headphones."""
    success, powered_on, connected = toggle_bluetooth()

    if success:
        if powered_on and connected:
            message = f"Bluetooth ON, connected to {connected}"
        elif powered_on:
            message = "Bluetooth ON"
        else:
            message = "Bluetooth OFF"

        return ActionResponse(
            success=True,
            message=message,
            new_state=powered_on
        )
    else:
        raise HTTPException(
            status_code=500,
            detail="Failed to toggle Bluetooth"
        )


def trigger_screen_off() -> bool:
    """Trigger screen off toggle via systemd user service."""
    try:
        result = subprocess.run(
            [SYSTEMCTL, "--user", "start", "screen-off-toggle.service"],
            capture_output=True,
            text=True,
            timeout=10
        )
        if result.returncode == 0:
            logger.info("Screen off toggle triggered successfully")
            return True
        else:
            logger.error(f"Screen off toggle failed: {result.stderr}")
            return False
    except Exception as e:
        logger.error(f"Failed to trigger screen off: {e}")
        return False


@app.post("/screen-off", response_model=ActionResponse)
async def screen_off_endpoint():
    """Turn off screen and mute notifications (Meta+F10 equivalent)."""
    success = trigger_screen_off()

    if success:
        return ActionResponse(
            success=True,
            message="Screen off triggered",
            new_state=None
        )
    else:
        raise HTTPException(
            status_code=500,
            detail="Failed to trigger screen off"
        )


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8201)
