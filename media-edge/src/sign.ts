/**
 * Signed media links. The server signs, the edge checks; both must agree byte for byte
 * (server `media/MediaEdge.kt`, shared cases in `test/vectors.json`).
 *
 * A link is `https://<edge>/<object key>?exp=<unix seconds>&ct=<content type>&cd=<content disposition>&sig=<signature>`.
 * The signature is HMAC-SHA256, base64url without padding, over these lines joined by "\n":
 * `v1`, the object key (not URL-encoded), exp, ct, cd.
 */

/** The longest a link may still be valid for; the server signs for at most about seven hours. */
export const MAX_LIFETIME_S = 48 * 3600;

export interface Signed {
  key: string;
  exp: number;
  contentType: string;
  disposition: string;
}

export function payload(key: string, exp: number, contentType: string, disposition: string): string {
  return ["v1", key, String(exp), contentType, disposition].join("\n");
}

const encoder = new TextEncoder();

async function hmacKey(secret: string, usage: "sign" | "verify"): Promise<CryptoKey> {
  return crypto.subtle.importKey("raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, [usage]);
}

export function base64url(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function fromBase64url(text: string): Uint8Array | null {
  if (!/^[A-Za-z0-9_-]{43}$/.test(text)) return null;
  const bin = atob(text.replace(/-/g, "+").replace(/_/g, "/") + "=");
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

/** The signature for these fields (the server's job; here for tests). */
export async function sign(secret: string, key: string, exp: number, contentType: string, disposition: string): Promise<string> {
  const mac = await crypto.subtle.sign("HMAC", await hmacKey(secret, "sign"), encoder.encode(payload(key, exp, contentType, disposition)));
  return base64url(new Uint8Array(mac));
}

/**
 * The signed fields when [params] carry a good, unexpired signature for [key]; otherwise null.
 * `crypto.subtle.verify` compares in constant time.
 */
export async function verify(secret: string, key: string, params: URLSearchParams, nowS: number): Promise<Signed | null> {
  const expText = params.get("exp");
  const contentType = params.get("ct");
  const disposition = params.get("cd");
  const sig = fromBase64url(params.get("sig") ?? "");
  if (expText === null || contentType === null || disposition === null || sig === null) return null;
  if (!/^\d{1,12}$/.test(expText)) return null;
  const exp = Number(expText);
  if (exp <= nowS || exp > nowS + MAX_LIFETIME_S) return null;
  if (contentType === "" || disposition === "") return null;
  const ok = await crypto.subtle.verify("HMAC", await hmacKey(secret, "verify"), sig, encoder.encode(payload(key, exp, contentType, disposition)));
  return ok ? { key, exp, contentType, disposition } : null;
}
