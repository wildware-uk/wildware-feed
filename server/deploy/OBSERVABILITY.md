# Watching the server: Sentry and Grafana

The server can send two kinds of health data. Both are off until you set them up, and both are
free on the services' free tiers at our size.

- **Sentry** (already used for crash reports): a small sample of request traces, and the server's
  operational events as Sentry logs. Same `SENTRY_DSN` as crash reports.
- **Grafana Cloud**: metrics (request counts and timings, database, MCP tools, sign-ins, pushes,
  uploads, memory) and the same operational events as logs, over OpenTelemetry (OTLP/HTTP).

Full server output still goes to the Heroku log as before. Nothing here replaces it.

## Settings (Heroku config vars)

### Sentry

| Variable | Default | What it does |
|---|---|---|
| `SENTRY_DSN` | unset (off) | Turns on crash reports, traces and logs. |
| `SENTRY_ENVIRONMENT` | `production` | Environment name in Sentry. |
| `SENTRY_TRACES_SAMPLE_RATE` | `0.05` | Share of requests traced, 0 to 1. `0` turns traces off. Anything that is not a number from 0 to 1 also means off. |
| `SENTRY_LOGS` | on | `off` (or `false`, `0`) stops sending events as Sentry logs. |

### Grafana Cloud (OpenTelemetry)

In Grafana Cloud: **Connections → OpenTelemetry (OTLP)** gives the endpoint and the stack's
instance id. Make an access-policy token that may write metrics and logs. The header is Basic auth
with the instance id as user and the token as password. Then:

```sh
heroku config:set -a APP \
  OTEL_EXPORTER_OTLP_ENDPOINT='https://otlp-gateway-prod-<region>.grafana.net/otlp' \
  OTEL_EXPORTER_OTLP_HEADERS="Authorization=Basic $(printf '%s' '<instance id>:<token>' | base64 -w0)"
```

| Variable | Default | What it does |
|---|---|---|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | unset (off) | Base OTLP/HTTP address; the server adds `/v1/metrics` and `/v1/logs`. Must be `http(s)://`. |
| `OTEL_EXPORTER_OTLP_HEADERS` | none | `key=value,key=value`. A space may be written as-is or as `%20`; `+` and `=` stay as they are (base64). Holds the token: never printed. |
| `OTEL_RESOURCE_ATTRIBUTES` | none | Only `deployment.environment` (or `deployment.environment.name`) is read from it. |
| `OTEL_METRIC_EXPORT_INTERVAL` | `60000` | Milliseconds between metric sends; at least 10000. |
| `OTEL_LOGS_EXPORTER` | `otlp` | `none` sends metrics only. |
| `OTEL_SDK_DISABLED` | `false` | `true` turns all of it off without removing the endpoint. |

`deployment.environment` falls back to `SENTRY_ENVIRONMENT`, then `production`. `service.name` is
always `wildware-feed-server` (`OTEL_SERVICE_NAME` is ignored), so the dashboard and alerts find
it as `job="wildware-feed-server"`. Heroku's `DYNO` (such as `web.1`) is the instance. Only
`http/protobuf` is sent; `OTEL_EXPORTER_OTLP_PROTOCOL` is ignored.

The start-up log says what is on: `Crash reports: on (SENTRY_DSN); traces 0.05, logs on` and
`Metrics: on (OTEL_EXPORTER_OTLP_ENDPOINT)`.

## What is sent

**Metrics** (Grafana), every minute. Labels are only the ones listed (`Telemetry.LABELS`):

| Metric (as Grafana stores it) | Labels |
|---|---|
| `http_server_request_duration_seconds` (histogram) | method, route pattern (`/api/cards/{id}`), status class (`2xx`) |
| `feed_streams_active` | stream kind (`owner`, `agent`) |
| `db_client_operation_duration_seconds` (histogram) | operation (`SELECT`, `INSERT`, `UPDATE`, `DELETE`, `OTHER`) |
| `db_client_connection_wait_time_seconds` (histogram) | none |
| `feed_mcp_tool_calls_total`, `feed_mcp_tool_duration_seconds` | tool name (registered names only, else `unknown`), outcome (`ok`, `refused`, `invalid`, `failed`, `cancelled`) |
| `feed_sign_in_attempts_total` | sign-in route, outcome (`ok`, `refused`, `blocked`, `error`) |
| `feed_push_sends_total` | push kind (`alert`, `live_activity`, `push_to_start`), outcome |
| `feed_uploads_total`, `feed_upload_bytes_total` | media kind (`image`, `video`, `audio`, `file`) |
| `feed_teams` | none (how many open teams, counted every 5 minutes) |
| `jvm_memory_used_bytes`, `jvm_memory_committed_bytes`, `jvm_memory_limit_bytes` | heap or non-heap |
| `jvm_gc_collections_total`, `jvm_gc_collection_time_seconds_total` | collector name |
| `jvm_thread_count`, `jvm_cpu_recent_utilization_ratio`, `process_memory_usage_bytes` (RSS) | none |

