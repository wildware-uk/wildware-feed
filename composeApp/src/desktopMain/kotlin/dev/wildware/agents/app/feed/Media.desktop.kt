package dev.wildware.agents.app.feed

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

/** A desktop hands videos to the system's player: it already has play, scrub and full screen. */
actual val playsVideoInApp: Boolean = false

/** Never shown on a desktop, where [playsVideoInApp] is false. */
@Composable
actual fun InAppVideo(video: VideoSource, auth: MediaAuth?, modifier: Modifier) {
    Box(modifier)
}

/** The JVM can't decode the sounds agents send, so the system's player plays them. */
@Composable
actual fun rememberSoundPlayer(sound: Sound, auth: MediaAuth?): SoundPlayer? = null

@Composable
actual fun rememberPlatformMediaHandler(engine: () -> HttpClientEngine): MediaHandler = remember(engine) { DesktopMediaHandler(engine) }

/**
 * Videos and sounds go to a temporary folder, named by their kind, and open in the system's
 * player. Files go to Downloads under their own name and are not opened: a file from an agent
 * could be a program, and opening it would run it.
 */
class DesktopMediaHandler(
    private val engine: () -> HttpClientEngine,
    private val downloads: File = defaultDownloads(),
    private val playing: File = File(System.getProperty("java.io.tmpdir"), "wildware-feed-media"),
    private val openWithSystem: (File) -> Unit = { Desktop.getDesktop().open(it) },
) : MediaHandler {
    override suspend fun open(item: Playable, auth: MediaAuth?): String {
        val folder = File(playing, item.url.hashCode().toUInt().toString(16)).apply { mkdirs() }
        val target = File(folder, item.fileName)
        if (!target.exists() || target.length() == 0L) download(item.url, auth, target)
        withContext(Dispatchers.IO) { openWithSystem(target) }
        return "Opened in your player"
    }

    override suspend fun save(file: FileAttachment, auth: MediaAuth?): String {
        downloads.mkdirs()
        val target = freeName(downloads, safeFileName(file.name))
        download(file.url, auth, target)
        return "Saved to Downloads as ${target.name}"
    }

    private suspend fun download(url: String, auth: MediaAuth?, target: File) = withContext(Dispatchers.IO) {
        val partial = File(target.parentFile, target.name + ".part")
        HttpClient(engine()).use { http ->
            http.prepareGet(url) { auth.headersFor(url).forEach { (name, value) -> header(name, value) } }.execute { response ->
                if (!response.status.isSuccess()) error("the dashboard answered ${response.status.value}")
                response.bodyAsChannel().toInputStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
            }
        }
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
    }

    companion object {
        fun defaultDownloads(): File {
            val home = File(System.getProperty("user.home"))
            return File(home, "Downloads").takeIf { it.isDirectory } ?: home
        }

        /** [name] with nothing that could climb out of the folder or upset a file system. */
        fun safeFileName(name: String): String {
            // Only the last part of a path: "../../build.log" is saved as "build.log".
            val cleaned = name.substringAfterLast('/').substringAfterLast('\\').map { c -> if (c == '/' || c == '\\' || c == ':' || c.code < 32 || c in "<>\"|?*") '_' else c }
                .joinToString("")
                .trim()
                .trimStart('.')
            return cleaned.ifEmpty { "download" }.take(200)
        }

        /** [name] in [folder], or "name (2).ext" and so on when that is taken. */
        fun freeName(folder: File, name: String): File {
            var candidate = File(folder, name)
            val stem = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
            var n = 2
            while (candidate.exists()) candidate = File(folder, "$stem ($n)$ext").also { n++ }
            return candidate
        }
    }
}
