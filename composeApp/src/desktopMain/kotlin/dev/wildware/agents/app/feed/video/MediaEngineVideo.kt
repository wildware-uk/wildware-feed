package dev.wildware.agents.app.feed.video

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference

/**
 * A video on Windows: Media Foundation's media engine (the one behind the system's own players)
 * reads, decodes in hardware through Direct3D 11, plays the sound and keeps it in step. At each
 * screen refresh the app asks it for the picture due, which the GPU scales into a texture of the size
 * it is shown at; only that is copied to Compose. Plain COM calls through JNA; nothing native to build.
 */
internal class MediaEngineVideo(url: String) : NativeVideo {
    private val device: Com
    private val context: Com
    private val manager: Com
    private val engine: Com
    private val notify = Notify()

    /** The GPU texture each picture is drawn into, and the copy of it the CPU can read. */
    private var target: Com? = null
    private var staging: Com? = null
    private var textureWidth = 0
    private var textureHeight = 0
    private var hasFrame = false

    @Volatile
    private var closed = false

    init {
        Win.startup
        Win.comOnThisThread()
        val (dev, ctx) = Win.createDevice()
        device = dev
        context = ctx
        // Media Foundation decodes on its own threads with the same device.
        device.query(Win.IID_ID3D10Multithread)?.let { mt ->
            mt.call(5, 1)
            mt.release()
        }
        val token = IntByReference()
        val managerRef = PointerByReference()
        Win.check(Win.mfplat.getFunction("MFCreateDXGIDeviceManager").invokeInt(arrayOf(token, managerRef)), "MFCreateDXGIDeviceManager")
        manager = Com(managerRef.value)
        Win.check(manager.call(7, device.p, token.value), "ResetDevice")

        val attributesRef = PointerByReference()
        Win.check(Win.mfplat.getFunction("MFCreateAttributes").invokeInt(arrayOf(attributesRef, 3)), "MFCreateAttributes")
        val attributes = Com(attributesRef.value)
        try {
            Win.check(attributes.call(27, Win.guid(Win.MF_MEDIA_ENGINE_CALLBACK), notify.pointer), "callback")
            Win.check(attributes.call(27, Win.guid(Win.MF_MEDIA_ENGINE_DXGI_MANAGER), manager.p), "manager")
            Win.check(attributes.call(21, Win.guid(Win.MF_MEDIA_ENGINE_VIDEO_OUTPUT_FORMAT), DXGI_FORMAT_B8G8R8A8_UNORM), "format")
            val factoryRef = PointerByReference()
            Win.check(
                Win.ole32.getFunction("CoCreateInstance").invokeInt(
                    arrayOf(Win.guid(Win.CLSID_MFMediaEngineClassFactory), null, CLSCTX_INPROC_SERVER, Win.guid(Win.IID_IMFMediaEngineClassFactory), factoryRef),
                ),
                "CoCreateInstance",
            )
            val factory = Com(factoryRef.value)
            try {
                val engineRef = PointerByReference()
                Win.check(factory.call(3, createFlags, attributes.p, engineRef), "CreateInstance")
                engine = Com(engineRef.value)
            } finally {
                factory.release()
            }
        } finally {
            attributes.release()
        }
        val source = Win.oleaut32.getFunction("SysAllocString").invokePointer(arrayOf(com.sun.jna.WString(url)))
        try {
            Win.check(engine.call(6, source), "SetSource")
        } finally {
            Win.oleaut32.getFunction("SysFreeString").invokeVoid(arrayOf(source))
        }
    }

    override fun play() {
        if (closed) return
        if (engine.callBool(27)) engine.call(17, 0.0)
        engine.call(32)
    }

    override fun pause() {
        if (!closed) engine.call(33)
    }

    override fun seek(seconds: Double) {
        if (!closed) engine.call(17, seconds.coerceAtLeast(0.0))
    }

