# 07 — Media, uploads and share links

Source of truth: the `agent-dashboard` working tree. All paths below are relative to it.
"ULID" = `^[0-9A-HJKMNP-TV-Z]{26}$` (`src/db/ids.ts:22-24`).

---

## 1. Configuration used here

| Var | Default | Use | Source |
|---|---|---|---|
| `DATA_DIR` | `data` (relative to cwd) | media root `<DATA_DIR>/media`, temp `<DATA_DIR>/tmp/uploads` | `src/config.ts:46`, `src/media/paths.ts:33-40` |
| `TOKEN_SECRET` | required, ≥32 chars | HMAC key for upload tokens and share-token hashes | `src/config.ts:57` |
| `PUBLIC_BASE_URL` | `http://localhost:5173`, trailing `/` stripped | absolute upload URL, share URL, agent download URL | `src/config.ts:60-66` |
| `MAX_IMAGE_BYTES` | 10485760 (10 MiB) | cap for kind `image` | `src/config.ts:69` |
| `MAX_VIDEO_BYTES` | 209715200 (200 MiB) | cap for kind `video` | `src/config.ts:72` |
| `MAX_FILE_BYTES` | 52428800 (50 MiB) | cap for kind `file` **and `audio`** | `src/config.ts:81`, `src/media/upload.ts:99-106` |
| `BODY_SIZE_LIMIT` | adapter default 512K | boot fails unless ≥ max of the three caps | `src/config.ts:290-307`, `src/hooks.server.ts:39` |

If config does not validate, the upload and media routes answer 503 (see §4, §6) (`src/http/media/env.ts:13-19`).

---

## 2. Types, kinds, allowlist (`src/media/mime.ts`)

Renderable allowlist (`ALLOWED_MIMES`, `src/media/mime.ts:39-65`), with stored extension (`:101-113`):

| mime | kind | ext |
|---|---|---|
| image/png | image | png |
| image/jpeg | image | jpg |
| image/webp | image | webp |
| image/gif | image | gif |
| video/mp4 | video | mp4 |
| video/webm | video | webm |
| video/quicktime | video | mov |
| audio/mpeg | audio | mp3 |
| audio/mp4 | audio | m4a |
| audio/ogg | audio | ogg |
| audio/wav | audio | wav |

- **Normalise**: `raw.split(';')[0].trim().toLowerCase()` (`:122-124`).
- **Aliases** (applied after normalise, `canonicalMime`, `:177-190`): `audio/x-m4a`→`audio/mp4`, `audio/m4a`→`audio/mp4`, `audio/x-wav`→`audio/wav`, `audio/wave`→`audio/wav`, `audio/vorbis`→`audio/ogg`, `audio/opus`→`audio/ogg`.
- **Kind `file`**: anything not on the allowlist that matches `^[a-z0-9][a-z0-9!#$&^_.+-]{0,62}\/[a-z0-9][a-z0-9!#$&^_.+-]{0,62}$` (`:88`, `:138-140`). Stored ext is always `bin` (`:79`, `:160-163`). Served as `application/octet-stream` (`:76`).
- Anything else (empty, not `type/subtype`) → refused `unsupported_type` (`src/media/upload.ts:170-171`, `:204-205`).
- SVG is never renderable; `image/svg+xml` is a valid `file` mime and downloads.
- **Sniffing** (renderable kinds only): npm `file-type` **22.0.2** `fileTypeFromBuffer(head)` on the first `SNIFF_BYTES = 4100` bytes, result passed through `canonicalMime` (`src/media/mime.ts:98`, `:203-207`). Empty head → no type. Exact file-type outputs that matter:
  - PNG with `acTL` before first `IDAT` → `image/apng` (refused) (`node_modules/file-type/source/detectors/png.js:24-26`).
  - ISO-BMFF `ftyp` major brand: `qt` → `video/quicktime`; `M4A ` → `audio/x-m4a`(→`audio/mp4`); `M4B`/`F4A`/`F4B` → `audio/mp4`; `M4V*` → `video/x-m4v` (refused); `M4P`/`F4V`/`F4P`/default → `video/mp4` (`node_modules/file-type/source/index.js:1283-1320`).
  - Ogg Opus → `audio/ogg; codecs=opus`(→`audio/ogg`); Ogg Theora → `video/ogg` (refused) (`index.js:713-753`).
  - EBML doctype `webm` → `video/webm` (matroska refused) (`detectors/ebml.js:109`).
  - MPEG ADTS → `audio/aac` (refused); MP3 frame / ID3 → `audio/mpeg` (`index.js:1866-1917`, `:592-595`).
  - The Kotlin port must reproduce file-type 22.0.2's verdicts for these 11 types on a ≤4100-byte prefix.

---

## 3. Upload reservation

### 3a. MCP `create_upload` (server-side contract)
`src/mcp/tools/create-upload.ts`, domain `src/domain/media.ts:137-152`, core `src/media/upload.ts:198-263`.

Input (zod, `create-upload.ts:18-47`): `filename: string, max 255` (UTF-16 length, before trim); `mime: string`; `bytes: int > 0`.

Checks in order (`src/media/upload.ts:198-220`):
1. filename trimmed; empty → `invalid_argument` "filename is required"; `trimmed.length > 255` → "filename must be at most 255 characters"; any code point `< 0x20` or `== 0x7f` → "filename contains control characters" (`:76-90`).
2. `mime = canonicalMime(mime)`; no kind → `unsupported_type` with message `JSON.stringify(mime) + " " + MIME_HELP` (`:120-124`, `:203-205`).
3. `bytes` not integer or `< 1` → `invalid_argument` (`:207-209`).
4. `bytes > limit(kind)` → `too_large` "`an|a <kind> may be at most <limit> bytes; this one declares <bytes>`" (article "an" for image/audio, "a" otherwise) (`:108-111`, `:211-216`).
5. Agent row missing → `not_found` (`:218-220`).

