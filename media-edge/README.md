# Media edge

A Cloudflare Worker that serves media files from the bucket, close to the viewer.

The server still decides who may see a file. When it is set up for the edge (`MEDIA_EDGE_URL` and
`MEDIA_EDGE_SECRET`, see [docs/HOSTING.md](../docs/HOSTING.md)), `/media/<id>/<variant>` checks
access as before and then answers `302` with a signed link here. This Worker checks the signature
and sends the bytes from the bucket, with byte ranges for video, and keeps files in Cloudflare's
cache so the next view is fast. A link with a missing, wrong or expired signature gets `403` and
nothing else.

## The link

`https://<edge>/<object key>?exp=<unix seconds>&ct=<content type>&cd=<content disposition>&sig=<signature>`

The signature is HMAC-SHA256 under the shared secret, base64url without padding, over these lines
joined by `\n`: `v1`, the object key (not URL-encoded), `exp`, `ct`, `cd`. The server
(`server/.../media/MediaEdge.kt`) and this Worker (`src/sign.ts`) are both tested against
[`test/vectors.json`](test/vectors.json).

## Settings

| Name | Where | What |
| --- | --- | --- |
| `MEDIA` | R2 binding (`wrangler.jsonc`) | The media bucket, the one the server's `S3_BUCKET` names. |
| `MEDIA_EDGE_SECRET` | Worker secret | The same value as the server's `MEDIA_EDGE_SECRET`, at least 32 characters. |
| `ALLOWED_ORIGIN_DOMAIN` | var | Pages on this domain and its subdomains may fetch files (CORS). |
| `EXTRA_ORIGINS` | var | More origins that may, comma-separated. |
| `CACHE_MAX_BYTES` | var | Files up to this size are cached whole at the edge (default 100 MB). |

## Deploy

```sh
npm ci
npm run check
npx wrangler secret put MEDIA_EDGE_SECRET
npx wrangler deploy
```

`wrangler.jsonc` routes the Worker to its own custom domain, which Cloudflare sets up on deploy.
