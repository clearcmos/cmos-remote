#!/usr/bin/env python3
"""Desk Remote Server - desktop control API for the Android remote app.

Endpoints:
- GET  /health     - health check for LAN detection
- GET  /status     - current mute/volume/Bluetooth state
- POST /mute       - toggle system audio mute
- POST /volume     - set volume level (0-100)
- POST /bluetooth  - toggle Bluetooth and connect the configured headphones
- POST /screen-off - turn off screens and enable DND

Runs on port 8201 as the logged-in desktop user, which is what gives it access
to that session's PipeWire, Bluetooth, and systemd user units.

Auth lives in auth.py, system commands in controls.py.
"""

from __future__ import annotations

import logging
import os
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from starlette.responses import Response

import controls
from auth import Authenticator

logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s")
logger = logging.getLogger(__name__)

# Shared secret from the environment (injected from 1Password by install.sh).
# Unset means the server runs open, which install.sh warns about.
authenticator = Authenticator(os.environ.get("DESKREMOTE_TOKEN", "").strip().encode())

if not authenticator.enabled:
    logger.warning("DESKREMOTE_TOKEN not set - running WITHOUT authentication")


class StatusResponse(BaseModel):
    """Current system status."""

    muted: bool
    volume: int  # 0-100
    bluetooth_on: bool
    bluetooth_connected: str | None = None  # device name, when connected


class VolumeRequest(BaseModel):
    """Request to set volume level."""

    level: int  # 0-100


class VolumeResponse(BaseModel):
    """Response from the volume endpoints."""

    success: bool
    level: int
    message: str


class ActionResponse(BaseModel):
    """Response from an action endpoint."""

    success: bool
    message: str
    new_state: bool | None = None


def require_command(command: str, hint: str) -> None:
    """503 with the command's name when a required helper is not installed.

    The endpoints that need something beyond PipeWire (the Bluetooth helper, the
    screen-off unit) are the ones most likely to be missing on a machine that is
    not the author's, so say which one it is rather than "failed".
    """
    if not controls.available(command):
        raise HTTPException(status_code=503, detail=f"{command} not found on PATH. {hint}")


async def authenticate(request: Request) -> None:
    """App-level auth dependency.

    Deliberately a thin wrapper rather than `Depends(authenticator)`: FastAPI
    captures the dependency object at app construction, and going through the
    module global keeps the active Authenticator swappable (tests do this).
    """
    await authenticator(request)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """Startup and shutdown events."""
    logger.info("Desk Remote Server starting...")
    yield
    logger.info("Desk Remote Server shutting down...")


app = FastAPI(
    title="Desk Remote Server",
    description="Desktop control API for Android remote app",
    version="1.0.0",
    lifespan=lifespan,
    dependencies=[Depends(authenticate)],
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
async def sign_response(request: Request, call_next: Callable[[Request], Awaitable[Response]]) -> Response:
    """Sign every response so the client can confirm it reached the real server."""
    response = await call_next(request)
    nonce = request.headers.get("x-auth-nonce")
    if not authenticator.enabled or not nonce:
        return response

    body = b""
    async for chunk in response.body_iterator:  # type: ignore[attr-defined]
        body += chunk

    resp_ts, sig = authenticator.sign_response(nonce, response.status_code, body)
    headers = dict(response.headers)
    # Dropped because the re-wrapped response recomputes it.
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
async def health_check() -> dict[str, str]:
    """Health check endpoint for LAN detection."""
    return {"status": "ok", "service": "deskremote"}


@app.get("/status", response_model=StatusResponse)
async def get_status() -> StatusResponse:
    """Get current system status (mute, volume, bluetooth)."""
    bt_on, bt_connected = controls.get_bluetooth_status()
    return StatusResponse(
        muted=controls.get_mute_status(),
        volume=controls.get_volume(),
        bluetooth_on=bt_on,
        bluetooth_connected=bt_connected,
    )


@app.post("/mute", response_model=ActionResponse)
async def toggle_mute_endpoint() -> ActionResponse:
    """Toggle system audio mute."""
    require_command(controls.WPCTL, "Install PipeWire/WirePlumber.")
    success, new_state = controls.toggle_mute()
    if not success:
        raise HTTPException(status_code=500, detail="Failed to toggle mute")
    return ActionResponse(
        success=True,
        message="Muted" if new_state else "Unmuted",
        new_state=new_state,
    )


@app.post("/volume", response_model=VolumeResponse)
async def set_volume_endpoint(request: VolumeRequest) -> VolumeResponse:
    """Set system volume level (0-100)."""
    require_command(controls.WPCTL, "Install PipeWire/WirePlumber.")
    success, new_level = controls.set_volume(request.level)
    if not success:
        raise HTTPException(status_code=500, detail="Failed to set volume")
    return VolumeResponse(success=True, level=new_level, message=f"Volume set to {new_level}%")


@app.get("/volume")
async def get_volume_endpoint() -> dict[str, int]:
    """Get current volume level."""
    return {"level": controls.get_volume()}


@app.post("/bluetooth", response_model=ActionResponse)
async def toggle_bluetooth_endpoint() -> ActionResponse:
    """Toggle Bluetooth and connect the configured headphones."""
    require_command(
        controls.BT_TOGGLE,
        "This helper is not part of the repo; supply your own or drop this endpoint. "
        "See README, 'Adapting This for Your Own Desktop'.",
    )
    success, powered_on, connected = controls.toggle_bluetooth()
    if not success:
        raise HTTPException(status_code=500, detail="Failed to toggle Bluetooth")

    if powered_on and connected:
        message = f"Bluetooth ON, connected to {connected}"
    elif powered_on:
        message = "Bluetooth ON"
    else:
        message = "Bluetooth OFF"
    return ActionResponse(success=True, message=message, new_state=powered_on)


@app.post("/screen-off", response_model=ActionResponse)
async def screen_off_endpoint() -> ActionResponse:
    """Turn off screens and enable DND (Meta+F10 equivalent)."""
    require_command(controls.SYSTEMCTL, "systemd is required for this endpoint.")
    if not controls.trigger_screen_off():
        raise HTTPException(status_code=500, detail="Failed to trigger screen off")
    return ActionResponse(success=True, message="Screen off triggered", new_state=None)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8201)
