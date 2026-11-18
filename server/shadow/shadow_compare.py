#!/usr/bin/env python3
"""
The shadow run's comparison (server/PLAN.md, phase 5).

Two servers answer for two identical copies of one database: A (the TypeScript server) and B (the
Kotlin server). This script reads everything both can answer, then makes the same writes on both in
lockstep, and counts where they differ.

The data may be real, so the report says only WHERE answers differ: route templates, counts, and
JSON paths. It never prints a value. With --debug-dir it also writes the two differing bodies of
each difference into that folder, for whoever runs it to look at locally; keep that folder private.

Standard library only.

  shadow_compare.py --a http://127.0.0.1:18201 --b http://127.0.0.1:18202 \
      --device-key KEY --agent-token TOKEN --report report.txt [--debug-dir DIR]
"""
import argparse
import collections
import json
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

ULID = re.compile(r"^[0-9A-HJKMNP-TV-Z]{26}$")
ISO = re.compile(r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$")


class Server:
    def __init__(self, base, device_key, agent_token):
        self.base = base.rstrip("/")
        self.device_key = device_key
        self.agent_token = agent_token
        self.mcp_id = 0

    def request(self, method, path, body=None, bearer=None, headers=None, raw=False):
        data = None
        h = {"Authorization": f"Bearer {bearer or self.device_key}"}
        if headers:
            h.update(headers)
        if body is not None:
            data = body if isinstance(body, bytes) else json.dumps(body).encode()
            h.setdefault("Content-Type", "application/json")
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=h)
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                return r.status, r.headers, r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.headers, e.read()

    def mcp(self, tool, arguments):
        # Stay under the per-token limit of 120 calls a minute.
        now = time.time()
        self.calls = [t for t in getattr(self, "calls", []) if now - t < 60]
        if len(self.calls) >= 100:
            time.sleep(60 - (now - self.calls[0]) + 0.5)
        self.calls.append(time.time())
        self.mcp_id += 1
        body = {"jsonrpc": "2.0", "id": self.mcp_id, "method": "tools/call" if tool else "tools/list",
                "params": {"name": tool, "arguments": arguments} if tool else {}}
        return self.request("POST", "/mcp", body, bearer=self.agent_token,
                            headers={"Accept": "application/json, text/event-stream"})


def ordered(text):
    return json.loads(text, object_pairs_hook=collections.OrderedDict)


def paths_differing(a, b, path="$"):
    """JSON paths where a and b differ (key order counts), never their values."""
    out = []
    if type(a) is not type(b):
        return [f"{path} (type)"]
    if isinstance(a, dict):
        if list(a.keys()) != list(b.keys()):
            if set(a.keys()) != set(b.keys()):
                only_a = sorted(set(a) - set(b))
                only_b = sorted(set(b) - set(a))
                out.append(f"{path} (keys: only A {only_a[:5]}, only B {only_b[:5]})")
            else:
                out.append(f"{path} (key order)")
        for k in a:
            if k in b:
                out += paths_differing(a[k], b[k], f"{path}.{k}")
        return out
    if isinstance(a, list):
        if len(a) != len(b):
            out.append(f"{path} (length {len(a)} vs {len(b)})")
        for i, (x, y) in enumerate(zip(a, b)):
            out += paths_differing(x, y, f"{path}[{i}]")
        return out
    return [] if a == b else [f"{path} (value)"]


def generic(path):
    """A route template for counting: ids, slugs and numbers become placeholders."""
    p, _, q = path.partition("?")
    parts = []
    for seg in p.split("/"):
        parts.append(":id" if ULID.match(seg) else seg)
    p = "/".join(parts)
    if "/projects/" in p:
        p = re.sub(r"/projects/[^/]+", "/projects/:project", p)
    if q:
        keys = sorted(k for k, _ in urllib.parse.parse_qsl(q, keep_blank_values=True))
        p += "?" + "&".join(f"{k}=…" for k in keys)
    return p


class Report:
    def __init__(self, debug_dir):
        self.same = collections.Counter()
        self.diff = collections.Counter()
        self.where = collections.defaultdict(collections.Counter)
        self.debug_dir = debug_dir
        self.n = 0

    def add(self, route, ok, paths=(), a=None, b=None):
        if ok:
            self.same[route] += 1
            return
        self.diff[route] += 1
        for p in paths[:8]:
            self.where[route][re.sub(r"\[\d+\]", "[]", p)] += 1
        if self.debug_dir and a is not None:
            self.n += 1
            with open(f"{self.debug_dir}/{self.n:05d}.json", "w") as f:
                json.dump({"route": route, "paths": list(paths[:20]), "a": a, "b": b}, f, indent=1)

    def text(self):
        lines = []
        routes = sorted(set(self.same) | set(self.diff))
        total_same = sum(self.same.values())
        total_diff = sum(self.diff.values())
        lines.append(f"compared {total_same + total_diff} answers: {total_same} identical, {total_diff} different")
        lines.append("")
        for r in routes:
            lines.append(f"{self.same[r] + self.diff[r]:6d} {r}: {self.diff[r]} different")
            for p, n in self.where[r].most_common(6):
                lines.append(f"         {n:5d} at {p}")
        return "\n".join(lines) + "\n"


