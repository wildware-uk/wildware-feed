package dev.wildware.agents

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutboxTest {
    private val now = 1_000_000L
    private val mega = mapOf("project" to "garden-game")

    private fun message(id: String, at: Long, thread: Map<String, String> = mega, text: String = "hi") =
        Outbox.message(id, at, "To Garden Game", thread, text, emptyList())

    @Test
    fun `the oldest due item goes first`() {
        val items = listOf(message("b", 2), message("a", 1, mapOf("project" to "other")))
        assertEquals("a", Outbox.next(items, now, ignoreBackoff = false, skip = emptyList())?.id)
    }

    @Test
    fun `a later message never overtakes an older one in the same thread`() {
        val stuck = message("a", 1).copy(attempts = 1, nextAttemptAtMillis = now + 5_000)
        val later = message("b", 2)
        val elsewhere = message("c", 3, mapOf("request" to "r1"))
        assertEquals("c", Outbox.next(listOf(stuck, later, elsewhere), now, false, emptyList())?.id)
    }

    @Test
    fun `a given-up message doesn't hold up the rest of its thread`() {
        val refused = message("a", 1).copy(gaveUp = true)
        assertEquals("b", Outbox.next(listOf(refused, message("b", 2)), now, false, emptyList())?.id)
    }

    @Test
    fun `an item another process is sending is left alone`() {
        val claimed = Outbox.claim(listOf(message("a", 1)), "a", now)
        assertNull(Outbox.next(claimed, now + 1_000, false, emptyList()))
        assertEquals("a", Outbox.next(claimed, now + Outbox.ClaimMillis + 1, false, emptyList())?.id)
    }

    @Test
    fun `the network coming back skips the wait, but each pass tries an item once`() {
        val waiting = message("a", 1).copy(attempts = 3, nextAttemptAtMillis = now + 60_000)
        assertNull(Outbox.next(listOf(waiting), now, false, emptyList()))
        assertEquals("a", Outbox.next(listOf(waiting), now, true, emptyList())?.id)
        assertNull(Outbox.next(listOf(waiting), now, true, listOf("a")))
    }

    @Test
    fun `waits double from ten seconds up to fifteen minutes`() {
        assertEquals(0, Outbox.backoffMillis(0))
        assertEquals(10_000, Outbox.backoffMillis(1))
        assertEquals(20_000, Outbox.backoffMillis(2))
        assertEquals(80_000, Outbox.backoffMillis(4))
        assertEquals(15 * 60_000L, Outbox.backoffMillis(9))
        assertEquals(15 * 60_000L, Outbox.backoffMillis(400))
    }

    @Test
    fun `only a refusal that won't change gives up`() {
        assertEquals(DeliveryOutcome.Retry, Outbox.outcomeFor(null))
        assertEquals(DeliveryOutcome.Retry, Outbox.outcomeFor(500))
        assertEquals(DeliveryOutcome.Retry, Outbox.outcomeFor(503))
        assertEquals(DeliveryOutcome.Retry, Outbox.outcomeFor(429))
        assertEquals(DeliveryOutcome.Retry, Outbox.outcomeFor(408))
        assertEquals(DeliveryOutcome.Retry, Outbox.outcomeFor(401))
        assertEquals(DeliveryOutcome.GiveUp, Outbox.outcomeFor(400))
        assertEquals(DeliveryOutcome.GiveUp, Outbox.outcomeFor(404))
        assertEquals(DeliveryOutcome.GiveUp, Outbox.outcomeFor(409))
        assertEquals(DeliveryOutcome.GiveUp, Outbox.outcomeFor(413))
        assertEquals(DeliveryOutcome.Sent, Outbox.outcomeFor(201))
    }

    @Test
    fun `a sent item leaves, a failed one waits longer each time, a refused one stays listed`() {
        val items = listOf(message("a", 1), message("b", 2))
        assertEquals(listOf("b"), Outbox.afterAttempt(items, "a", DeliveryResult(DeliveryOutcome.Sent), now).map { it.id })

        val once = Outbox.afterAttempt(items, "a", DeliveryResult(DeliveryOutcome.Retry, message = "offline"), now)
        assertEquals(1, once[0].attempts)
        assertEquals(now + 10_000, once[0].nextAttemptAtMillis)
        assertEquals("offline", once[0].lastError)
        val twice = Outbox.afterAttempt(once, "a", DeliveryResult(DeliveryOutcome.Retry), now)
        assertEquals(now + 20_000, twice[0].nextAttemptAtMillis)

        val refused = Outbox.afterAttempt(items, "a", DeliveryResult(DeliveryOutcome.GiveUp, 400, "too long"), now)
        assertTrue(refused[0].gaveUp)
        assertEquals(listOf("b"), Outbox.waiting(refused).map { it.id })
        assertEquals(listOf("a"), Outbox.failed(refused).map { it.id })
        assertEquals("Couldn't send: too long", Outbox.status(refused[0], now))

        val again = Outbox.retryNow(refused, "a")
        assertEquals("a", Outbox.next(again, now, false, emptyList())?.id)
    }

    @Test
    fun `status says where each item stands`() {
        assertEquals("Waiting to send", Outbox.status(message("a", 1), now))
        assertEquals("Sending…", Outbox.status(message("a", 1).copy(claimedUntilMillis = now + 1), now))
        assertEquals("Trying again shortly", Outbox.status(message("a", 1).copy(attempts = 1, nextAttemptAtMillis = now + 20_000), now))
        assertEquals("Trying again in 3 min", Outbox.status(message("a", 1).copy(attempts = 5, nextAttemptAtMillis = now + 150_000), now))
    }

    @Test
    fun `the queue survives a round trip and a damaged file reads as empty`() {
        val items = listOf(
            Outbox.message("a", 1, "To Garden Game", mega, " hello ", listOf(OutboxFile("a-0.jpg", "photo-1.jpg", "image/jpeg"))),
            Outbox.ticket("b", 2, ProjectRef("garden-game", "Garden Game"), "fix it"),
            Outbox.answer("c", 3, "Builder", OutboxAnswer("r1", "confirm", null, RequestActions.Yes)),
        )
        assertEquals(items, Outbox.decode(Outbox.encode(items)))
        assertEquals("hello", items[0].text)
        assertEquals(emptyList(), Outbox.decode("{not json"))
        assertEquals(emptyList(), Outbox.decode(""))
    }

    @Test
    fun `files are deleted only once nothing uses them`() {
        val withPhoto = Outbox.message("a", 1, "x", mega, null, listOf(OutboxFile("a-0.jpg", "p.jpg", "image/jpeg")))
        assertEquals(listOf("a-0.jpg"), Outbox.orphanedFiles(listOf(withPhoto), emptyList()))
        assertEquals(emptyList(), Outbox.orphanedFiles(listOf(withPhoto), listOf(withPhoto.copy(attempts = 1))))
    }

    @Test
    fun `the next wake is the soonest due item`() {
        val items = listOf(
            message("a", 1).copy(nextAttemptAtMillis = now + 50),
            message("b", 2).copy(nextAttemptAtMillis = now + 10),
            message("c", 3).copy(nextAttemptAtMillis = now + 1, gaveUp = true),
        )
        assertEquals(now + 10, Outbox.nextWakeMillis(items))
        assertNull(Outbox.nextWakeMillis(emptyList()))
    }

    private fun client(handler: io.ktor.client.engine.mock.MockRequestHandler) =
        DashboardClient("https://dash.test/", "key", MockEngine(handler))

    @Test
    fun `delivering a message posts it to its thread`() = runTest {
        val result = client { respond("""{"message":{"id":"m1"}}""", HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json")) }
            .deliver(message("a", 1, mapOf("request" to "r1")), emptyList())
        assertEquals(DeliveryOutcome.Sent, result.outcome)
    }

    @Test
    fun `a server error is retried and a refusal gives up`() = runTest {
        assertEquals(DeliveryOutcome.Retry, client { respondError(HttpStatusCode.BadGateway) }.deliver(message("a", 1), emptyList()).outcome)
        val refused = client {
            respond("""{"error":"invalid_argument","message":"body is too long"}""", HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json"))
        }.deliver(message("a", 1), emptyList())
        assertEquals(DeliveryOutcome.GiveUp, refused.outcome)
        assertEquals("body is too long", refused.message)
    }

    @Test
    fun `no connection at all is retried`() = runTest {
        val result = client { throw IllegalStateException("offline") }.deliver(message("a", 1), emptyList())
        assertEquals(DeliveryOutcome.Retry, result.outcome)
        assertNull(result.status)
    }

    @Test
    fun `a ticket whose mark failed is only marked on the retry, never posted twice`() = runTest {
        val paths = mutableListOf<String>()
        val first = client { request ->
            paths += request.url.encodedPath
            if (request.url.encodedPath == "/api/messages") {
                respond("""{"message":{"id":"m7"}}""", HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respondError(HttpStatusCode.ServiceUnavailable)
            }
        }.deliver(Outbox.ticket("t", 1, ProjectRef("garden-game", "Garden Game"), "fix it"), emptyList())
        assertEquals(DeliveryOutcome.Retry, first.outcome)
        assertEquals("m7", first.ticketMessageId)

        val queued = Outbox.afterAttempt(listOf(Outbox.ticket("t", 1, ProjectRef("garden-game", "Garden Game"), "fix it")), "t", first, now)
        paths.clear()
        val second = client { request ->
            paths += request.url.encodedPath
            respond("""{"status":"todo"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }.deliver(queued.single(), emptyList())
        assertEquals(DeliveryOutcome.Sent, second.outcome)
        assertEquals(listOf("/api/cards/message/m7/status"), paths)
    }

    @Test
    fun `an answer whose button no longer fits gives up`() = runTest {
        val stale = Outbox.answer("c", 3, "Builder", OutboxAnswer("r1", "buttons", listOf("A"), "option-5"))
        assertEquals(DeliveryOutcome.GiveUp, client { error("unreachable") }.deliver(stale, emptyList()).outcome)
    }
}
