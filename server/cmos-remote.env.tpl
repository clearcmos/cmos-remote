# Template resolved by `op inject` at install time (see install.sh).
# The reference points at the 1Password `api` vault item read via the SVC_API
# service account. The resolved file lands at ~/.config/cmos-remote/env (0600)
# and is loaded by the systemd unit; the secret is never stored in this repo.
CMOS_REMOTE_TOKEN=op://api/CMOS_REMOTE/password
