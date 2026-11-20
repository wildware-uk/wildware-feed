#!/usr/bin/env bash
# The customer journey end to end, against the real server as it runs hosted: many teams
# (TEAM_DOMAIN), Postgres with row security forced and a database user that can't skip it, and
# admin keys from a throwaway environment. A driver signs admin calls as the billing control plane
# does; a headless Chrome walks a new team's owner through setting a password and signing in with
# it, then a second person, then the home team. See journey-web.mjs for the steps. In front of the
# server, journey-router.mjs plays Heroku's router: https, X-Forwarded-For and X-Forwarded-Proto.
#
#   PG_ADMIN_URL=postgres://postgres:pw@127.0.0.1:5432/postgres server/deploy/journey.sh
#   SKIP_DIST=1 CHROME=/path/to/chrome SHOTS=dir server/deploy/journey.sh
#
# PG_ADMIN_URL is a superuser on a throwaway Postgres: the script makes a fresh database and a
# plain login role for the server there, as on Heroku. Leaves nothing running.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
port="${JOURNEY_PORT:-18291}"
router_port="${JOURNEY_ROUTER_PORT:-18292}"
shots="${SHOTS:-$root/server/build/journey}"
work="$(mktemp -d)"
pid=""
router=""
db="journey_$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')"
: "${PG_ADMIN_URL:?PG_ADMIN_URL: a superuser URL for a throwaway Postgres}"
cleanup() {
    [ -n "$router" ] && kill "$router" 2>/dev/null
    [ -n "$pid" ] && kill "$pid" 2>/dev/null && wait "$pid" 2>/dev/null
    psql -q "$PG_ADMIN_URL" -c "DROP DATABASE IF EXISTS $db" >/dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT

if [ -z "${SKIP_DIST:-}" ]; then
    (cd "$root" && ./gradlew --no-daemon -q :server:installDist)
fi
bin="$root/server/build/install/feed-server/bin/feed-server"
[ -f "$root/server/build/install/feed-server/web/index.html" ] || { echo "journey: the distribution has no web app"; exit 1; }

# The server's database user, like Heroku's: logs in, owns its database, is no superuser and
# can't skip row security. The role is shared by runs; each run has its own database.
secret() { od -An -N24 -tx1 /dev/urandom | tr -d ' \n'; }
pgpass="$(secret)"
psql -q -v ON_ERROR_STOP=1 "$PG_ADMIN_URL" >/dev/null <<SQL
DO \$\$ BEGIN
  CREATE ROLE journey_app LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
EXCEPTION WHEN duplicate_object THEN NULL; END \$\$;
ALTER ROLE journey_app PASSWORD '$pgpass';
CREATE DATABASE $db OWNER journey_app;
SQL
pghost="$(node -e 'const u = new URL(process.argv[1]); console.log(u.hostname + ":" + (u.port || 5432))' "$PG_ADMIN_URL")"

home_password="journey-home-$(secret)"
admin_key="$(secret)$(secret)"
export ADMIN_PASSWORD_HASH="$("$bin" hash-password "$home_password" 2>/dev/null | sed -n 1p)"
export SESSION_SECRET="$(secret)" TOKEN_SECRET="$(secret)"
export DATABASE_URL="postgres://journey_app:$pgpass@$pghost/$db"
export TEAM_DOMAIN=feed.test
export ADMIN_API_KEYS="$admin_key"
# The control plane's page, which the browser test stands in for at this address.
export CONTROL_PLANE_ORIGIN=https://feed.test
# Behind a router, as on Heroku: the home team at an https address of its own.
export PUBLIC_BASE_URL=https://home.test ADDRESS_HEADER=X-Forwarded-For XFF_DEPTH=1
export PORT="$port" HOST=127.0.0.1 DATA_DIR="$work/data"
"$bin" >"$work/server.log" 2>&1 &
pid=$!
base="http://127.0.0.1:$port"
for _ in $(seq 1 90); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' "$base/api/server" || true)" = "200" ] && break
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
done
fail() { echo "journey: $*"; tail -40 "$work/server.log"; exit 1; }
[ "$(curl -s -o /dev/null -w '%{http_code}' "$base/api/server" || true)" = "200" ] || fail "the server did not start"
grep -q "Database: Postgres" "$work/server.log" || fail "the server is not on Postgres"

# The router, with a certificate for every test address.
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=feed.test \
    -addext "subjectAltName=DNS:feed.test,DNS:*.feed.test,DNS:home.test" \
    -keyout "$work/key.pem" -out "$work/cert.pem" 2>/dev/null
ROUTER_PORT="$router_port" SERVER_PORT="$port" CERT="$work/cert.pem" KEY="$work/key.pem" \
    node "$root/server/deploy/journey-router.mjs" >"$work/router.log" 2>&1 &
router=$!
for _ in $(seq 1 20); do
    [ "$(curl -sk -o /dev/null -w '%{http_code}' --resolve "home.test:$router_port:127.0.0.1" "https://home.test:$router_port/api/server" || true)" = "200" ] && break
    sleep 0.5
done

# Exactly as in production: every team table has row security forced, and the server's user
# can't skip it.
forced="$(psql -tA "postgres://journey_app:$pgpass@$pghost/$db" -c \
    "SELECT count(*) FILTER (WHERE relforcerowsecurity), count(*) FROM pg_class WHERE relname = 'messages' AND relkind = 'r'")"
[ "$forced" = "1|1" ] || fail "row security is not forced on messages ($forced)"
[ "$(psql -tA "$PG_ADMIN_URL" -c "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = 'journey_app'")" = "f" ] \
    || fail "the server's database user can skip row security"
echo "journey: server up on Postgres behind an https router, many teams under feed.test, row security forced"

(cd "$work" && npm init -y >/dev/null && timeout 300 npm install --no-audit --no-fund --silent playwright@1.65.1 >/dev/null)
if [ -z "${CHROME:-}" ]; then (cd "$work" && timeout 600 npx playwright install chromium >/dev/null); fi

mkdir -p "$shots"
shots="$(cd "$shots" && pwd)"
# JOURNEY_SCRIPT runs another script in its place, with the same server and settings (for debugging).
cp "$root/server/deploy/journey.mjs" "$work/"
cp "${JOURNEY_SCRIPT:-$root/server/deploy/journey-web.mjs}" "$work/run.mjs"
status=0
(cd "$work" && ROUTER_PORT="$router_port" TEAM_DOMAIN="$TEAM_DOMAIN" ADMIN_KEY="$admin_key" HOME_PASSWORD="$home_password" \
    SHOTS="$shots" timeout 600 node run.mjs) || status=$?
if [ "$status" != 0 ]; then
    echo "journey: the server's log, last lines:"
    tail -30 "$work/server.log"
    exit "$status"
fi
echo "journey: ok (screenshots in $shots)"
