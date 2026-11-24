package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AccessibilityPrefs
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.Author
import dev.wildware.agents.app.feed.Avatar
import dev.wildware.agents.app.feed.AvatarPictureTag
import dev.wildware.agents.app.feed.FilePicker
import dev.wildware.agents.app.feed.LocalMediaAuth
import dev.wildware.agents.app.feed.MediaAuth
import dev.wildware.agents.app.feed.PendingFile
import dev.wildware.agents.app.feed.pngOf
import dev.wildware.agents.app.feed.squareAvatarPng
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.session.MemorySessionStore
import dev.wildware.agents.app.session.SavedSession
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Member profiles: a role line and a description for each person and agent. Everyone reads them;
 * you change your own, owners and admins change an agent's, and nobody changes another person's.
 */
@OptIn(ExperimentalTestApi::class)
class MemberProfileTest {
    private val server = FakeServer()

    @AfterTest
    fun stop() = server.close()

    /** Sam, in [role], and Alex, the owner, who has written a profile. */
    private fun asRole(role: String) {
        server.me = """{"person":{"id":"m-sam","name":"Sam","handle":"sam","role":"$role"},"team":true}"""
        server.people = """{"people":[""" +
            """{"id":"founder","name":"Alex","handle":"alex","role":"owner","founder":true,"profileRole":"Marketing","description":"Runs the launch.\nAsk me about the blog."},""" +
            """{"id":"m-sam","name":"Sam","handle":"sam","role":"$role"}]}"""
    }

    /** A picture the picker hands over: wider than it is tall, so it has to be cropped. */
    private val photo = PendingFile("photo.png", "image/png", pngOf(80, 40, IntArray(80 * 40) { i -> if (i % 80 < 40) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }))

    private fun ComposeUiTest.app() {
        val platform = AppPlatform.inMemory(
            engine = { server.engine() },
            sessions = MemorySessionStore(SavedSession("https://dash.test", "key-1", "Test device", deviceId = "d1")),
            filePicker = FilePicker { picked -> picked(listOf(photo)) },
        )
        setContent { Box(Modifier.size(1440.dp, 900.dp)) { AgentsApp(platform, dark = true, accessibility = AccessibilityPrefs()) } }
    }

    private fun ComposeUiTest.waitFor(matcher: SemanticsMatcher): SemanticsNodeInteraction = try {
        waitUntil(timeoutMillis = 20_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(matcher).onFirst()
    } catch (timeout: Throwable) {
        throw AssertionError("never saw ${matcher.description}; the screen had:\n" + onAllNodes(isRoot()).printToString(), timeout)
    }

    private fun clickLabelled(label: String) = SemanticsMatcher("clicks to \"$label\"") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }

    private fun ComposeUiTest.openProfile(name: String) = waitFor(clickLabelled("Open $name's profile")).performScrollTo().performClick()

    private fun ComposeUiTest.openForge() = waitFor(hasClickAction() and hasContentDescription("forge", substring = true)).performScrollTo().performClick()

    private fun ComposeUiTest.field(label: String) = waitFor(hasContentDescription(label) and hasSetTextAction())

    private fun ComposeUiTest.hasNo(matcher: SemanticsMatcher) = onAllNodes(matcher).fetchSemanticsNodes().isEmpty()

    private fun ComposeUiTest.waitForWrite(method: String, path: String): String {
        waitUntil(timeoutMillis = 20_000) { server.wrote(method, path) != null }
        return server.wrote(method, path)!!
    }

    private fun parsed(body: String) = Json.parseToJsonElement(body)

