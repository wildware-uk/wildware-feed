# Running the Kotlin server

The server is one Java program. It reads the same settings as the TypeScript server (environment
variables, see `../spec/01-config-and-auth.md`), so an existing `.env` and data folder work as they
are. It needs Java 21, and `ffmpeg` on `PATH` for video posters, transcodes and image thumbnails
(without it, images keep only their original and videos fail to process).

## Build the distribution

```sh
./gradlew --no-daemon :server:installDist   # server/build/install/feed-server/{bin,lib}
./gradlew --no-daemon :server:distTar       # server/build/distributions/feed-server.tar
```

`bin/feed-server` serves. `bin/feed-server <command>` is the admin CLI (`mint-token`,
`hash-password`, `list-tokens`, `rename-token`, `revoke-token`, `help`).

The distribution also holds `web/`, the web app (the Compose app built for browsers), which the
server serves at its root: every path but `/api`, `/mcp` and `/media` is the app's, and any page
path without a file opens `index.html`. `WEB_DIR` points it somewhere else. Add `-Pfeed.web=false`
to leave it out, which skips the few minutes its build takes; the server then answers 404 there,
as before.

The web app signs in with the owner password and gets its device key as an `HttpOnly`,
`SameSite=Strict` cookie (`feed_device`, `Secure` when `PUBLIC_BASE_URL` is https). A request that
changes something on the strength of that cookie must come from the server's own page
(`Sec-Fetch-Site: same-origin`, or a matching `Origin`), so the app has to be served from the same
address as the API. Serving the bundle from another site would need CORS and a cross-site cookie,
which the server does not offer.

## Docker

```sh
./gradlew --no-daemon :server:installDist
docker build -f server/deploy/Dockerfile -t wildware-feed-server server/build/install/feed-server
docker compose -f server/deploy/docker-compose.yml up -d     # reads .env beside it
docker compose -f server/deploy/docker-compose.yml exec feed mint-token my-agent
```

The image carries a trimmed Java runtime and ffmpeg, runs as a non-root user, keeps everything in
the `/data` volume and listens on 8010 (published on 127.0.0.1 only; put your TLS proxy in front).

## systemd

`feed-server.service` is a hardened unit for the tarball install; its header lists the commands.
Logs: `journalctl -u feed-server`. Upgrade: unpack the new tarball over `/opt/feed-server`, then
`systemctl restart feed-server` (the server stops in under two seconds; agents and apps reconnect).

## Stopping

On SIGTERM the server stops taking requests, gives open ones half a second, closes live streams,
stops its timers and closes the database. A database is never left half-written: SQLite's WAL makes
each write all-or-nothing either way.

## Back up first

Before an upgrade, copy the data folder while the server is stopped, or use the TypeScript server's
`backup` command while it runs. A newer server may add a migration; an older one then refuses the
database ("refuses to start" is the safe failure), so the backup is how you go back.

## Check the customer journey

`journey.sh` is the end-to-end test CI runs on every change (the `journey` job): the server as it
runs hosted (many teams, Postgres with row security forced), a team made through the signed admin
API, and a headless Chrome that sets the owner's password, signs out and back in with it, posts, and
signs in a teammate; then the apps' sign-ins over HTTP, and checks that no team's password opens
another. It needs a throwaway Postgres it may make a database and a role on:

    PG_ADMIN_URL=postgres://postgres:pw@127.0.0.1:5432/postgres server/deploy/journey.sh

After each deploy, run the same journey against the live server, over HTTP (no browser):

    ORIGIN=https://feed.example.com ADMIN_KEY_FILE=path/to/admin-key server/deploy/smoke-live.sh

`ORIGIN` is where the admin API answers (`TEAM_DOMAIN`, or `ADMIN_HOST`); `ADMIN_KEY_FILE` holds one
of the server's `ADMIN_API_KEYS`, read by the script and never printed. It makes two throwaway teams
(`journey-<random>-a` and `-b`, with `@example.com` emails), runs every check, and deletes both teams
whatever happened. Add `HOME_ORIGIN` and `HOME_PASSWORD_FILE` to check the home team too. Every step
prints `ok` or `FAIL`; it exits 1 if any failed, and says so if a team could not be deleted.
