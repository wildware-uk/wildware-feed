package dev.wildware.agents

import kotlinx.serialization.Serializable

/**
 * A hosted team's plan, as `GET /api/plan` answers (server docs/HOSTING.md §7, "The free plan"):
 * `free` or `team`, its limits, what is used, and where the owner upgrades. A self-hosted server
 * has no plan ([plan] null), and the app shows nothing about plans for it.
 *
 * Buying happens on the billing site ([upgradeUrl], in the browser), or in the iPhone app through
 * Apple's in-app purchase, which carries [appAccountToken] to tie it to this team.
 */
@Serializable
data class TeamPlan(
    val plan: String? = null,
    val limits: PlanLimits = PlanLimits(),
    val used: PlanUse = PlanUse(),
    val upgradeUrl: String? = null,
    /** When the team was made (ms since 1970), or null from a dashboard before it said. */
    val createdAt: Long? = null,
    /** Who takes the money: "web" (the billing site), "apple" (the App Store), or null for nobody. */
    val billing: String? = null,
    /** The token an App Store purchase for this team carries, or null where the dashboard sells nothing. */
    val appAccountToken: String? = null,
) {
    val free: Boolean get() = plan == Free

    /** On Team, paid through the App Store: changed or cancelled there. */
    val paidByApple: Boolean get() = plan == Team && billing == Apple

    /** "Free plan" or "Team plan". */
    val title: String get() = if (free) "Free plan" else "Team plan"

    /**
     * The free plan's limits with what is used, one line each: "People 1 of 2", "Agents 2 of 3",
     * "Files 120 MB of 500 MB", "History: last 30 days". None on the Team plan.
     */
    val lines: List<String>
        get() {
            if (!free) return emptyList()
            return buildList {
                limits.people?.let { add("People ${used.people} of $it") }
                limits.agents?.let { add("Agents ${used.agents} of $it") }
                limits.storageBytes?.let { add("Files ${megabytes(used.storageBytes)} of ${megabytes(it)}") }
                limits.historyDays?.let { add("History: last $it days") }
            }
        }

    companion object {
        const val Free = "free"
        const val Team = "team"
        const val Apple = "apple"

        /** Bytes in whole megabytes, as the plan names them: "500 MB". Anything above nothing shows as at least 1 MB. */
        fun megabytes(bytes: Long): String {
            val mb = bytes / (1024 * 1024)
            return "${if (bytes > 0 && mb == 0L) 1 else mb} MB"
        }
    }
}

@Serializable
data class PlanLimits(
    val people: Long? = null,
    val agents: Long? = null,
    val storageBytes: Long? = null,
    val historyDays: Long? = null,
)

@Serializable
data class PlanUse(
    val people: Long = 0,
    val agents: Long = 0,
    val storageBytes: Long = 0,
)

/**
 * The free plan said no: what the dashboard said, which limit (`people`, `agents` or `storage`),
 * and where the owner can upgrade (null when the dashboard has nowhere to send them).
 */
data class PlanLimitHit(val message: String, val limit: String?, val upgradeUrl: String?) {
    companion object {
        const val Code = "plan_limit"

        /** The hit [failed] was, or null when it was anything else. */
        fun of(failed: Throwable?): PlanLimitHit? {
            val refused = failed as? DashboardException ?: return null
            if (refused.code != Code) return null
            return PlanLimitHit(refused.message ?: "The free plan has no room for that.", refused.limit, refused.upgradeUrl)
        }
    }
}

/**
 * What the dashboard said about an App Store purchase it passed on: [status] 200 when the team is
 * on Team now, else [error] (such as `paid_on_web`, `wrong_team`, `expired`) and [message] in words.
 * 502 means the purchase couldn't be passed on yet: try again later.
 */
data class AppStoreRelay(val status: Int, val error: String? = null, val message: String? = null) {
    val ok: Boolean get() = status == 200

    /** The dashboard decided (yes or no): the App Store can be told the purchase is dealt with. */
    val settled: Boolean get() = status == 200 || status == 400 || status == 409
}
