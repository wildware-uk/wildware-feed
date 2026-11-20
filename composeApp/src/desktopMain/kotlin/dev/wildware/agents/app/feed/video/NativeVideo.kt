package dev.wildware.agents.app.feed.video

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.sun.jna.Pointer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/** Where a video has got to, as its engine says. */
internal data class VideoStatus(
    val playing: Boolean = false,
    val positionSec: Double = 0.0,
    /** 0 until it is known. */
    val durationSec: Double = 0.0,
    /** Opening, or waiting for more of it to arrive. */
    val loading: Boolean = true,
    val ended: Boolean = false,
    val error: String? = null,
)

/**
 * A video played by the computer's own engine: it decodes (in hardware where it can), plays the
 * sound and keeps the two in step. The app only asks it, once per screen refresh, for the picture
 * due now ([pullFrame]), so frames reach the screen in time with it rather than on a timer.
 */
internal interface NativeVideo : AutoCloseable {
    fun play()

    fun pause()

    fun seek(seconds: Double)

    var volume: Float

    fun status(): VideoStatus

    /**
     * Copies the picture due on screen now into [frames], when there is a new one, no bigger than
     * [maxWidth] by [maxHeight] (the engine scales it down where it can). True when it copied one.
     */
    fun pullFrame(frames: VideoFrames, maxWidth: Int, maxHeight: Int): Boolean

    companion object {
        /** An engine for [url] on this computer, or null where the app plays video another way. */
        fun open(url: String): NativeVideo? {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            return when {
                os.contains("mac") -> AvFoundationVideo(url)
                os.contains("windows") -> MediaEngineVideo(url)
                else -> null
            }
        }

        /** Whether [open] gives an engine here. */
        val available: Boolean
            get() = System.getProperty("os.name").orEmpty().lowercase().let { it.contains("mac") || it.contains("windows") }
    }
}

/**
 * The pictures of a playing video, for Compose to draw. Each frame is copied once into a new,
 * immutable Skia bitmap, which Compose then draws without copying again; the last few are kept until
 * no draw can still be using them, then freed.
 */
internal class VideoFrames : AutoCloseable {
    /** The picture to draw now. Read it while drawing, so a new frame redraws without recomposing. */
    var image: ImageBitmap? by mutableStateOf(null)
        private set

    var width = 0
        private set
    var height = 0
        private set

    private var current: Bitmap? = null
    private val retired = ArrayDeque<Bitmap>()

    /** Takes a BGRA picture [width] by [height] from native memory at [pixels], [rowBytes] to a row. */
    fun write(width: Int, height: Int, rowBytes: Int, pixels: Pointer) {
        val bitmap = Bitmap()
        if (!bitmap.allocPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE))) {
            bitmap.close()
            return
        }
        val pixmap = bitmap.peekPixels() ?: run { bitmap.close(); return }
        try {
            val target = Pointer(pixmap.addr)
            val targetRow = pixmap.rowBytes
            val row = width * 4
            if (targetRow == rowBytes) {
                val size = rowBytes.toLong() * height
                target.getByteBuffer(0, size).put(pixels.getByteBuffer(0, size))
            } else {
                for (y in 0 until height) {
                    target.getByteBuffer(y.toLong() * targetRow, row.toLong()).put(pixels.getByteBuffer(y.toLong() * rowBytes, row.toLong()))
                }
            }
        } finally {
            pixmap.close()
        }
        bitmap.setImmutable()
        current?.let(retired::addLast)
        while (retired.size > Kept) retired.removeFirst().close()
        current = bitmap
        this.width = width
        this.height = height
        image = bitmap.asComposeImageBitmap()
    }

    override fun close() {
        image = null
        current?.close()
        current = null
        retired.forEach { it.close() }
        retired.clear()
    }

    private companion object {
        /** Frames older than this have been drawn and flushed: a draw only ever uses the newest. */
        const val Kept = 3
    }
}
