#!/usr/bin/env bash
# The web app end to end, in a headless Chromium against a throwaway local server: it draws the
# sign-in, signs in with the owner password, shows the feed an agent posted to, sends a reply the
# agent can read, and plays a video. Saves screenshots (light and dark, desktop and phone) to
# SHOTS (default: server/build/web-smoke). Leaves nothing running.
#
#   server/deploy/smoke-web.sh                 (builds the distribution first)
#   SKIP_DIST=1 server/deploy/smoke-web.sh
#   CHROME=/path/to/chrome server/deploy/smoke-web.sh   (use that browser, download none)
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
port="${SMOKE_PORT:-18191}"
shots="${SHOTS:-$root/server/build/web-smoke}"
work="$(mktemp -d)"
pid=""
cleanup() { [ -n "$pid" ] && kill "$pid" 2>/dev/null; rm -rf "$work"; }
trap cleanup EXIT

if [ -z "${SKIP_DIST:-}" ]; then
    (cd "$root" && ./gradlew --no-daemon -q :server:installDist)
fi
bin="$root/server/build/install/feed-server/bin/feed-server"
[ -f "$root/server/build/install/feed-server/web/index.html" ] || { echo "smoke-web: the distribution has no web app"; exit 1; }

secret() { od -An -N24 -tx1 /dev/urandom | tr -d ' \n'; }
export ADMIN_PASSWORD_HASH="$("$bin" hash-password smoke-password 2>/dev/null | sed -n 1p)"
export SESSION_SECRET="$(secret)" TOKEN_SECRET="$(secret)"
export PUBLIC_BASE_URL="http://127.0.0.1:$port" PORT="$port" HOST=127.0.0.1 DATA_DIR="$work/data"
"$bin" >"$work/server.log" 2>&1 &
pid=$!
base="http://127.0.0.1:$port"
for _ in $(seq 1 60); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' "$base/api/server" || true)" = "200" ] && break
    sleep 1
done
fail() { echo "smoke-web: $*"; tail -20 "$work/server.log"; exit 1; }
[ "$(curl -s -o /dev/null -w '%{content_type}' "$base/")" = "text/html; charset=UTF-8" ] || fail "/ is not the web app"

token="$("$bin" mint-token smoke-agent 2>/dev/null | grep -E '^[A-Za-z0-9_-]{43}$')"
[ -n "$token" ] || fail "mint-token printed no token"

# Playwright, in a folder of its own. CHROME skips its browser download.
(cd "$work" && npm init -y >/dev/null && timeout 300 npm install --no-audit --no-fund --silent playwright@1.65.1 >/dev/null)
if [ -z "${CHROME:-}" ]; then (cd "$work" && timeout 600 npx playwright install --with-deps chromium >/dev/null); fi

ffmpeg -loglevel error -y -f lavfi -i testsrc=duration=3:size=640x360:rate=25 -pix_fmt yuv420p "$work/clip.mp4"
mkdir -p "$shots"
shots="$(cd "$shots" && pwd)"
cp "$root/server/deploy/smoke-web.mjs" "$work/"
(cd "$work" && BASE="$base" TOKEN="$token" CLIP="$work/clip.mp4" SHOTS="$shots" timeout 300 node smoke-web.mjs) || fail "the browser run failed"
echo "smoke-web: ok (screenshots in $shots)"
