package dev.wildware.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InboxTest {
    // 2026-11-16T12:00:00Z
    private val now = 1794830400000L

    private fun request(
        id: String,
        expiresAt: String = "2026-11-16T13:00:00Z",
        kind: RequestKind = RequestKind.Confirm,
        options: List<String>? = null,
    ) = PendingRequest(id, "Builder", "garden-game", "Garden Game", "Question?", kind, options, expiresAt)

    @Test
    fun `the question closing soonest comes first`() {
        val later = request("later", "2026-11-16T15:00:00Z")
        val soon = request("soon", "2026-11-16T12:10:00Z")
        val middle = request("middle", "2026-11-16T13:00:00Z")

        assertEquals(listOf("soon", "middle", "later"), Inbox.pending(listOf(later, soon, middle), now).map { it.id })
    }

    @Test
    fun `expired questions are dropped and unreadable times go last`() {
        val gone = request("gone", "2026-11-16T11:59:00Z")
        val odd = request("odd", "not a time")
        val open = request("open", "2026-11-16T12:30:00Z")

        assertEquals(listOf("open", "odd"), Inbox.pending(listOf(gone, odd, open), now).map { it.id })
        assertEquals(2, Inbox.badge(listOf(gone, odd, open), now))
    }

    @Test
    fun `time left reads in plain words`() {
        assertEquals("Under a minute left", Inbox.timeLeft("2026-11-16T12:00:30Z", now))
        assertEquals("12 min left", Inbox.timeLeft("2026-11-16T12:12:40Z", now))
        assertEquals("1 hour left", Inbox.timeLeft("2026-11-16T13:00:00Z", now))
        assertEquals("1 hour 5 min left", Inbox.timeLeft("2026-11-16T13:05:00Z", now))
        assertEquals("3 hours left", Inbox.timeLeft("2026-11-16T15:30:00Z", now))
        assertEquals("1 day left", Inbox.timeLeft("2026-11-17T13:00:00Z", now))
        assertEquals("4 days left", Inbox.timeLeft("2026-11-20T12:00:00Z", now))
        assertEquals("Expired", Inbox.timeLeft("2026-11-16T11:00:00Z", now))
        assertNull(Inbox.timeLeft("soon", now))
    }

    @Test
    fun `fractional seconds and offsets are read too`() {
        assertEquals("30 min left", Inbox.timeLeft("2026-11-16T12:30:00.000Z", now))
        assertEquals("30 min left", Inbox.timeLeft("2026-11-16T13:30:00+01:00", now))
    }

    @Test
    fun `the last five minutes are urgent`() {
        assertTrue(Inbox.isUrgent("2026-11-16T12:04:00Z", now))
        assertFalse(Inbox.isUrgent("2026-11-16T12:06:00Z", now))
    }

    @Test
    fun `only a yes-or-no question answers on a swipe`() {
        assertTrue(Inbox.canSwipe(request("r", kind = RequestKind.Confirm)))
        assertFalse(Inbox.canSwipe(request("r", kind = RequestKind.Buttons, options = listOf("Ship"))))
        assertFalse(Inbox.canSwipe(request("r", kind = RequestKind.Text)))
    }

    @Test
    fun `each kind gets its own control`() {
        assertEquals(InboxControl.Choices, Inbox.control(RequestKind.Confirm))
        assertEquals(InboxControl.Choices, Inbox.control(RequestKind.Buttons))
        assertEquals(InboxControl.Choices, Inbox.control(RequestKind.Choice))
        assertEquals(InboxControl.Text, Inbox.control(RequestKind.Text))
        assertEquals(InboxControl.Dashboard, Inbox.control(RequestKind.MultiChoice))
        assertEquals(InboxControl.Dashboard, Inbox.control(RequestKind.Form))
    }

    @Test
    fun `every option gets a button, even more than the lock screen fits`() {
        val options = listOf("A", "B", "C", "D", "E", "F")
        val request = request("r", kind = RequestKind.Choice, options = options)
        val choices = Inbox.choices(request)

        assertEquals(options, choices.map { it.title })
        choices.forEachIndexed { index, choice ->
            assertEquals(
                kotlinx.serialization.json.JsonPrimitive(options[index]),
                RequestActions.answerFor(request.kind, request.options, choice.actionId, null),
            )
        }
    }

    @Test
    fun `a confirm offers yes then a destructive no that answer true and false`() {
        val choices = Inbox.choices(request("r"))

        assertEquals(listOf("Yes", "No"), choices.map { it.title })
        assertTrue(choices[1].isDestructive)
        assertEquals(
            kotlinx.serialization.json.JsonPrimitive(true),
            RequestActions.answerFor(RequestKind.Confirm, null, choices[0].actionId, null),
        )
    }

    @Test
    fun `a typed answer goes through the reply action`() {
        assertEquals(
            kotlinx.serialization.json.JsonPrimitive("ship it"),
            RequestActions.answerFor(RequestKind.Text, null, Inbox.TextActionId, " ship it "),
        )
    }

    @Test
    fun `the dashboard page focuses the question`() {
        assertEquals("/projects/garden-game?focus=r9", Inbox.dashboardPath(request("r9")))
        assertEquals("/", Inbox.dashboardPath(request("r9").copy(projectSlug = null)))
    }
}
