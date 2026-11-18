# Shadow run

The TypeScript server and the Kotlin server on two identical copies of one database, compared
answer by answer (`server/PLAN.md`, phase 5). Loopback ports and throwaway secrets only.

```sh
SHADOW_SOURCE=<folder with agent-dashboard.db and media/, a backup copy> \
SHADOW_WORK=<empty private folder on the same filesystem> \
TS_SERVER_DIR=<a built TypeScript server> \
KOTLIN_DIST=server/build/install/feed-server \
server/shadow/shadow-run.sh
```

The report names routes, counts and JSON paths, never values, so it can be shared even when the
data is real. `SHADOW_WORK/debug/` holds the two bodies of each difference for local inspection:
keep it private, and delete `SHADOW_WORK` when done.
