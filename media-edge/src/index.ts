/**
 * The media edge (docs/HOSTING.md §4, "Media from the edge"). The server checks who may see a file, then redirects to a
 * link it signed here; this Worker checks the signature and serves the file from the bucket, kept
 * in Cloudflare's cache near the viewer, with byte ranges for video. Nothing here decides access:
 * a link without a good, unexpired signature gets 403 and nothing else.
 */
import { verify, type Signed } from "./sign";
import { range, notModified, singleRange } from "./range";

export interface Env {
  MEDIA: R2Bucket;
  /** A Worker secret, the server's MEDIA_EDGE_SECRET. */
  MEDIA_EDGE_SECRET?: string;
  /** Pages on this domain and its subdomains may fetch media (CORS). */
  ALLOWED_ORIGIN_DOMAIN?: string;
  /** More allowed origins, comma-separated. */
  EXTRA_ORIGINS?: string;
  /** The biggest file kept whole in the edge cache, in bytes. */
  CACHE_MAX_BYTES?: string;
}

const MIN_SECRET_LENGTH = 32;
const DEFAULT_CACHE_MAX = 100 * 1024 * 1024;

/** Keys this isolate is copying into the cache now, so a burst of range requests copies once. */
const filling = new Set<string>();

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const cors = corsHeaders(request.headers.get("Origin"), env);
    if (request.method === "OPTIONS") return preflight(cors);
    if (request.method !== "GET" && request.method !== "HEAD") {
      return bare(405, { Allow: "GET, HEAD, OPTIONS", ...cors });
    }
    const secret = env.MEDIA_EDGE_SECRET;
    if (!secret || secret.length < MIN_SECRET_LENGTH) return bare(503, cors);
    const url = new URL(request.url);
    const key = keyOf(url.pathname);
    const signed = key === null ? null : await verify(secret, key, url.searchParams, Math.floor(Date.now() / 1000));
    if (signed === null) return bare(403, cors);
    return serve(request, env, ctx, signed, url.origin, cors);
  },
} satisfies ExportedHandler<Env>;

