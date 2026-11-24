package dev.wildware.agents.app.feed

import androidx.compose.runtime.Immutable

/*
 * What the Docs tab draws: a channel's linked markdown docs (server migration 076). Mapped from the
 * dashboard's answers in `engine/DocsWatch.kt`. Read on the channel reads them; Post edits them.
 */

/** One doc in the channel's list. */
@Immutable
data class DocItem(
    val id: String,
    val title: String,
    /** "Sam", "scout": who saved it last. */
    val savedBy: String,
    val savedByAgent: Boolean,
    /** "5m ago". */
    val savedAgo: String,
    val size: Int,
    /** The channel's Index (titled "Index", any case): pinned at the top, and what the tab opens on. */
    val index: Boolean = false,
)

/** A doc that links to the open one: its "Linked from" row. */
@Immutable
data class DocBacklink(val id: String, val title: String)

/** One kept version, in the History list. */
@Immutable
data class DocVersionItem(
    val version: Long,
    val title: String,
    val savedBy: String,
    val savedByAgent: Boolean,
    val savedAgo: String,
    val restoredFrom: Long?,
    val size: Int,
)

/**
 * A save refused because someone else saved first: [theirs] is the doc as it is now, at
 * [theirsVersion], saved by [theirsBy]; [mine] is what this person tried to save. Shown side by side,
 * to merge into one text saved on top of theirs.
 */
@Immutable
data class DocConflict(
    val theirs: String,
    val theirsTitle: String,
    val theirsVersion: Long,
    val theirsBy: String,
    val mine: String,
    val mineTitle: String,
)

/** The doc open in the Docs tab. */
@Immutable
data class OpenDoc(
    val id: String,
    val title: String,
    val body: String,
    val version: Long,
    val savedBy: String = "",
    val savedByAgent: Boolean = false,
    val savedAgo: String = "",
    val canEdit: Boolean = false,
    val backlinks: List<DocBacklink> = emptyList(),
    /** Being edited: the editor holds the words; the engine only knows it is open. */
    val editing: Boolean = false,
    val saving: Boolean = false,
    /** Not saved yet: the channel's first Index, written from the server's template. Saving creates it. */
    val draft: Boolean = false,
    /** Someone else saved while this person was editing: their save will ask to merge. */
    val newerVersion: Long? = null,
    val conflict: DocConflict? = null,
    /** The History list, open, newest first; null while closed. */
    val history: List<DocVersionItem>? = null,
    /** One old version open for reading, from History. */
    val viewing: ViewedVersion? = null,
    val problem: String? = null,
)

@Immutable
data class ViewedVersion(val version: Long, val title: String, val body: String, val savedBy: String, val savedAgo: String)

/**
 * The Docs tab for [channel] (a slug): its docs, newest change first (or the search's), and the doc
 * open. [missing] is a [[link]] to a doc nobody has written yet, which this person can't create.
 */
@Immutable
data class DocsState(
    val channel: String? = null,
    val docs: List<DocItem> = emptyList(),
    val canEdit: Boolean = false,
    val query: String = "",
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val problem: String? = null,
    val open: OpenDoc? = null,
    /** A doc being opened by its title or id: shown as loading. */
    val opening: String? = null,
    val missing: String? = null,
    /** The channel has no Index yet: this is a first one to write. Nothing is saved until someone saves it. */
    val indexTemplate: String? = null,
)

/** What the owner does in the Docs tab. Each goes to the engine as [FeedEvent.Docs]. */
sealed interface DocsAction {
    /** The tab is on screen for [channel] (a slug), or (null) put away. */
    data class Show(val channel: String?) : DocsAction
    data class Search(val query: String) : DocsAction
    data class Open(val id: String) : DocsAction
    /** A [[link]] tapped: opens the doc titled [title] in [channel], creating it when it is missing and this person may. */
    data class OpenTitle(val channel: String, val title: String) : DocsAction
    data object Close : DocsAction
    data class Create(val title: String) : DocsAction
    /** Open the editor on the channel's first Index, from the template. Saving creates it. */
    data object WriteIndex : DocsAction
    data class Edit(val on: Boolean) : DocsAction
    /** Saves [body] and [title] on top of [baseVersion]. */
    data class Save(val title: String, val body: String, val baseVersion: Long) : DocsAction
    data object Delete : DocsAction
    data class History(val open: Boolean) : DocsAction
    data class ViewVersion(val version: Long?) : DocsAction
    data class Restore(val version: Long) : DocsAction
    data object DismissConflict : DocsAction
    data object DismissProblem : DocsAction
}
