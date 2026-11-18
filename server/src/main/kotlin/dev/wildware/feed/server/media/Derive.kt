package dev.wildware.feed.server.media

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Ulid
import dev.wildware.feed.server.events.Bus
import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Running ffmpeg and ffprobe the way the TS server does (spec/07 §5, TS `src/media/ffmpeg.ts`). */
class FfmpegTools(private val ffmpeg: String = "ffmpeg", private val ffprobe: String = "ffprobe") {
    data class Probe(val durationMs: Long?, val width: Int?, val height: Int?, val videoCodec: String?, val audioCodec: String?)

    /** Whether ffmpeg can be run at all; a desktop install may not have it. */
    val available: Boolean by lazy { runCatching { run(ffmpeg, listOf("-version"), 10_000) }.isSuccess }

    fun probe(file: Path): Probe {
        val out = run(ffprobe, listOf("-v", "error", "-print_format", "json", "-show_format", "-show_streams", file.toString()), PROBE_TIMEOUT_MS).first
        val parsed = runCatching { Wire.json.parseToJsonElement(out).jsonObject }.getOrElse { throw IllegalStateException("ffprobe returned output that is not JSON") }
        val streams = parsed["streams"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        val format = parsed["format"]?.jsonObject
        if (format == null && streams.isEmpty()) throw IllegalStateException("ffprobe found no streams and no container in this file")
        val video = streams.firstOrNull { it.str("codec_type") == "video" }
        val audio = streams.firstOrNull { it.str("codec_type") == "audio" }
        return Probe(
            durationMs = seconds(format?.str("duration")) ?: seconds(video?.str("duration")),
            width = video?.get("width")?.jsonPrimitive?.intOrNull,
            height = video?.get("height")?.jsonPrimitive?.intOrNull,
            videoCodec = video?.str("codec_name"),
            audioCodec = audio?.str("codec_name"),
        )
    }

    fun ffmpeg(args: List<String>) {
        run(ffmpeg, listOf("-hide_banner", "-nostdin", "-loglevel", "error") + args, FFMPEG_TIMEOUT_MS)
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** `parseFloat`, whole milliseconds; null for missing, negative or not a number. */
    private fun seconds(value: String?): Long? {
        val parsed = value?.let { Regex("^\\s*[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?").find(it)?.value?.trim()?.toDoubleOrNull() } ?: return null
        return if (parsed.isFinite() && parsed >= 0) Math.round(parsed * 1000) else null
    }

    /** stdout and stderr; a failure is `<binary> failed: <last 4 stderr lines>`. */
    private fun run(binary: String, args: List<String>, timeoutMs: Long): Pair<String, String> {
        val process = try {
            ProcessBuilder(listOf(binary) + args).redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null").takeIf { it.exists() } ?: java.io.File("NUL"))).start()
        } catch (e: java.io.IOException) {
            throw IllegalStateException("$binary failed: ${e.message ?: "not found"} (is it installed?)")
        }
        val stdout = java.util.concurrent.CompletableFuture.supplyAsync { process.inputStream.readAllBytes().toString(Charsets.UTF_8) }
        val stderr = java.util.concurrent.CompletableFuture.supplyAsync { process.errorStream.readAllBytes().toString(Charsets.UTF_8) }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("$binary failed: timed out after $timeoutMs ms")
        }
        val err = stderr.get()
        if (process.exitValue() != 0) {
            val tail = err.trim().split('\n').takeLast(4).joinToString("; ").trim()
            throw IllegalStateException("$binary failed: ${tail.ifEmpty { "Command failed: $binary ${args.joinToString(" ")}" }}")
        }
        return stdout.get() to err
    }

    companion object {
        const val PROBE_TIMEOUT_MS = 30_000L
        const val FFMPEG_TIMEOUT_MS = 15 * 60_000L

        fun posterSeconds(durationMs: Long?): Double =
            if (durationMs == null || durationMs > 1200) 1.0 else maxOf(0.0, Math.round(durationMs / 2000.0 * 1000) / 1000.0)

        fun webPlayable(mime: String, p: Probe): Boolean {
            val (video, audio) = when (mime) {
                "video/mp4" -> listOf("h264") to listOf("aac", "mp3")
                "video/webm" -> listOf("vp8", "vp9", "av1") to listOf("opus", "vorbis")
                else -> return false
            }
            if (p.videoCodec == null || p.videoCodec !in video) return false
            return p.audioCodec == null || p.audioCodec in audio
        }

        /** JS `String(n)` for a seek time. */
        fun js(n: Double): String = if (n == Math.floor(n)) n.toLong().toString() else n.toString()
    }
}

/**
 * Turning uploaded originals into what cards show (spec/07 §5): WebP thumbnails at 640 and 1600 px
 * for images, a poster (and an mp4 when the original will not play in a browser) for video, a
 * duration for audio. Thumbnails are encoded by ffmpeg's libwebp at quality 80 after undoing the EXIF
 * orientation, so their bytes differ from the TS server's but their sizes and types do not. Without
 * ffmpeg, an image is ready with its original only (spec/README.md, known differences).
 */
class Derive(
    private val db: Database,
    private val bus: Bus,
    private val paths: MediaPaths,
    private val tools: FfmpegTools = FfmpegTools(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Produced(val kind: String, val width: Int?, val height: Int?, val name: String, val bytes: Long)
    private class Measured(val files: List<Produced>, val width: Int?, val height: Int?, val durationMs: Long?)
    private class Row(val id: String, val kind: String, val mime: String, val sha256: String, val status: String)

    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "media-derive").apply { isDaemon = true } }
    private val queued = ConcurrentHashMap.newKeySet<String>()

