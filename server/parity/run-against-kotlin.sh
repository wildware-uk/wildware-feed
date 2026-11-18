#!/usr/bin/env bash
# Run the parity suite against the Kotlin server.
#
# 1. Start the TypeScript server on a fresh data folder, build a rich database through its API and
#    record every read (ReadParityTest in record mode). Stop it.
# 2. Start the Kotlin server on the same folder with the same secrets, and run the tests for the
#    phases the Kotlin server has reached, plus ReadParityTest in compare mode.
#
#   TS_SERVER_DIR / TS_SERVER_REF / SKIP_BUILD   as in run-against-ts.sh
#   PARITY_PORT                                  port for both servers (default 18110)
#   KOTLIN_TESTS                                 which test classes run against Kotlin (default below)
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../.." && pwd)"
port="${PARITY_PORT:-18110}"
ref="${TS_SERVER_REF:-b65832d7a254318c6dd19c9eb3231392a80e2c3a}"
remote="${TS_SERVER_REPO:-https://github.com/wildware-uk/agent-dashboard.git}"
password="parity-$(openssl rand -hex 12)"
# Phase 1: boot, auth and reads.
# Every phase: boot, auth, reads, writes, streams, every MCP tool, media.
kotlin_tests="${KOTLIN_TESTS:-SmokeTest AuthTest DatabaseTest McpToolsListTest ReadParityTest FeedApiTest RequestsTest DevicesTest StreamTest McpTransportTest McpToolsTest McpEdgeTest MediaTest RateLimitTest}"
# Tests inside those classes that need a later phase.
# tsOnly: what the Kotlin server deliberately leaves out (the website, share links, Web Push).
exclude_tags="${KOTLIN_EXCLUDE_TAGS:-tsOnly}"

work="$(mktemp -d "${TMPDIR:-/tmp}/parity-kt.XXXXXX")"
server_pid=""
stop_server() {
    if [ -n "$server_pid" ] && kill -0 "$server_pid" 2>/dev/null; then
        kill "$server_pid" 2>/dev/null || true
        for _ in $(seq 20); do kill -0 "$server_pid" 2>/dev/null || break; sleep 0.5; done
        kill -9 "$server_pid" 2>/dev/null || true
    fi
    server_pid=""
}
cleanup() { stop_server; rm -rf "$work"; }
trap cleanup EXIT

wait_for_server() {
    for _ in $(seq 90); do
        # Any HTTP answer will do: the Kotlin server has no /login page to ask for.
        code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/api/summary" 2>/dev/null || true)"
        if [ -n "$code" ] && [ "$code" != "000" ]; then return 0; fi
        kill -0 "$server_pid" 2>/dev/null || { echo "server died:"; cat "$1"; return 1; }
        sleep 1
    done
    echo "server never came up:"; cat "$1"; return 1
}

gradle() {
    (cd "$repo_root" && timeout 1500 ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx3g "$@")
}

# ── Build both servers ───────────────────────────────────────────────────────────────────────────
echo "== building the Kotlin server"
gradle -q :server:installDist
kotlin_bin="$repo_root/server/build/install/feed-server/bin/feed-server"

if [ -z "${TS_SERVER_DIR:-}" ]; then
    echo "== cloning $remote at $ref"
    timeout 300 git clone --quiet "$remote" "$work/ts"
    git -C "$work/ts" checkout --quiet "$ref"
    TS_SERVER_DIR="$work/ts"
fi
ts_dir="$(cd "$TS_SERVER_DIR" && pwd)"
if [ "${SKIP_BUILD:-}" != "1" ]; then
    echo "== building the TypeScript server"
    (cd "$ts_dir" && timeout 900 npm ci --no-audit --no-fund && timeout 900 npm run build)
fi

if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then echo "port $port is already in use"; exit 1; fi

# ── One set of secrets and one data folder for both ─────────────────────────────────────────────
mkdir -p "$work/data"
hash="$("$kotlin_bin" hash-password "$password" | head -1)"
cat > "$work/env.sh" <<EOF
export DATA_DIR='$work/data'
export ADMIN_PASSWORD_HASH='$hash'
export SESSION_SECRET='$(openssl rand -hex 32)'
export TOKEN_SECRET='$(openssl rand -hex 32)'
export PUBLIC_BASE_URL='http://127.0.0.1:$port'
export ORIGIN='http://127.0.0.1:$port'
export PORT='$port'
export HOST='127.0.0.1'
export BODY_SIZE_LIMIT=209715200
unset ADDRESS_HEADER XFF_DEPTH VAPID_PUBLIC_KEY VAPID_PRIVATE_KEY VAPID_SUBJECT
unset APNS_KEY_ID APNS_TEAM_ID APNS_KEY_PATH APNS_BUNDLE_ID
EOF
cat > "$work/ts-cli.sh" <<EOF
#!/bin/sh
set -e
. '$work/env.sh'
cd '$ts_dir'
exec timeout 60 node build/cli.js "\$@"
EOF
cat > "$work/kt-cli.sh" <<EOF
#!/bin/sh
set -e
. '$work/env.sh'
exec timeout 60 '$kotlin_bin' "\$@"
EOF
chmod +x "$work/ts-cli.sh" "$work/kt-cli.sh"

common_env=(PARITY_BASE_URL="http://127.0.0.1:$port" PARITY_ADMIN_PASSWORD="$password" PARITY_DATA_DIR="$work/data" PARITY_READS_FILE="$work/reads.json")

# ── 1. TypeScript: seed and record ───────────────────────────────────────────────────────────────
echo "== recording reads from the TypeScript server"
( . "$work/env.sh"; cd "$ts_dir"; exec timeout 3600 node build ) > "$work/ts.log" 2>&1 &
server_pid=$!
wait_for_server "$work/ts.log"
(cd "$repo_root" && env "${common_env[@]}" PARITY_CLI="$work/ts-cli.sh" PARITY_READS=record \
    timeout 1500 ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx3g -q :server-parity:test --tests '*ReadParityTest*') \
    || { echo "== recording failed; TS server log:"; tail -60 "$work/ts.log"; exit 1; }
stop_server

# ── 2. Kotlin: the same database, the same questions ────────────────────────────────────────────
echo "== starting the Kotlin server on the same data"
( . "$work/env.sh"; exec timeout 3600 "$kotlin_bin" ) > "$work/kt.log" 2>&1 &
server_pid=$!
wait_for_server "$work/kt.log"

filters=()
for t in $kotlin_tests; do filters+=(--tests "*.$t"); done
echo "== running against the Kotlin server: $kotlin_tests"
status=0
(cd "$repo_root" && env "${common_env[@]}" PARITY_CLI="$work/kt-cli.sh" PARITY_READS=compare PARITY_EXCLUDE_TAGS="$exclude_tags" \
    PARITY_OWNER_AUTH=device PARITY_WEB=0 \
    timeout 1500 ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx3g :server-parity:test "${filters[@]}" ${@+"$@"}) \
    || status=$?
if [ "$status" -ne 0 ]; then echo "== Kotlin server log (last 80 lines)"; tail -80 "$work/kt.log"; fi
exit "$status"