    /** How many avatars show a picture. Rows merge their avatar into themselves, so look inside them. */
    private fun ComposeUiTest.pictures() = onAllNodes(hasTestTag(AvatarPictureTag), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun ComposeUiTest.waitForPicture() = waitUntil(timeoutMillis = 20_000) { pictures() > 0 }

    private fun ComposeUiTest.noPictureControls() =
        hasNo(hasText("Add a picture")) && hasNo(hasText("Change picture")) && hasNo(hasText("Remove picture"))

    @Test
    fun aPersonsProfileShowsTheirRoleAndDescriptionAndCantBeChangedByAnotherPerson() = runDesktopComposeUiTest(1440, 900) {
        asRole("owner")
        app()
        openProfile("Alex")
        waitFor(hasText("Marketing", substring = true))
        waitFor(hasText("Runs the launch.\nAsk me about the blog."))
        waitFor(hasText("@alex", substring = true))
        assertTrue(hasNo(hasText("Edit profile")) && hasNo(hasText("Add a description")), "even an owner can't change another person's profile")
        assertTrue(hasNo(hasContentDescription("Description") and hasSetTextAction()))
        assertTrue(noPictureControls(), "nor their picture")
    }

    @Test
    fun youChangeYourOwnProfileAndItIsSentToTheDashboard() = runDesktopComposeUiTest(1440, 900) {
        asRole("member")
        app()
        openProfile("Sam")
        waitFor(hasText("No description yet. Say what you do and when to contact you."))
        waitFor(hasText("Add a description") and hasClickAction()).performClick()
        // The role line stops at 60 characters, and says so.
        field("Role line").performTextInput("D".repeat(70))
        waitFor(hasText("60/60"))
        field("Role line").performTextReplacement("Design")
        field("Description").performTextInput("Draws the screens.\nAsk me about colours.")
        waitFor(hasText("Save profile") and hasClickAction()).performClick()
        assertEquals(
            parsed("""{"profileRole":"Design","description":"Draws the screens.\nAsk me about colours."}"""),
            parsed(waitForWrite("PATCH", "/api/me")),
        )
        // Saved: the profile shows what the dashboard now has.
        waitFor(hasText("Draws the screens.\nAsk me about colours."))
        waitFor(hasText("Edit profile") and hasClickAction())
    }

    @Test
    fun aRefusedProfileSaysWhyAndKeepsWhatWasTyped() = runDesktopComposeUiTest(1440, 900) {
        asRole("member")
        server.refuseProfile = "description must be at most 500 characters"
        app()
        openProfile("Sam")
        waitFor(hasText("Add a description") and hasClickAction()).performClick()
        field("Description").performTextInput("Draws the screens.")
        waitFor(hasText("Save profile") and hasClickAction()).performClick()
        waitFor(hasText("description must be at most 500 characters", substring = true))
        waitFor(hasText("Draws the screens."))
    }

    @Test
    fun anAdminChangesAnAgentsProfile() = runDesktopComposeUiTest(1440, 900) {
        asRole("admin")
        app()
        openForge()
        waitFor(hasText("Say what this agent does, so others know when to @mention it."))
        waitFor(hasText("Add a description") and hasClickAction()).performClick()
        field("Role line").performTextInput("Development")
        field("Description").performTextInput("Builds the app. @mention it for build failures.")
        waitFor(hasText("Save profile") and hasClickAction()).performClick()
        assertEquals(
            parsed("""{"profileRole":"Development","description":"Builds the app. @mention it for build failures."}"""),
            parsed(waitForWrite("PATCH", "/api/agents/a1")),
        )
        // Read again from the dashboard: the rail and the dialog show it.
        waitFor(hasText("Builds the app. @mention it for build failures."))
        waitFor(hasText("Development", substring = true))
    }

    @Test
    fun aMemberReadsAnAgentsProfileButCantChangeIt() = runDesktopComposeUiTest(1440, 900) {
        asRole("member")
        server.agentProfiles["a1"] = "Development" to "Builds the app."
        app()
        openForge()
        waitFor(hasText("Builds the app."))
        waitFor(hasText("Development", substring = true))
        assertTrue(hasNo(hasText("Edit profile")) && hasNo(hasText("Add a description")), "a member can't change an agent's profile")
        assertTrue(hasNo(hasContentDescription("Role line") and hasSetTextAction()))
        assertTrue(noPictureControls(), "nor its picture")
    }

    @Test
    fun youSetYourPictureThenRemoveIt() = runDesktopComposeUiTest(1440, 900) {
        asRole("member")
        app()
        openProfile("Sam")
        waitFor(hasText("Add a picture") and hasClickAction()).performClick()
        // Up first, as a small square PNG, then on your profile.
        assertEquals(parsed("""{"avatarMediaId":"md-up"}"""), parsed(waitForWrite("PATCH", "/api/me")))
        assertTrue(server.count("/api/media?filename=avatar.png") == 1, "calls were ${server.calls}")
        assertTrue(server.wrote("POST", "/api/media")!!.contains("IHDR"), "a PNG goes up")
        // Read again: your avatar is the picture now, here and in the members list.
        waitForPicture()
        waitFor(hasText("Remove picture") and hasClickAction()).performClick()
        waitUntil(timeoutMillis = 20_000) { server.wrote("PATCH", "/api/me") == """{"avatarMediaId":null}""" }
        waitFor(hasText("Add a picture") and hasClickAction())
        waitUntil(timeoutMillis = 20_000) { pictures() == 0 }
    }

    @Test
    fun anAdminSetsAnAgentsPicture() = runDesktopComposeUiTest(1440, 900) {
        asRole("admin")
        app()
        openForge()
        waitFor(hasText("Add a picture") and hasClickAction()).performClick()
        assertEquals(parsed("""{"avatarMediaId":"md-up"}"""), parsed(waitForWrite("PATCH", "/api/agents/a1")))
        waitFor(hasText("Remove picture") and hasClickAction())
        waitForPicture()
    }

    @Test
    fun theAvatarShowsThePictureWhenThereIsOneAndTheLetterOtherwise() = runComposeUiTest {
        val auth = MediaAuth("https://dash.test", "Bearer key-1")
        val author = androidx.compose.runtime.mutableStateOf<Author>(Author.Person("Alex", "alex", 1, avatarMediaId = "m1"))
        val withAuth = androidx.compose.runtime.mutableStateOf(true)
        setContent {
            AgentTheme(dark = true) {
                CompositionLocalProvider(LocalMediaAuth provides auth.takeIf { withAuth.value }) { Avatar(author.value, 40.dp) }
            }
        }
        waitForPicture()
        author.value = Author.Person("Alex", "alex", 1)
        waitForIdle()
        assertEquals(0, pictures(), "no picture: the letter")
        author.value = Author.Owner("You", avatarMediaId = "m2")
        waitForPicture()
        withAuth.value = false
        waitForIdle()
        assertEquals(0, pictures(), "a preview with no dashboard shows the letter")
    }

    @Test
    fun aPickedPictureIsCroppedToItsMiddleAndMadeSmall() {
        fun size(png: ByteArray) = org.jetbrains.skia.Image.makeFromEncoded(png).let { it.width to it.height }
        assertEquals(40 to 40, size(squareAvatarPng(photo.bytes)!!), "a small one is only cropped")
        val big = pngOf(1200, 800, IntArray(1200 * 800) { 0xFF336699.toInt() })
        val made = squareAvatarPng(big)!!
        assertEquals(384 to 384, size(made))
        assertTrue(made.size < 2 * 1024 * 1024, "well under the dashboard's 2 MB: ${made.size}")
        assertEquals(null, squareAvatarPng("not a picture".encodeToByteArray()))
    }
}
