package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AccessibilityPrefs
import dev.wildware.agents.app.feed.DocLinkUriHandler
import dev.wildware.agents.app.feed.docLinked
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.session.MemorySessionStore
import dev.wildware.agents.app.session.SavedSession
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Docs tab (server migration 076): the list, a doc with its links and backlinks, editing, a refused save to merge, and [[links]] in messages. */
@OptIn(ExperimentalTestApi::class)
class DocsTest {
    private val server = FakeServer()

    @AfterTest
    fun closeStreams() = server.close()

    private val sam = """{"principal":"member:s1","kind":"person","name":"Sam"}"""
    private val forge = """{"principal":"agent:a1","kind":"agent","name":"forge"}"""

    private fun doc(version: Int, body: String, by: String = sam) =
        """{"id":"d1","projectId":"p1","title":"Runbook","body":"$body","version":$version,"size":10,""" +
            """"createdAt":"2026-11-20T10:00:00Z","createdBy":$sam,"updatedAt":"2026-11-20T10:00:00Z","updatedBy":$by}"""

    private fun page(version: Int, body: String, by: String = sam) =
        """{"doc":${doc(version, body, by)},"backlinks":[{"id":"d2","title":"Checklist"}],"canEdit":true}"""

    private fun ComposeUiTest.app() {
        server.docAnswers["GET /api/projects/mega/docs"] = 200 to
            """{"projectId":"p1","docs":[${doc(1, "")}],"canEdit":true}"""
        server.docAnswers["GET /api/docs/d1"] = 200 to page(1, "Steps. See [[Checklist]].")
        val platform = AppPlatform.inMemory(engine = { server.engine() }, sessions = MemorySessionStore(SavedSession("https://dash.test", "key-1", "Test device")))
        setContent { Box(Modifier.size(1440.dp, 900.dp)) { AgentsApp(platform, dark = true, accessibility = AccessibilityPrefs()) } }
    }

    private fun ComposeUiTest.waitForNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        try {
            waitUntil(timeoutMillis = 10_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        } catch (timeout: Throwable) {
            throw AssertionError("never saw ${matcher.description}; the screen had:\n" + onRoot().printToString(), timeout)
        }
        return onAllNodes(matcher).onFirst()
    }

