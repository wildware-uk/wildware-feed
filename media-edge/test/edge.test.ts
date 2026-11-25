import { createExecutionContext, env, waitOnExecutionContext } from "cloudflare:test";
import { beforeEach, describe, expect, it } from "vitest";
import worker, { keyOf, originAllowed, type Env } from "../src/index";
import { range } from "../src/range";
import { sign, verify } from "../src/sign";
import vectors from "./vectors.json";

const E = env as unknown as Env;
const SECRET = E.MEDIA_EDGE_SECRET!;
const BASE = "https://media.example.test";
const KEY = "t/01JD3QJ0000000000000000000/01/01JD3QK5Z8X9W7V6T5S4R3Q2P1/playback.mp4";
const BYTES = Uint8Array.from({ length: 1000 }, (_, i) => i % 251);

async function link(key = KEY, opts: { exp?: number; ct?: string; cd?: string; secret?: string } = {}): Promise<string> {
  const exp = opts.exp ?? Math.floor(Date.now() / 1000) + 3600;
  const ct = opts.ct ?? "video/mp4";
  const cd = opts.cd ?? "inline";
  const sig = await sign(opts.secret ?? SECRET, key, exp, ct, cd);
  const path = key.split("/").map(encodeURIComponent).join("/");
  return `${BASE}/${path}?exp=${exp}&ct=${encodeURIComponent(ct)}&cd=${encodeURIComponent(cd)}&sig=${sig}`;
}

async function call(url: string, init: RequestInit = {}, e: Env = E): Promise<Response> {
  const ctx = createExecutionContext();
  const res = await worker.fetch(new Request(url, init), e, ctx);
  // Let the cache copy finish, so the next request may be served from it.
  await waitOnExecutionContext(ctx);
  return res;
}

async function bytes(res: Response): Promise<Uint8Array> {
  return new Uint8Array(await res.arrayBuffer());
}

let n = 0;
/** A fresh key per test, so one test's cache copy never answers another's. */
async function stored(name = "playback.mp4", data: Uint8Array = BYTES): Promise<string> {
  const key = `t/01JD3QJ0000000000000000000/01/01JD3QK5Z8X9W7V6T5S4R3Q2P${n++}/${name}`;
  await E.MEDIA.put(key, data);
  return key;
}

beforeEach(() => {
  n += 100;
});

describe("signatures", () => {
  it("agree with the server's test vectors", async () => {
    for (const c of vectors.cases) {
      expect(await sign(vectors.secret, c.key, c.exp, c.ct, c.cd)).toBe(c.sig);
      // The server's own link parses back to the same fields and passes.
      const url = new URL(c.url);
      expect(url.origin).toBe(vectors.edge);
      expect(keyOf(url.pathname)).toBe(c.key);
      const ok = await verify(vectors.secret, c.key, url.searchParams, c.exp - 3600);
      expect(ok).toEqual({ key: c.key, exp: c.exp, contentType: c.ct, disposition: c.cd });
    }
  });

  it("serve a good link", async () => {
    const key = await stored();
    const res = await call(await link(key));
    expect(res.status).toBe(200);
    expect(await bytes(res)).toEqual(BYTES);
  });

  it("refuse a wrong signature with a bare 403", async () => {
    const key = await stored();
    const res = await call(await link(key, { secret: "someone-elses-secret-that-is-long-enough" }));
    expect(res.status).toBe(403);
    expect(await res.text()).toBe("");
    expect(res.headers.get("Cache-Control")).toBe("no-store");
  });

  it("refuse an expired link", async () => {
    const key = await stored();
    const res = await call(await link(key, { exp: Math.floor(Date.now() / 1000) - 1 }));
    expect(res.status).toBe(403);
  });

  it("refuse a link valid for too long", async () => {
    const key = await stored();
    const res = await call(await link(key, { exp: Math.floor(Date.now() / 1000) + 30 * 24 * 3600 }));
    expect(res.status).toBe(403);
  });

  it("refuse a link moved to another key, or with its headers changed", async () => {
    const a = await stored();
    const b = await stored("original.mp4");
    const good = new URL(await link(a));
    const moved = new URL(good);
    moved.pathname = "/" + b;
    expect((await call(moved.toString())).status).toBe(403);
    const download = new URL(good);
    download.searchParams.set("cd", "attachment");
    expect((await call(download.toString())).status).toBe(403);
    const html = new URL(good);
    html.searchParams.set("ct", "text/html");
    expect((await call(html.toString())).status).toBe(403);
    const unsigned = new URL(good);
    unsigned.searchParams.delete("sig");
    expect((await call(unsigned.toString())).status).toBe(403);
  });

  it("answer 503 without a secret", async () => {
    const key = await stored();
    const res = await call(await link(key), {}, { ...E, MEDIA_EDGE_SECRET: undefined });
    expect(res.status).toBe(503);
  });

  it("answer 404 for a signed key with no object", async () => {
    const res = await call(await link("01/01JD3QK5Z8X9W7V6T5S4R3QZZZ/nothing.mp4"));
    expect(res.status).toBe(404);
  });
});

