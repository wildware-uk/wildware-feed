package dev.wildware.agents

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A voice note sent on letting go of the mic: marked as voice, held a few seconds for an Undo. */
class VoiceSendTest {
    private var ids = 0

    private fun TestScope.setUp(server: FakeDashboard): Pair<OutboxQueue, OwnerActions> {
        val queue = OutboxQueue(server.client(), MemoryOutboxStorage(), backgroundScope, clock = { testScheduler.currentTime })
        queue.start()
        return queue to OwnerActions(queue, clock = { testScheduler.currentTime }, newId = { "id${++ids}" })
    }

    @Test
    fun `letting go sends at once unless the owner reviews voice notes`() {
        assertTrue(VoiceNote.sendsOnRelease(review = false, canSendHere = true))
        assertFalse(VoiceNote.sendsOnRelease(review = true, canSendHere = true))
        assertFalse(VoiceNote.sendsOnRelease(review = false, canSendHere = false), "a box that can't send a note puts the words in it")
    }

    @Test
    fun `the review setting is off until turned on and is remembered`() {
        val store = MemoryTextStore()
        val setting = VoiceReviewSetting(store)
        assertFalse(setting.enabled.value)
        setting.set(true)
        assertTrue(VoiceReviewSetting(store).enabled.value)
        setting.set(false)
        assertFalse(VoiceReviewSetting(store).enabled.value)
    }

    @Test
    fun `a voice note waits out its undo window then goes up marked as voice`() = runTest {
        val server = FakeDashboard(testScheduler)
        val (queue, actions) = setUp(server)

        actions.post(ProjectRef("mega", "Mega"), "Ship it on Friday", voice = OwnerActions.Voice(VoiceNote.UndoMillis))
        runCurrent()
        queue.kick(ignoreBackoff = true)
        advanceTimeBy(VoiceNote.UndoMillis - 500)
        assertTrue(server.writes.isEmpty(), "nothing goes during the undo window, not even on a kick")

        advanceTimeBy(1_000)
        assertEquals(
            listOf("POST /api/messages" to """{"project":"mega","source":"voice","body":"Ship it on Friday","sendKey":"id1"}"""),
            server.writes,
        )
        assertTrue(queue.items.value.isEmpty())
    }

    @Test
    fun `undo within the window sends nothing`() = runTest {
        val server = FakeDashboard(testScheduler)
        val (queue, actions) = setUp(server)

        val id = actions.reply(CardRef(CardKinds.Message, "post1"), "Never mind", voice = OwnerActions.Voice(VoiceNote.UndoMillis))
        runCurrent()
        advanceTimeBy(2_000)
        queue.remove(id)
        advanceTimeBy(VoiceNote.UndoMillis * 10)

        assertTrue(server.writes.isEmpty(), server.writes.toString())
        assertTrue(queue.items.value.isEmpty())
    }

    @Test
    fun `a reviewed voice note goes at once and is still marked as voice`() = runTest {
        val server = FakeDashboard(testScheduler)
        val (_, actions) = setUp(server)

        actions.reply(CardRef(CardKinds.Update, "u1"), "Checked words", voice = OwnerActions.Voice())
        runCurrent()

        assertEquals(listOf("""{"update":"u1","source":"voice","body":"Checked words","sendKey":"id1"}"""), server.writes.map { it.second })
    }

    @Test
    fun `a typed message has no source`() = runTest {
        val server = FakeDashboard(testScheduler)
        val (_, actions) = setUp(server)

        actions.post(ProjectRef("mega", "Mega"), "Typed")
        runCurrent()

        assertEquals(listOf("""{"project":"mega","body":"Typed","sendKey":"id1"}"""), server.writes.map { it.second })
    }
}
