package dev.wildware.agents.app.feed

import androidx.compose.runtime.Immutable

/** One thing search can find: a card, a reply in a thread, or a question. */
@Immutable
data class SearchEntry(
    val id: String,
    /** The card whose thread holds it; opening the result opens this thread. */
    val threadId: String?,
    val kind: Kind,
    val title: String,
    val snippet: String,
    val who: String,
    val projectName: String,
    val projectSlug: String?,
    val time: String,
) {
    enum class Kind(val label: String) { Card("Card"), Reply("Reply"), Question("Question") }

    private val haystack: String by lazy { "$title $snippet $who $projectName".lowercase() }

    /** Every word of [query] appears somewhere in it. */
    fun matches(query: String): Boolean = query.lowercase().split(' ').filter { it.isNotBlank() }.all { it in haystack }
}

/** Everything searchable in [sections], replies included. */
fun searchEntriesOf(sections: List<FeedSection>): List<SearchEntry> = sections.flatMap { it.items }.distinctBy { it.id }.flatMap { item ->
    when (item) {
        is FeedItem.Update -> listOf(
            SearchEntry(item.id, item.id, SearchEntry.Kind.Card, item.title, plain(item.body), item.agent.name, item.project.name, item.project.slug, item.time),
        ) + item.replies.map { it.entry(item.id, item.project) }
        is FeedItem.Post -> listOf(
            SearchEntry(item.id, item.id, SearchEntry.Kind.Card, item.title, plain(item.body), item.author.name, item.project.name, item.project.slug, item.time),
        ) + item.replies.map { it.entry(item.id, item.project) }
        is FeedItem.Question -> listOf(
            SearchEntry(item.id, null, SearchEntry.Kind.Question, item.prompt, plain(item.body), item.agent.name, item.project.name, item.project.slug, item.time),
        )
    }
}

private fun Reply.entry(threadId: String, project: Project) =
    SearchEntry(id, threadId, SearchEntry.Kind.Reply, plain(body).take(120), "", author.name, project.name, project.slug, time)

/** Markdown with its marks taken out, for matching and for one-line snippets. */
private fun plain(markdown: String): String =
    markdown.replace(Regex("[*_`#>]"), "").replace(Regex("\\s+"), " ").trim()