MediaError → tool error codes (`src/domain/media.ts:75-87`): `not_found`→`not_found`, `conflict`→`conflict`, everything else (`too_large`, `unsupported_type`, `invalid_argument`) → `invalid_argument`.

Writes, one transaction (`src/media/upload.ts:229-252`):
- `media` row: `agent_id`, `author = "agent:<agentId>"` (`src/db/media.ts:94`), `kind`, canonical `mime`, `bytes = declared`, `sha256 = ''` (means "no bytes yet"), `filename = trimmed` **only for kind `file`**, else NULL (the Kotlin server also keeps it for `audio` and `video`: see README "Known differences"); `status='pending'`, `created_at=now`.
- `upload_tokens` row: new ULID `id`, `agent_id`, `media_id`, `max_bytes = min(bytes, limit)` (= `bytes`, since step 4), `mime_allow = JSON ["<mime>"]`, `expires_at = now + 900000` (`src/media/tokens.ts:26`), `used_at NULL`.

Tool structured result (`create-upload.ts:113-121`): `{ media_id, upload_url, expires_at: ISO-8601 (Date.toISOString), max_bytes }`. Text: `Reserved <args.mime> upload <id>. PUT the raw bytes to <url> within 15 minutes, then pass <id> to post_update as media_ids.` (note: echoes the *raw* `args.mime`).

**Token format** (`src/media/tokens.ts:35-71`): `<tokenRowId>.<sig>`, `sig = base64url(HMAC-SHA256(TOKEN_SECRET, "agent-dashboard:upload-token:v1:" + tokenRowId))` (43 chars, no padding). Token row id ≠ media id. Parse: `split('.')` must give exactly 2 parts; id must be ULID; sig non-empty; constant-time compare. Any failure → one answer (`token_rejected`).

**Upload URL**: `PUBLIC_BASE_URL + "/api/upload/" + token` (`src/media/tokens.ts:29`, `:74-87`).

### 3b. Owner `POST /api/media`
`src/http/routes/api/media/+server.ts:10-12` → `src/http/owner/media.ts:28-45` → `src/domain/media.ts:179-209` → `src/media/upload.ts:161-196` + `storeBytes`.

- Auth: hook (session cookie, or device bearer) then `ownerAction` re-check; unauthenticated → `401 {"error":"unauthenticated"}` `content-type: application/json` (`src/http/auth/handle.ts:18-26`, `src/http/stream/owner.ts:60-83`).
- Request: raw body; `Content-Type` = declared mime; `?filename=` (default `"upload"`); `Content-Length` optional (`src/http/owner/media.ts:30-41`). Browser client: `src/web/actions.ts:468-473`.
- No body (adapter gives `null` when there is no `Content-Type`, or `Content-Length: 0`, or HTTP/1 with neither length nor chunked — `node_modules/@sveltejs/kit/src/exports/node/index.js:14-27`) → 400 `invalid_argument` "the request carried no body" (`src/domain/media.ts:184`).
- Same filename/mime checks as 3a; the filename is then stored only when kind is `file`, so an owner image has `filename: null` (`src/media/upload.ts:190`); declared `Content-Length > limit` → `too_large`. **Row is inserted before bytes are read** (`author='human'`, `agent_id NULL`, `bytes=0`, `sha256=''`), no token row (`src/media/upload.ts:180-195`).
- Then `storeBytes` (§4 steps 4-9) with `maxBytes = limit(kind)`, `allowed=[mime]`.
- All MediaErrors are translated (`translatingAsync`, `src/domain/media.ts:91-99`): `too_large`/`unsupported_type`/`invalid_argument` → **400**, `not_found` → 404, `conflict` → 409. Body `{"error":<code>,"message":<text>}` (`src/http/owner/actions.ts:159-160`).
- Success: `201 {"media": <Media>}`, `content-type: application/json; charset=utf-8`, `cache-control: no-store` (`src/http/owner/actions.ts:295-304`). `<Media>` key order: `seq,id,agentId,author,updateId,messageId,requestId,taskId,kind,mime,bytes,sha256,width,height,durationMs,filename,status,createdAt` (`src/db/media.ts:38-59`). `status` is `pending`.

---

## 4. `PUT /api/upload/[token]`
Mount `src/http/routes/api/upload/[token]/+server.ts:27-29`; handler `src/http/media/upload.ts:91-155`; ingest `src/media/ingest.ts:102-352`.

Only `PUT` is exported: other methods → SvelteKit `405` text `"<METHOD> method not allowed"`, `allow: PUT` (`node_modules/@sveltejs/kit/src/runtime/server/utils.js:17-26`). Exempt from the session guard (`src/http/auth/guard.ts:33`).

**Before the handler (framework):**
- CSRF: a `PUT` whose `Content-Type` (pre-`;`, trimmed, lower-cased) is `application/x-www-form-urlencoded`, `multipart/form-data`, `text/plain` or `application/x-sveltekit-formdata` and whose `Origin` ≠ the server origin (absent counts) → `403` text `Cross-site PUT form submissions are forbidden` (JSON `{"message":...}` if `Accept: application/json`). Token not touched (`node_modules/@sveltejs/kit/src/runtime/server/respond.js:80-101`, `utils/http.js:85-103`). Same rule applies to `POST /api/media`.
- `BODY_SIZE_LIMIT`: declared length over it, or streamed bytes over it, errors the body stream (`exports/node/index.js:40-79`).

