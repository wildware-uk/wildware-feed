#!/usr/bin/env bash
# Run the parity suite against a throwaway copy of the TypeScript server.
#
# It builds the server (unless told not to), starts it on its own port with a fresh temporary
# data directory and freshly generated secrets, runs the suite, and stops it again. It never
# touches a real deployment's data or secrets.
#
#   TS_SERVER_DIR  a checkout of wildware-uk/agent-dashboard to use. If unset, the script
#                  clones TS_SERVER_REPO at TS_SERVER_REF into a temporary directory.
#   TS_SERVER_REF  the commit to clone (default below). Ignored when TS_SERVER_DIR is set.
#   SKIP_BUILD=1   use TS_SERVER_DIR's existing build/ (and node_modules) as they are.
#   PARITY_PORT    the port to start it on (default 18110).
#   Anything after the options is passed to Gradle, e.g. --tests '*McpToolsTest*'.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../.." && pwd)"
port="${PARITY_PORT:-18110}"
ref="${TS_SERVER_REF:-bef26e7be05aa0d2feb6e1eae9282e751d100abe}"
remote="${TS_SERVER_REPO:-https://github.com/wildware-uk/agent-dashboard.git}"
password="parity-$(openssl rand -hex 12)"

work="$(mktemp -d "${TMPDIR:-/tmp}/parity-ts.XXXXXX")"
server_pid=""
cleanup() {
    if [ -n "$server_pid" ] && kill -0 "$server_pid" 2>/dev/null; then
        kill "$server_pid" 2>/dev/null || true
        for _ in $(seq 20); do kill -0 "$server_pid" 2>/dev/null || break; sleep 0.5; done
        kill -9 "$server_pid" 2>/dev/null || true
    fi
    rm -rf "$work"
}
trap cleanup EXIT

if [ -z "${TS_SERVER_DIR:-}" ]; then
    echo "== cloning $remote at $ref"
    timeout 300 git clone --quiet "$remote" "$work/server"
    git -C "$work/server" checkout --quiet "$ref"
    TS_SERVER_DIR="$work/server"
fi
server_dir="$(cd "$TS_SERVER_DIR" && pwd)"

if [ "${SKIP_BUILD:-}" != "1" ]; then
    # A checkout with .local/ is somebody's live deployment: building there replaces what it runs.
    if [ -e "$server_dir/.local" ]; then
        echo "$server_dir looks like a live deployment (.local/ exists); copy it elsewhere or set SKIP_BUILD=1"
        exit 1
    fi
    echo "== building the TypeScript server in $server_dir"
    (cd "$server_dir" && timeout 900 npm ci --no-audit --no-fund && timeout 900 npm run build)
fi
[ -f "$server_dir/build/index.js" ] || { echo "no build/index.js in $server_dir"; exit 1; }
[ -f "$server_dir/build/cli.js" ] || { echo "no build/cli.js in $server_dir"; exit 1; }

if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
    echo "port $port is already in use; set PARITY_PORT"; exit 1
fi

# Everything the server reads, generated fresh. The data directory lives and dies with $work.
mkdir -p "$work/data"
hash="$(cd "$server_dir" && timeout 60 node build/cli.js hash-password "$password" | head -1)"
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

# The suite mints and revokes its agents with the server's own CLI, against the same data directory.
cat > "$work/cli.sh" <<EOF
#!/bin/sh
set -e
. '$work/env.sh'
cd '$server_dir'
exec timeout 60 node build/cli.js "\$@"
EOF
chmod +x "$work/cli.sh"

echo "== starting it on port $port"
(
    . "$work/env.sh"
    cd "$server_dir"
    exec timeout 3600 node build
) > "$work/server.log" 2>&1 &
server_pid=$!

for _ in $(seq 60); do
    if curl -fsS -o /dev/null "http://127.0.0.1:$port/login" 2>/dev/null; then break; fi
    kill -0 "$server_pid" 2>/dev/null || { echo "server died:"; cat "$work/server.log"; exit 1; }
    sleep 1
done
curl -fsS -o /dev/null "http://127.0.0.1:$port/login" || { echo "server never came up:"; cat "$work/server.log"; exit 1; }

echo "== running the parity suite"
status=0
(
    cd "$repo_root"
    PARITY_BASE_URL="http://127.0.0.1:$port" \
    PARITY_ADMIN_PASSWORD="$password" \
    PARITY_CLI="$work/cli.sh" \
    PARITY_DATA_DIR="$work/data" \
    timeout 1500 ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx3g :server-parity:test "$@"
) || status=$?

if [ "$status" -ne 0 ]; then
    echo "== server log (last 80 lines)"
    tail -80 "$work/server.log"
fi
exit "$status"
