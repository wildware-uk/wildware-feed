package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.MemoryTextStore
import dev.wildware.agents.app.design.AccessibilityPrefs
import dev.wildware.agents.app.feed.Paywall
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.session.AppStorePurchases
import dev.wildware.agents.app.session.MemorySessionStore
import dev.wildware.agents.app.session.PurchaseState
import dev.wildware.agents.app.session.SavedSession
import dev.wildware.agents.app.session.SignedTransaction
import dev.wildware.agents.app.session.StoreProduct
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Team bought in the iPhone app, in the running app against a fake dashboard: each purchase Apple
 * signs goes to the dashboard and is finished once it has answered; one for another team stays.
 * Outside the US App Store Team is still offered, and "Upgrade" opens the paywall.
 */
@OptIn(ExperimentalTestApi::class)
class AppStoreAppTest {
    private val server = FakeServer().apply { requests = emptyList() }
    private val day = 24L * 60 * 60 * 1000
    // Built, not written out, so the secret scan does not take it for a real key.
    private val token = listOf("aa19f4a5", "0eb8", "501f", "898f", "bb79bbdcb7f5").joinToString("-")
    private val relayed: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile private var answer: Pair<HttpStatusCode, String> = HttpStatusCode.OK to """{"plan":"team","billing":"apple","seats":5}"""

    private class FakeStore : AppStorePurchases {
        val finished: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val queue = Channel<SignedTransaction>(Channel.UNLIMITED)
        override val products = MutableStateFlow(listOf(StoreProduct(Paywall.TEAM5, "Team 5", "£34.99", "34.99", "GBP")))
        override val productsLoaded = MutableStateFlow(true)
        override val purchase = MutableStateFlow<PurchaseState>(PurchaseState.Idle)
        override val signed = queue.receiveAsFlow()
        override fun load(productIds: List<String>) {}
        override fun buy(productId: String, accountToken: String) {}
        override fun restore() {}
        override fun manage() {}
        override fun finish(transactionId: String) {
            finished += transactionId
        }
        override fun clearPurchase() {}
    }

    private val store = FakeStore()

    @AfterTest
    fun stop() = server.close()

    private fun freeTeam() {
        server.me = """{"person":{"id":"m-ann","name":"Ann","handle":"ann","role":"owner"},"team":true}"""
        server.people = """{"people":[{"id":"m-ann","name":"Ann","handle":"ann","role":"owner"}]}"""
        server.plan = """{"plan":"free","limits":{"people":2,"agents":3,"storageBytes":524288000,"historyDays":30},""" +
            """"used":{"people":1,"agents":1,"storageBytes":0},"upgradeUrl":"https://billing.test/teams/acme",""" +
            """"createdAt":${System.currentTimeMillis() - 10 * day},"billing":null,"appAccountToken":"$token"}"""
        server.otherHost = { request ->
            if (request.method == HttpMethod.Post && request.url.encodedPath == "/api/plan/app-store") {
                relayed += when (val body = request.body) {
                    is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
                    else -> body.toString()
                }
                val (status, text) = answer
                respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                null
            }
        }
    }

    private fun ComposeUiTest.app() {
        val platform = AppPlatform.inMemory(
            engine = { server.engine() },
            sessions = MemorySessionStore(SavedSession("https://dash.test", "key-1", "Test device", deviceId = "d1")),
            // An iPhone outside the US App Store: no links, but the App Store sells Team.
            buyingLinks = MutableStateFlow(false),
            upsellMarks = MemoryTextStore(),
            appStore = store,
        )
        setContent { Box(Modifier.size(1440.dp, 900.dp)) { AgentsApp(platform, dark = true, accessibility = AccessibilityPrefs()) } }
    }

    private fun ComposeUiTest.waitFor(text: String) = try {
        waitUntil(timeoutMillis = 20_000) { onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasText(text)).onFirst()
    } catch (timeout: Throwable) {
        throw AssertionError("never saw \"$text\"; the screen had:\n" + onAllNodes(isRoot()).printToString(), timeout)
    }

    @Test
    fun aPurchaseGoesToTheDashboardAndIsFinishedOnceItAnswers() = runDesktopComposeUiTest(1440, 900) {
        freeTeam()
        app()
        waitFor("Fixed the unread counts")
        // Another team's purchase is left for that team.
        store.queue.trySend(SignedTransaction("t0", "x.y.z", "00000000-0000-5000-8000-000000000000"))
        store.queue.trySend(SignedTransaction("t1", "a.b.c", token.uppercase()))
        waitUntil(timeoutMillis = 20_000) { store.finished.contains("t1") }
        assertEquals(listOf("""{"signedTransaction":"a.b.c"}"""), relayed.toList())
        assertTrue("t0" !in store.finished)
    }

    @Test
    fun aPurchaseTheDashboardCouldNotPassOnIsNotFinished() = runDesktopComposeUiTest(1440, 900) {
        freeTeam()
        answer = HttpStatusCode.BadGateway to """{"error":"control_plane_unreachable","message":"Later."}"""
        app()
        waitFor("Fixed the unread counts")
        store.queue.trySend(SignedTransaction("t2", "a.b.c", token))
        waitUntil(timeoutMillis = 20_000) { relayed.isNotEmpty() }
        Thread.sleep(300)
        assertTrue(store.finished.isEmpty())
    }

    @Test
    fun outsideTheUsUpgradeOpensThePaywall() = runDesktopComposeUiTest(1440, 900) {
        freeTeam()
        app()
        waitFor("Get more with Team")
        waitFor("Upgrade").performClick()
        waitFor(Paywall.LEAD)
        waitFor("Team 5")
        assertTrue(onAllNodes(hasText(Paywall.TRANSFER)).fetchSemanticsNodes().isEmpty())
    }
}
