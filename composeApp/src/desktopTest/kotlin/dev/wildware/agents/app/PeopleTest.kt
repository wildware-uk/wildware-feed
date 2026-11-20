package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.wildware.agents.HostCheck
import dev.wildware.agents.SignInMethod
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.CreatedInviteRow
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.InviteRow
import dev.wildware.agents.app.feed.PersonRow
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.feed.ScreenPreview
import dev.wildware.agents.app.feed.TeamState
import dev.wildware.agents.app.signin.ConnectState
import dev.wildware.agents.app.signin.SignInChoice
import dev.wildware.agents.app.signin.SignInScreen
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The People sheet (who is in the team, inviting, roles) and joining with an invite at sign-in. */
@OptIn(ExperimentalTestApi::class)
class PeopleTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
    private val events = mutableListOf<FeedEvent>()

    private val owner = TeamState(
        me = PersonRow("founder", "Sam", "sam", "owner", "sam@example.com", you = true, founder = true),
        people = listOf(
            PersonRow("founder", "Sam", "sam", "owner", "sam@example.com", you = true, founder = true),
            PersonRow("p2", "Alex Morgan", "alex-morgan", "admin", "alex@example.com"),
            PersonRow("p3", "Priya", "priya", "member", "priya@example.com"),
            PersonRow("p4", "Jo", "jo", "member", "jo@example.com", removed = true),
        ),
        invites = listOf(InviteRow("i1", "member", "kim@example.com", "Expires 26 November 2026")),
        team = true,
        loaded = true,
    )

    private val member = owner.copy(
        me = owner.people[2].copy(you = true),
        people = owner.people.map { it.copy(you = it.id == "p3", email = if (it.id == "p3") it.email else null) },
        invites = emptyList(),
    )

    private fun ComposeUiTest.sheet(team: TeamState = owner, scale: Float = 1f, dark: Boolean = true) = setContent {
        CompositionLocalProvider(LocalDensity provides Density(scale)) {
            AgentTheme(dark = dark) {
                Box(Modifier.fillMaxSize()) {
                    FeedScreen(FakeFeed.state.copy(team = team), SafeArea(), preview = ScreenPreview(peopleOpen = true), onEvent = { events += it })
                }
            }
        }
    }

    @Test
    fun itListsThePeopleYouFirstAndReadsThemWhenOpened() = runDesktopComposeUiTest(width = 1440, height = 900) {
        sheet()
        // The sheet, and the members list beside the feed.
        assertEquals(2, onAllNodesWithText("Alex Morgan").fetchSemanticsNodes().size)
        onNodeWithText("Admin · @alex-morgan · alex@example.com").assertExists()
        onNodeWithText("You", substring = true).assertExists()
        // Someone who left isn't listed (what they wrote still names them).
        assertTrue(onAllNodesWithText("Jo").fetchSemanticsNodes().isEmpty())
        onNodeWithText("kim@example.com").assertExists()
        assertTrue(FeedEvent.ReadPeople in events)
    }

    @Test
    fun anOwnerInvitesWithARole() = runDesktopComposeUiTest(width = 1440, height = 900) {
        sheet()
        onNodeWithText("Invite someone").performClick()
        onAllNodesWithText("Admin").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty(), "an owner picks the role") }
        onAllNodes(hasText("Admin") and hasClickAction()).onFirst().performClick()
        onNodeWithText("Make invite").performClick()
        waitForIdle()
        assertEquals(listOf(FeedEvent.InvitePerson("admin", null)), events.filterIsInstance<FeedEvent.InvitePerson>())
    }

    @Test
    fun removingAsksFirst() = runDesktopComposeUiTest(width = 1440, height = 900) {
        sheet()
        onAllNodes(hasText("Change") and hasClickAction())[2].performClick()
        onNodeWithText("Remove Priya").performClick()
        assertTrue(events.none { it is FeedEvent.RemovePerson }, "nothing is removed before the owner says so")
        onNodeWithText("Remove Priya? Their devices are signed out at once. What they wrote stays, under their name.").assertExists()
        onNodeWithText("Remove").performClick()
        waitForIdle()
        assertEquals(listOf(FeedEvent.RemovePerson("p3")), events.filterIsInstance<FeedEvent.RemovePerson>())
    }

    @Test
    fun aMemberSeesNoInvitesAndCanOnlyChangeThemselves() = runDesktopComposeUiTest(width = 1440, height = 900) {
        sheet(member)
        assertTrue(onAllNodesWithText("Invite someone").fetchSemanticsNodes().isEmpty())
        assertEquals(1, onAllNodes(hasText("Change") and hasClickAction()).fetchSemanticsNodes().size)
        onNodeWithText("Ask an owner or an admin to invite someone.").assertExists()
    }

    @Test
    fun joiningWithAnInviteLink() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val joined = mutableListOf<List<String>>()
        setContent {
            AgentTheme(false) {
                SignInScreen(
                    "Laptop", null, SafeArea(), checkHost = { error("no network here") }, signIn = { _, _, _ -> },
                    acceptInvite = { server, invite, name, email, password, device -> joined += listOf(server, invite, name, email, password, device) },
                )
            }
        }
        onNodeWithText("I have an invite").performClick()
        val code = "A".repeat(43)
        onAllNodes(androidx.compose.ui.test.hasSetTextAction())[0].performTextInput("https://feed.example.com/join/$code")
        onNodeWithText("YOUR NAME").assertExists()
        onAllNodes(androidx.compose.ui.test.hasSetTextAction())[1].performTextInput("Priya")
        onAllNodes(androidx.compose.ui.test.hasSetTextAction())[2].performTextInput("priya@example.com")
        onAllNodes(androidx.compose.ui.test.hasSetTextAction())[3].performTextInput("a long password")
        onNodeWithText("Join the team").performClick()
        waitUntil(timeoutMillis = 5_000) { joined.isNotEmpty() }
        assertEquals("https://feed.example.com", joined.single()[0])
        assertEquals("https://feed.example.com/join/$code", joined.single()[1])
        assertEquals("Priya", joined.single()[2])
    }

    @Test
    fun anInviteForAnotherServerIsRefusedNotFollowed() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val joined = mutableListOf<String>()
        setContent {
            AgentTheme(false) {
                val connect = remember { ConnectState("https://feed.example.com", HostCheck.Feed("https://feed.example.com", listOf(SignInMethod.Password))) }
                SignInScreen(
                    "Laptop", null, SafeArea(), startWith = SignInChoice.Host, connect = connect, checkHost = { error("no network here") },
                    acceptInvite = { server, _, _, _, _, _ -> joined += server },
                    startInvite = "https://elsewhere.example/join/${"A".repeat(43)}",
                    signIn = { _, _, _ -> },
                )
            }
        }
        onAllNodes(androidx.compose.ui.test.hasSetTextAction())[1].performTextInput("Priya")
        onAllNodes(androidx.compose.ui.test.hasSetTextAction())[2].performTextInput("priya@example.com")
        onAllNodes(androidx.compose.ui.test.hasSetTextAction())[3].performTextInput("a long password")
        onNodeWithText("Join the team").performClick()
        waitForIdle()
        onNodeWithText("That invite is for https://elsewhere.example, not https://feed.example.com.").assertExists()
        assertTrue(joined.isEmpty(), "nothing was sent anywhere")
    }

    @Test
    fun screenshots() {
        for (dark in listOf(true, false)) {
            runDesktopComposeUiTest(2880, 1800) { shoot("desktop", 2f, dark) }
            runDesktopComposeUiTest(1170, 2532) { shoot("phone", 3f, dark) }
            runDesktopComposeUiTest(2880, 1800) { shootInvite("desktop", 2f, dark) }
            runDesktopComposeUiTest(1170, 2532) { shootInvite("phone", 3f, dark) }
            runDesktopComposeUiTest(2880, 1800) { shootJoin("desktop", 2f, dark) }
            runDesktopComposeUiTest(1170, 2532) { shootJoin("phone", 3f, dark) }
        }
    }

    private fun ComposeUiTest.save(name: String) {
        waitForIdle()
        val file = File(outDir, "$name.png")
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", file)
        println("screenshot: ${file.absolutePath}")
    }

    private fun ComposeUiTest.shoot(size: String, scale: Float, dark: Boolean) {
        sheet(owner, scale, dark)
        save("$size-${if (dark) "dark" else "light"}-people")
    }

    private fun ComposeUiTest.shootInvite(size: String, scale: Float, dark: Boolean) {
        val code = "q4Xk2Vb7Nn0sLwR9tYp3Hc8uJm1ZaEe6Gd5Fo2Ii7Kx"
        sheet(owner.copy(created = CreatedInviteRow("https://feed.example.com/join/$code", code, "member", "kim@example.com")), scale, dark)
        save("$size-${if (dark) "dark" else "light"}-people-invite")
    }

    private fun ComposeUiTest.shootJoin(size: String, scale: Float, dark: Boolean) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(scale)) {
                AgentTheme(dark) {
                    val connect = remember { ConnectState("https://feed.example.com", HostCheck.Feed("https://feed.example.com", listOf(SignInMethod.Password, SignInMethod.EmailPassword))) }
                    SignInScreen(
                        "Priya's laptop", null, if (size == "phone") SafeArea(top = 54.dp, bottom = 34.dp) else SafeArea(),
                        startWith = SignInChoice.Host, connect = connect,
                        checkHost = { error("no network here") }, signIn = { _, _, _ -> },
                        signInWithEmail = { _, _, _, _ -> },
                        acceptInvite = { _, _, _, _, _, _ -> },
                        startInvite = "https://feed.example.com/join/q4Xk2Vb7Nn0sLwR9tYp3Hc8uJm1ZaEe6Gd5Fo2Ii7Kx",
                    )
                }
            }
        }
        save("$size-${if (dark) "dark" else "light"}-people-join")
    }
}
