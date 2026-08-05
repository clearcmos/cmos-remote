#!/usr/bin/env bash
# Install the Desk Remote server as a systemd *user* service.
#
# Idempotent: safe to re-run (also the way to redeploy after code changes).
# Creates the venv, installs deps, provisions the auth token, deploys and
# enables the user unit.
#
# Requires: systemd (user instance), python3 with venv, and PipeWire for the
# audio endpoints. Works on any distro with those; nothing here is Arch-specific.
#
# Auth token, in order of preference:
#   1. DESKREMOTE_TOKEN in the environment  -> written to the env file
#      e.g.  DESKREMOTE_TOKEN="$(openssl rand -hex 32)" ./server/install.sh
#   2. 1Password via `op inject` (the author's setup; needs op plus a service
#      account token at ~/.config/op/SVC_API.token)
#   3. Neither: the server runs open, with a warning. Enter the same token in
#      the app's settings to enable auth on both sides.
#
# Does NOT touch the firewall: restrict port 8201 to your LAN with whatever
# firewall you use. See README.md.
set -euo pipefail

SERVER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV="$SERVER_DIR/.venv"
UNIT_SRC="$SERVER_DIR/deskremote.service"
UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
UNIT_DST="$UNIT_DIR/deskremote.service"

echo "==> Server dir: $SERVER_DIR"

# 1. Python venv + dependencies
if [[ ! -d "$VENV" ]]; then
    echo "==> Creating venv at $VENV"
    python3 -m venv "$VENV"
fi
# Installs from the hash-locked requirements.lock, not requirements.txt: with
# --require-hashes pip refuses anything whose artifact does not match the
# recorded sha256, so a compromised or swapped wheel fails the install instead
# of landing on the host. Regenerate the lock after editing requirements.txt:
#   uv pip compile requirements.txt -o requirements.lock --universal \
#       --generate-hashes --python-version 3.11
echo "==> Installing Python dependencies (hash-verified)"
"$VENV/bin/pip" install -q --upgrade pip
"$VENV/bin/pip" install -q --require-hashes -r "$SERVER_DIR/requirements.lock"

# 2. Provision the shared auth token into ~/.config/deskremote/env (0600),
#    which the unit loads via EnvironmentFile.
ENV_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/deskremote"
ENV_FILE="$ENV_DIR/env"
ENV_TPL="$SERVER_DIR/deskremote.env.tpl"
mkdir -p "$ENV_DIR"; chmod 700 "$ENV_DIR"

write_env_file() {
    # umask first: the file must never exist world-readable, even briefly.
    ( umask 077; printf 'DESKREMOTE_TOKEN=%s\n' "$1" > "$ENV_FILE" )
    chmod 600 "$ENV_FILE"
}

if [[ -n "${DESKREMOTE_TOKEN:-}" ]]; then
    write_env_file "$DESKREMOTE_TOKEN"
    echo "==> Auth token taken from the environment, written to $ENV_FILE"
elif command -v op >/dev/null 2>&1 && [[ -f "$HOME/.config/op/SVC_API.token" ]]; then
    # shellcheck disable=SC1091
    source "$HOME/.config/op/SVC_API.token"; export OP_SERVICE_ACCOUNT_TOKEN
    if op inject -f -i "$ENV_TPL" -o "$ENV_FILE" 2>/dev/null; then
        chmod 600 "$ENV_FILE"
        echo "==> Auth token injected from 1Password into $ENV_FILE"
    else
        echo "WARNING: op inject failed (is op://api/DESKREMOTE/password created?)."
        echo "         Server will run WITHOUT auth until this resolves. See README."
    fi
elif [[ -f "$ENV_FILE" ]]; then
    echo "==> Keeping the existing auth token at $ENV_FILE"
else
    echo "WARNING: no auth token provisioned; the server will run WITHOUT auth."
    echo "         To enable it, re-run with a token in the environment:"
    echo "           DESKREMOTE_TOKEN=\"\$(openssl rand -hex 32)\" ./server/install.sh"
    echo "         then enter the same value in the app's settings."
fi

# 3. Deploy the user unit, substituting this checkout's location so the repo can
#    live anywhere.
echo "==> Deploying user unit to $UNIT_DST"
mkdir -p "$UNIT_DIR"
sed "s|@SERVER_DIR@|$SERVER_DIR|g" "$UNIT_SRC" > "$UNIT_DST"
if grep -q '@SERVER_DIR@' "$UNIT_DST"; then
    echo "ERROR: unit still contains the @SERVER_DIR@ placeholder." >&2
    exit 1
fi

# 4. Enable + (re)start to pick up any code changes
systemctl --user daemon-reload
systemctl --user enable deskremote.service
systemctl --user restart deskremote.service || true

echo
echo "==> Status:"
systemctl --user --no-pager --lines=0 status deskremote.service || true
echo
echo "Test:  curl http://127.0.0.1:8201/health   (returns 401 once auth is on)"
echo
echo "First install? Open port 8201 to your LAN only. The author's mechanism is"
echo "a rule in ~/arch/config/nftables/nftables.conf, applied with:"
echo "  sudo install -m644 ~/arch/config/nftables/nftables.conf /etc/nftables.conf && sudo systemctl restart nftables"