    private fun waitForWrite(method: String, path: String): String {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            server.wrote(method, path)?.let { return it }
            Thread.sleep(25)
        }
        error("no $method $path; writes were ${server.writes}")
    }

    private fun ComposeUiTest.openRunbook() {
        waitForNode(hasText("Fixed the unread counts"))
        waitForNode(hasText("Mega") and hasClickAction()).performClick()
        waitForNode(hasText("Docs") and hasClickAction()).performClick()
        waitForNode(hasText("Runbook") and hasClickAction()).performClick()
        waitForNode(hasText("Linked from", ignoreCase = true))
    }

    @Test
    fun theDocsTabListsAChannelsDocsAndOpensOneWithTheDocsLinkingToIt() = runDesktopComposeUiTest(width = 1440, height = 900) {
        app()
        openRunbook()
        // Its [[link]] reads as the doc's title, and the doc linking here is listed.
        waitForNode(hasText("Checklist", substring = true) and hasClickAction())
        waitForNode(hasText("Version 1 · saved by Sam", substring = true))
        assertTrue(synchronized(server.calls) { server.calls.toList() }.any { it == "/api/projects/mega/docs" })
    }

    @Test
    fun aSaveSomeoneElseBeatShowsBothTextsAndSavesTheMergeOnTopOfTheirs() = runDesktopComposeUiTest(width = 1440, height = 900) {
        app()
        openRunbook()
        server.docAnswers["PUT /api/docs/d1"] = 409 to
            """{"error":"conflict","message":"this doc changed since version 1","baseVersion":1,"doc":${doc(2, "Forge's steps.", forge)}}"""
        waitForNode(hasText("Edit") and hasClickAction()).performClick()
        waitForNode(hasText("Save") and hasClickAction()).performClick()
        assertTrue(waitForWrite("PUT", "/api/docs/d1").contains("\"baseVersion\":1"))
        // Both texts, side by side; nothing was saved over forge's.
        waitForNode(hasText("forge saved this doc while you were editing"))
        waitForNode(hasText("Forge's steps."))
        waitForNode(hasText("THEIRS (VERSION 2)"))
        server.docAnswers["PUT /api/docs/d1"] = 200 to page(3, "Merged.")
        server.writes.clear()
        waitForNode(hasText("Save merged") and hasClickAction()).performClick()
        assertTrue(waitForWrite("PUT", "/api/docs/d1").contains("\"baseVersion\":2"), "the merge saves on top of their version")
        waitForNode(hasText("Version 3 · saved by Sam", substring = true))
    }

    @Test
    fun theDocsTabOpensOnTheChannelsIndexPinnedAtTheTop() = runDesktopComposeUiTest(width = 1440, height = 900) {
        fun index(body: String) = """{"id":"i1","projectId":"p1","title":"Index","body":"$body","version":1,"size":10,""" +
            """"createdAt":"2026-11-20T09:00:00Z","createdBy":$sam,"updatedAt":"2026-11-20T09:00:00Z","updatedBy":$sam}"""
        // The server lists the Index second here: the app still pins it first.
        server.docAnswers["GET /api/projects/mega/docs"] = 200 to """{"projectId":"p1","docs":[${doc(1, "")},${index("")}],"canEdit":true}"""
        server.docAnswers["GET /api/docs/i1"] = 200 to """{"doc":${index("Mega ships the app. See [[Runbook]].")},"backlinks":[],"canEdit":true}"""
        val platform = AppPlatform.inMemory(engine = { server.engine() }, sessions = MemorySessionStore(SavedSession("https://dash.test", "key-1", "Test device")))
        setContent { Box(Modifier.size(1440.dp, 900.dp)) { AgentsApp(platform, dark = true, accessibility = AccessibilityPrefs()) } }
        waitForNode(hasText("Fixed the unread counts"))
        waitForNode(hasText("Mega") and hasClickAction()).performClick()
        waitForNode(hasText("Docs") and hasClickAction()).performClick()
        // Nothing tapped: the Index is open.
        waitForNode(hasText("Mega ships the app.", substring = true))
        assertTrue(synchronized(server.calls) { server.calls.toList() }.none { it == "/api/docs/d1" }, "only the Index was opened")
        val indexRow = waitForNode(hasContentDescription("Index, saved by", substring = true)).fetchSemanticsNode().boundsInRoot.top
        val runbookRow = waitForNode(hasContentDescription("Runbook, saved by", substring = true)).fetchSemanticsNode().boundsInRoot.top
        assertTrue(indexRow < runbookRow, "the Index is listed first")
    }

    @Test
    fun aChannelWithNoIndexOffersOneFromTheTemplateSavedOnlyWhenSaved() = runDesktopComposeUiTest(width = 1440, height = 900) {
        server.docAnswers["GET /api/projects/mega/docs"] = 200 to
            """{"projectId":"p1","docs":[${doc(1, "")}],"canEdit":true,"indexTemplate":"# Mega\n\nTemplate words. [[Runbook]]"}"""
        val index = """{"id":"i1","projectId":"p1","title":"Index","body":"# Mega","version":1,"size":6,""" +
            """"createdAt":"2026-11-20T09:00:00Z","createdBy":$sam,"updatedAt":"2026-11-20T09:00:00Z","updatedBy":$sam}"""
        server.docAnswers["POST /api/projects/mega/docs"] = 201 to """{"doc":$index,"backlinks":[],"canEdit":true}"""
        val platform = AppPlatform.inMemory(engine = { server.engine() }, sessions = MemorySessionStore(SavedSession("https://dash.test", "key-1", "Test device")))
        setContent { Box(Modifier.size(1440.dp, 900.dp)) { AgentsApp(platform, dark = true, accessibility = AccessibilityPrefs()) } }
        waitForNode(hasText("Fixed the unread counts"))
        waitForNode(hasText("Mega") and hasClickAction()).performClick()
        waitForNode(hasText("Docs") and hasClickAction()).performClick()
        waitForNode(hasContentDescription("No Index yet. Write one") and hasClickAction()).performClick()
        // The editor holds the template; nothing is saved yet.
        waitForNode(hasText("Template words.", substring = true))
        assertTrue(server.writes.none { it.first == "POST /api/projects/mega/docs" }, "nothing saved before Save")
        waitForNode(hasText("Save") and hasClickAction()).performClick()
        val sent = waitForWrite("POST", "/api/projects/mega/docs")
        assertTrue(sent.contains("\"title\":\"Index\"") && sent.contains("Template words."), sent)
        waitForNode(hasText("Version 1 · saved by Sam", substring = true))
    }

    @Test
    fun aLinkInPlainWordsBecomesALinkToTheDocInItsChannel() {
        val linked = docLinked("See [[Runbook|the runbook]] and [[Deploy]].", "mega", Color.Red)!!
        assertEquals("See the runbook and Deploy.", linked.text)
        val urls = linked.getLinkAnnotations(0, linked.length).map { (it.item as LinkAnnotation.Url).url }
        assertEquals(listOf("wildware-doc:mega/Runbook", "wildware-doc:mega/Deploy"), urls)
        assertNull(docLinked("no links", "mega", Color.Red))
    }

    @Test
    fun docLinksOpenInTheAppAndOtherLinksGoOnAsBefore() {
        val opened = mutableListOf<String>()
        val passed = mutableListOf<String>()
        val handler = DocLinkUriHandler(object : UriHandler { override fun openUri(uri: String) { passed += uri } }) { channel, title -> opened += "$channel/$title" }
        handler.openUri("wildware-doc:mega/Release%20plan")
        handler.openUri("wildware-doc:Notes")
        handler.openUri("https://example.com")
        assertEquals(listOf("mega/Release plan", "null/Notes"), opened)
        assertEquals(listOf("https://example.com"), passed)
    }
}
