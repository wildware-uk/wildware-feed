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
