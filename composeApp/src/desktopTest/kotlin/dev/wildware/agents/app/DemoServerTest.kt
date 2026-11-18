package dev.wildware.agents.app

import dev.wildware.agents.AlertPrefs
import dev.wildware.agents.CardKinds
import dev.wildware.agents.CardRef
import dev.wildware.agents.DashboardClient
import dev.wildware.agents.NewMessage
import dev.wildware.agents.app.demo.DemoServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The demo's dashboard keeps what is written to it, as the real one does. */
class DemoServerTest {
    private val server = DemoServer()
    private val client = DashboardClient("https://demo.example", "demo", server.engine())

    @Test
    fun aReplyIsThereOnTheNextRead() = runTest {
        client.postMessage(NewMessage(body = "Lovely", update = "demo-u1"))
        val replies = client.messages().messages.filter { it.updateId == "demo-u1" }
        assertEquals("Lovely", replies.last().body)
    }

    @Test
    fun answeredAndDismissedQuestionsStopWaiting() = runTest {
        client.answer("demo-q1", JsonPrimitive("Ship it"))
        client.dismissRequest("demo-q3")
        assertEquals(listOf("demo-q2"), client.waitingRequests().requests.map { it.id })
    }

    @Test
    fun reactingTwiceTakesItBack() = runTest {
        val card = CardRef(CardKinds.Update, "demo-u2")
        client.react(card, "👍")
        assertTrue(client.snapshot().updates.items.first { it.id == "demo-u2" }.reactions.any { it.isOwner && it.emoji == "👍" })
        client.react(card, "👍")
        assertTrue(client.snapshot().updates.items.first { it.id == "demo-u2" }.reactions.none { it.isOwner })
    }

    @Test
    fun deletingAnUpdateRemovesItAndItsReplies() = runTest {
        client.deleteUpdate("demo-u1")
        assertTrue(client.snapshot().updates.items.none { it.id == "demo-u1" })
        assertTrue(client.messages().messages.none { it.updateId == "demo-u1" })
    }

    @Test
    fun alertSettingsAreKeptAndThereAreNoNotifications() = runTest {
        val prefs = AlertPrefs(types = listOf(AlertPrefs.Request))
        client.setAlertPrefs(prefs)
        assertEquals(prefs, client.alertPrefs())
        assertEquals(0, client.notifications().notifications.size)
    }
}
