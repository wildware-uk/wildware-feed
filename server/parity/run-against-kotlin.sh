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
#   KOTLIN_DATABASE_URL                          a Postgres server (postgres://user:pass@host:port/any) to run
#                                                Kotlin on Postgres: between the steps, `feed-server migrate`
#                                                copies the TS server's file into a new database there. The
#                                                file-level DatabaseTest is skipped then (it reads SQLite).
#   KOTLIN_S3_ENDPOINT, KOTLIN_S3_ACCESS_KEY_ID,  with KOTLIN_DATABASE_URL: media in a new bucket on that
#   KOTLIN_S3_SECRET_ACCESS_KEY                  S3-compatible server (a local MinIO, never a real one);
#                                                migrate copies the TS server's media folder into it.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../.." && pwd)"
port="${PARITY_PORT:-18110}"
ref="${TS_SERVER_REF:-b65832d7a254318c6dd19c9eb3231392a80e2c3a}"
remote="${TS_SERVER_REPO:-https://github.com/wildware-uk/agent-dashboard.git}"
password="parity-$(openssl rand -hex 12)"
# Phase 1: boot, auth and reads.
# Every phase: boot, auth, reads, writes, streams, every MCP tool, media.
kotlin_tests="${KOTLIN_TESTS:-SmokeTest AuthTest $([ -n "${KOTLIN_DATABASE_URL:-}" ] || echo DatabaseTest) McpToolsListTest ReadParityTest FeedApiTest RequestsTest DevicesTest StreamTest McpTransportTest McpToolsTest McpEdgeTest MediaTest RateLimitTest}"
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
if [ -n "${KOTLIN_DATABASE_URL:-}" ]; then
    # A new database on that server, named for this run, with the TS server's data copied in.
    pg_admin="${KOTLIN_DATABASE_URL%/*}/postgres"
    pg_name="parity_$(basename "$work" | tr -c 'a-zA-Z0-9\n' '_' | tr 'A-Z' 'a-z')"
    psql_run() { docker run --rm --network host postgres:16-alpine psql "$pg_admin" -v ON_ERROR_STOP=1 -q -c "$1"; }
    if command -v psql >/dev/null; then psql_run() { psql "$pg_admin" -v ON_ERROR_STOP=1 -q -c "$1"; }; fi
    psql_run "CREATE DATABASE $pg_name"
    echo "export DATABASE_URL='${KOTLIN_DATABASE_URL%/*}/$pg_name'" >> "$work/env.sh"
    if [ -n "${KOTLIN_S3_ENDPOINT:-}" ]; then
        bucket="$(echo "$pg_name" | tr '_' '-')"
        curl -fsS --aws-sigv4 "aws:amz:auto:s3" --user "$KOTLIN_S3_ACCESS_KEY_ID:$KOTLIN_S3_SECRET_ACCESS_KEY" \
            -X PUT "${KOTLIN_S3_ENDPOINT%/}/$bucket" -o /dev/null
        {
            echo "export MEDIA_STORE=s3"
            echo "export S3_ENDPOINT='$KOTLIN_S3_ENDPOINT'"
            echo "export S3_BUCKET='$bucket'"
            echo "export S3_ACCESS_KEY_ID='$KOTLIN_S3_ACCESS_KEY_ID'"
            echo "export S3_SECRET_ACCESS_KEY='$KOTLIN_S3_SECRET_ACCESS_KEY'"
        } >> "$work/env.sh"
        echo "== media goes to bucket $bucket"
    fi
    echo "== copying the TS server's database into Postgres ($pg_name)"
    ( . "$work/env.sh"; timeout 600 "$kotlin_bin" migrate --from "$work/data" ) | grep -E '^(Copied|Identical|Media)' 
fi
echo "== starting the Kotlin server on the same data"
( . "$work/env.sh"; exec timeout 3600 "$kotlin_bin" ) > "$work/kt.log" 2>&1 &
server_pid=$!
wait_for_server "$work/kt.log"

filters=()
for t in $kotlin_tests; do filters+=(--tests "*.$t"); done
echo "== running against the Kotlin server: $kotlin_tests"
status=0
(cd "$repo_root" && env "${common_env[@]}" PARITY_CLI="$work/kt-cli.sh" PARITY_READS=compare PARITY_EXCLUDE_TAGS="$exclude_tags" \
    PARITY_OWNER_AUTH=device PARITY_WEB=0 PARITY_SERVER=kotlin \
    timeout 1500 ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx3g :server-parity:test "${filters[@]}" ${@+"$@"}) \
    || status=$?
if [ "$status" -ne 0 ]; then echo "== Kotlin server log (last 80 lines)"; tail -80 "$work/kt.log"; fi
if [ -n "${KOTLIN_DATABASE_URL:-}" ]; then stop_server; psql_run "DROP DATABASE $pg_name" || true; fi
exit "$status"