    override var volume: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (!closed) engine.call(37, field.toDouble())
        }

    override fun status(): VideoStatus {
        if (closed) return VideoStatus(loading = false)
        val errorRef = PointerByReference()
        if (engine.call(3, errorRef) == 0 && errorRef.value != null) {
            val error = Com(errorRef.value)
            val code = error.call(3) and 0xFFFF
            error.release()
            if (code != 0) return VideoStatus(loading = false, error = ErrorWords[code] ?: "Couldn't play this video")
        }
        val ready = engine.call(14) and 0xFFFF
        val paused = engine.callBool(20)
        val ended = engine.callBool(27)
        val duration = engine.callDouble(19).takeIf { it.isFinite() && it > 0 } ?: 0.0
        return VideoStatus(
            playing = !paused && !ended,
            positionSec = engine.callDouble(16),
            durationSec = duration,
            // HAVE_FUTURE_DATA (3) or better: it can play on from here.
            loading = ready < 3 || engine.callBool(15),
            ended = ended,
        )
    }

    override fun pullFrame(frames: VideoFrames, maxWidth: Int, maxHeight: Int): Boolean {
        if (closed) return false
        val pts = LongByReference()
        val newFrame = engine.call(44, pts) == 0
        val w = IntByReference()
        val h = IntByReference()
        if (engine.call(40, w, h) != 0 || w.value <= 0 || h.value <= 0) return false
        val scale = if (maxWidth > 0 && maxHeight > 0) minOf(1.0, maxWidth.toDouble() / w.value, maxHeight.toDouble() / h.value) else 1.0
        val width = (w.value * scale).toInt().coerceAtLeast(2) and 1.inv()
        val height = (h.value * scale).toInt().coerceAtLeast(2) and 1.inv()
        val resized = kotlin.math.abs(width - textureWidth) > textureWidth / 10 || textureHeight == 0
        // A new size redraws the current picture even when no new one is due.
        if (!newFrame && !(resized && hasFrame)) return false
        if (resized) makeTextures(width, height)
        val rect = Memory(16).apply {
            setInt(0, 0)
            setInt(4, 0)
            setInt(8, textureWidth)
            setInt(12, textureHeight)
        }
        if (engine.call(43, target!!.p, null, rect, Win.Black) != 0) return false
        context.call(47, staging!!.p, target!!.p)
        val mapped = Memory(16)
        if (context.call(14, staging!!.p, 0, D3D11_MAP_READ, 0, mapped) != 0) return false
        try {
            val data = mapped.getPointer(0) ?: return false
            frames.write(textureWidth, textureHeight, mapped.getInt(8), data)
        } finally {
            context.call(15, staging!!.p, 0)
        }
        hasFrame = true
        return true
    }

    private fun makeTextures(width: Int, height: Int) {
        target?.release()
        staging?.release()
        target = texture(width, height, usage = 0, bind = D3D11_BIND_RENDER_TARGET or D3D11_BIND_SHADER_RESOURCE, cpu = 0)
        staging = texture(width, height, usage = D3D11_USAGE_STAGING, bind = 0, cpu = D3D11_CPU_ACCESS_READ)
        textureWidth = width
        textureHeight = height
    }

    private fun texture(width: Int, height: Int, usage: Int, bind: Int, cpu: Int): Com {
        val desc = Memory(44).apply {
            setInt(0, width)
            setInt(4, height)
            setInt(8, 1) // MipLevels
            setInt(12, 1) // ArraySize
            setInt(16, DXGI_FORMAT_B8G8R8A8_UNORM)
            setInt(20, 1) // SampleDesc.Count
            setInt(24, 0) // SampleDesc.Quality
            setInt(28, usage)
            setInt(32, bind)
            setInt(36, cpu)
            setInt(40, 0) // MiscFlags
        }
        val ref = PointerByReference()
        Win.check(device.call(5, desc, null, ref), "CreateTexture2D")
        return Com(ref.value)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { engine.call(42) } // Shutdown
        engine.release()
        target?.release()
        staging?.release()
        manager.release()
        context.release()
        device.release()
        notify.close()
    }

    /**
     * The engine's callback, as a COM object built by hand: the engine needs one, and the app reads
     * everything it needs by asking, so each event is simply taken.
     */
    private class Notify : AutoCloseable {
        private fun interface QueryInterface : Callback {
            fun invoke(self: Pointer, iid: Pointer, out: Pointer): Int
        }

        private fun interface Counted : Callback {
            fun invoke(self: Pointer): Int
        }

        private fun interface EventNotify : Callback {
            fun invoke(self: Pointer, event: Int, param1: Long, param2: Int): Int
        }

        private var refs = 1
        private val query = QueryInterface { self, _, out ->
            out.setPointer(0, self)
            refs++
            0
        }
        private val addRef = Counted { ++refs }
        private val release = Counted { --refs }
        private val event = EventNotify { _, _, _, _ -> 0 }
        private val vtable = Memory(8L * 4).apply {
            setPointer(0, com.sun.jna.CallbackReference.getFunctionPointer(query))
            setPointer(8, com.sun.jna.CallbackReference.getFunctionPointer(addRef))
            setPointer(16, com.sun.jna.CallbackReference.getFunctionPointer(release))
            setPointer(24, com.sun.jna.CallbackReference.getFunctionPointer(event))
        }
        val pointer: Memory = Memory(8).apply { setPointer(0, vtable) }

        /** Kept alive with the player: the engine may call in until it is shut down. */
        override fun close() = Unit
    }

    internal companion object {
        /** MF_MEDIA_ENGINE_CREATEFLAGS for new engines; the bench tries real-time mode (4) against none. */
        @Volatile
        internal var createFlags = 0

        const val DXGI_FORMAT_B8G8R8A8_UNORM = 87
        const val CLSCTX_INPROC_SERVER = 1
        const val D3D11_USAGE_STAGING = 3
        const val D3D11_BIND_SHADER_RESOURCE = 0x8
        const val D3D11_BIND_RENDER_TARGET = 0x20
        const val D3D11_CPU_ACCESS_READ = 0x20000
        const val D3D11_MAP_READ = 1

        val ErrorWords = mapOf(
            1 to "The video stopped loading",
            2 to "Couldn't reach the video",
            3 to "This video can't be decoded here",
            4 to "This kind of video can't play here",
            5 to "This video is protected",
        )
    }
}