**Handler order:**
1. Settings missing → `503 {"error":"misconfigured","message":"this deployment has no valid media configuration; see the server log"}` (`upload.ts:102-108`).
2. **Rate limit**: bucket = `token.split('.')[0].slice(0,64)`; sliding window **6 attempts / 60 000 ms**, every attempt counts (successful too), refusals not recorded; in-memory, max 10 000 keys LRU (`upload.ts:41-44`, `:86-88`, `:111-118`; `src/mcp/rate-limit.ts:51-113`). Over → `429 {"error":"rate_limited","message":"too many upload attempts for this token"}` + `retry-after: max(1, ceil(ms/1000))` (`src/mcp/rate-limit.ts:116-118`).
3. `contentLength = Number(header)` if finite and > 0, else null (`upload.ts:120`, `:128`).
4. Token parse/verify (§3a). Bad → 403 `token_rejected` (`ingest.ts:105-106`).
5. **Body null → 400 `invalid_argument`, token NOT spent** (`ingest.ts:96-100`, `:115`).
6. Spend: `UPDATE upload_tokens SET used_at=? WHERE id=? AND used_at IS NULL AND expires_at > ? RETURNING …` (`src/db/upload-tokens.ts:84-100`). No row (unknown/used/expired, `expires_at <= now`) → 403 (`ingest.ts:119-120`).
7. Media row gone → 404 `not_found`; `sha256 != ''` → 409 `conflict` (`ingest.ts:122-124`).
8. `storeBytes` (`ingest.ts:159-201`): for kind `file` no sniff, stored under row mime; else `allowed = token.mime_allow ∩ ALLOWED_MIMES`, empty → 415. Declared `contentLength > max_bytes` → 413 without reading (`:174-178`).
9. Stream (`ingest.ts:228-322`): each chunk adds to `total`; `total > max_bytes` → 413 (cancel reader, close+unlink temp). Renderable: buffer first 4100 bytes; when full (or at EOF if shorter) sniff; sniffed must be on allowlist **and** in `allowed` else 415. Only after the verdict is anything written. Temp file `<DATA_DIR>/tmp/uploads/<randomUUID>.part`, opened `wx`. `total == 0` → 400 "the request body was empty" (token already spent). SHA-256 hex over all bytes.
10. Place (`ingest.ts:331-352`): target `<DATA_DIR>/media/<id[0:2]>/<id>/original.<ext>` (mkdir -p). Dedup: newest media row with same sha256 (`ORDER BY seq DESC LIMIT 1`, `src/db/media.ts:130-137`); if it is another id **and same mime**, `link()` its original to target and delete temp; any link error → fall back. Otherwise `rename(temp, target)`.
11. `UPDATE media SET bytes=<actual>, sha256=<hex>` — status stays `pending` (`src/db/media.ts:154-170`, `ingest.ts:195-198`).

**Responses** (all `content-type: application/json`, `cache-control: no-store`, `upload.ts:73-78`):
- `201 {"media_id","kind","mime","bytes","sha256","status":"pending","deduped":bool}` in that key order (`upload.ts:133-141`).
- MediaError → `{"error":<code>,"message":<text>}` with status: `token_rejected` 403, `too_large` 413, `unsupported_type` 415, `invalid_argument` 400, `not_found` 404, `conflict` 409 (`upload.ts:64-71`, `:143-145`).
- Anything else (incl. body-stream errors: client abort, `BODY_SIZE_LIMIT`) → `500 {"error":"internal_error","message":"the dashboard failed to store this upload; see server logs"}` (`upload.ts:148-152`).

Exact messages:
- 403: `this upload token is not valid: it may be expired, already used, or not one of ours. Call create_upload again for a fresh one.` (`ingest.ts:203-210`)
- 413: `this upload may be at most <max> bytes. The token is spent either way: call create_upload again with the real size.` (`ingest.ts:212-217`)
- 415 (sniff): `these bytes are "<sniffed>"|of no recognised type, and this upload was reserved for <allowed joined ", ">. The declared type must match the actual bytes; SVG and anything off the allowlist is never accepted.` (`ingest.ts:267-273`); 415 (no servable type): `this upload authorises no servable type` (`:171`).
- 400 no body: `the request had no body. Send \`Content-Type\` set to the mime you declared to create_upload — a PUT without a Content-Type header has its body discarded before it reaches this route. This token has NOT been used; retry with the same upload_url.` (`ingest.ts:96-100`)

**Idempotency / reuse**: single use, enforced by the conditional UPDATE. After any outcome past step 6 (201, 413, 415, empty-body 400, 500) the token is spent and the reservation can never be filled; the agent must call `create_upload` again (new media id). The `Content-Type` header value is ignored except for body presence and the CSRF rule; the stored type is the reservation's. The upload publishes no event (`src/domain/media.ts:19-25`).

---

## 5. Derivative pipeline
`src/media/pipeline.ts`, `queue.ts`, `derive.ts`, `ffmpeg.ts`. Started at boot: `startDerivativeWorker()` (`src/hooks.server.ts:94`).

**Statuses**: `pending | ready | failed` only — no `processing` (`src/db/types.ts:152`). `failed` is terminal (never retried automatically).

**Worker** (`pipeline.ts:255-303`): first tick `setTimeout(0)`, then every `WORKER_INTERVAL_MS = 1000` (`:50`). Each tick: `SELECT … FROM media WHERE status IN ('pending') AND sha256 <> '' ORDER BY seq LIMIT 50` (`DERIVATIVE_BATCH`, `:39`; `src/db/media.ts:352-370`) and submits each id. Queue: concurrency `2` (`queue.ts:27`); a submit for an id already queued or running joins it (`queue.ts:122-136`). A crash mid-job leaves `pending` → retried next boot/tick. No other retries.

