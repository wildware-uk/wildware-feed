package dev.wildware.agents.app

import dev.wildware.agents.app.desktop.DesktopNotice
import dev.wildware.agents.app.desktop.NotifySendNotifier
import dev.wildware.agents.app.engine.NoticeCleanup
import dev.wildware.agents.app.engine.SystemNotices
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Notifications come down once the owner has dealt with them on any device. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NoticeCleanupTest {
    private class Screen(vararg tags: String) : SystemNotices {
        val showing = tags.toMutableSet()
        override suspend fun shown(): Set<String> = showing.toSet()
        override fun remove(tags: Set<String>) {
            showing -= tags
        }
    }

    @Test
    fun `a clear from the live connection takes down what it names and never the app's own notices`() = runTest {
        val screen = Screen("message-m1", "request-r1", "update-check")
        val cleared = MutableSharedFlow<List<String>>()
        val cleanup = NoticeCleanup(screen, { emptyList() }, cleared, MutableStateFlow(false))
        backgroundScope.launch { cleanup.run() }
        runCurrent()

        cleared.emit(listOf("message-m1", "update-check", "message-elsewhere"))
        runCurrent()

        assertEquals(setOf("request-r1", "update-check"), screen.showing)
    }

    @Test
    fun `coming back online asks about what still shows and takes down what was dealt with meanwhile`() = runTest {
        val screen = Screen("message-m1", "request-r1", "pairing-p1", "update-check")
        val asked = mutableListOf<List<String>>()
        val live = MutableStateFlow(false)
        val cleanup = NoticeCleanup(screen, { tags -> asked += tags; tags.filter { it != "request-r1" } }, MutableSharedFlow(), live)
        backgroundScope.launch { cleanup.run() }
        runCurrent()
        assertTrue(asked.isEmpty(), "nothing asked while offline")

        live.value = true
        runCurrent()

        assertEquals(listOf("request-r1"), screen.showing.filter { it != "update-check" })
        assertEquals(setOf("message-m1", "request-r1", "pairing-p1"), asked.single().toSet())
    }

    @Test
    fun `returning to the app catches up and a failed ask leaves everything up`() = runTest {
        val screen = Screen("message-m1")
        val foreground = MutableSharedFlow<Unit>()
        var fail = true
        val cleanup = NoticeCleanup(screen, { tags -> if (fail) error("offline") else tags }, MutableSharedFlow(), MutableStateFlow(false), foreground)
        backgroundScope.launch { cleanup.run() }
        runCurrent()

        foreground.emit(Unit)
        runCurrent()
        assertEquals(setOf("message-m1"), screen.showing)

        fail = false
        foreground.emit(Unit)
        runCurrent()
        assertEquals(emptySet(), screen.showing)
    }

    @Test
    fun `nothing showing asks nothing`() = runTest {
        var asked = 0
        NoticeCleanup(Screen("update-check"), { asked++; emptyList() }, MutableSharedFlow(), MutableStateFlow(true)).sync()
        assertEquals(0, asked)
    }

    /** A notify-send that waits until it is closed. */
    private class Waiting : Process() {
        val closed = CountDownLatch(1)
        override fun getInputStream(): InputStream = object : InputStream() {
            override fun read(): Int {
                closed.await()
                return -1
            }
        }
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int {
            closed.await()
            return 0
        }
        override fun exitValue(): Int = if (closed.count == 0L) 0 else throw IllegalThreadStateException()
        override fun isAlive(): Boolean = closed.count > 0
        override fun destroy() = closed.countDown()
    }

    @Test
    fun `on Linux a notice dealt with elsewhere is closed by interrupting its notify-send`() {
        val processes = java.util.concurrent.ConcurrentHashMap<String, Waiting>()
        val interrupted = mutableListOf<Process>()
        val notifier = NotifySendNotifier(
            File("/nonexistent/icon.png"),
            fallback = { _, _ -> error("not used") },
            start = { command -> Waiting().also { processes[command.last()] = it } },
            interrupt = { interrupted += it; it.destroy() },
        )
        notifier.show(DesktopNotice("message-m1", "Builder", "one", null)) {}
        notifier.show(DesktopNotice("request-r1", "Builder", "two", null)) {}
        val deadline = System.currentTimeMillis() + 5_000
        while (notifier.shown().size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(setOf("message-m1", "request-r1"), notifier.shown())

        notifier.remove(setOf("message-m1", "update-gone"))

        assertEquals(listOf<Process>(processes.getValue("one")), interrupted)
        assertEquals(setOf("request-r1"), notifier.shown())
        processes.getValue("two").destroy()
    }
}