/** A COM interface pointer: methods are called by their place in its table. */
internal class Com(val p: Pointer) {
    private fun method(index: Int): Function = Function.getFunction(p.getPointer(0).getPointer(index * 8L))

    fun call(index: Int, vararg args: Any?): Int = method(index).invokeInt(arrayOf(p, *args))

    fun callBool(index: Int, vararg args: Any?): Boolean = call(index, *args) != 0

    fun callDouble(index: Int, vararg args: Any?): Double = method(index).invokeDouble(arrayOf(p, *args))

    fun query(iid: ByteArray): Com? {
        val out = PointerByReference()
        return if (call(0, Win.guid(iid), out) == 0 && out.value != null) Com(out.value) else null
    }

    fun release() {
        call(2)
    }
}

/** The Windows libraries and constants the media engine needs. */
internal object Win {
    val ole32: NativeLibrary by lazy { NativeLibrary.getInstance("ole32") }
    val oleaut32: NativeLibrary by lazy { NativeLibrary.getInstance("oleaut32") }
    val mfplat: NativeLibrary by lazy { NativeLibrary.getInstance("mfplat") }
    private val d3d11: NativeLibrary by lazy { NativeLibrary.getInstance("d3d11") }

    /** Media Foundation started once for the app's life (MF_VERSION, MFSTARTUP_FULL). */
    val startup: Unit by lazy {
        // RPC_E_CHANGED_MODE just means this thread already has COM another way, which also works.
        ole32.getFunction("CoInitializeEx").invokeInt(arrayOf(null, 0))
        check(mfplat.getFunction("MFStartup").invokeInt(arrayOf(0x20070, 0)), "MFStartup")
    }

    /** COM on the calling thread (the window's), in the multithreaded apartment Media Foundation works in. */
    fun comOnThisThread() {
        ole32.getFunction("CoInitializeEx").invokeInt(arrayOf(null, 0))
    }

    /** Which Direct3D device the last engine got: "hardware" or "warp" (software). */
    @Volatile
    var driver: String? = null
        private set

    /** A Direct3D 11 device that can decode video: the GPU's, or WARP (software) where there is none. */
    fun createDevice(): Pair<Com, Com> {
        for (driverType in listOf(D3D_DRIVER_TYPE_HARDWARE, D3D_DRIVER_TYPE_WARP)) {
            val deviceRef = PointerByReference()
            val contextRef = PointerByReference()
            val hr = d3d11.getFunction("D3D11CreateDevice").invokeInt(
                arrayOf(null, driverType, null, D3D11_CREATE_DEVICE_VIDEO_SUPPORT or D3D11_CREATE_DEVICE_BGRA_SUPPORT, null, 0, D3D11_SDK_VERSION, deviceRef, null, contextRef),
            )
            if (hr == 0) {
                driver = if (driverType == D3D_DRIVER_TYPE_HARDWARE) "hardware" else "warp"
                return Com(deviceRef.value) to Com(contextRef.value)
            }
        }
        error("no Direct3D 11 device")
    }

    fun check(hr: Int, what: String) {
        if (hr < 0) error("$what failed: 0x%08x".format(hr))
    }

    /** A GUID in memory, laid out as Windows keeps it. */
    fun guid(bytes: ByteArray): Memory = Memory(16).apply { write(0, bytes, 0, 16) }

    /** Builds a GUID's 16 bytes from its usual written form. */
    private fun guidOf(text: String): ByteArray {
        val hex = text.replace("-", "")
        val b = ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        // Data1, Data2 and Data3 are little-endian; Data4 is bytes in order.
        return byteArrayOf(b[3], b[2], b[1], b[0], b[5], b[4], b[7], b[6]) + b.copyOfRange(8, 16)
    }

    val CLSID_MFMediaEngineClassFactory = guidOf("b44392da-499b-446b-a4cb-005fead0e6d5")
    val IID_IMFMediaEngineClassFactory = guidOf("4d645ace-26aa-4688-9be1-df3516990b93")
    val MF_MEDIA_ENGINE_CALLBACK = guidOf("c60381b8-83a4-41f8-a3d0-de05076849a9")
    val MF_MEDIA_ENGINE_DXGI_MANAGER = guidOf("065702da-1094-486d-8617-ee7cc4ee4648")
    val MF_MEDIA_ENGINE_VIDEO_OUTPUT_FORMAT = guidOf("5066893c-8cf9-42bc-8b8a-472212e52726")
    val IID_ID3D10Multithread = guidOf("9b7e4e00-342c-4106-a19f-4f2704f689f0")

    /** MFARGB black, for the bars TransferVideoFrame leaves. */
    val Black: Memory = Memory(4).apply { setInt(0, 0xFF000000.toInt()) }

    private const val D3D_DRIVER_TYPE_HARDWARE = 1
    private const val D3D_DRIVER_TYPE_WARP = 5
    private const val D3D11_CREATE_DEVICE_BGRA_SUPPORT = 0x20
    private const val D3D11_CREATE_DEVICE_VIDEO_SUPPORT = 0x800
    private const val D3D11_SDK_VERSION = 7
}