**`processMedia`** (`derive.ts:121-230`), never throws:
- No row → skip; `sha256==''` → skip; already `ready` (and not force) → skip.
- kind `file`: delete `error.txt`, set `ready`, publish `media.ready`. No derivatives (`:138-162`).
- mime not on allowlist → `failed` (`:164-168`).
- image/video/audio below; then `upsertDerivative` each file (conflict key `(media_id, kind, coalesce(width,-1))`, updates path/bytes/height — `src/db/derivatives.ts:79-103`); delete `error.txt`; `UPDATE media SET status='ready', width=coalesce(new,width), height=…, duration_ms=…` (`src/db/media.ts:183-205`); publish `media.ready`.
- Any throw → `status='failed'` (width/height untouched), write `<mediaDir>/error.txt` = `<ISO time> <reason>\n`, reason = error message whitespace-collapsed, max 500 chars (`derive.ts:60`, `:255-281`). No row points at `error.txt`; it is never served.

**Image** (`derive.ts:292-326`), sharp **0.35.3**:
- `sharp(original).metadata()`; media width/height = `metadata.autoOrient` (EXIF-rotated) dims.
- For `w` in `[640, 1600]` (`:51`): `sharp(original).rotate().resize({ width: w }).webp({ quality: 80 }).toFile(thumb-<w>.webp)`. Exact width `w`, aspect kept, **enlarges** smaller sources; all metadata (EXIF/GPS) dropped; GIF → first frame only.
- Rows: `kind='thumb'`, `width=w` (nominal), `height=<output height>`, `bytes=<output size>`.

**Video** (`derive.ts:367-412`, `ffmpeg.ts`):
- Probe: `ffprobe -v error -print_format json -show_format -show_streams <original>`; timeout 30 000 ms; maxBuffer 8 MiB; `execFile`, no shell (`ffmpeg.ts:34`, `:86-116`, `:149-166`). duration = `format.duration` else first video stream `duration`, seconds → `round(s*1000)` ms; width/height/codec from first video stream; audio codec from first audio stream. Neither format nor streams → error.
- Poster: seek `s = posterSeconds(durationMs)` = `1` if duration null or `> 1200` ms, else `round(durationMs/2000*1000)/1000` (`ffmpeg.ts:218-221`). Run `ffmpeg -hide_banner -nostdin -loglevel error -y -ss <s> -i <original> -frames:v 1 -an -q:v 3 -f image2 <dir>/poster.jpg`; if output missing/empty retry with `-ss 0`; still nothing → error `ffmpeg produced no poster frame from this video` (`derive.ts:423-457`). Timeout 15 min (`ffmpeg.ts:43`). Row `kind='poster'`, width/height = sharp metadata of the jpeg (fallback probe).
- Web-playable test (`ffmpeg.ts:186-207`): `video/mp4` needs video codec `h264` and audio `aac|mp3|none`; `video/webm` needs `vp8|vp9|av1` and `opus|vorbis|none`; `video/quicktime` never.
- Not playable → `ffmpeg -hide_banner -nostdin -loglevel error -y -i <original> -map 0:v:0 -map 0:a? -c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p -vf scale=trunc(iw/2)*2:trunc(ih/2)*2 -c:a aac -b:a 128k -movflags +faststart <dir>/video.mp4`; empty/missing → error (`derive.ts:460-501`). Re-probed; row `kind='mp4'`, real width/height.
- media width/height/duration = probe values (`derive.ts:406-411`).
- Failure message: `<binary> failed: <last 4 stderr lines joined "; ">` or `… (is it installed?)` on ENOENT (`ffmpeg.ts:164`, `:176-183`).

**Audio** (`derive.ts:351-365`): ffprobe for duration only; any probe failure ignored. No files. Always `ready`.

**Event `media.ready`** (exactly once per media; not on `force`) payload: `{ mediaId, updateId, messageId, requestId, kind }` (row as read back after the status flip) (`derive.ts:144-150`, `:207-215`; type `src/events/types.ts:100-107`). No `taskId`. Published synchronously right after the `ready` UPDATE.

---

## 6. Disk layout (Kotlin must read existing files)
`src/media/paths.ts:4-131`.

```
<DATA_DIR>/media/<id[0:2]>/<id>/original.<ext>     ext from §2 table, or "bin" for kind file
<DATA_DIR>/media/<id[0:2]>/<id>/thumb-640.webp
<DATA_DIR>/media/<id[0:2]>/<id>/thumb-1600.webp
<DATA_DIR>/media/<id[0:2]>/<id>/poster.jpg
<DATA_DIR>/media/<id[0:2]>/<id>/video.mp4
<DATA_DIR>/media/<id[0:2]>/<id>/error.txt          failure note, never served
<DATA_DIR>/tmp/uploads/<uuid>.part                  in-flight upload, never served
```
- Original path is **computed** from `media.id` + `media.mime` (`paths.ts:60-69`), not stored.
- Derivative path is **stored** in `derivatives.path`, relative to the media root with `/` separators: `<id[0:2]>/<id>/<name>` (`paths.ts:87-97`). On read it is resolved under the root and rejected if it escapes (`paths.ts:121-131`).
- Deduped originals are hard links; each row has its own directory.

Tables (final shapes): `media` `src/db/migrations/038-sound-on-a-card.ts:32-71`; `derivatives` (kind `thumb|poster|mp4`, unique `(media_id, kind, coalesce(width,-1))`, FK cascade) `001-initial-schema.ts:98-115`; `upload_tokens` (FK cascade) `001-initial-schema.ts:117-129`; `update_shares` `005-update-shares.ts:26-46`.