describe("headers", () => {
  it("are the signed type and disposition, with the server's safety headers", async () => {
    const key = await stored("original.jpg");
    const cd = `attachment; filename="photo.jpg"; filename*=UTF-8''photo.jpg`;
    const res = await call(await link(key, { ct: "image/jpeg", cd }));
    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Type")).toBe("image/jpeg");
    expect(res.headers.get("Content-Disposition")).toBe(cd);
    expect(res.headers.get("X-Content-Type-Options")).toBe("nosniff");
    expect(res.headers.get("Content-Security-Policy")).toBe("default-src 'none'; sandbox");
    expect(res.headers.get("Accept-Ranges")).toBe("bytes");
    expect(res.headers.get("Content-Length")).toBe(String(BYTES.length));
    expect(res.headers.get("ETag")).toMatch(/^".+"$/);
    expect(res.headers.get("Cache-Control")).toMatch(/^private, max-age=\d+$/);
    await res.arrayBuffer();
  });

  it("let the app's pages read the file (CORS), and nobody else", async () => {
    const key = await stored();
    const url = await link(key);
    const team = await call(url, { headers: { Origin: "https://acme.feed.wildware.dev" } });
    expect(team.headers.get("Access-Control-Allow-Origin")).toBe("https://acme.feed.wildware.dev");
    expect(team.headers.get("Access-Control-Expose-Headers")).toContain("Content-Range");
    // The web app copies a picture with `credentials: 'include'`.
    expect(team.headers.get("Access-Control-Allow-Credentials")).toBe("true");
    await team.arrayBuffer();
    const other = await call(url, { headers: { Origin: "https://evil.example" } });
    expect(other.headers.get("Access-Control-Allow-Origin")).toBeNull();
    expect(other.headers.get("Access-Control-Allow-Credentials")).toBeNull();
    await other.arrayBuffer();
    expect(originAllowed("https://feed.wildware.dev", E)).toBe(true);
    expect(originAllowed("http://acme.feed.wildware.dev", E)).toBe(false);
    expect(originAllowed("https://feed.wildware.dev.evil.example", E)).toBe(false);
    expect(originAllowed("https://evilfeed.wildware.dev", E)).toBe(false);
    expect(originAllowed("http://localhost:8080", { ...E, EXTRA_ORIGINS: "http://localhost:8080" })).toBe(true);
  });

  it("answer a preflight from an allowed page", async () => {
    const res = await call(`${BASE}/anything`, {
      method: "OPTIONS",
      headers: { Origin: "https://acme.feed.wildware.dev", "Access-Control-Request-Method": "GET", "Access-Control-Request-Headers": "range" },
    });
    expect(res.status).toBe(204);
    expect(res.headers.get("Access-Control-Allow-Headers")).toContain("Range");
    const refused = await call(`${BASE}/anything`, { method: "OPTIONS", headers: { Origin: "https://evil.example" } });
    expect(refused.status).toBe(403);
  });

  it("refuse other methods", async () => {
    const key = await stored();
    expect((await call(await link(key), { method: "PUT", body: "x" })).status).toBe(405);
  });
});

