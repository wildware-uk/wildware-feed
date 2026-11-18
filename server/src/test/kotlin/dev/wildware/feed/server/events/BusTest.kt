package dev.wildware.feed.server.events

import dev.wildware.feed.server.http.Frames
import dev.wildware.feed.server.http.parseCursor
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class BusTest {
    private val none = JsonObject(emptyMap())

    @Test
    fun `an event published by a listener waits its turn, so every listener sees seq order`() {
        val bus = Bus(boot = "abc")
        val first = mutableListOf<Long>()
        val second = mutableListOf<Long>()
        bus.subscribe { if (it.type == "update.created") bus.publish("notification.created", none) }
        bus.subscribe { first += it.seq }
        bus.subscribe { second += it.seq }
        bus.publish("update.created", none)
        assertEquals(listOf(1L, 2L), first)
        assertEquals(listOf(1L, 2L), second)
        assertEquals(2, bus.lastSeq)
    }

    @Test
    fun `the ring replays what it holds and says why when it cannot`() {
        val bus = Bus(capacity = 3, boot = "abc")
        repeat(5) { bus.publish("x", none) }
        assertEquals(listOf(4L, 5L), (bus.replaySince(3) as Ring.Replay.Hit).events.map { it.seq })
        assertEquals(emptyList(), (bus.replaySince(5) as Ring.Replay.Hit).events)
        assertEquals("expired", (bus.replaySince(1) as Ring.Replay.Miss).reason)
        assertEquals("ahead", (bus.replaySince(6) as Ring.Replay.Miss).reason)
        assertIs<Ring.Replay.Hit>(bus.replaySince(2))
        assertFailsWith<IllegalArgumentException> { Bus(boot = "Not-Valid") }
    }

    @Test
    fun `cursors are a seq or boot-seq, and frames say so`() {
        assertEquals(null, parseCursor("abc")?.boot)
        assertEquals(5L, parseCursor(" 5 ")!!.seq)
        assertEquals("ab12", parseCursor("ab12-7")!!.boot)
        assertNull(parseCursor("1.5"))
        assertNull(parseCursor("-1"))
        val event = Bus(boot = "b00t").publish("project.created", none)
        assertEquals(
            "id: b00t-1\nevent: project.created\ndata: {\"type\":\"project.created\",\"seq\":1,\"at\":\"${event.at}\",\"payload\":{}}\n\n",
            Frames.event(event, "b00t"),
        )
        assertEquals("id: b00t-0\nevent: hello\ndata: {\"type\":\"hello\",\"boot\":\"b00t\",\"seq\":0}\n\n", Frames.hello("b00t", 0))
        assertEquals(": a b\n\n", Frames.comment("a\nb"))
    }
}