# ---- normalising ---------------------------------------------------------------------------------

CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"


def ulid_ms(text):
    n = 0
    for ch in text[:10]:
        n = n * 32 + CROCKFORD.index(ch)
    return n


def made_during_run(value, ids):
    """Ids, times and dates stamped after the run started: rows the write script made."""
    start = ids.get("start_ms") if ids else None
    if start is None:
        return False
    if isinstance(value, str) and ULID.match(value):
        return ulid_ms(value) >= start
    if isinstance(value, int) and not isinstance(value, bool) and start <= value < start + 86_400_000:
        return True
    if isinstance(value, str) and ISO.match(value):
        return value >= time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(start / 1000))
    return False


KNOWN = collections.Counter()


def normalise(route, body, ids=None):
    """Blank what the servers stamp themselves and what the write script made."""
    if isinstance(body, dict):
        out = collections.OrderedDict()
        for k, v in body.items():
            if k == "share":  # share links are not ported (spec README, deliberate differences)
                KNOWN["update.share (share links not ported)"] += 1
                continue
            if k in ("at",) and isinstance(v, str) and ISO.match(v):
                out[k] = "<at>"
            elif k == "seq" and route.startswith(("/api/snapshot", "/api/messages")) and isinstance(v, int) and len(out) == 0:
                out[k] = "<seq>"
            elif k in ("lastSeenAt", "last_seen_at", "lastHeartbeatAt") and ids is not None and ids.get("volatile"):
                out[k] = "<seen>"
            else:
                out[k] = normalise(route, v, ids)
        return out
    if isinstance(body, list):
        return [normalise(route, v, ids) for v in body]
    if made_during_run(body, ids):
        return "<new>"
    if isinstance(body, str) and ids and ids.get("start_ms"):
        for x in re.findall(r"[0-9A-HJKMNP-TV-Z]{26}", body):
            if ulid_ms(x) >= ids["start_ms"]:
                body = body.replace(x, "<new>")
        # Times the servers stamped during the write script, quoted inside a tool's text block.
        for x in re.findall(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z", body):
            if made_during_run(x, ids):
                body = body.replace(x, "<new>")
    return body


def compare(report, route, ra, rb, ids=None, bytes_only=False):
    (sa, ha, ba), (sb, hb, bb) = ra, rb
    if sa != sb:
        report.add(route, False, [f"status {sa} vs {sb}"])
        return
    if bytes_only:
        report.add(route, ba == bb, ["bytes"])
        return
    try:
        ja, jb = ordered(ba), ordered(bb)
    except ValueError:
        report.add(route, ba == bb, ["body (not JSON)"])
        return
    na, nb = normalise(route, ja, ids), normalise(route, jb, ids)
    if json.dumps(na) == json.dumps(nb):
        report.add(route, True)
    else:
        report.add(route, False, paths_differing(na, nb), na, nb)


# ---- the read sweep ------------------------------------------------------------------------------

def read_sweep(a, b, report, ids):
    def both(path, **kw):
        ra = a.request("GET", path)
        path_b = path
        for x, y in ids.get("a_to_b", {}).items():
            path_b = path_b.replace(x, y)
        rb = b.request("GET", path_b)
        compare(report, generic(path), ra, rb, ids, **kw)
        return ra

    snap = ordered(both("/api/snapshot?limit=200")[2])
    projects = snap.get("projects", [])
    for path in ("/api/summary", "/api/devices", "/api/notifications", "/api/notifications?unseen=true",
                 "/api/notifications?limit=500", "/api/snapshot/requests", "/api/snapshot/agents",
                 "/api/snapshot", "/api/snapshot?status=archived", "/api/snapshot?status=active",
                 "/api/messages", "/api/messages?limit=200", "/api/devices/current/alert-prefs"):
        both(path)

    update_ids, message_ids, post_ids, request_ids, media = set(), set(), set(), set(), {}

    def collect_updates(items):
        for u in items:
            update_ids.add(u["id"])
            for m in u.get("media", []):
                media[m["id"]] = m.get("variants", [])

    for project in projects:
        slug, pid = project["slug"], project["id"]
        both(f"/api/projects/{slug}/tags")
        both(f"/api/snapshot?project={pid}")
        both(f"/api/messages?project={slug}")
        cursor = None
        while True:
            path = f"/api/snapshot/updates?project={slug}&limit=200" + (f"&cursor={cursor}" if cursor else "")
            page = ordered(both(path)[2])
            updates = page.get("updates", {})
            collect_updates(updates.get("items", []))
            cursor = updates.get("nextCursor")
            if not updates.get("hasMore") or not cursor:
                break
        snapshot = ordered(both(f"/api/snapshot?project={slug}&limit=200")[2])
        for m in snapshot.get("messages", []):
            message_ids.add(m["id"])
            if m.get("updateId") is None and m.get("replyTo") is None:
                post_ids.add(m["id"])
        for ms in snapshot.get("messageMedia", {}).values():
            for m in ms:
                media[m["id"]] = m.get("variants", [])

    requests = ordered(both("/api/snapshot/requests")[2]).get("requests", [])
    for r in requests:
        request_ids.add(r["id"])
        for m in r.get("media", []):
            media[m["id"]] = m.get("variants", [])

    for u in sorted(update_ids):
        both(f"/api/messages?update={u}")
    for p in sorted(post_ids):
        both(f"/api/messages?replyTo={p}")
    for r in sorted(request_ids):
        both(f"/api/messages?request={r}")

    for mid, variants in sorted(media.items()):
        for v in ("original",) + tuple(x for x in variants if x != "original"):
            if v == "original":
                ra = both(f"/media/{mid}/original", bytes_only=True)
                rb = b.request("HEAD", f"/media/{mid}/original")
                names = ("content-type", "content-disposition", "etag", "cache-control", "x-content-type-options",
                         "content-security-policy", "accept-ranges", "content-length")
                ha = [(n, ra[1].get(n)) for n in names]
                hb = [(n, rb[1].get(n)) for n in names]
                report.add("/media/:id/original (headers)", ha == hb, [f"header {n}" for (n, x), (_, y) in zip(ha, hb) if x != y])
            else:
                ra, rb = a.request("GET", f"/media/{mid}/{v}"), b.request("GET", f"/media/{mid}/{v}")
                same = ra[0] == rb[0] and ra[1].get("content-type") == rb[1].get("content-type")
                report.add(f"/media/:id/{v} (status, type)", same, ["status or type"])
    return {"projects": projects, "updates": sorted(update_ids), "posts": sorted(post_ids), "requests": sorted(request_ids)}


def mcp_reads(a, b, report, ids, seen):
    def both(tool, args):
        ra, rb = a.mcp(tool, args), b.mcp(tool, args)
        compare(report, f"mcp {tool or 'tools/list'}", ra, rb, ids)

    both(None, {})
    both("list_projects", {})
    both("list_work", {})
    both("list_embed_types", {})
    both("list_apps", {})
    both("get_messages", {"mark_read": False})
    for project in seen["projects"][:30]:
        both("list_tags", {"project": project["slug"]})
        both("list_work", {"project": project["slug"]})
        both("get_messages", {"project": project["slug"], "mark_read": False})
    for u in seen["updates"][-60:]:
        both("get_card_fields", {"update_id": u})


# ---- the write script ----------------------------------------------------------------------------

class Stream(threading.Thread):
    """Reads one owner stream's frames until stopped."""

    def __init__(self, server):
        super().__init__(daemon=True)
        self.server, self.frames, self.stop = server, [], False

    def run(self):
        req = urllib.request.Request(self.server.base + "/api/stream",
                                     headers={"Authorization": f"Bearer {self.server.device_key}"})
        try:
            with urllib.request.urlopen(req, timeout=600) as r:
                frame = {}
                for raw in r:
                    if self.stop:
                        return
                    line = raw.decode().rstrip("\n")
                    if line == "":
                        if frame:
                            self.frames.append(frame)
                        frame = {}
                    elif not line.startswith(":"):
                        k, _, v = line.partition(":")
                        frame[k] = v[1:] if v.startswith(" ") else v
        except Exception:
            return


def writes(a, b, report, ids, seen):
    """The same writes on both, in lockstep; ids B made are mapped to A's."""
    b2a = ids["b_to_a"]
    sa, sb = Stream(a), Stream(b)
    sa.start(), sb.start()
    time.sleep(2)

    def call(route, method, path_a, path_b, body_a=None, body_b=None, tool=None):
        if tool:
            ra, rb = a.mcp(tool, body_a), b.mcp(tool, body_b)
        else:
            ra, rb = a.request(method, path_a, body_a), b.request(method, path_b, body_b)
        try:
            for x, y in zip(re.findall(r'"([0-9A-HJKMNP-TV-Z]{26})"', ra[2].decode()), re.findall(r'"([0-9A-HJKMNP-TV-Z]{26})"', rb[2].decode())):
                if x != y:
                    b2a.setdefault(y, x)
                    ids["a_to_b"].setdefault(x, y)
        except UnicodeDecodeError:
            pass
        compare(report, route, ra, rb, ids)
        return ordered(ra[2]) if ra[2][:1] in (b"{", b"[") else None, ordered(rb[2]) if rb[2][:1] in (b"{", b"[") else None

    a2b = lambda x: next((k for k, v in b2a.items() if v == x), x)
    proj = call("mcp create_project", None, None, None, {"name": "Shadow run"}, {"name": "Shadow run"}, tool="create_project")
    slug = proj[0]["result"]["structuredContent"]["project"]["slug"]
    upd = call("mcp post_update", None, None, None, {"project": slug, "body": "shadow **update**", "title": "Shadow"},
               {"project": slug, "body": "shadow **update**", "title": "Shadow"}, tool="post_update")
    ua = upd[0]["result"]["structuredContent"]["update"]["id"]
    ub = a2b(ua)
    for route, method, path, body in (
        ("POST /api/messages", "POST", "/api/messages", {"update": "{u}", "body": "owner reply"}),
        ("POST /api/cards/update/:id/status", "POST", "/api/cards/update/{u}/status", {"status": "in_progress"}),
        ("POST /api/cards/update/:id/pin", "POST", "/api/cards/update/{u}/pin", {"pinned": True}),
        ("POST /api/cards/update/:id/reactions", "POST", "/api/cards/update/{u}/reactions", {"emoji": "👍"}),
        ("POST /api/cards/update/:id/tags", "POST", "/api/cards/update/{u}/tags", {"tag": "shadow", "create": True}),
        ("POST /api/cards/update/:id/fields", "POST", "/api/cards/update/{u}/fields", {"name": "Branch", "value": "main"}),
        ("POST /api/messages (post)", "POST", "/api/messages", {"project": slug, "body": "a shadow post"}),
    ):
        ba = json.loads(json.dumps(body).replace("{u}", ua))
        bb = json.loads(json.dumps(body).replace("{u}", ub))
        call(route, method, path.replace("{u}", ua), path.replace("{u}", ub), ba, bb)
    ask_a = {"kind": "choice", "question": "Shadow?", "options": ["yes", "no"], "project": slug, "timeout_s": 60}
    req = call("mcp request_input", None, None, None, ask_a, ask_a, tool="request_input")
    ra_id = req[0]["result"]["structuredContent"]["request_id"]
    call("POST /api/requests/:id/answer", "POST", f"/api/requests/{ra_id}/answer", f"/api/requests/{a2b(ra_id)}/answer",
         {"value": "yes"}, {"value": "yes"})
    call("mcp await_request", None, None, None, {"request_id": ra_id}, {"request_id": a2b(ra_id)}, tool="await_request")
    time.sleep(3)
    sa.stop = sb.stop = True

    # The streams: same event types, in the same order, with the same payload keys.
    ta = [(f.get("event"), sorted(ordered(f["data"]).get("payload", {}).keys()) if f.get("data", "").startswith("{") else None)
          for f in sa.frames if f.get("event") not in (None, "hello")]
    tb = [(f.get("event"), sorted(ordered(f["data"]).get("payload", {}).keys()) if f.get("data", "").startswith("{") else None)
          for f in sb.frames if f.get("event") not in (None, "hello")]
    report.add("GET /api/stream (event sequence)", ta == tb, [f"events {len(ta)} vs {len(tb)}"])
    return slug


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--a", required=True)
    ap.add_argument("--b", required=True)
    ap.add_argument("--device-key", required=True)
    ap.add_argument("--agent-token", required=True)
    ap.add_argument("--report", required=True)
    ap.add_argument("--debug-dir")
    ap.add_argument("--no-writes", action="store_true")
    args = ap.parse_args()
    a = Server(args.a, args.device_key, args.agent_token)
    b = Server(args.b, args.device_key, args.agent_token)
    report = Report(args.debug_dir)
    ids = {"b_to_a": {}, "a_to_b": {}, "volatile": True}
    started = time.time()
    seen = read_sweep(a, b, report, ids)
    mcp_reads(a, b, report, ids, seen)
    if not args.no_writes:
        ids["start_ms"] = int(time.time() * 1000) - 1000
        writes(a, b, report, ids, seen)
        read_sweep(a, b, report, ids)
    with open(args.report, "w") as f:
        known = "".join(f"  {n} × {k}\n" for k, n in KNOWN.items())
        f.write(f"shadow run, {time.time() - started:.0f} s\n\nknown differences left out:\n{known}\n" + report.text())
    print(open(args.report).read())


if __name__ == "__main__":
    main()
