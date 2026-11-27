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
    fun `picking a word holds the note until a word is chosen then it goes with that word`() = runTest {
        val server = FakeDashboard(testScheduler)
        val (queue, actions) = setUp(server)
        val unsure = listOf(VoiceWord(8, 14, 0.3, listOf("Monday")))

        val id = actions.post(ProjectRef("mega", "Mega"), "Ship on Friday", voice = OwnerActions.Voice(VoiceNote.UndoMillis, unsure))
        runCurrent()
        assertEquals(unsure, queue.items.value.single().words, "the unsure words wait with the note")
        advanceTimeBy(2_000)
        queue.pauseVoice(id)
        advanceTimeBy(VoiceNote.UndoMillis * 5)
        assertTrue(server.writes.isEmpty(), "nothing goes while the choices are open")

        queue.chooseWord(id, 0, "Monday")
        advanceTimeBy(VoiceNote.UndoMillis - 500)
        assertTrue(server.writes.isEmpty(), "a fresh wait after choosing, to see the change")
        advanceTimeBy(1_000)
        assertEquals(
            listOf("""{"project":"mega","source":"voice","body":"Ship on Monday","sendKey":"id1"}"""),
            server.writes.map { it.second },
            "the chosen word goes; the word data never does",
        )
    }

    @Test
    fun `a paused note still goes in the end`() = runTest {
        val server = FakeDashboard(testScheduler)
        val (queue, actions) = setUp(server)

        val id = actions.post(ProjectRef("mega", "Mega"), "Ship it", voice = OwnerActions.Voice(VoiceNote.UndoMillis))
        runCurrent()
        queue.pauseVoice(id)
        advanceTimeBy(VoiceNote.PauseMillis + 1_000)
        assertEquals(1, server.writes.size)
    }

    @Test
    fun `send now sends a waiting note at once and only once`() = runTest {
        val server = FakeDashboard(testScheduler)
        val (queue, actions) = setUp(server)

        val id = actions.post(ProjectRef("mega", "Mega"), "Ship it", voice = OwnerActions.Voice(VoiceNote.UndoMillis))
        runCurrent()
        advanceTimeBy(1_000)
        queue.sendVoiceNow(id)
        queue.sendVoiceNow(id)
        runCurrent()
        assertEquals(1, server.writes.size, "it goes without waiting out the rest")

        queue.sendVoiceNow(id)
        advanceTimeBy(VoiceNote.UndoMillis * 2)
        assertEquals(1, server.writes.size, "neither another tap nor the old deadline sends it again")
        assertTrue(queue.items.value.isEmpty())
    }

    @Test
    fun `send now as the wait runs out sends once`() = runTest {
        val server = FakeDashboard(testScheduler)
        val (queue, actions) = setUp(server)

        val id = actions.post(ProjectRef("mega", "Mega"), "Ship it", voice = OwnerActions.Voice(VoiceNote.UndoMillis))
        runCurrent()
        advanceTimeBy(VoiceNote.UndoMillis)
        queue.sendVoiceNow(id)
        advanceTimeBy(VoiceNote.UndoMillis)
        assertEquals(1, server.writes.size)
    }

    @Test
    fun `send now on a paused note sends it with its words as they are`() = runTest {
        val server = FakeDashboard(testScheduler)
        val (queue, actions) = setUp(server)
        val unsure = listOf(VoiceWord(8, 14, 0.3, listOf("Monday")))

        val id = actions.post(ProjectRef("mega", "Mega"), "Ship on Friday", voice = OwnerActions.Voice(VoiceNote.UndoMillis, unsure))
        runCurrent()
        queue.pauseVoice(id)
        advanceTimeBy(10_000)
        assertTrue(server.writes.isEmpty())

        queue.sendVoiceNow(id)
        runCurrent()
        assertEquals(
            listOf("""{"project":"mega","source":"voice","body":"Ship on Friday","sendKey":"id1"}"""),
            server.writes.map { it.second },
        )
        advanceTimeBy(VoiceNote.PauseMillis)
        assertEquals(1, server.writes.size)
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
