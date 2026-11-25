package dev.wildware.agents.app

import dev.wildware.agents.MemoryTextStore
import dev.wildware.agents.PlanLimitHit
import dev.wildware.agents.PlanLimits
import dev.wildware.agents.PlanUse
import dev.wildware.agents.TeamPlan
import dev.wildware.agents.app.feed.Upsell
import dev.wildware.agents.app.feed.UpsellMarks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Who is offered Team, when the notes and the popup show, and what a refusal says where Team may not be offered. */
class UpsellTest {
    private val day = 24L * 60 * 60 * 1000
    private val made = 1_700_000_000_000

    private val free = TeamPlan(
        plan = TeamPlan.Free,
        limits = PlanLimits(people = 2, agents = 3, storageBytes = 500L * 1024 * 1024, historyDays = 30),
        used = PlanUse(people = 1, agents = 1, storageBytes = 0),
        upgradeUrl = "https://billing.test/teams/acme",
        createdAt = made,
    )

    @Test
    fun onlyOwnersAndAdminsOfAFreeTeamAreOffered() {
        assertNotNull(Upsell.offerFor(free, "owner", links = true))
        assertNotNull(Upsell.offerFor(free, "admin", links = true))
        assertNull(Upsell.offerFor(free, "member", links = true))
        assertNull(Upsell.offerFor(free, null, links = true))
    }

    @Test
    fun noOfferOnTeamOrSelfHostedOrWithNowhereToGo() {
        assertNull(Upsell.offerFor(TeamPlan(plan = TeamPlan.Team, upgradeUrl = null), "owner", links = true))
        assertNull(Upsell.offerFor(null, "owner", links = true))
        assertNull(Upsell.offerFor(free.copy(upgradeUrl = null), "owner", links = true))
        assertNull(Upsell.offerFor(free.copy(upgradeUrl = " "), "owner", links = true))
    }

    @Test
    fun anIphoneOutsideTheUsStoreIsOfferedNothing() {
        assertTrue(Upsell.storefrontAllowsLinks("USA"))
        assertFalse(Upsell.storefrontAllowsLinks("GBR"))
        assertFalse(Upsell.storefrontAllowsLinks("CAN"))
        assertFalse(Upsell.storefrontAllowsLinks(null))
        assertNull(Upsell.offerFor(free, "owner", links = Upsell.storefrontAllowsLinks("GBR")))
    }

    @Test
    fun filesNoteFromEightyPercent() {
        assertNull(Upsell.storageNote(free.copy(used = PlanUse(storageBytes = 399L * 1024 * 1024))))
        assertEquals("400 MB of 500 MB used. Team gives you more room.", Upsell.storageNote(free.copy(used = PlanUse(storageBytes = 400L * 1024 * 1024))))
        assertEquals("412 MB of 500 MB used. Team gives you more room.", Upsell.storageNote(free.copy(used = PlanUse(storageBytes = 412L * 1024 * 1024))))
        assertNull(Upsell.storageNote(TeamPlan(plan = TeamPlan.Team)))
    }

    @Test
    fun agentsAndPeopleNotesOnlyOnceFull() {
        assertNull(Upsell.agentsNote(free.copy(used = PlanUse(agents = 2)), offer = true))
        assertEquals("3 of 3 agents on the free plan. Team lets you connect as many as you like.", Upsell.agentsNote(free.copy(used = PlanUse(agents = 3)), offer = true))
        assertEquals("3 of 3 agents on the free plan. Remove one before you connect another.", Upsell.agentsNote(free.copy(used = PlanUse(agents = 3)), offer = false))
        assertNull(Upsell.peopleNote(free, offer = true))
        assertEquals(
            "2 of 2 people on the free plan, counting invites still waiting. Team gives you room for more.",
            Upsell.peopleNote(free.copy(used = PlanUse(people = 2)), offer = true),
        )
        assertNull(Upsell.peopleNote(TeamPlan(plan = TeamPlan.Team, limits = PlanLimits(people = 5), used = PlanUse(people = 5)), offer = true))
    }

    @Test
    fun thePopupWaitsTwoDaysThenAWeekBetween() {
        assertFalse(Upsell.popupDue(free, made + day, lastShown = null, busy = false))
        assertFalse(Upsell.popupDue(free, made + 2 * day - 1, lastShown = null, busy = false))
        assertTrue(Upsell.popupDue(free, made + 2 * day, lastShown = null, busy = false))
        val shown = made + 3 * day
        assertFalse(Upsell.popupDue(free, shown + 7 * day - 1, lastShown = shown, busy = false))
        assertTrue(Upsell.popupDue(free, shown + 7 * day, lastShown = shown, busy = false))
        assertEquals(7 * day, Upsell.POPUP_EVERY_MS)
    }

    @Test
    fun thePopupNeverShowsWhileSomethingWaitsOrWithoutTheTeamsStart() {
        assertFalse(Upsell.popupDue(free, made + 30 * day, lastShown = null, busy = true))
        assertFalse(Upsell.popupDue(free.copy(createdAt = null), made + 30 * day, lastShown = null, busy = false))
        // A clock set back never brings it sooner.
        assertFalse(Upsell.popupDue(free, made + 10 * day, lastShown = made + 20 * day, busy = false))
    }

    @Test
    fun marksAreKeptPerTeam() {
        val store = MemoryTextStore()
        val marks = UpsellMarks(store)
        assertNull(marks.popupShown("https://acme.test"))
        marks.markPopup("https://acme.test", 5)
        marks.markFilesHidden("https://acme.test", 7)
        marks.markPopup("https://other.test", 9)
        val again = UpsellMarks(store)
        assertEquals(5, again.popupShown("https://acme.test"))
        assertEquals(7, again.filesHidden("https://acme.test"))
        assertEquals(9, again.popupShown("https://other.test"))
        assertNull(UpsellMarks(MemoryTextStore("not json")).popupShown("https://acme.test"))
    }

    @Test
    fun whereTeamIsNotOfferedARefusalHasPlainWordsAndNowhereToGo() {
        val hit = PlanLimitHit("The free plan has room for 3 agents. Remove one, or upgrade to Team to add more.", "agents", "https://billing.test/teams/acme")
        assertEquals(hit, Upsell.refusal(hit, offer = true))
        val plain = Upsell.refusal(hit, offer = false)
        assertNull(plain.upgradeUrl)
        assertFalse("upgrade" in plain.message.lowercase() || "team" in plain.message.lowercase(), plain.message)
        for (limit in listOf("people", "agents", "storage", null)) {
            val words = Upsell.plainRefusal(limit).lowercase()
            assertFalse("upgrade" in words || "team" in words || "website" in words, words)
        }
    }
}
