#!/usr/bin/env bash
# Install the CMOS Remote server as a systemd *user* service on Arch.
#
# Idempotent: safe to re-run (also the way to redeploy after code changes).
# Creates the venv, installs deps, deploys + enables the user unit.
#
# Does NOT touch the firewall. Port 8201 is opened separately in
# ~/arch/config/nftables/nftables.conf (this repo owns the app; ~/arch only
# knows the port is open). See README.md.
set -euo pipefail

SERVER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV="$SERVER_DIR/.venv"
UNIT_SRC="$SERVER_DIR/cmos-remote.service"
UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
UNIT_DST="$UNIT_DIR/cmos-remote.service"

echo "==> Server dir: $SERVER_DIR"

# 1. Python venv + dependencies
if [[ ! -d "$VENV" ]]; then
    echo "==> Creating venv at $VENV"
    python3 -m venv "$VENV"
fi
echo "==> Installing Python dependencies"
"$VENV/bin/pip" install -q --upgrade pip
"$VENV/bin/pip" install -q -r "$SERVER_DIR/requirements.txt"

# 2. Provision the shared auth token from 1Password (op inject).
#    Resolves cmos-remote.env.tpl -> ~/.config/cmos-remote/env (0600). If op,
#    the SVC_API token, or the vault item is unavailable, the server runs open
#    (the unit's EnvironmentFile is optional) until a re-run succeeds.
ENV_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/cmos-remote"
ENV_FILE="$ENV_DIR/env"
ENV_TPL="$SERVER_DIR/cmos-remote.env.tpl"
mkdir -p "$ENV_DIR"; chmod 700 "$ENV_DIR"
if command -v op >/dev/null 2>&1 && [[ -f "$HOME/.config/op/SVC_API.token" ]]; then
    # shellcheck disable=SC1091
    source "$HOME/.config/op/SVC_API.token"; export OP_SERVICE_ACCOUNT_TOKEN
    if op inject -f -i "$ENV_TPL" -o "$ENV_FILE" 2>/dev/null; then
        chmod 600 "$ENV_FILE"
        echo "==> Auth token injected from 1Password into $ENV_FILE"
    else
        echo "WARNING: op inject failed (is op://api/CMOS_REMOTE/password created?)."
        echo "         Server will run WITHOUT auth until this resolves. See README."
    fi
else
    echo "WARNING: op CLI or ~/.config/op/SVC_API.token not found."
    echo "         Server will run WITHOUT auth (no token provisioned). See README."
fi

# 3. Deploy the user unit, rewriting the paths to this checkout's real
#    location (so it works even if the repo lives somewhere else).
echo "==> Deploying user unit to $UNIT_DST"
mkdir -p "$UNIT_DIR"
sed "s|/mnt/data/git/cmos-remote/server|$SERVER_DIR|g" "$UNIT_SRC" > "$UNIT_DST"

# 4. Enable + (re)start to pick up any code changes
systemctl --user daemon-reload
systemctl --user enable cmos-remote.service
systemctl --user restart cmos-remote.service || true

echo
echo "==> Status:"
systemctl --user --no-pager --lines=0 status cmos-remote.service || true
echo
echo "Test:  curl http://127.0.0.1:8201/health"
echo
echo "If this is a first install, open port 8201 on the LAN by adding the rule"
echo "to ~/arch/config/nftables/nftables.conf, then apply it with:"
echo "  sudo install -m644 ~/arch/config/nftables/nftables.conf /etc/nftables.conf && sudo systemctl restart nftables"