Sign-ins are counted from the answers of `POST /api/devices/sign-in` and `POST /oauth/authorize`
(429 is `blocked`), so the sign-in code itself is untouched.

**Events** (Grafana logs and Sentry logs). A fixed name and only the attributes in
`Telemetry.EVENT_KEYS`:

| Event | Attributes |
|---|---|
| `server.start` | version, `db.system` (`postgresql`/`sqlite`), many teams or not |
| `http.unhandled` | method, route pattern, error type (`IllegalStateException`) |
| `job.failed` | job name (`media sweep`), error type |
| `mcp.tool_failed` | tool name, error type |
| `db.slow_wait`, `db.slow_statement` | milliseconds, operation |

**Traces** (Sentry), 5% of requests by default: the request named by its route pattern
(`GET /api/cards/{id}`), its method, status and timing, and each database statement as a span
described by its SQL with every literal written in it replaced by `?`. The server binds every
value, so the SQL is the code's text, never anyone's data. Live streams are never timed in metrics.

## What is never sent

Not to Sentry, not to Grafana:

- request or response bodies, query strings or real paths (only route patterns);
- `Authorization` headers, cookies, tokens, keys, passwords, secret values;
- a team's id, name or address, a person's name, email or id, an agent's name;
- card, message, request or feedback text, file names, error messages (they can quote a card);
- IP addresses (Sentry's is blanked to `0.0.0.0`), the machine's name.

This is held by construction (each metric and event takes fixed labels from the code) and by the
scrubbers (`CrashReports.scrub`, `scrubTransaction`, `scrubLog`); `TelemetryTest` and
`CrashReportsTest` check both.

## Free tier fit

Grafana Cloud free: 10k active series, 50 GB logs, 14 days. This server makes roughly 1,500 to
3,000 series (about 120 routes × few methods and status classes × 14 series per histogram, only for
routes that are hit; about 50 tools × outcomes), and a handful of event lines an hour. Sentry free:
traces at 5% of requests and a few log lines an hour stay well inside it. Raise
`SENTRY_TRACES_SAMPLE_RATE` only while chasing something.

## Memory

The OpenTelemetry SDK is used directly (no Java agent), with the JDK's own HTTP client (no OkHttp):
1.4 MB of jars. Measured on Linux, JDK 21, with the dyno's settings (`-XX:MaxRAM=512m
-XX:MaxRAMPercentage=75`), SQLite, after start-up, 100 requests and a forced GC, three runs each:

| | Resident memory | Live heap | Metaspace | Threads |
|---|---|---|---|---|
| Metrics off | 155 to 159 MB | 10.4 MB | 22.1 MB | 48 to 53 |
| Metrics and logs on | 174 to 186 MB | 14.1 MB | 30.3 MB | 67 to 72 |

So about **+20 MB** resident (4% of the dyno): the SDK's classes, its export threads and two JDK HTTP
clients. `OTEL_LOGS_EXPORTER=none` drops one of the clients. With `OTEL_EXPORTER_OTLP_ENDPOINT`
unset the SDK and exporters are never loaded (only a few small API classes). Sentry tracing was not
measured here (it needs a DSN); at 5% it is a few objects per traced request, sent by Sentry's
existing background thread.

## Dashboard and alerts

- `grafana/dashboard.json`: import it (Dashboards → New → Import), or `POST /api/dashboards/db`
  with `{"dashboard": <file>, "overwrite": true}`. Pick the Grafana Cloud Prometheus data source
  for its `datasource` variable.
- `grafana/alerts.yaml`: Prometheus-format rules (mimirtool layout, with `namespace`). Load with
  `mimirtool rules load grafana/alerts.yaml --address=<prometheus url> --id=<instance id> --key=<token>`,
  or create them as Grafana-managed rules with the same queries. They are:
  5xx over 2% for 5 minutes; p95 over 2 s for 5 minutes (routes slow on purpose left out); no
  requests for 10 minutes; more than 10 blocked sign-ins in 10 minutes; memory over 460 MB for 10
  minutes.

Metric names assume Grafana Cloud's default OTLP translation (dots to underscores, unit suffixes,
`_total` on counters).
