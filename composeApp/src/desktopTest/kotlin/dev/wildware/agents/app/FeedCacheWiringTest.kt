package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.MemoryOutboxStorage
import dev.wildware.agents.MemoryTextStore
import dev.wildware.agents.app.design.AccessibilityPrefs
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.session.MemorySessionStore
import dev.wildware.agents.app.session.SavedSession
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test
import kotlin.test.assertTrue

/** The real app, launched twice on one device: the second launch opens onto the first one's feed. */
@OptIn(ExperimentalTestApi::class)
class FeedCacheWiringTest {
    private val signedIn = SavedSession("https://dash.test", "key-1", "Test device")
    private val kept = MemoryTextStore()

    private fun platform(engine: () -> io.ktor.client.engine.HttpClientEngine) = AppPlatform(
        sessions = MemorySessionStore(signedIn),
        readMarks = MemoryTextStore(),
        outbox = MemoryOutboxStorage(),
        httpEngine = engine,
        deviceName = "Test device",
        imageCacheDir = null,
        feedCache = kept,
    )

    @Test
    fun aSecondLaunchWithNoNetworkShowsTheLastFeed() {
        val server = FakeServer()
        try {
            runComposeUiTest {
                setContent { Box(Modifier.size(390.dp, 844.dp)) { AgentsApp(platform { server.engine() }, dark = true, accessibility = AccessibilityPrefs()) } }
                waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Fixed the unread counts", substring = true)).fetchSemanticsNodes().isNotEmpty() }
                // The cache writes a moment after the reads settle.
                waitUntil(timeoutMillis = 10_000) { kept.read() != null }
            }
        } finally {
            server.close()
        }
        assertTrue("key-1" !in kept.read().orEmpty(), "the device key stays out of the cache")

        // Next launch: the network never answers.
        val silent = { MockEngine { awaitCancellation() } }
        runComposeUiTest {
            setContent { Box(Modifier.size(390.dp, 844.dp)) { AgentsApp(platform(silent), dark = true, accessibility = AccessibilityPrefs()) } }
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Fixed the unread counts", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        }
    }
}
