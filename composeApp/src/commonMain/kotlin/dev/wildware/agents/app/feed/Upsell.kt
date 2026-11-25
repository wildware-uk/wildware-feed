package dev.wildware.agents.app.feed

import androidx.compose.runtime.Immutable
import dev.wildware.agents.PlanLimitHit
import dev.wildware.agents.TeamPlan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Gentle offers of the Team plan, for a free team's owners and admins: a note when a limit is near
 * (files at 80%, agents or people full), and now and then a popup saying what Team gives. Never for
 * a member, a team already on Team, or a self-hosted dashboard (no plan). Never where the app may not
 * link to buying outside it ([AppPlatform.buyingLinks]): on an iPhone outside the US App Store, none
 * of this shows at all, and a free-plan refusal is told in plain words with nowhere to go.
 */
object Upsell {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** How often the popup may show, per team, on this device. "Not now" starts the wait again. */
    const val POPUP_EVERY_MS = 7 * DAY_MS

    /** A new team is left alone this long before the popup may first show. */
    const val NEW_TEAM_QUIET_MS = 2 * DAY_MS

    /**
     * Whether an iPhone on App Store storefront [countryCode] (ISO alpha-3, as StoreKit gives it)
     * may link to buying outside the app: only the United States (guidelines 3.1.1, 3.1.3).
     */
    fun storefrontAllowsLinks(countryCode: String?): Boolean = countryCode == "USA"

    /** A limit is near once this much of it is used. */
    const val NEAR = 0.8

    /**
     * The plan to offer Team for, or null to offer nothing: a free [plan] with somewhere to upgrade,
     * seen by an owner or admin ([role]), where the app may link to buying ([links]).
     */
    fun offerFor(plan: TeamPlan?, role: String?, links: Boolean): TeamPlan? =
        plan?.takeIf { links && it.free && (role == "owner" || role == "admin") && upgradeLink(it) != null }

    /** "412 MB of 500 MB used. Team gives you more room.", once files pass [NEAR] of the limit. */
    fun storageNote(plan: TeamPlan): String? {
        val limit = plan.limits.storageBytes?.takeIf { it > 0 } ?: return null
        val used = plan.used.storageBytes
        if (used < limit * NEAR) return null
        return "${TeamPlan.megabytes(used)} of ${TeamPlan.megabytes(limit)} used. Team gives you more room."
    }

    /** "3 of 3 agents on the free plan. …", once no more fit. [offer]: say what Team gives, else what to do. */
    fun agentsNote(plan: TeamPlan, offer: Boolean): String? {
        if (!plan.free) return null
        val limit = plan.limits.agents ?: return null
        if (plan.used.agents < limit) return null
        if (!offer) return "${plan.used.agents} of $limit agents in use. Remove one before you connect another."
        return "${plan.used.agents} of $limit agents on the free plan. Team lets you connect as many as you like."
    }

    /** "2 of 2 people on the free plan, …", once no more fit. [offer]: say what Team gives, else what to do. */
    fun peopleNote(plan: TeamPlan, offer: Boolean): String? {
        if (!plan.free) return null
        val limit = plan.limits.people ?: return null
        if (plan.used.people < limit) return null
        if (!offer) return "${plan.used.people} of $limit people, counting invites still waiting. Remove someone or a waiting invite to invite another."
        return "${plan.used.people} of $limit people on the free plan, counting invites still waiting. Team gives you room for more."
    }

    /** The popup's title and what it lists. */
    const val POPUP_TITLE = "Get more with Team"

    fun popupLines(plan: TeamPlan): List<String> = listOf(
        "Room for more people",
        "As many agents as you like",
        "More room for files",
        plan.limits.historyDays?.let { "All your history, not just the last $it days" } ?: "All your history",
    )

    /**
     * Whether the popup is due for [plan]'s team at [now]: the team is past its first quiet days, it
     * last showed ([lastShown]) at least [POPUP_EVERY_MS] ago, and nothing is waiting on the owner
     * ([busy]: a question, an agent asking to join). A team whose start the dashboard didn't say never gets it.
     */
    fun popupDue(plan: TeamPlan, now: Long, lastShown: Long?, busy: Boolean): Boolean {
        if (busy) return false
        val made = plan.createdAt ?: return false
        if (now - made < NEW_TEAM_QUIET_MS) return false
        // A clock set back reads as shown just now: still never sooner than a week.
        return lastShown == null || now - lastShown >= POPUP_EVERY_MS
    }

    /**
     * A free-plan refusal in plain words: what is full and what to do, with no offer and no link.
     * For where the app may not link to buying, and for members, who can't upgrade.
     */
    fun plainRefusal(limit: String?): String = when (limit) {
        "people" -> "This team has no room for more people, counting invites still waiting. Remove someone or a waiting invite to invite another."
        "agents" -> "This team has no room for more agents. Remove one to add another."
        "storage" -> "This team has used all its room for files. Delete some to add more."
        else -> "This team has no room for that."
    }

    /** [hit] as this person may be told it: the dashboard's own words with "Upgrade" when [offer], else [plainRefusal]. */
    fun refusal(hit: PlanLimitHit, offer: Boolean): PlanLimitHit =
        if (offer) hit else PlanLimitHit(plainRefusal(hit.limit), hit.limit, upgradeUrl = null)
}

/** What the feed shows to offer Team: [offer] when it may, and whether the popup is up. */
@Immutable
data class UpsellState(val offer: TeamPlan? = null, val popup: Boolean = false, val storageHidden: Boolean = false)

/**
 * When each team last saw the popup and hid the files note, on this device: `{"<server>": ms,
 * "<server>#files": ms}` in one small file. Unreadable reads as never.
 */
internal class UpsellMarks(private val store: dev.wildware.agents.TextStore) {
    private fun all(): Map<String, Long> = runCatching {
        Json.parseToJsonElement(store.read().orEmpty().ifBlank { "{}" }).jsonObject
            .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.longOrNull?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap())

    fun popupShown(team: String): Long? = all()[team]
    fun filesHidden(team: String): Long? = all()["$team#files"]

    fun markPopup(team: String, at: Long) = put(team, at)
    fun markFilesHidden(team: String, at: Long) = put("$team#files", at)

    private fun put(key: String, at: Long) {
        val next = all() + (key to at)
        runCatching { store.write(JsonObject(next.mapValues { JsonPrimitive(it.value) }).toString()) }
    }
}
