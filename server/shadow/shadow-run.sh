#!/usr/bin/env bash
# The shadow run (server/PLAN.md, phase 5): the TypeScript server and the Kotlin server on two
# identical copies of one database, side by side on loopback ports, compared by shadow_compare.py.
#
#   SHADOW_SOURCE  a folder with agent-dashboard.db and media/ (a backup; never a live data folder)
#   SHADOW_WORK    an empty private folder on the same filesystem (media is hard-linked from the source)
#   TS_SERVER_DIR  a built TypeScript server (build/index.js, build/cli.js, node_modules)
#   KOTLIN_DIST    the Kotlin server's installDist folder (bin/feed-server)
#   SHADOW_PG_URL  optional: a local Postgres server (postgres://user:pass@host:port/any). Copy B then goes
#                  into a new database there with `feed-server migrate`, and the Kotlin server runs on it.
#   SHADOW_S3_ENDPOINT, SHADOW_S3_ACCESS_KEY_ID, SHADOW_S3_SECRET_ACCESS_KEY
#                  optional, with SHADOW_PG_URL: copy B's media goes into a new bucket on a local
#                  S3-compatible server (MinIO; never a real bucket). The database is dropped afterwards;
#                  the bucket is left for you to empty.
#
# Throwaway secrets only. Prints the report; nothing is left running.
set -euo pipefail
: "${SHADOW_SOURCE:?}" "${SHADOW_WORK:?}" "${TS_SERVER_DIR:?}" "${KOTLIN_DIST:?}"
here="$(cd "$(dirname "$0")" && pwd)"
port_a="${PORT_A:-18201}"
port_b="${PORT_B:-18202}"
[ -f "$SHADOW_SOURCE/agent-dashboard.db" ] || { echo "no agent-dashboard.db in SHADOW_SOURCE"; exit 1; }
case "$SHADOW_SOURCE" in *.local*) echo "refusing: that looks like a live data folder"; exit 1;; esac
[ -z "$(ls -A "$SHADOW_WORK" 2>/dev/null)" ] || { echo "SHADOW_WORK must be empty"; exit 1; }
umask 077
mkdir -p "$SHADOW_WORK/a" "$SHADOW_WORK/b" "$SHADOW_WORK/debug"

secret() { od -An -N24 -tx1 /dev/urandom | tr -d ' \n'; }
password="$(secret)"
hash="$("$KOTLIN_DIST/bin/feed-server" hash-password "$password" 2>/dev/null | sed -n 1p)"
common=(TOKEN_SECRET="$(secret)" SESSION_SECRET="$(secret)" ADMIN_PASSWORD_HASH="$hash"
    PUBLIC_BASE_URL="http://127.0.0.1:18200" ORIGIN="http://127.0.0.1:18200" HOLD_S=5 BODY_SIZE_LIMIT=209715200
    HOME="$HOME" PATH="$PATH")

copy_db() { python3 -c 'import sqlite3,sys; s=sqlite3.connect(f"file:{sys.argv[1]}?mode=ro", uri=True); d=sqlite3.connect(sys.argv[2]); s.backup(d); d.close(); s.close()' "$1" "$2"; }

pids=()
stop_all() { for p in "${pids[@]}"; do kill "$p" 2>/dev/null || true; done; wait 2>/dev/null || true; pids=(); }
trap stop_all EXIT
wait_up() { for _ in $(seq 1 120); do [ "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$1/api/summary" || true)" = 401 ] && return 0; sleep 0.5; done; echo "server on $1 did not start"; exit 1; }
start_ts() { (cd "$TS_SERVER_DIR" && env -i "${common[@]}" DATA_DIR="$1" PORT="$2" HOST=127.0.0.1 node build) >"$SHADOW_WORK/ts.log" 2>&1 & pids+=($!); wait_up "$2"; }
kt_env=()
start_kt() { env -i "${common[@]}" "${kt_env[@]}" DATA_DIR="$1" PORT="$2" HOST=127.0.0.1 "$KOTLIN_DIST/bin/feed-server" >"$SHADOW_WORK/kt.log" 2>&1 & pids+=($!); wait_up "$2"; }

echo "== copy A"
copy_db "$SHADOW_SOURCE/agent-dashboard.db" "$SHADOW_WORK/a/agent-dashboard.db"
cp -al "$SHADOW_SOURCE/media" "$SHADOW_WORK/a/media"

echo "== a shadow device and agent, made by the TS server in copy A"
start_ts "$SHADOW_WORK/a" "$port_a"
key="$(curl -s -X POST -H 'Content-Type: application/json' -d "{\"name\":\"shadow\",\"password\":\"$password\"}" \
    "http://127.0.0.1:$port_a/api/devices/sign-in" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')"
stop_all
token="$(cd "$TS_SERVER_DIR" && env -i "${common[@]}" DATA_DIR="$SHADOW_WORK/a" node build/cli.js mint-token shadow-agent | grep -E '^[A-Za-z0-9_-]{43}$')"

echo "== copy B from A"
copy_db "$SHADOW_WORK/a/agent-dashboard.db" "$SHADOW_WORK/b/agent-dashboard.db"
cp -al "$SHADOW_WORK/a/media" "$SHADOW_WORK/b/media"

if [ -n "${SHADOW_PG_URL:-}" ]; then
    db="shadow_$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')"
    psql "${SHADOW_PG_URL%/*}/postgres" -q -v ON_ERROR_STOP=1 -c "CREATE DATABASE $db"
    kt_env+=(DATABASE_URL="${SHADOW_PG_URL%/*}/$db")
    cleanup_pg() { stop_all; psql "${SHADOW_PG_URL%/*}/postgres" -q -c "DROP DATABASE IF EXISTS $db" || true; }
    trap cleanup_pg EXIT
    if [ -n "${SHADOW_S3_ENDPOINT:-}" ]; then
        bucket="$(echo "$db" | tr '_' '-')"
        curl -fsS --aws-sigv4 "aws:amz:auto:s3" --user "$SHADOW_S3_ACCESS_KEY_ID:$SHADOW_S3_SECRET_ACCESS_KEY" -X PUT "${SHADOW_S3_ENDPOINT%/}/$bucket" -o /dev/null
        kt_env+=(MEDIA_STORE=s3 S3_ENDPOINT="$SHADOW_S3_ENDPOINT" S3_BUCKET="$bucket"
            S3_ACCESS_KEY_ID="$SHADOW_S3_ACCESS_KEY_ID" S3_SECRET_ACCESS_KEY="$SHADOW_S3_SECRET_ACCESS_KEY")
        echo "== copy B into Postgres ($db) and bucket $bucket"
    else
        echo "== copy B into Postgres ($db)"
    fi
    env -i "${kt_env[@]}" HOME="$HOME" PATH="$PATH" "$KOTLIN_DIST/bin/feed-server" migrate --from "$SHADOW_WORK/b" --sizes-only \
        | grep -E '^(Copied|Identical|Media)'
fi

echo "== both servers"
start_ts "$SHADOW_WORK/a" "$port_a"
start_kt "$SHADOW_WORK/b" "$port_b"
sleep "${SETTLE_S:-40}"   # both sweep expired requests (every 30 s) before anything is read

python3 "$here/shadow_compare.py" --a "http://127.0.0.1:$port_a" --b "http://127.0.0.1:$port_b" \
    --device-key="$key" --agent-token="$token" --report "$SHADOW_WORK/report.txt" --debug-dir "$SHADOW_WORK/debug" ${SHADOW_ARGS:-}
