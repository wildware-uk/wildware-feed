package dev.wildware.feed.parity

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.assertEquals

/** Making files to upload, and uploading them the way an agent does. */
object Media {
    /** A real PNG, [width]×[height], drawn with a gradient so every upload can differ by [seed]. */
    fun png(width: Int = 800, height: Int = 600, seed: Int = Parity.suffix().hashCode()): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) {
            image.setRGB(x, y, Color((x * 255 / width + seed) and 0xff, (y * 255 / height) and 0xff, seed and 0xff).rgb)
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    val ffmpegAvailable: Boolean by lazy {
        runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false)
    }

    /** A short test-pattern clip made with ffmpeg: "mp4" (h264) or "mov". */
    fun clip(format: String = "mp4", seconds: Double = 1.0): ByteArray {
        val file = Files.createTempFile("parity", ".$format")
        try {
            val process = ProcessBuilder(
                "ffmpeg", "-y", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc=duration=$seconds:size=160x120:rate=10",
                "-pix_fmt", "yuv420p", "-c:v", "libx264", file.toString(),
            ).redirectErrorStream(true).start()
            check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) {
                "ffmpeg failed: " + process.inputStream.bufferedReader().readText()
            }
            return Files.readAllBytes(file)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    fun tinyMp4(): ByteArray = clip("mp4", 0.5)

    /** Reserve an upload; returns the create_upload result. */
    suspend fun reserve(agent: Agent, filename: String, mime: String, bytes: Int) =
        agent.ok("create_upload", obj("filename" to filename, "mime" to mime, "bytes" to bytes))

    /** PUT bytes to an upload URL handed out by create_upload (always rewritten onto the base URL). */
    suspend fun put(uploadUrl: String, bytes: ByteArray?, mime: String?, configure: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
        val path = "/api/upload/" + uploadUrl.substringAfter("/api/upload/")
        return call(HttpMethod.Put, path) {
            if (bytes != null) {
                setBody(ByteArrayContent(bytes, mime?.let { ContentType.parse(it) } ?: ContentType.Application.OctetStream))
            }
            configure()
        }
    }

    /** Reserve and upload in one go; returns the media id. */
    suspend fun upload(agent: Agent, filename: String, mime: String, bytes: ByteArray): String {
        val reserved = reserve(agent, filename, mime, bytes.size)
        val response = put(reserved["upload_url"].s, bytes, mime)
        assertEquals(201, response.status.value, "upload $filename")
        return reserved["media_id"].s
    }

    suspend fun uploadPng(agent: Agent, width: Int = 800, height: Int = 600) = upload(agent, "picture.png", "image/png", png(width, height))
}
