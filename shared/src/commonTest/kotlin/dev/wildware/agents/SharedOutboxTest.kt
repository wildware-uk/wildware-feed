package dev.wildware.agents

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On iPhone the app's outbox shares its file with the share sheet, Siri and alert buttons, which
 * run in other processes. These check the queue works on the file, not on a copy it holds.
 */
class SharedOutboxTest {
    private fun TestScope.queue(server: FakeDashboard, storage: OutboxStorage): OutboxQueue {
        val queue = OutboxQueue(server.client(), storage, backgroundScope, clock = { testScheduler.currentTime })
        queue.start()
        runCurrent()
        return queue
    }

    /** What another process does: it writes an item straight into the stored list. */
    private fun OutboxStorage.addFromElsewhere(id: String, text: String) =
        update { Outbox.encode(Outbox.decode(it) + Outbox.message(id, 0, "Shared", mapOf("project" to "mega"), text, emptyList())) }

    @Test
    fun `an item another process added is sent when told to look again`() = runTest {
        val server = FakeDashboard(testScheduler)
        val storage = MemoryOutboxStorage()
        val queue = queue(server, storage)

        storage.addFromElsewhere("s1", "From the share sheet")
        queue.reload()
        runCurrent()

        assertEquals(listOf("""{"project":"mega","body":"From the share sheet","sendKey":"s1"}"""), server.writes.map { it.second })
        assertTrue(queue.items.value.isEmpty())
        assertTrue(Outbox.decode(storage.load()).isEmpty())
    }

    @Test
    fun `the app's own change keeps what another process added meanwhile`() = runTest {
        val server = FakeDashboard(testScheduler).apply { onWrite = { _, _ -> throw IllegalStateException("offline") } }
        val storage = MemoryOutboxStorage()
        val queue = queue(server, storage)
        OwnerActions(queue, clock = { testScheduler.currentTime }, newId = { "app1" }).post(ProjectRef("mega", "Mega"), "From the app")
        runCurrent()

        // Siri queues something; the app has not looked again yet, then changes its own item.
        storage.addFromElsewhere("siri1", "From Siri")
        queue.retryNow("app1")
        runCurrent()

        assertEquals(setOf("app1", "siri1"), Outbox.decode(storage.load()).map { it.id }.toSet())
        assertEquals(setOf("app1", "siri1"), queue.items.value.map { it.id }.toSet())
    }

    @Test
    fun `an item another process is sending is left alone`() = runTest {
        val server = FakeDashboard(testScheduler)
        val storage = MemoryOutboxStorage()
        val queue = queue(server, storage)

        storage.addFromElsewhere("s1", "Being sent by the share sheet")
        storage.update { text -> Outbox.encode(Outbox.claim(Outbox.decode(text), "s1", testScheduler.currentTime)) }
        queue.reload()
        runCurrent()

        assertTrue(server.writes.isEmpty())
        assertEquals(listOf("s1"), queue.items.value.map { it.id })
    }

    @Test
    fun `looking again with nothing to do writes nothing`() = runTest {
        val server = FakeDashboard(testScheduler)
        var saves = 0
        val storage = object : OutboxStorage by MemoryOutboxStorage() {
            private val inner = MemoryOutboxStorage()
            override fun load() = inner.load()
            override fun save(text: String) {
                saves++
                inner.save(text)
            }
        }
        val queue = queue(server, storage)
        saves = 0

        queue.reload()
        queue.kick(ignoreBackoff = true)
        runCurrent()

        assertEquals(0, saves)
    }
}
