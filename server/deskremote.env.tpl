# Template resolved by `op inject` at install time (see install.sh).
# The reference points at the 1Password `api` vault item read via the SVC_API
# service account. The resolved file lands at ~/.config/deskremote/env (0600)
# and is loaded by the systemd unit; the secret is never stored in this repo.
DESKREMOTE_TOKEN=op://api/DESKREMOTE/password