describe("ranges and caching", () => {
  it("serve a range from the bucket, then from the cache", async () => {
    const key = await stored();
    const url = await link(key);
    for (const round of [1, 2]) {
      const res = await call(url, { headers: { Range: "bytes=10-19" } });
      expect(res.status, `round ${round}`).toBe(206);
      expect(res.headers.get("Content-Range")).toBe(`bytes 10-19/${BYTES.length}`);
      expect(res.headers.get("Content-Length")).toBe("10");
      expect(res.headers.get("Content-Type")).toBe("video/mp4");
      expect(await bytes(res)).toEqual(BYTES.slice(10, 20));
    }
  });

  it("serve from the cache once the bucket has lost it, by key and not by signature", async () => {
    const key = await stored();
    await (await call(await link(key))).arrayBuffer();
    await E.MEDIA.delete(key);
    // A different link (another expiry, so another signature) to the same file.
    const again = await call(await link(key, { exp: Math.floor(Date.now() / 1000) + 7200 }));
    expect(again.status).toBe(200);
    expect(await bytes(again)).toEqual(BYTES);
    // Still never without a good signature.
    expect((await call(await link(key, { secret: "someone-elses-secret-that-is-long-enough" }))).status).toBe(403);
  });

  it("serve suffix and open ranges, and refuse impossible ones", async () => {
    const key = await stored();
    const url = await link(key);
    const tail = await call(url, { headers: { Range: "bytes=-5" } });
    expect(tail.status).toBe(206);
    expect(await bytes(tail)).toEqual(BYTES.slice(995));
    const open = await call(url, { headers: { Range: "bytes=990-" } });
    expect(open.status).toBe(206);
    expect(await bytes(open)).toEqual(BYTES.slice(990));
    const key2 = await stored();
    const past = await call(await link(key2), { headers: { Range: "bytes=5000-" } });
    expect(past.status).toBe(416);
    expect(past.headers.get("Content-Range")).toBe(`bytes */${BYTES.length}`);
  });

  it("keep files over the cap out of the cache", async () => {
    const small = { ...E, CACHE_MAX_BYTES: "100" };
    const key = await stored();
    await (await call(await link(key), {}, small)).arrayBuffer();
    await E.MEDIA.delete(key);
    expect((await call(await link(key), {}, small)).status).toBe(404);
  });

  it("answer HEAD with the size and no body", async () => {
    const key = await stored();
    const res = await call(await link(key), { method: "HEAD" });
    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Length")).toBe(String(BYTES.length));
    expect(await res.text()).toBe("");
  });

  it("answer 304 to a matching If-None-Match, from the bucket and from the cache", async () => {
    const key = await stored();
    const url = await link(key);
    const first = await call(url);
    const etag = first.headers.get("ETag")!;
    await first.arrayBuffer();
    for (const round of [1, 2]) {
      const res = await call(url, { headers: { "If-None-Match": etag } });
      expect(res.status, `round ${round}`).toBe(304);
      expect(res.headers.get("ETag")).toBe(etag);
    }
    const fresh = await stored();
    const miss = await call(await link(fresh), { headers: { "If-None-Match": `W/${(await E.MEDIA.head(fresh))!.httpEtag}` } });
    expect(miss.status).toBe(304);
  });
});

describe("parsing", () => {
  it("turns paths into keys, refusing ones that climb out", () => {
    expect(keyOf("/01/abc/original.jpg")).toBe("01/abc/original.jpg");
    expect(keyOf("/t/x/01/abc/caf%C3%A9.jpg")).toBe("t/x/01/abc/café.jpg");
    expect(keyOf("/01/../secret")).toBeNull();
    expect(keyOf("/01//x")).toBeNull();
    expect(keyOf("/01/%2e%2e/x")).toBeNull();
    expect(keyOf("/01/a%2Fb")).toBeNull();
    expect(keyOf("/01/%E0%A4%A")).toBeNull();
    expect(keyOf("/01/a%0Ab")).toBeNull();
    expect(keyOf("/")).toBeNull();
  });

  it("reads ranges as the server does", () => {
    expect(range(null, 10)).toEqual({ kind: "whole" });
    expect(range("bytes=0-0", 10)).toEqual({ kind: "span", start: 0, end: 0 });
    expect(range("bytes=2-", 10)).toEqual({ kind: "span", start: 2, end: 9 });
    expect(range("bytes=-3", 10)).toEqual({ kind: "span", start: 7, end: 9 });
    expect(range("bytes=-30", 10)).toEqual({ kind: "span", start: 0, end: 9 });
    expect(range("bytes=5-100", 10)).toEqual({ kind: "span", start: 5, end: 9 });
    expect(range("bytes=10-", 10)).toEqual({ kind: "unsatisfiable" });
    expect(range("bytes=5-4", 10)).toEqual({ kind: "unsatisfiable" });
    expect(range("bytes=-0", 10)).toEqual({ kind: "unsatisfiable" });
    expect(range("bytes=0-1,3-4", 10)).toEqual({ kind: "whole" });
    expect(range("items=0-1", 10)).toEqual({ kind: "whole" });
  });
});
