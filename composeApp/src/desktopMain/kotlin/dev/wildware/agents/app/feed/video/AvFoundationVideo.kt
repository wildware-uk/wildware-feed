package dev.wildware.agents.app.feed.video

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * A video on macOS: AVPlayer decodes it (with VideoToolbox, in hardware) and plays its sound, and an
 * AVPlayerItemVideoOutput hands over the picture due at each screen refresh, already scaled to the
 * size it is shown at. Driven from Kotlin through the Objective-C runtime; nothing native to build.
 */
internal class AvFoundationVideo(url: String) : NativeVideo {
    private interface CoreVideo : Library {
        fun CVPixelBufferLockBaseAddress(buffer: Pointer, flags: Long): Int

        fun CVPixelBufferUnlockBaseAddress(buffer: Pointer, flags: Long): Int

        fun CVPixelBufferGetBaseAddress(buffer: Pointer): Pointer?

        fun CVPixelBufferGetBytesPerRow(buffer: Pointer): Long

        fun CVPixelBufferGetWidth(buffer: Pointer): Long

        fun CVPixelBufferGetHeight(buffer: Pointer): Long

        fun CVBufferRelease(buffer: Pointer)
    }

    private interface QuartzCore : Library {
        fun CACurrentMediaTime(): Double
    }

    private val item: Pointer
    private val player: Pointer
    private var output: Pointer? = null
    private var outputWidth = 0
    private var outputHeight = 0

    /** Whether the owner wants it playing: AVPlayer stops by itself at the end, and that is "ended". */
    @Volatile
    private var wanted = false

    @Volatile
    private var ended = false

    @Volatile
    private var closed = false

    init {
        // Loading AVFoundation registers its classes with the runtime.
        Frameworks.load
        val made = ObjC.pool {
            val nsUrl = ObjC.send(ObjC.cls("NSURL"), "URLWithString:", ObjC.string(url)) ?: error("not a URL: $url")
            val item = ObjC.retain(ObjC.send(ObjC.cls("AVPlayerItem"), "playerItemWithURL:", nsUrl))!!
            val player = ObjC.retain(ObjC.send(ObjC.cls("AVPlayer"), "playerWithPlayerItem:", item))!!
            item to player
        }
        item = made.first
        player = made.second
        replaceOutput(0, 0)
    }

    /** A new output for pictures [width] by [height], or the video's own size when either is 0. */
    private fun replaceOutput(width: Int, height: Int) = ObjC.pool {
        val format = ObjC.send(ObjC.cls("NSNumber"), "numberWithUnsignedInt:", BGRA)
        val keys = mutableListOf(Frameworks.pixelFormatKey)
        val values = mutableListOf(format)
        if (width > 0 && height > 0) {
            keys += Frameworks.widthKey
            values += ObjC.send(ObjC.cls("NSNumber"), "numberWithInt:", width)
            keys += Frameworks.heightKey
            values += ObjC.send(ObjC.cls("NSNumber"), "numberWithInt:", height)
        }
        val keyArray = ObjC.send(ObjC.cls("NSArray"), "arrayWithObjects:count:", Pointers(keys), keys.size.toLong())
        val valueArray = ObjC.send(ObjC.cls("NSArray"), "arrayWithObjects:count:", Pointers(values), values.size.toLong())
        val settings = ObjC.send(ObjC.cls("NSDictionary"), "dictionaryWithObjects:forKeys:", valueArray, keyArray)
        val made = ObjC.send(ObjC.send(ObjC.cls("AVPlayerItemVideoOutput"), "alloc"), "initWithPixelBufferAttributes:", settings)
        output?.let {
            ObjC.sendVoid(item, "removeOutput:", it)
            ObjC.release(it)
        }
        ObjC.sendVoid(item, "addOutput:", made)
        output = made
        outputWidth = width
        outputHeight = height
    }

    override fun play() {
        if (closed) return
        if (ended) seek(0.0)
        ended = false
        wanted = true
        ObjC.pool { ObjC.sendVoid(player, "play") }
    }

    override fun pause() {
        if (closed) return
        wanted = false
        ObjC.pool { ObjC.sendVoid(player, "pause") }
    }

    override fun seek(seconds: Double) {
        if (closed) return
        ended = false
        ObjC.pool {
            ObjC.sendVoid(player, "seekToTime:toleranceBefore:toleranceAfter:", CMTime.of(seconds.coerceAtLeast(0.0)), CMTime.Zero, CMTime.Zero)
        }
    }