---

## 7. Serving `GET /media/[id]/[variant]`
Mount `src/http/routes/media/[id]/[variant]/+server.ts:18-20`; handler `src/http/media/serve.ts:84-159`; file lookup `src/media/serve.ts:125-206`.

**Auth**: hook first — no valid session cookie / device bearer → **`303` `location: /login?redirectTo=<encodeURIComponent(path+search)>`** (path is not under `/api`) (`src/http/auth/handle.ts:27-28`, `:43-53`; `guard.ts:66-70`). Handler re-checks and would answer `401 {"error":"unauthenticated"}` (`serve.ts:98-100`).

Then: settings missing → `503 {"error":"misconfigured"}` (`content-type: application/json`, `cache-control: no-store`) (`serve.ts:102-108`).

Variants: `original`, `thumb-640`, `thumb-1600`, `poster`, `video` (`src/media/serve.ts:52`).

**404** `{"error":"not_found"}` (`application/json`, `no-store`, `serve.ts:76-81`) when any of:
- variant not in the list; id not a ULID; no row; `status == 'failed'` (**all variants, including original**);
- kind ≠ `file` and row mime not on allowlist;
- kind `file` and variant ≠ `original`;
- original requested and `sha256 == ''`;
- derivative row missing (`findDerivative`: `kind` match and, for thumbs, width match; `ORDER BY width LIMIT 1`, `src/db/derivatives.ts:116-134`), or its path escapes the root;
- file on disk missing / not a regular file (`src/media/serve.ts:131-152`).
- `pending` with bytes **does** serve `original`.

Mime emitted: original = row mime (kind `file` → `application/octet-stream`); `thumb-*` `image/webp`; `poster` `image/jpeg`; `video` `video/mp4` (`src/media/serve.ts:62-67`).

**Headers on 200/206/304/416** (`serve.ts:121-133`):
```
content-type: <mime>
content-length: <stat size>            (200; 206 = span length; absent on 304/416)
cache-control: public, max-age=31536000, immutable
x-content-type-options: nosniff
content-disposition: inline | attachment[; filename="<ascii>"; filename*=UTF-8''<enc>]
content-security-policy: default-src 'none'; sandbox
accept-ranges: bytes
etag: "<media.sha256>-<variant>"
```
- Disposition (`serve.ts:172-180`): renderable → `inline`. `file` with no filename → `attachment`. Else `ascii` = filename with every UTF-16 code unit outside `0x20-0x7e` → `_`, then `"` and `\` → `_`; `enc` = JS `encodeURIComponent(filename)` (leaves `A-Za-z0-9-_.!~*'()` unescaped).
- No `Last-Modified`; `If-Modified-Since` and `If-Range` ignored.

**Conditional** (`serve.ts:135-139`, `:188-195`): `If-None-Match` trimmed `*`, or any comma-split candidate (trimmed, leading `W/` stripped) equal to the etag → `304`, no body, no content-length. Checked **before** Range.

**Range** (`src/http/media/range.ts:27-65`), regex `^bytes=(\d*)-(\d*)$` on the trimmed header (case-sensitive, no spaces):
- No header / no match (multi-range, other units) / `bytes=-` → `200` whole file.
- File size 0 → `416`.
- `bytes=-N`: N=0 → 416; else start = `max(size-N,0)`, end = `size-1`.
- `bytes=S-` / `bytes=S-E`: S ≥ size → 416; end = `min(E, size-1)` (or `size-1`); end < S → 416.
- `206` with `content-range: bytes <s>-<e>/<size>`, `content-length: e-s+1`, body exactly that span (`serve.ts:150-155`).
- `416` with `content-range: bytes */<size>`, no body (`serve.ts:143-148`).

`HEAD` is served by the GET handler with the body dropped (`node_modules/@sveltejs/kit/src/runtime/server/endpoint.js:19`). Media responses are never compressed (types not in the compress allowlist, `src/http/compress.ts:64-73`).

`derivativesFor` (what cards list as `variants`) reads rows only, never the disk: `[]` for missing/failed/off-allowlist; `file` → `["original"]` if bytes landed; else `original` (if bytes) then each derivative row in `ORDER BY kind, width, seq` (so `video` comes before `poster`; a `thumb` row whose width is not 640 or 1600 is skipped) (`src/media/serve.ts:224-271`, `src/db/derivatives.ts:106-113`). Card shape `MediaAttachment` `src/domain/media.ts:522-573`.

---

## 8. Agent fetch `GET /api/agent/media/[id]`
Mount `src/http/routes/api/agent/media/[id]/+server.ts:11-18` (variant forced to `original`); handler `src/http/media/agent.ts:53-80`. Exempt from session guard (`guard.ts:33`).

- Authorise = `authenticateMcpRequest` with `Authorization: Bearer <agent token>`, then `mediaIsAttached(id)` = row has non-null `update_id`, `message_id` or `request_id` (`src/domain/media.ts:402-425`). Rate limiter: a **separate** in-memory instance, default 120 / 60 s keyed by HMAC of the bearer (`agent.ts:58`, `src/mcp/rate-limit.ts:19-22`). A successful auth stamps `agents.last_seen_at` (`src/mcp/auth.ts:160`).
- **Every refusal is `404 {"error":"not_found"}`** — no bearer, malformed, revoked, rate-limited, unattached, unknown (`serve.ts:93-97`). No 401/429, no `retry-after`.
- Then identical to §7 for variant `original` (headers, Range, 304). Images/video/audio come back `inline` with their own type; only kind `file` is `attachment` + octet-stream. No project scoping.
- Where agents are told this URL: `PUBLIC_BASE_URL + "/api/agent/media/" + id` (`src/mcp/attachments.ts:112-115`).

