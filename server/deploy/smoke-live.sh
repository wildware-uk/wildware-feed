#!/usr/bin/env bash
# After each deploy: the customer journey against the live server, over HTTP (no browser).
# It makes two throwaway teams with the admin API, sets their owners' passwords, signs in as the
# apps do, invites a member, checks no team's password opens another, and deletes both teams.
#
#   ORIGIN=https://feed.example.com ADMIN_KEY_FILE=~/.config/feed/admin-key server/deploy/smoke-live.sh
#
# ORIGIN is where the admin API answers (TEAM_DOMAIN, or ADMIN_HOST). ADMIN_KEY_FILE holds one of
# the server's ADMIN_API_KEYS; it is read by the script and never printed. With HOME_ORIGIN and
# HOME_PASSWORD_FILE (the home team's address and owner password) it also checks the home team still
# signs in and its password opens no hosted team. Exits 1 if any step failed.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
: "${ORIGIN:?ORIGIN: the admin API origin, such as https://feed.example.com}"
: "${ADMIN_KEY_FILE:?ADMIN_KEY_FILE: a file holding the admin signing key}"
[ -r "$ADMIN_KEY_FILE" ] || { echo "smoke-live: can't read ADMIN_KEY_FILE"; exit 1; }
if [ -n "${HOME_PASSWORD_FILE:-}" ] && [ -z "${HOME_ORIGIN:-}" ]; then echo "smoke-live: HOME_PASSWORD_FILE needs HOME_ORIGIN"; exit 1; fi
exec timeout 300 node "$here/smoke-live.mjs"
