#!/usr/bin/env bash
# The server as it runs hosted, for end-to-end tests, then a command run against it: many teams
# (TEAM_DOMAIN=feed.test), Postgres with row security forced and a database user that can't skip
# it, admin keys from a throwaway environment, and test-router.mjs in front playing Heroku's
# router (https, X-Forwarded-For, X-Forwarded-Proto). Leaves nothing running.
#
#   PG_ADMIN_URL=postgres://postgres:pw@127.0.0.1:5432/postgres server/deploy/test-stack.sh <command> [args]
#
# The command gets, in its environment:
#   E2E_ORIGIN          https://feed.test, where the admin API answers
#   E2E_ADMIN_KEY       the admin signing key
#   E2E_CONNECT         127.0.0.1:<port>: where every test address really is (the router)
#   E2E_TEAM_DOMAIN     feed.test
#   E2E_HOME_ORIGIN     https://home.test, the home team
#   E2E_HOME_PASSWORD   the home team's owner password
#   E2E_SERVER_LOG      the server's log
#
# PG_ADMIN_URL is a superuser on a throwaway Postgres: the script makes a database for this run
# and a plain login role for the server. SKIP_DIST=1 uses the distribution already built;
# SERVER_DIST points at another one. STACK_PORT and STACK_ROUTER_PORT choose the ports.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
port="${STACK_PORT:-18291}"
router_port="${STACK_ROUTER_PORT:-18292}"
work="$(mktemp -d)"
pid=""
router=""
db="e2e_$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')"
# A login role of its own per run, so stacks started side by side never change each other's password.
role="${db}_app"
: "${PG_ADMIN_URL:?PG_ADMIN_URL: a superuser URL for a throwaway Postgres}"
[ $# -gt 0 ] || { echo "test-stack: give a command to run against the server"; exit 2; }
cleanup() {
    if [ -n "$router" ]; then kill "$router" 2>/dev/null || true; fi
    # The server, killed, makes wait answer 143; that is no failure of the run.
    if [ -n "$pid" ]; then kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; fi
    psql -q "$PG_ADMIN_URL" -c "DROP DATABASE IF EXISTS $db WITH (FORCE)" -c "DROP ROLE IF EXISTS $role" >/dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT

dist="${SERVER_DIST:-$root/server/build/install/feed-server}"
if [ -z "${SKIP_DIST:-}" ] && [ -z "${SERVER_DIST:-}" ]; then
    (cd "$root" && ./gradlew --no-daemon -q :server:installDist)
fi
bin="$dist/bin/feed-server"
[ -f "$dist/web/index.html" ] || { echo "test-stack: the distribution has no web app"; exit 1; }

# The server's database user, like Heroku's: logs in, owns its database, is no superuser and
# can't skip row security.
secret() { od -An -N24 -tx1 /dev/urandom | tr -d ' \n'; }
pgpass="$(secret)"
psql -q -v ON_ERROR_STOP=1 "$PG_ADMIN_URL" >/dev/null <<SQL
CREATE ROLE $role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '$pgpass';
CREATE DATABASE $db OWNER $role;
SQL
pghost="$(node -e 'const u = new URL(process.argv[1]); console.log(u.hostname + ":" + (u.port || 5432))' "$PG_ADMIN_URL")"
app_url="postgres://$role:$pgpass@$pghost/$db"

home_password="e2e-home-$(secret)"
admin_key="$(secret)$(secret)"
log="$work/server.log"
(
    export ADMIN_PASSWORD_HASH="$("$bin" hash-password "$home_password" 2>/dev/null | sed -n 1p)"
    export SESSION_SECRET="$(secret)" TOKEN_SECRET="$(secret)" SECRETS_KEY="$(secret)"
    export DATABASE_URL="$app_url"
    export TEAM_DOMAIN=feed.test ADMIN_API_KEYS="$admin_key"
    # The control plane's page, which the tests stand in for at this address.
    export CONTROL_PLANE_ORIGIN=https://feed.test
    # Behind a router, as on Heroku: the home team at an https address of its own.
    export PUBLIC_BASE_URL=https://home.test ADDRESS_HEADER=X-Forwarded-For XFF_DEPTH=1
    export PORT="$port" HOST=127.0.0.1 DATA_DIR="$work/data"
    exec "$bin"
) >"$log" 2>&1 &
pid=$!
fail() { echo "test-stack: $*"; tail -40 "$log"; exit 1; }
for _ in $(seq 1 90); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/api/server" || true)" = "200" ] && break
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
done
[ "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/api/server" || true)" = "200" ] || fail "the server did not start"
grep -q "Database: Postgres" "$log" || fail "the server is not on Postgres"

# Exactly as in production: every team table has row security forced, and the server's user
# can't skip it.
forced="$(psql -tA "$app_url" -c "SELECT count(*) FILTER (WHERE relforcerowsecurity), count(*) FROM pg_class WHERE relname = 'messages' AND relkind = 'r'")"
[ "$forced" = "1|1" ] || fail "row security is not forced on messages ($forced)"
[ "$(psql -tA "$PG_ADMIN_URL" -c "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = '$role'")" = "f" ] \
    || fail "the server's database user can skip row security"

# The router, with a certificate for every test address.
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=feed.test \
    -addext "subjectAltName=DNS:feed.test,DNS:*.feed.test,DNS:home.test" \
    -keyout "$work/key.pem" -out "$work/cert.pem" 2>/dev/null
ROUTER_PORT="$router_port" SERVER_PORT="$port" CERT="$work/cert.pem" KEY="$work/key.pem" \
    node "$root/server/deploy/test-router.mjs" >"$work/router.log" 2>&1 &
router=$!
for _ in $(seq 1 20); do
    [ "$(curl -sk -o /dev/null -w '%{http_code}' --resolve "home.test:$router_port:127.0.0.1" "https://home.test:$router_port/api/server" || true)" = "200" ] && break
    sleep 0.5
done
echo "test-stack: server up on Postgres behind an https router, many teams under feed.test, row security forced"

status=0
E2E_ORIGIN=https://feed.test E2E_ADMIN_KEY="$admin_key" E2E_CONNECT="127.0.0.1:$router_port" E2E_TEAM_DOMAIN=feed.test \
    E2E_HOME_ORIGIN=https://home.test E2E_HOME_PASSWORD="$home_password" E2E_SERVER_LOG="$log" \
    "$@" || status=$?
if [ "$status" != 0 ]; then
    echo "test-stack: the server's log, last lines:"
    grep -v '^admin: ' "$log" | tail -30
fi
exit "$status"
