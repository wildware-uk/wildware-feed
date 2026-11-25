package dev.wildware.feed.server.mcp

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.CardRef
import dev.wildware.feed.server.domain.DomainError
import dev.wildware.feed.server.domain.feed
import dev.wildware.feed.server.domain.invalid
import dev.wildware.feed.server.domain.jsBoolean
import dev.wildware.feed.server.domain.jsString
import dev.wildware.feed.server.github.CardPrs
import dev.wildware.feed.server.github.PullSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `link_pr` (migration 079, docs/GITHUB.md): an agent links its pull request to its update. */
fun ToolRegistry.registerGitHubTools(app: App) {
    register("link_pr") { call ->
        val github = app.github ?: throw DomainError(
            "unavailable", "GitHub isn't set up on this server, so pull requests can't be linked to cards. Put the PR's link in the card instead.", 400,
        )
        val updateId = call.args["update_id"].jsString?.takeIf { it.isNotBlank() } ?: throw invalid("update_id is required")
        val url = call.args["url"].jsString?.takeIf { it.isNotBlank() } ?: throw invalid("url is required: the pull request's address on github.com")
        val prs = CardPrs(app.feed)
        val (repo, number) = CardPrs.parse(url)
        // A card this agent can't post on is missing, before anything is asked of GitHub.
        app.feed.cards.resolveToWrite(CardRef("update", updateId))

        if (call.args["remove"].jsBoolean == true) {
            val removed = prs.unlink(updateId, url)
            val summary = if (removed) "Took #$number ($repo) off the card." else "#$number ($repo) wasn't on that card."
            return@register ToolResult.ok(summary, buildJsonObject { put("update_id", updateId); put("removed", removed) })
        }

        // What GitHub says now, read once, so the card is right at once. Never under the database lock.
        val ours = app.githubInstalls.ids()
        var note: String? = null
        val snapshot: PullSnapshot? = if (ours.isEmpty()) {
            note = "Your team hasn't connected GitHub yet, so the card shows only the link for now. A team owner connects it in Settings → GitHub."
            null
        } else {
            withContext(Dispatchers.IO) {
                val installation = github.api.installationFor(repo)
                if (installation == null || installation !in ours) {
                    note = "The GitHub App isn't installed on $repo for your team, so the card won't hear about this PR yet. A team owner adds the repo in Settings → GitHub."
                    null
                } else {
                    github.api.pull(installation, repo, number).also {
                        if (it == null) note = "GitHub didn't answer just now; the card will catch up at the PR's next change."
                    }
                }
            }
        }
        val row = prs.link(call.author, updateId, url, snapshot)
        val summary = "Linked #$number ($repo) to the card. You're its owner: if CI fails or a reviewer asks for changes, a line in the card's thread tells you." +
            (note?.let { "\n$it" } ?: "")
        ToolResult.ok(summary, buildJsonObject {
            put("update_id", updateId)
            put("pr", row.json(call.agent.name))
            note?.let { put("note", it) }
        })
    }
}