    override var volume: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (!closed) ObjC.pool { ObjC.sendVoid(player, "setVolume:", field) }
        }

    override fun status(): VideoStatus {
        if (closed) return VideoStatus(loading = false)
        return ObjC.pool {
            val itemStatus = ObjC.sendLong(item, "status")
            if (itemStatus == ItemFailed) {
                val error = ObjC.send(item, "error")
                val words = ObjC.text(ObjC.send(error, "localizedDescription"))
                return@pool VideoStatus(loading = false, error = words ?: "Couldn't play this video")
            }
            val position = ObjC.sendStruct(CMTime.ByValue::class.java, CMTime.Size, player, "currentTime").seconds ?: 0.0
            val duration = ObjC.sendStruct(CMTime.ByValue::class.java, CMTime.Size, item, "duration").seconds ?: 0.0
            val control = ObjC.sendLong(player, "timeControlStatus")
            if (wanted && control == ControlPaused && duration > 0 && position >= duration - 0.1) {
                wanted = false
                ended = true
            }
            VideoStatus(
                playing = control == ControlPlaying,
                positionSec = position,
                durationSec = duration,
                loading = itemStatus == ItemUnknown || control == ControlWaiting,
                ended = ended,
            )
        }
    }

    override fun pullFrame(frames: VideoFrames, maxWidth: Int, maxHeight: Int): Boolean {
        if (closed) return false
        return ObjC.pool {
            fitOutput(maxWidth, maxHeight)
            val out = output ?: return@pool false
            val now = Frameworks.quartz.CACurrentMediaTime()
            val itemTime = ObjC.sendStruct(CMTime.ByValue::class.java, CMTime.Size, out, "itemTimeForHostTime:", now)
            if (!ObjC.sendBool(out, "hasNewPixelBufferForItemTime:", itemTime)) return@pool false
            val buffer = ObjC.send(out, "copyPixelBufferForItemTime:itemTimeForDisplay:", itemTime, Pointer.NULL) ?: return@pool false
            val cv = Frameworks.coreVideo
            try {
                if (cv.CVPixelBufferLockBaseAddress(buffer, ReadOnly) != 0) return@pool false
                try {
                    val base = cv.CVPixelBufferGetBaseAddress(buffer) ?: return@pool false
                    frames.write(
                        cv.CVPixelBufferGetWidth(buffer).toInt(),
                        cv.CVPixelBufferGetHeight(buffer).toInt(),
                        cv.CVPixelBufferGetBytesPerRow(buffer).toInt(),
                        base,
                    )
                } finally {
                    cv.CVPixelBufferUnlockBaseAddress(buffer, ReadOnly)
                }
            } finally {
                cv.CVBufferRelease(buffer)
            }
            true
        }
    }

    /**
     * Asks for pictures the size they are shown at, never bigger than the video: a 4K video in a
     * card is scaled by VideoToolbox, not copied at full size. Only a real change of size swaps it.
     */
    private fun fitOutput(maxWidth: Int, maxHeight: Int) {
        if (maxWidth <= 0 || maxHeight <= 0) return
        val natural = ObjC.sendStruct(CGSize.ByValue::class.java, CGSize.Size, item, "presentationSize")
        if (natural.width <= 0 || natural.height <= 0) return
        val scale = minOf(1.0, maxWidth / natural.width, maxHeight / natural.height)
        val width = (natural.width * scale).toInt().coerceAtLeast(2) and 1.inv()
        val height = (natural.height * scale).toInt().coerceAtLeast(2) and 1.inv()
        val current = if (outputWidth == 0) natural.width.toInt() else outputWidth
        if (kotlin.math.abs(width - current) <= current / 10 && outputWidth != 0) return
        if (outputWidth == 0 && scale >= 0.9) return
        replaceOutput(width, height)
    }

    override fun close() {
        if (closed) return
        closed = true
        ObjC.pool {
            ObjC.sendVoid(player, "pause")
            output?.let { ObjC.sendVoid(item, "removeOutput:", it) }
            ObjC.sendVoid(player, "replaceCurrentItemWithPlayerItem:", Pointer.NULL)
        }
        ObjC.release(output)
        output = null
        ObjC.release(player)
        ObjC.release(item)
    }

    /** The frameworks, loaded once, and the CoreVideo keys an output is set up with. */
    private object Frameworks {
        val load: Unit = run {
            NativeLibrary.getInstance("AVFoundation")
            NativeLibrary.getInstance("CoreMedia")
        }
        val coreVideo: CoreVideo = Native.load("CoreVideo", CoreVideo::class.java)
        val quartz: QuartzCore = Native.load("QuartzCore", QuartzCore::class.java)
        private val cvLib = NativeLibrary.getInstance("CoreVideo")
        val pixelFormatKey: Pointer = cvLib.getGlobalVariableAddress("kCVPixelBufferPixelFormatTypeKey").getPointer(0)
        val widthKey: Pointer = cvLib.getGlobalVariableAddress("kCVPixelBufferWidthKey").getPointer(0)
        val heightKey: Pointer = cvLib.getGlobalVariableAddress("kCVPixelBufferHeightKey").getPointer(0)
    }

    /** A C array of object pointers, for arrayWithObjects:count:. */
    private class Pointers(items: List<Pointer?>) : com.sun.jna.Memory(Native.POINTER_SIZE.toLong() * items.size.coerceAtLeast(1)) {
        init {
            items.forEachIndexed { i, p -> setPointer(i.toLong() * Native.POINTER_SIZE, p) }
        }
    }

    private companion object {
        /** kCVPixelFormatType_32BGRA: 'BGRA'. */
        const val BGRA = 0x42475241
        const val ReadOnly = 1L
        const val ItemUnknown = 0L
        const val ItemFailed = 2L
        const val ControlPaused = 0L
        const val ControlWaiting = 1L
        const val ControlPlaying = 2L
    }
}
