package dev.wildware.agents.app.feed.video

import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.util.concurrent.ConcurrentHashMap

/**
 * Just enough of the Objective-C runtime to drive AVFoundation from Kotlin through JNA, with nothing
 * native to build: classes, selectors and objc_msgSend called with each method's own signature.
 * macOS only.
 */
internal object ObjC {
    private val objc = NativeLibrary.getInstance("objc")
    private val msgSend: Function = objc.getFunction("objc_msgSend")

    /** On Intel, a method returning a struct bigger than 16 bytes needs the _stret entry point. */
    private val msgSendStret: Function =
        if (System.getProperty("os.arch") == "aarch64") msgSend else objc.getFunction("objc_msgSend_stret")
    private val getClass = objc.getFunction("objc_getClass")
    private val registerName = objc.getFunction("sel_registerName")
    private val poolPush = objc.getFunction("objc_autoreleasePoolPush")
    private val poolPop = objc.getFunction("objc_autoreleasePoolPop")
    private val selectors = ConcurrentHashMap<String, Pointer>()

    fun cls(name: String): Pointer = getClass.invokePointer(arrayOf(name)) ?: error("no Objective-C class $name")

    fun sel(name: String): Pointer = selectors.getOrPut(name) { registerName.invokePointer(arrayOf(name)) }

    fun send(receiver: Pointer?, selector: String, vararg args: Any?): Pointer? =
        msgSend.invokePointer(arrayOf(receiver, sel(selector), *args))

    fun sendLong(receiver: Pointer?, selector: String, vararg args: Any?): Long =
        msgSend.invokeLong(arrayOf(receiver, sel(selector), *args))

    fun sendBool(receiver: Pointer?, selector: String, vararg args: Any?): Boolean =
        (msgSend.invokeInt(arrayOf(receiver, sel(selector), *args)) and 0xFF) != 0

    fun sendFloat(receiver: Pointer?, selector: String, vararg args: Any?): Float =
        msgSend.invokeFloat(arrayOf(receiver, sel(selector), *args))

    fun sendVoid(receiver: Pointer?, selector: String, vararg args: Any?) {
        msgSend.invokeVoid(arrayOf(receiver, sel(selector), *args))
    }

    /** A method returning a struct by value: a CMTime (24 bytes) or a CGSize (16). */
    fun <T : Structure> sendStruct(type: Class<T>, size: Int, receiver: Pointer?, selector: String, vararg args: Any?): T {
        val entry = if (size > 16) msgSendStret else msgSend
        @Suppress("UNCHECKED_CAST")
        return entry.invoke(type, arrayOf(receiver, sel(selector), *args)) as T
    }

    /** An NSString, autoreleased. */
    fun string(text: String): Pointer =
        send(cls("NSString"), "stringWithUTF8String:", (text + "\u0000").toByteArray(Charsets.UTF_8)) ?: error("no NSString")

    /** The UTF-8 text of an NSString, or null for nil. */
    fun text(nsString: Pointer?): String? = nsString?.let { send(it, "UTF8String")?.getString(0, "UTF-8") }

    fun retain(obj: Pointer?): Pointer? = obj?.also { sendVoid(it, "retain") }

    fun release(obj: Pointer?) {
        if (obj != null) sendVoid(obj, "release")
    }

    /** Runs [block] in an autorelease pool, so the objects AVFoundation hands back are freed. */
    inline fun <T> pool(block: () -> T): T {
        val pool = push()
        try {
            return block()
        } finally {
            pop(pool)
        }
    }

    fun push(): Pointer? = poolPush.invokePointer(emptyArray())

    fun pop(pool: Pointer?) {
        poolPop.invokeVoid(arrayOf(pool))
    }
}

/** CoreMedia's time: a count of [timescale]ths of a second. 24 bytes. */
@Structure.FieldOrder("value", "timescale", "flags", "epoch")
internal open class CMTime : Structure() {
    @JvmField var value: Long = 0

    @JvmField var timescale: Int = 0

    @JvmField var flags: Int = 0

    @JvmField var epoch: Long = 0

    /** Seconds, or null when the time isn't a number yet (an item still loading, a live stream). */
    val seconds: Double?
        get() = if ((flags and 1) == 0 || (flags and 0x1C) != 0 || timescale == 0) null else value.toDouble() / timescale

    class ByValue : CMTime(), Structure.ByValue

    companion object {
        const val Size = 24

        /** [seconds] at a fine enough scale for a video frame. */
        fun of(seconds: Double): ByValue = ByValue().apply {
            timescale = 600_000
            value = (seconds * timescale).toLong()
            flags = 1
        }

        val Zero: ByValue get() = ByValue().apply { timescale = 1; flags = 1 }
    }
}

/** CoreGraphics' size: two doubles. 16 bytes. */
@Structure.FieldOrder("width", "height")
internal open class CGSize : Structure() {
    @JvmField var width: Double = 0.0

    @JvmField var height: Double = 0.0

    class ByValue : CGSize(), Structure.ByValue

    companion object {
        const val Size = 16
    }
}