    /** One worker tick: queue every pending media with bytes, oldest first, 50 at a time. */
    fun tick() {
        val ids = db.query("SELECT id FROM media WHERE status IN ('pending') AND sha256 <> '' ORDER BY seq LIMIT 50") { it.getString(1) }
        for (id in ids) {
            if (!queued.add(id)) continue
            pool.execute {
                try {
                    process(id)
                } catch (e: Exception) {
                    System.err.println("derive $id failed: $e")
                } finally {
                    queued.remove(id)
                }
            }
        }
    }

    fun close() = pool.shutdownNow()

    /** Derive one media; never throws for a bad file (it is marked failed instead). */
    fun process(id: String) {
        val media = db.one("SELECT id, kind, mime, sha256, status FROM media WHERE id = ?", id) {
            Row(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5))
        } ?: return
        if (media.sha256.isEmpty() || media.status == "ready") return
        if (media.kind == "file") {
            Files.deleteIfExists(failureFile(id))
            ready(id, null, null, null)
            return
        }
        if (!Mime.isAllowed(media.mime)) return fail(id, "type is not servable: ${media.mime}")
        val measured = try {
            when (media.kind) {
                "video" -> video(media)
                "audio" -> audio(media)
                else -> image(media)
            }
        } catch (e: Exception) {
            return fail(id, reason(e))
        }
        for (f in measured.files) {
            db.update(
                "INSERT INTO derivatives (id, media_id, kind, path, bytes, width, height) VALUES (?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (media_id, kind, coalesce(width, -1)) DO UPDATE SET path = excluded.path, bytes = excluded.bytes, height = excluded.height",
                Ulid.next(clock()), id, f.kind, paths.relative(id, f.name), f.bytes, f.width, f.height,
            )
        }
        runCatching { Files.deleteIfExists(failureFile(id)) }
        ready(id, measured.width, measured.height, measured.durationMs)
    }

    private fun ready(id: String, width: Int?, height: Int?, durationMs: Long?) {
        val payload = db.one(
            "UPDATE media SET status = 'ready', width = coalesce(?, width), height = coalesce(?, height), duration_ms = coalesce(?, duration_ms) " +
                "WHERE id = ? RETURNING id, update_id, message_id, request_id, kind",
            width, height, durationMs, id,
        ) { rs ->
            fun s(i: Int) = rs.getString(i)?.let(::JsonPrimitive) ?: JsonNull
            buildJsonObject {
                put("mediaId", s(1)); put("updateId", s(2)); put("messageId", s(3)); put("requestId", s(4)); put("kind", s(5))
            }
        } ?: return
        bus.publish("media.ready", payload)
    }

    private fun failureFile(id: String) = paths.dir(id).resolve("error.txt")

    private fun fail(id: String, reason: String) {
        db.update("UPDATE media SET status = 'failed' WHERE id = ?", id)
        runCatching {
            Files.createDirectories(paths.dir(id))
            Files.writeString(failureFile(id), "${Wire.isoOf(clock())} $reason\n")
        }
    }

    private fun reason(e: Exception): String {
        val flat = (e.message ?: e.javaClass.simpleName).replace(Regex("\\s+"), " ").trim()
        return flat.ifEmpty { "unknown failure" }.take(500)
    }

    private fun image(media: Row): Measured {
        val source = paths.original(media.id, media.mime)
        val head = Files.newInputStream(source).use { it.readNBytes(256 * 1024) }
        val orientation = Orientation.of(head)
        if (!tools.available) {
            val dims = javax.imageio.ImageIO.createImageInputStream(source.toFile())?.use { input ->
                javax.imageio.ImageIO.getImageReaders(input).asSequence().firstOrNull()?.let { reader ->
                    reader.input = input
                    reader.getWidth(0) to reader.getHeight(0)
                }
            }
            val (w, h) = dims ?: (null to null)
            return if (Orientation.swapsAxes(orientation)) Measured(emptyList(), h, w, null) else Measured(emptyList(), w, h, null)
        }
        val probe = tools.probe(source)
        val rawW = probe.width ?: throw IllegalStateException("Input file contains unsupported image format")
        val rawH = probe.height ?: throw IllegalStateException("Input file contains unsupported image format")
        val (w, h) = if (Orientation.swapsAxes(orientation)) rawH to rawW else rawW to rawH
        Files.createDirectories(paths.dir(media.id))
        val files = THUMB_WIDTHS.map { width ->
            val height = maxOf(1, Math.round(h.toDouble() * width / w).toInt())
            val name = "thumb-$width.webp"
            val target = paths.dir(media.id).resolve(name)
            val filters = listOfNotNull(Orientation.filter(orientation), "scale=$width:$height:flags=lanczos").joinToString(",")
            tools.ffmpeg(
                listOf("-y", "-noautorotate", "-i", source.toString(), "-frames:v", "1", "-vf", filters, "-c:v", "libwebp", "-quality", "80", "-f", "webp", target.toString()),
            )
            if (!Files.isRegularFile(target) || Files.size(target) == 0L) throw IllegalStateException("ffmpeg produced no thumbnail from this image")
            Produced("thumb", width, height, name, Files.size(target))
        }
        return Measured(files, w, h, null)
    }

    private fun audio(media: Row): Measured {
        val duration = if (tools.available) runCatching { tools.probe(paths.original(media.id, media.mime)).durationMs }.getOrNull() else null
        return Measured(emptyList(), null, null, duration)
    }

    private fun video(media: Row): Measured {
        val source = paths.original(media.id, media.mime)
        val probe = tools.probe(source)
        Files.createDirectories(paths.dir(media.id))
        val files = mutableListOf<Produced>()
        val poster = paths.dir(media.id).resolve("poster.jpg")
        var wrote = false
        for (seek in listOf(FfmpegTools.posterSeconds(probe.durationMs), 0.0)) {
            tools.ffmpeg(listOf("-y", "-ss", FfmpegTools.js(seek), "-i", source.toString(), "-frames:v", "1", "-an", "-q:v", "3", "-f", "image2", poster.toString()))
            if (Files.isRegularFile(poster) && Files.size(poster) > 0) { wrote = true; break }
        }
        if (!wrote) throw IllegalStateException("ffmpeg produced no poster frame from this video")
        val posterDims = runCatching { javax.imageio.ImageIO.read(poster.toFile())?.let { it.width to it.height } }.getOrNull()
        files += Produced("poster", posterDims?.first ?: probe.width, posterDims?.second ?: probe.height, "poster.jpg", Files.size(poster))
        if (!FfmpegTools.webPlayable(media.mime, probe)) {
            val video = paths.dir(media.id).resolve("video.mp4")
            tools.ffmpeg(
                listOf(
                    "-y", "-i", source.toString(), "-map", "0:v:0", "-map", "0:a?", "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
                    "-pix_fmt", "yuv420p", "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2", "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart",
                    video.toString(),
                ),
            )
            if (!Files.isRegularFile(video) || Files.size(video) == 0L) throw IllegalStateException("ffmpeg produced no mp4 from this video")
            val transcoded = tools.probe(video)
            files += Produced("mp4", transcoded.width, transcoded.height, "video.mp4", Files.size(video))
        }
        return Measured(files, probe.width ?: posterDims?.first, probe.height ?: posterDims?.second, probe.durationMs)
    }

    companion object {
        val THUMB_WIDTHS = listOf(640, 1600)
        const val WORKER_INTERVAL_MS = 1000L
    }
}
