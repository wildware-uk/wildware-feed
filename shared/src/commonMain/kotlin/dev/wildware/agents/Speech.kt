package dev.wildware.agents

/** Sentences Siri reads out. Written to be heard, not seen: no symbols, numbers as words where short. */
object Speech {
    fun status(summary: DashboardSummary): String {
        val online = summary.onlineAgents.size
        val waiting = summary.requests.size
        val working = summary.work.inProgress

        val parts = mutableListOf<String>()
        parts += when (online) {
            0 -> "No agents are online."
            1 -> "One agent is online."
            else -> "${count(online)} agents are online."
        }
        if (working > 0) parts += "${count(working)} ${plural(working, "card is", "cards are")} in progress."
        if (summary.work.blocked > 0) {
            parts += "${count(summary.work.blocked)} ${plural(summary.work.blocked, "is", "are")} blocked."
        }
        parts += when (waiting) {
            0 -> "Nobody is waiting on you."
            else -> {
                val top = summary.requests.first()
                val lead = if (waiting == 1) "One question is waiting." else "${count(waiting)} questions are waiting."
                "$lead ${top.agentName} asks: ${top.question}"
            }
        }
        return parts.joinToString(" ")
    }

    fun request(request: PendingRequest): String {
        val choices = request.options.orEmpty()
        val tail = when {
            request.kind == RequestKind.Confirm -> " Yes or no?"
            choices.isNotEmpty() && choices.size <= RequestActions.MaxOptionButtons -> " Options are: " + spokenList(choices) + "."
            else -> ""
        }
        return "${request.agentName} asks: ${request.question}$tail"
    }

    fun ticketCreated(project: ProjectRef): String = "Done. The ticket is on ${project.name}'s board."

    private fun spokenList(items: List<String>): String = when (items.size) {
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " or " + items.last()
    }

    private fun plural(n: Int, one: String, many: String) = if (n == 1) one else many

    private val smallNumbers = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
    )

    private fun count(n: Int): String = smallNumbers.getOrNull(n)?.replaceFirstChar { it.uppercase() } ?: n.toString()
}