---

## 9. Share links

### Mint / revoke — `POST|DELETE /api/updates/[id]/share`
Mount `src/http/routes/api/updates/[id]/share/+server.ts:11-15`; handlers `src/http/owner/shares.ts:41-71`; domain `src/domain/shares.ts:100-151`. Owner auth as §3b. JSON helper as §3b (`application/json; charset=utf-8`, `no-store`).

- **POST**: update missing or `deleted_at` set → `404 {"error":"not_found","message":"no such update: <id>"}`. Else: revoke any live share (`UPDATE update_shares SET revoked_at=now WHERE update_id=? AND revoked_at IS NULL`), token = `base64url(32 random bytes)` (43 chars), insert row `{id: ULID, update_id, token_hash, created_at: now}` (`src/db/shares.ts:67-84`), publish `update.updated {updateId, projectId, pinned}`. Response **200** `{"url":"<PUBLIC_BASE_URL>/s/<token>","share":{"id","update_id","created_at"}}` (`shares.ts:47-54`). The token is never retrievable again.
- `token_hash = hex(HMAC-SHA256(TOKEN_SECRET, "share:" + token))` (`src/domain/shares.ts:78-80`).
- **DELETE**: `200 {"revoked": true|false}`; never 404 (unknown id → `false`). Publishes `update.updated` only when something was revoked (`src/domain/shares.ts:131-142`).
- No expiry: a share lives until revoked or re-minted. At most one live share per update (partial unique index, `005-update-shares.ts:45-46`).
- Timeline exposes per-update `{views, sharedAt}` only (`src/domain/shares.ts:164-176`).

