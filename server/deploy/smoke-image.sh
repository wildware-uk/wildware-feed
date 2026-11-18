#!/usr/bin/env bash
# Build the server image and check it: it answers, signs a device in, runs the CLI inside, and stops
# quickly on SIGTERM. Uses throwaway secrets and a loopback port; leaves nothing running.
#
#   server/deploy/smoke-image.sh            (builds the distribution first)
#   SKIP_DIST=1 server/deploy/smoke-image.sh
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
port="${SMOKE_PORT:-18190}"
image="wildware-feed-server:smoke"
name="feed-smoke-$$"
work="$(mktemp -d)"
cleanup() { docker rm -f "$name" >/dev/null 2>&1 || true; rm -rf "$work"; }
trap cleanup EXIT

if [ -z "${SKIP_DIST:-}" ]; then
    (cd "$root" && ./gradlew --no-daemon -q :server:installDist)
fi
docker build -q -f "$root/server/deploy/Dockerfile" -t "$image" "$root/server/build/install/feed-server" >/dev/null

secret() { od -An -N24 -tx1 /dev/urandom | tr -d ' \n'; }
hash="$(docker run --rm "$image" hash-password smoke-password 2>/dev/null | sed -n 1p)"
{
    echo "ADMIN_PASSWORD_HASH=$hash"
    echo "SESSION_SECRET=$(secret)"
    echo "TOKEN_SECRET=$(secret)"
    echo "PUBLIC_BASE_URL=http://127.0.0.1:$port"
} > "$work/env"
docker run -d --name "$name" --env-file "$work/env" -p "127.0.0.1:$port:8010" "$image" >/dev/null

base="http://127.0.0.1:$port"
for _ in $(seq 1 60); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' "$base/api/summary" || true)" = "401" ] && break
    sleep 1
done
fail() { echo "smoke: $*"; docker logs "$name" 2>&1 | tail -20; exit 1; }
[ "$(curl -s "$base/api/summary")" = '{"error":"unauthenticated"}' ] || fail "a stranger did not get 401"
key="$(curl -s -X POST -H 'Content-Type: application/json' -d '{"name":"smoke","password":"smoke-password"}' "$base/api/devices/sign-in" \
    | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')"
[ -n "$key" ] || fail "device sign-in gave no key"
[ "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $key" "$base/api/summary")" = "200" ] || fail "the device key was refused"
docker exec "$name" mint-token smoke-agent | grep -Eq '^[A-Za-z0-9_-]{43}$' || fail "mint-token printed no token"
docker exec "$name" bash -c 'ffmpeg -version >/dev/null' || fail "no ffmpeg in the image"
started=$(date +%s%N)
docker stop -t 10 "$name" >/dev/null
took=$(( ($(date +%s%N) - started) / 1000000 ))
[ "$took" -lt 5000 ] || fail "stopping took ${took} ms"
echo "smoke: ok (image $(docker image ls "$image" --format '{{.Size}}'), stopped in ${took} ms)"