/** The object key a path names (each segment URL-decoded), or null for one that can't be a key. */
export function keyOf(pathname: string): string | null {
  const parts = pathname.replace(/^\//, "").split("/");
  const out: string[] = [];
  for (const raw of parts) {
    let part: string;
    try {
      part = decodeURIComponent(raw);
    } catch {
      return null;
    }
    if (part === "" || part === "." || part === ".." || part.includes("/") || /[\u0000-\u001f\u007f]/.test(part)) return null;
    out.push(part);
  }
  const key = out.join("/");
  return key.length > 0 && key.length <= 1024 ? key : null;
}

function cacheKeyOf(origin: string, key: string): string {
  return `${origin}/${key.split("/").map(encodeURIComponent).join("/")}`;
}

async function serve(request: Request, env: Env, ctx: ExecutionContext, signed: Signed, origin: string, cors: Record<string, string>): Promise<Response> {
  const now = Math.floor(Date.now() / 1000);
  const base: Record<string, string> = {
    "Content-Type": signed.contentType,
    "Content-Disposition": signed.disposition,
    "X-Content-Type-Options": "nosniff",
    "Content-Security-Policy": "default-src 'none'; sandbox",
    "Accept-Ranges": "bytes",
    // The browser may keep it as long as the link is good; no shared cache may (it was signed for someone).
    "Cache-Control": `private, max-age=${Math.max(0, signed.exp - now)}`,
    ...cors,
  };
  const head = request.method === "HEAD";
  const rangeHeader = request.headers.get("Range");
  const inm = request.headers.get("If-None-Match");
  const cacheKey = cacheKeyOf(origin, signed.key);
  const cache = caches.default;

  // 1. The edge cache: Cloudflare answers ranges from a cached whole file itself.
  const cacheRange = singleRange(rangeHeader);
  const hit = await cache.match(new Request(cacheKey, { headers: cacheRange ? { Range: cacheRange } : {} }));
  if (hit) {
    const etag = hit.headers.get("ETag") ?? "";
    if (etag && notModified(inm, etag)) {
      await hit.body?.cancel();
      return new Response(null, { status: 304, headers: { ...base, ETag: etag } });
    }
    const headers: Record<string, string> = { ...base };
    for (const name of ["ETag", "Content-Length", "Content-Range"]) {
      const v = hit.headers.get(name);
      if (v !== null) headers[name] = v;
    }
    if (head) await hit.body?.cancel();
    return new Response(head ? null : hit.body, { status: hit.status, headers });
  }

  // 2. The bucket. A range needs the size first; a whole file comes in one read.
  let object: R2Object | R2ObjectBody | null;
  let span: { start: number; end: number } | null = null;
  if (head || cacheRange !== null) {
    object = await env.MEDIA.head(signed.key);
  } else {
    object = await env.MEDIA.get(signed.key);
  }
  if (object === null) return bare(404, cors);
  const etag = object.httpEtag;
  const size = object.size;
  const fill = () => {
    if (!head && size <= cacheMax(env) && !filling.has(signed.key)) {
      filling.add(signed.key);
      ctx.waitUntil(copyToCache(env, cache, cacheKey, signed.key).finally(() => filling.delete(signed.key)));
    }
  };
  if (notModified(inm, etag)) {
    if ("body" in object) await object.body.cancel();
    return new Response(null, { status: 304, headers: { ...base, ETag: etag } });
  }
  const wanted = range(rangeHeader, size);
  if (wanted.kind === "unsatisfiable") {
    if ("body" in object) await object.body.cancel();
    return new Response(null, { status: 416, headers: { ...base, ETag: etag, "Content-Range": `bytes */${size}` } });
  }
  if (wanted.kind === "span") span = wanted;
  fill();
  if (span === null) {
    const body = head ? null : (object as R2ObjectBody).body;
    return new Response(body, { status: 200, headers: { ...base, ETag: etag, "Content-Length": String(size) } });
  }
  const length = span.end - span.start + 1;
  const headers = { ...base, ETag: etag, "Content-Length": String(length), "Content-Range": `bytes ${span.start}-${span.end}/${size}` };
  if (head) return new Response(null, { status: 206, headers });
  const part = await env.MEDIA.get(signed.key, { range: { offset: span.start, length } });
  if (part === null) return bare(404, cors);
  return new Response(part.body, { status: 206, headers });
}

function cacheMax(env: Env): number {
  const n = Number(env.CACHE_MAX_BYTES);
  return Number.isFinite(n) && n >= 0 ? n : DEFAULT_CACHE_MAX;
}

/** Copies the whole object into the edge cache, by its key alone (never the signature). */
async function copyToCache(env: Env, cache: Cache, cacheKey: string, key: string): Promise<void> {
  try {
    const object = await env.MEDIA.get(key);
    if (object === null) return;
    await cache.put(
      cacheKey,
      new Response(object.body, {
        headers: {
          "Content-Type": "application/octet-stream",
          "Content-Length": String(object.size),
          ETag: object.httpEtag,
          // Objects never change under a key (they are per media and per variant).
          "Cache-Control": "public, max-age=31536000, immutable",
        },
      }),
    );
  } catch (e) {
    console.warn(`cache fill failed: ${e instanceof Error ? e.message : String(e)}`);
  }
}

/**
 * CORS for an allowed origin: the app's own domain and its subdomains, or one of EXTRA_ORIGINS.
 * Credentials are allowed because the web app's own fetches send its cookie to the dashboard and
 * follow the redirect here; this Worker never reads a cookie, so they change nothing.
 */
export function corsHeaders(origin: string | null, env: Env): Record<string, string> {
  const vary = { Vary: "Origin" };
  if (!origin || !originAllowed(origin, env)) return vary;
  return {
    ...vary,
    "Access-Control-Allow-Origin": origin,
    "Access-Control-Allow-Credentials": "true",
    "Access-Control-Expose-Headers": "Content-Length, Content-Range, Accept-Ranges, ETag, Content-Disposition",
  };
}

export function originAllowed(origin: string, env: Env): boolean {
  const extra = (env.EXTRA_ORIGINS ?? "").split(",").map((s) => s.trim()).filter((s) => s !== "");
  if (extra.includes(origin)) return true;
  const domain = (env.ALLOWED_ORIGIN_DOMAIN ?? "").trim().toLowerCase();
  if (domain === "") return false;
  let url: URL;
  try {
    url = new URL(origin);
  } catch {
    return false;
  }
  if (url.protocol !== "https:" || url.origin !== origin) return false;
  const host = url.hostname.toLowerCase();
  return host === domain || host.endsWith(`.${domain}`);
}

function preflight(cors: Record<string, string>): Response {
  if (!cors["Access-Control-Allow-Origin"]) return bare(403, cors);
  return new Response(null, {
    status: 204,
    headers: {
      ...cors,
      "Access-Control-Allow-Methods": "GET, HEAD",
      "Access-Control-Allow-Headers": "Range, If-None-Match",
      "Access-Control-Max-Age": "86400",
    },
  });
}

/** A status with no body and nothing about why. */
function bare(status: number, headers: Record<string, string>): Response {
  return new Response(null, { status, headers: { ...headers, "Cache-Control": "no-store" } });
}