### `GET /s/[token]` (page)
`src/http/routes/s/[token]/+page.server.ts:18-37`, page `+page.svelte`. Exempt from session guard.
- Resolve: hash token, look up by `token_hash`, constant-time re-compare, `revoked_at` null, update exists and not deleted — any failure → SvelteKit `error(404, 'not found')` (default error page; no robots/cache headers set) (`src/domain/shares.ts:228-290`).
- On success: `views = views + 1, last_viewed_at = now` (every load, incl. crawlers, HEAD, `__data.json`) (`src/db/shares.ts:136-140`); response headers `x-robots-tag: noindex, nofollow`, `cache-control: no-store`; HTML also has `<meta name="robots" content="noindex, nofollow"/>` (self-closed, as Svelte renders it).
- Exposed data (`SharedCard`, `src/domain/shares.ts:190-214`): `update {id,title,body,level,createdAt,editedAt}`, `agentName` (agent name or `"An agent"`), `projectName` (or null), `media` (the update's attachments as `MediaAttachment[]`), `embeds`. No thread, no ids of agent/project.
- Unfurl meta (`src/web/preview.ts:49-92`): title = update title or `"<agent> in <project>"` / `"An update from <agent>"`; description = markdown excerpt ≤200 chars (`src/web/markdown.ts:112`, `:129-159`) or title; `og:url = <base>/s/<token>`; first `ready` image (`thumb-1600`, else `original`) or else first ready video's `poster` as `og:image` (absolute `<base>/s/<token>/media/<id>/<variant>`), width/height from the media row; first ready video → `og:video` (`video` variant → `video/mp4`, else `original` with row mime).

### `GET /s/[token]/media/[id]/[variant]`
`src/http/routes/s/[token]/media/[id]/[variant]/+server.ts:20-29`: §7 handler with `authorise = shareGrantsMedia` — live share, update not deleted, and `id` is in `SELECT … FROM media WHERE update_id = <shared update>` (`src/domain/shares.ts:267-275`). Any miss → `404 {"error":"not_found"}` (never 401). All §7 headers apply, **including `cache-control: public, max-age=31536000, immutable`**. Does not count a view.

---

## 10. Sweeper (orphan cleanup)
`startMediaSweeper()` (`src/hooks.server.ts:83`; `src/domain/media.ts:644-688`) → `sweepOrphanedMedia` (`src/media/sweeper.ts:70-99`).

- `setInterval` every **15 min** (`MEDIA_SWEEP_INTERVAL_MS`); no run at boot — first sweep 15 min after start.
- Selects up to **200** rows (`SWEEP_BATCH`) `ORDER BY seq` where: `update_id, message_id, request_id, task_id` all NULL; `status IN ('ready','pending','failed')`; `created_at < now - 3600000`; id not any `projects.theme` `$.logoMediaId`; id not any text value anywhere in `embeds.data` (`json_tree`) (`src/db/media.ts:384-425`).
- For each: `rm -rf <DATA_DIR>/media/<id[0:2]>/<id>`, then `DELETE FROM media WHERE id=?` (cascades derivatives and upload_tokens).
- Then `DELETE FROM upload_tokens WHERE used_at IS NULL AND expires_at <= now` (`src/db/upload-tokens.ts:106-109`). Spent tokens are kept forever.
- Nothing cleans `<DATA_DIR>/tmp/uploads`.

## 11. Embeds and other media references
- `embeds.data` (flat JSON of strings) may hold media ids (avatar/image); they protect media from the sweeper (above). The web client renders a ULID-looking value as `/media/<id>/thumb-640` (`src/web/embeds.ts:415`; `src/domain/embeds.ts:841-844`).
- `embed_types.spec` `image` (mark) may also be a media id (`src/domain/embeds.ts:235-240`) — **not** protected by the sweeper.
- `projects.theme.logoMediaId` (`src/db/types.ts:40`) — protected.
- Attaching: `attach_media` / `post_update` set `media.update_id` in one scoped UPDATE (`src/db/media.ts:254-280`, `src/domain/media.ts:290-329`); max 24 ids (`:72`). Attaching publishes no event.

---

## 12. Media retention (Kotlin server only, migration 048)

The owner chooses how long media is kept. Off by default; nothing is removed until it is turned on.

**Setting** (owner only, session or device key):
- `GET /api/settings/media-retention` → 200 `{"days": <int> | null, "kinds": ["image", "video", "audio", "file"]}`.
  `days: null` means never. `kinds` defaults to all four, in that order.
- `PUT /api/settings/media-retention` with `{"days": 1..3650 | null, "kinds"?: [...]}` → 200, the stored setting.
  Omitted `kinds` keeps the current ones. Errors (400 `invalid_argument`):
  - `days must be a whole number of days from 1 to 3650, or null for never`
  - `kinds must be a list of: image, video, audio, file`
  - `choose at least one kind of media`
  Kinds are deduped and put in the order above. The apps offer 7, 14, 30 and 90 days, or a custom number.

**Removal:** once a night, 02:00–05:00 UTC; it catches up if it hasn't run for 36 hours, and the last run
is kept in the database (`server_settings`), so restarts neither skip nor repeat it. Media is removed when
it is older than `days`, its kind is chosen, and it has bytes (`sha256 <> ''`, status `ready` or `failed`).
Kept whatever its age:
- media on a pinned card (update, message, task or request);
- media on a request that is still pending, or on an update or message with a pending request about it;
- project logos.

A removal deletes the media's folder from the store (original and derivatives), then, in one
transaction, deletes its `derivatives` rows and sets `media.removed_at` and `media.removed_after_days`.
A run cut short between the two is finished by the next one. It then publishes:

```
event: media.removed
data: {..., "payload": {"mediaId", "updateId", "messageId", "requestId", "taskId", "kind", "afterDays"}}
```

**Reading removed media:**
- In a card's `media` list it keeps its place and fields, with `variants: []` and one more key, last:
  `"removed": {"at": "<ISO time>", "afterDays": <int>}`. Media that is kept has no `removed` key, so
  everything reads exactly as before until something is removed. The apps show "Media removed after
  N days" in its place.
- `/media/<id>/<variant>` and `/api/agent/media/<id>` answer the usual 404.
- `get_messages` leaves it out with the note `<id> (<kind>, removed after N days)` and, in its
  attachment view, `included: false`, `reason: "removed after N days: the owner keeps media for N days"`
  and no `download_url`.
- A new upload with the same bytes is stored again, never linked to the removed one.

## Surprises

1. **Shard is always `01`.** `id[0:2]` of a ULID is the timestamp's top bits; every id minted between 2005 and 2039 starts `01`, so all media sits in `media/01/` (`src/media/paths.ts:51`).
2. **Comments contradict code on ranges**: `src/http/media/serve.ts:25-26` says `Accept-Ranges: none`; the code sends `bytes` and implements 206 (`:131`).
3. **413 and 415 burn the token**, as do an empty chunked body (400 "the request body was empty") and any stream error (500). Only "no body at all" (400) leaves it usable (`src/media/ingest.ts:115-120`). The burned reservation stays `pending` with `sha256=''` until swept.
4. **`BODY_SIZE_LIMIT` or client abort → 500 `internal_error`**, not 413, and the token is spent (the stream error is not a `MediaError`, `src/http/media/upload.ts:142-152`).
5. **CSRF 403 before any app code**: a PUT with `Content-Type: text/plain` (or a form type) and no matching `Origin` is refused by SvelteKit. A native client uploading a `.txt` to `POST /api/media` with a device bearer and `Content-Type: text/plain` gets 403 too.
6. **Owner upload errors flatten to 400** (no 413/415), and the media row exists before the bytes — every failed owner upload leaves an orphan for the sweeper (`src/domain/media.ts:189-208`).
7. **Sniffer traps**: an animated PNG (`image/apng`), an `M4V`-brand mp4 (`video/x-m4v`), Ogg Theora (`video/ogg`), AAC ADTS (`audio/aac`) are all 415. An AAC-in-mp4 with brand `isom` declared `audio/mp4` sniffs `video/mp4` → 415, despite the comment at `src/media/mime.ts:56-60`.
8. **Agent media route**: every refusal (incl. bad token, rate limit) is 404; images are served inline with their own type, not "always as a download" as `src/http/media/agent.ts:27-31` claims; no project scoping; its rate limiter is a different instance from `/mcp`'s.
9. **`cache-control: public, …, immutable` on session-guarded and share media.** A revoked share's media can live a year in any cache that saw it.
10. **Share page embeds break**: embed images use `/media/<id>/thumb-640` (session-only, redirects to login), and `shareGrantsMedia` only covers `media.update_id` rows, so `/s/<t>/media/<embed-id>/…` is 404 too.
11. `og:image:width/height` are the original's dimensions even when `thumb-1600` is the image (`src/web/preview.ts:76-77`).
12. Share views count every request to the page (crawlers, unfurls, refreshes). Shares never expire.
13. `failed` hides the original too, though the bytes are on disk (`src/media/serve.ts:134`).
14. `upload` 201 always reports `status:"pending"`, even for a `file` that becomes `ready` ≤1 s later.
15. Poster/mp4 rows store real width, so the "unique per (kind,width)" index does not stop two poster rows after a forced reprocess with different dims; `findDerivative` then picks the smallest width.
16. Video `media.width/height` come from ffprobe and ignore rotation metadata, while ffmpeg auto-rotates the poster, so a portrait phone video can report landscape dims.
17. ETag is `sha256-variant`; a forced reprocess that changes a derivative keeps the same ETag.
18. Migration 038 rebuilt `media` without recreating `media_request`/`media_task` indexes (`038-sound-on-a-card.ts:66-71` vs `030:44-45`).
19. `embed_types.spec` image marks that are media ids get swept after an hour.
20. `tmp/uploads/*.part` left by a crash are never cleaned.
21. Sweeper's first run is 15 min after boot; derivative worker's is immediate.
22. `create_upload` text echoes the raw `args.mime`, not the canonical one; `filename*` uses JS `encodeURIComponent`, which leaves `'()*!` unescaped (not strict RFC 5987); the ASCII fallback replaces each UTF-16 unit, so one emoji → `__`.
23. `media.ready` never carries `taskId`; `attach_media` publishes nothing, so attaching an already-`ready` item does not refresh open cards by itself.
24. `/s/[token]/media/…` reads `loadConfig()` inside authorise; a broken environment there is a 500, not the 503 the other media routes give.

## Parity test notes

Black-box, against both servers with the same `TOKEN_SECRET`, `PUBLIC_BASE_URL`, caps and a fresh `DATA_DIR`.

1. **PNG happy path**: `create_upload {filename:"a.png", mime:"image/png", bytes:N}` → `upload_url` = `<base>/api/upload/<ULID>.<43 b64url chars>`, `expires_at` = now+15 min ISO, `max_bytes=N`. `PUT` with `Content-Type: image/png` → 201, exact key order `media_id,kind,mime,bytes,sha256,status,deduped`, `status:"pending"`, `deduped:false`. Poll a card/timeline until `status:"ready"` (≤~2 s); `variants` = `["original","thumb-640","thumb-1600"]` (derivatives `ORDER BY kind, width, seq`); file `media/01/<id>/thumb-640.webp` is exactly 640 px wide.
2. **Re-PUT same token** → 403 `token_rejected`, exact message. After 6 PUTs within 60 s on one token id → 429 with `retry-after` ≥1.
3. **No body**: PUT with no `Content-Type` → 400 with the NO_BODY message; a following correct PUT on the same URL → 201.
4. **Oversize**: reserve `bytes:10`, PUT 11 bytes with `Content-Length: 11` → 413 (`at most 10 bytes`); same with chunked encoding (no length) → 413; token spent afterwards (403).
5. **Wrong type**: reserve `image/png`, PUT a zip → 415 `these bytes are "application/zip", and this upload was reserved for image/png. …`; PUT a 10-byte text file → `of no recognised type`. Reserve `image/png`, PUT an APNG → 415 `"image/apng"`.
6. **CSRF**: PUT with `Content-Type: text/plain`, no `Origin` → 403 text `Cross-site PUT form submissions are forbidden`; token still usable.
7. **File kind**: reserve `{mime:"text/plain", filename:"ü \"x\".txt"}`, PUT as `application/octet-stream` → 201 `kind:"file"`; after attach, `GET /media/<id>/original` → `content-type: application/octet-stream`, `content-disposition: attachment; filename="_ _x_.txt"; filename*=UTF-8''%C3%BC%20%22x%22.txt`; `GET …/thumb-640` → 404.
8. **Dedup**: upload the same PNG bytes to two reservations → second 201 `deduped:true`; both originals share an inode.
9. **Serve headers**: owner GET original → 200 with all 8 headers byte-exact; `If-None-Match: "<sha>-original"` → 304 no `content-length`; `W/"<sha>-original"` → 304; `*` → 304.
10. **Range**: `bytes=0-9` → 206, `content-range: bytes 0-9/<size>`, 10 bytes; `bytes=5-` → to end; `bytes=-4` → last 4; `bytes=<size>-` → 416 `content-range: bytes */<size>`; `bytes=5-2` → 416; `bytes=0-1,4-5` → 200 whole; `Bytes=0-1` → 200 whole.
11. **404 matrix**: bad ULID, unknown id, unknown variant, `thumb-640` on a still-pending image, a `failed` media's original → all `404 {"error":"not_found"}` with `cache-control: no-store`.
12. **Unauthenticated `/media/…`** → 303 `location: /login?redirectTo=%2Fmedia%2F<id>%2Foriginal`.
13. **Agent fetch**: attached media + valid bearer → 200 (inline for PNG); unattached → 404; no bearer → 404 (not 401).
14. **Video**: upload a `.mov` → ready with `poster` (jpeg) and `video` (h264 mp4 with even dims, faststart); an h264/aac `.mp4` → `poster` only, no `video` variant; `duration_ms` set. A 0.5 s clip → poster taken at 0.25 s and present.
15. **Corrupt image** (valid PNG header, garbage after) → `status:"failed"`, `error.txt` present, every variant 404.
16. **Share**: POST share → 200 `{url, share{id,update_id,created_at}}`, url = `<base>/s/<43 chars>`; GET url → 200 with `x-robots-tag: noindex, nofollow`, `cache-control: no-store`; views +1 per GET. `/s/<t>/media/<attached id>/original` → 200; another update's media id → 404. POST share again → first url 404, second 200. DELETE → `{"revoked":true}`, url → 404, media under it → 404; DELETE again → `{"revoked":false}`; DELETE on unknown update → 200 `{"revoked":false}`. POST on deleted update → 404.
17. **Sweeper**: an unattached ready media with `created_at` > 1 h old is gone (row + directory) after the next 15-minute tick; a project logo and a media id inside `embeds.data` survive; expired unused token rows are deleted, spent ones kept.
18. **Owner upload**: `POST /api/media?filename=a.png` with `Content-Type: image/png` → 201 `{"media":{…}}` key order per §3b, `author:"human"`, `agentId:null`; oversize → **400** (not 413); no body → 400 `the request carried no body`.
