# Running the server on Heroku

The server runs on one Basic web dyno (Common Runtime, EU region) from the same container image as
`server/deploy/Dockerfile`. A Heroku dyno's disk is wiped on every restart, so the data lives
elsewhere:

- the database in **Heroku Postgres** (`DATABASE_URL`, set by the add-on);
- media in a private **S3-compatible bucket** (Cloudflare R2 here), always served through the
  server, which checks access and sets the headers.

Nothing here is created automatically: each step below is run by hand, by someone with access to
the Heroku account and the bucket's keys. `APP` is the Heroku app's name and `dashboard.example.com`
stands for the real domain.

## Cost

Basic dyno (about $7 a month) + Heroku Postgres Essential-0 (about $5; 1 GB, enough for a database
of about 50 MB) + R2 (10 GB of storage free, then $0.015 per GB a month; no egress charges).

## Heroku's limits, and the settings that meet them

| Limit | Setting |
| --- | --- |
| The router waits 30 s for the first byte of an answer, counted from the end of the request body | `HOLD_S=25`: `request_input` and `await_request` answer within 25 s (agents loop on `pending`). Uploads answer once the bytes are in the bucket: about 8 s for 200 MB at 25 MB/s to the bucket. |
| 55 s without a byte closes a connection | Event streams already send a heartbeat every 15 s. |
| 512 MB of memory | `JAVA_OPTS` caps the heap; `FFMPEG_THREADS=1` and `DERIVE_WORKERS=1` keep ffmpeg at about 215 MB. Measured peak, server and ffmpeg transcoding 1080p: 417 MB. |
| The disk is wiped on restart | `DATA_DIR=/tmp/feed` holds only uploads in progress and ffmpeg's work files. An upload cut off by a restart is retried by its client. |
| The dyno restarts at least once a day | The boot id changes and clients resync, as after any restart. |
| Requests reach the dyno through the router | `ADDRESS_HEADER=X-Forwarded-For`, `XFF_DEPTH=1`, so rate limits and sign-in lockouts see the real client address. |

## 1. Create the app (once)

```sh
heroku create APP --region eu --stack container
heroku addons:create heroku-postgresql:essential-0 -a APP
heroku pg:backups:schedule DATABASE_URL --at '03:00 UTC' -a APP
```

Essential plans have no point-in-time recovery, so the daily backup schedule is the safety net.
`heroku pg:backups:capture -a APP` takes one by hand, for example just before a cut-over.

## 2. Configuration

```sh
heroku config:set -a APP \
  ADMIN_PASSWORD_HASH='<argon2id hash>' \
  TOKEN_SECRET='<same as the old server, so agents' tokens keep working>' \
  SESSION_SECRET='<same as the old server>' \
  PUBLIC_BASE_URL=https://dashboard.example.com \
  DATA_DIR=/tmp/feed HOLD_S=25 \
  ADDRESS_HEADER=X-Forwarded-For XFF_DEPTH=1 \
  FFMPEG_THREADS=1 DERIVE_WORKERS=1 \
  JAVA_OPTS='-XX:MaxRAM=512m -XX:MaxRAMPercentage=40 -XX:MaxMetaspaceSize=96m -Xss512k -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true' \
  MEDIA_STORE=s3 \
  S3_ENDPOINT=https://<account id>.r2.cloudflarestorage.com \
  S3_BUCKET=<bucket> S3_ACCESS_KEY_ID=<key id> S3_SECRET_ACCESS_KEY=<secret> S3_REGION=auto
```

For iOS alerts and Live Activities add `APNS_KEY_ID`, `APNS_TEAM_ID`, `APNS_BUNDLE_ID` and, since a
dyno has no key file, `APNS_KEY` with the .p8 file's contents in place of `APNS_KEY_PATH`:
`heroku config:set APNS_KEY="$(cat AuthKey_XXXX.p8)" -a APP`.

`TOKEN_SECRET` must be the old server's, or every agent token stops working. `PORT` is set by
Heroku. The server prints `Database: Postgres` and `Media: bucket <name>` when it starts; check
the log for both.

## 3. Deploy

The image is built in CI (the `server-image` job's build, smoke-tested), not on Heroku: a Heroku
build would run the Gradle build, which also configures the Android app and needs its SDK.
`.github/workflows/deploy-heroku.yml` builds, smoke-tests, pushes to Heroku's registry and
releases, when started by hand (Actions → Deploy to Heroku → Run workflow). It needs two
repository secrets: `HEROKU_API_KEY` and `HEROKU_APP`.

By hand, the same thing:

```sh
./gradlew --no-daemon :server:installDist
docker build -f server/deploy/Dockerfile -t registry.heroku.com/APP/web server/build/install/feed-server
heroku container:login
docker push registry.heroku.com/APP/web
heroku container:release web -a APP
```

The first start creates the schema (Postgres migration 47, the baseline).

## 4. Move the data (cut-over)

`feed-server migrate` copies the old server's SQLite file into the new Postgres database and its
media folder into the bucket, then checks both: each table's row count and checksum, each
original's SHA-256 against the database, each derivative's size. Media is the slow part (the old
machine's upload speed); it can run ahead while the old server is still live, because media files
never change and a second run skips what is already there.

Run it on the machine that has the old data folder, with the new settings taken straight from
Heroku, so no secret is typed or stored:

```sh
heroku config -s -a APP | grep -E '^(DATABASE_URL|MEDIA_STORE|S3_)' > /tmp/heroku-migrate.env
chmod 600 /tmp/heroku-migrate.env
run() { env $(cat /tmp/heroku-migrate.env | xargs) feed-server migrate "$@"; }
```

1. **A day before:** lower the DNS record's TTL to 60 s.
2. **Media ahead of time** (the old server keeps running): `run --from <old data dir> --media-only`
   copies the media folder into the bucket and checks it against the old database, without
   touching Postgres. Any later run skips what is already in the bucket.
3. **Stop the old server.** Nothing may change while the database copies.
4. `heroku pg:backups:capture -a APP` (an empty baseline, for the record), then
   `run --from <old data dir>`. It refuses a database that already has data. It prints
   `Identical: … rows` and `Media identical: … originals by SHA-256`; anything else stops the cut-over.
5. `heroku ps:scale web=1 -a APP`, then check the log, sign in from the app, and check that an
   agent can post.
6. **Domain:** `heroku domains:add dashboard.example.com -a APP` prints a DNS target
   (`<something>.herokudns.com`). Change the domain's record to a `CNAME` to that target (it was an
   `A` record to the old machine). Then `heroku certs:auto:enable -a APP` for the certificate.
   Agents keep their URL and token.
7. `rm /tmp/heroku-migrate.env`.

**Going back:** until anything is written on Heroku, start the old server again and point the DNS
record back. After that, bring the new writes back with `feed-server export`: scale the dyno to 0,
then, on the old machine,

```sh
heroku config -s -a APP | grep -E '^(DATABASE_URL|MEDIA_STORE|S3_)' > /tmp/heroku-migrate.env
env $(cat /tmp/heroku-migrate.env | xargs) feed-server export --to <new data dir>
```

It writes a new SQLite file and media folder (it refuses an existing one), checks them the same way
as the copy forward, and the old server can start on that folder.

## Watching it

- `heroku logs --tail -a APP`; H12 (timeout), H15 (idle) and R14 (memory) are the codes to look for.
- `heroku pg:info -a APP` for the database's size against the plan's 1 GB.
